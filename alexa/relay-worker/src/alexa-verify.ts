/**
 * Alexa request verification for a self-hosted (non-Lambda) skill endpoint.
 * https://developer.amazon.com/docs/custom-skills/host-a-custom-skill-as-a-web-service.html
 *
 * Checks, in order:
 *  1. SignatureCertChainUrl points at Amazon's echo.api bucket (https, s3.amazonaws.com:443, /echo.api/).
 *  2. The leaf certificate is currently valid and its SAN contains echo-api.amazon.com.
 *  3. The leaf is signed by the next certificate in the chain (each link is checked).
 *  4. Signature-256 (SHA256withRSA over the raw body) verifies with the leaf's public key.
 *  5. The request timestamp is within 150 seconds.
 *
 * The chain is fetched over TLS from an Amazon-owned bucket, which is what anchors trust;
 * the in-chain signature checks are defence in depth. Uses only WebCrypto, no dependencies.
 */

const SAN_OID = "2.5.29.17";
const REQUIRED_SAN = "echo-api.amazon.com";
const MAX_SKEW_MS = 150_000;
const CERT_CACHE_MS = 60 * 60 * 1000;

const RSA_ALGS: Record<string, string> = {
  "1.2.840.113549.1.1.11": "SHA-256",
  "1.2.840.113549.1.1.12": "SHA-384",
  "1.2.840.113549.1.1.13": "SHA-512",
};

export class AlexaVerificationError extends Error {}

// ---------------------------------------------------------------- minimal DER reader

interface Tlv {
  tag: number;
  start: number; // offset of the tag byte
  valueStart: number;
  end: number; // exclusive
}

function readTlv(buf: Uint8Array, offset: number): Tlv {
  const tag = buf[offset];
  let len = buf[offset + 1];
  let p = offset + 2;
  if (len & 0x80) {
    const n = len & 0x7f;
    if (n === 0 || n > 4) throw new AlexaVerificationError("unsupported DER length");
    len = 0;
    for (let i = 0; i < n; i++) len = (len << 8) | buf[p++];
  }
  const end = p + len;
  if (end > buf.length) throw new AlexaVerificationError("truncated DER");
  return { tag, start: offset, valueStart: p, end };
}

function children(buf: Uint8Array, parent: Tlv): Tlv[] {
  const out: Tlv[] = [];
  let p = parent.valueStart;
  while (p < parent.end) {
    const t = readTlv(buf, p);
    out.push(t);
    p = t.end;
  }
  return out;
}

function decodeOid(bytes: Uint8Array): string {
  const parts: number[] = [Math.floor(bytes[0] / 40), bytes[0] % 40];
  let v = 0;
  for (let i = 1; i < bytes.length; i++) {
    v = (v << 7) | (bytes[i] & 0x7f);
    if (!(bytes[i] & 0x80)) {
      parts.push(v);
      v = 0;
    }
  }
  return parts.join(".");
}

function decodeTime(buf: Uint8Array, t: Tlv): number {
  const s = new TextDecoder().decode(buf.subarray(t.valueStart, t.end));
  let y: number, rest: string;
  if (t.tag === 0x17) {
    // UTCTime YYMMDDHHMMSSZ
    const yy = parseInt(s.slice(0, 2), 10);
    y = yy >= 50 ? 1900 + yy : 2000 + yy;
    rest = s.slice(2);
  } else if (t.tag === 0x18) {
    // GeneralizedTime YYYYMMDDHHMMSSZ
    y = parseInt(s.slice(0, 4), 10);
    rest = s.slice(4);
  } else {
    throw new AlexaVerificationError("bad certificate time");
  }
  const n = (i: number) => parseInt(rest.slice(i, i + 2), 10);
  return Date.UTC(y, n(0) - 1, n(2), n(4), n(6), n(8));
}

export interface ParsedCert {
  tbs: Uint8Array<ArrayBuffer>;
  sigAlgOid: string;
  signature: Uint8Array<ArrayBuffer>;
  spki: Uint8Array<ArrayBuffer>;
  notBefore: number;
  notAfter: number;
  dnsNames: string[];
}

export function parseCertificate(der: Uint8Array): ParsedCert {
  const cert = readTlv(der, 0);
  const [tbsTlv, algTlv, sigTlv] = children(der, cert);
  const tbsFields = children(der, tbsTlv);
  let i = 0;
  if (tbsFields[0].tag === 0xa0) i++; // [0] version
  i++; // serialNumber
  i++; // signature AlgorithmIdentifier
  i++; // issuer
  const validity = children(der, tbsFields[i++]);
  i++; // subject
  const spkiTlv = tbsFields[i++];

  const dnsNames: string[] = [];
  for (; i < tbsFields.length; i++) {
    if (tbsFields[i].tag !== 0xa3) continue; // [3] extensions
    const extSeq = children(der, tbsFields[i])[0];
    for (const ext of children(der, extSeq)) {
      const parts = children(der, ext);
      const oid = decodeOid(der.subarray(parts[0].valueStart, parts[0].end));
      if (oid !== SAN_OID) continue;
      const octet = parts[parts.length - 1]; // OCTET STRING (critical flag is optional)
      const names = readTlv(der, octet.valueStart);
      for (const gn of children(der, names)) {
        if (gn.tag === 0x82) dnsNames.push(new TextDecoder().decode(der.subarray(gn.valueStart, gn.end)));
      }
    }
  }

  const algOidTlv = children(der, algTlv)[0];
  return {
    tbs: der.slice(tbsTlv.start, tbsTlv.end),
    sigAlgOid: decodeOid(der.subarray(algOidTlv.valueStart, algOidTlv.end)),
    signature: der.slice(sigTlv.valueStart + 1, sigTlv.end), // skip BIT STRING unused-bits byte
    spki: der.slice(spkiTlv.start, spkiTlv.end),
    notBefore: decodeTime(der, validity[0]),
    notAfter: decodeTime(der, validity[1]),
    dnsNames,
  };
}

export function pemToDerChain(pem: string): Uint8Array[] {
  const blocks = pem.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g) ?? [];
  return blocks.map((b) => {
    const b64 = b.replace(/-----(BEGIN|END) CERTIFICATE-----/g, "").replace(/\s+/g, "");
    return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  });
}

// ---------------------------------------------------------------- verification

export function validateCertChainUrl(raw: string | null): URL {
  if (!raw) throw new AlexaVerificationError("missing SignatureCertChainUrl");
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new AlexaVerificationError("invalid SignatureCertChainUrl");
  }
  // new URL() normalises "/echo.api/../" segments and lower-cases the host.
  if (url.protocol !== "https:") throw new AlexaVerificationError("cert URL must be https");
  if (url.hostname !== "s3.amazonaws.com") throw new AlexaVerificationError("cert URL host");
  if (url.port !== "" && url.port !== "443") throw new AlexaVerificationError("cert URL port");
  if (!url.pathname.startsWith("/echo.api/")) throw new AlexaVerificationError("cert URL path");
  return url;
}

async function importRsaKey(spki: Uint8Array<ArrayBuffer>, hash: string): Promise<CryptoKey> {
  return crypto.subtle.importKey("spki", spki, { name: "RSASSA-PKCS1-v1_5", hash }, false, ["verify"]);
}

export async function verifyChain(chain: ParsedCert[], now: number): Promise<ParsedCert> {
  if (chain.length === 0) throw new AlexaVerificationError("empty certificate chain");
  const leaf = chain[0];
  if (now < leaf.notBefore || now > leaf.notAfter) throw new AlexaVerificationError("certificate expired");
  if (!leaf.dnsNames.includes(REQUIRED_SAN)) throw new AlexaVerificationError("certificate SAN mismatch");
  for (let i = 0; i < chain.length - 1; i++) {
    const hash = RSA_ALGS[chain[i].sigAlgOid];
    if (!hash) throw new AlexaVerificationError(`unsupported chain signature ${chain[i].sigAlgOid}`);
    const issuerKey = await importRsaKey(chain[i + 1].spki, hash);
    const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", issuerKey, chain[i].signature, chain[i].tbs);
    if (!ok) throw new AlexaVerificationError(`certificate chain broken at ${i}`);
  }
  return leaf;
}

const certCache = new Map<string, { leaf: ParsedCert; fetchedAt: number }>();

async function loadLeaf(url: URL, now: number, fetcher: typeof fetch): Promise<ParsedCert> {
  const key = url.toString();
  const cached = certCache.get(key);
  if (cached && now - cached.fetchedAt < CERT_CACHE_MS && now <= cached.leaf.notAfter) return cached.leaf;
  const res = await fetcher(key);
  if (!res.ok) throw new AlexaVerificationError(`cert fetch failed ${res.status}`);
  const chain = pemToDerChain(await res.text()).map(parseCertificate);
  const leaf = await verifyChain(chain, now);
  certCache.set(key, { leaf, fetchedAt: now });
  return leaf;
}

/** Throws AlexaVerificationError unless the request is a genuine, fresh Alexa request. */
export async function verifyAlexaRequest(
  headers: Headers,
  rawBody: string,
  opts: { now?: number; fetcher?: typeof fetch } = {},
): Promise<void> {
  const now = opts.now ?? Date.now();
  const url = validateCertChainUrl(headers.get("SignatureCertChainUrl"));
  const sigB64 = headers.get("Signature-256");
  if (!sigB64) throw new AlexaVerificationError("missing Signature-256");

  const leaf = await loadLeaf(url, now, opts.fetcher ?? fetch);
  const key = await importRsaKey(leaf.spki, "SHA-256");
  const signature = Uint8Array.from(atob(sigB64), (c) => c.charCodeAt(0));
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature, new TextEncoder().encode(rawBody));
  if (!ok) throw new AlexaVerificationError("bad request signature");

  let timestamp: number;
  try {
    timestamp = Date.parse(JSON.parse(rawBody)?.request?.timestamp);
  } catch {
    throw new AlexaVerificationError("unparseable body");
  }
  if (!Number.isFinite(timestamp) || Math.abs(now - timestamp) > MAX_SKEW_MS) {
    throw new AlexaVerificationError("stale request");
  }
}
