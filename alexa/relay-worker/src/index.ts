/**
 * Nuvio Alexa relay.
 *
 *   Echo ──HTTPS──▶ POST /alexa ──▶ TvRelay (Durable Object) ──WebSocket──▶ Nuvio on the TV
 *                                        ◀── {"id","ok","code"} ◀──
 *
 * The TV keeps a WebSocket open to GET /tv while Nuvio is in the foreground, authenticated
 * with TV_TOKEN. Alexa requests are signature-verified and pinned to ALEXA_SKILL_ID.
 */

import { AlexaVerificationError, verifyAlexaRequest } from "./alexa-verify";
import { langOf, mapRequest, say, speakResult, type TvCommand, type TvReply } from "./intents";

export interface Env {
  RELAY: DurableObjectNamespace;
  ALEXA_SKILL_ID: string;
  TV_TOKEN: string;
}

const TV_REPLY_TIMEOUT_MS = 4_000;

async function constantTimeEquals(a: string, b: string): Promise<boolean> {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  const x = new Uint8Array(ha);
  const y = new Uint8Array(hb);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

function relay(env: Env): DurableObjectStub {
  // One TV per deployment, so a single fixed-name instance.
  return env.RELAY.get(env.RELAY.idFromName("tv"));
}

async function handleAlexa(request: Request, env: Env): Promise<Response> {
  const body = await request.text();
  try {
    await verifyAlexaRequest(request.headers, body);
  } catch (e) {
    const reason = e instanceof AlexaVerificationError ? e.message : "verification error";
    console.warn("rejected alexa request:", reason);
    return new Response("bad request", { status: 400 });
  }

  const envelope = JSON.parse(body);
  const appId: string | undefined =
    envelope?.context?.System?.application?.applicationId ?? envelope?.session?.application?.applicationId;
  if (!env.ALEXA_SKILL_ID || appId !== env.ALEXA_SKILL_ID) {
    return new Response("forbidden", { status: 403 });
  }

  const outcome = mapRequest(envelope.request ?? { type: "" });
  if (outcome.kind === "reply") return Response.json(outcome.response);

  const res = await relay(env).fetch("https://relay/command", {
    method: "POST",
    body: JSON.stringify(outcome.command),
  });
  const reply = (await res.json()) as TvReply;
  return Response.json(say(speakResult(outcome.command, reply, langOf(envelope.request?.locale))));
}

async function handleTv(request: Request, env: Env): Promise<Response> {
  if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
    return new Response("expected websocket", { status: 426 });
  }
  const auth = request.headers.get("Authorization") ?? "";
  if (!env.TV_TOKEN || !(await constantTimeEquals(auth, `Bearer ${env.TV_TOKEN}`))) {
    return new Response("unauthorized", { status: 401 });
  }
  return relay(env).fetch("https://relay/tv", request);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/alexa" && request.method === "POST") return handleAlexa(request, env);
    if (pathname === "/tv" && request.method === "GET") return handleTv(request, env);
    if (pathname === "/health") return new Response("ok");
    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;

/**
 * Holds the TV's WebSocket (hibernation API, so an idle connection costs nothing) and
 * correlates each command with the TV's reply.
 */
export class TvRelay implements DurableObject {
  private pending = new Map<string, (reply: TvReply) => void>();

  constructor(private readonly state: DurableObjectState, _env: Env) {}

  async fetch(request: Request): Promise<Response> {
    const { pathname } = new URL(request.url);

    if (pathname === "/tv") {
      // Only one TV: a new connection replaces any previous one.
      for (const old of this.state.getWebSockets()) {
        try {
          old.close(4000, "replaced by a newer connection");
        } catch {
          /* already closed */
        }
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.state.acceptWebSocket(server);
      return new Response(null, { status: 101, webSocket: client });
    }

    if (pathname === "/command") {
      const command = (await request.json()) as TvCommand;
      const sockets = this.state.getWebSockets();
      const socket = sockets[sockets.length - 1];
      if (!socket) return Response.json({ ok: false, code: "tv_offline" } satisfies TvReply);

      const id = crypto.randomUUID();
      const reply = new Promise<TvReply>((resolve) => {
        this.pending.set(id, resolve);
        setTimeout(() => {
          if (this.pending.delete(id)) resolve({ ok: false, code: "timeout" });
        }, TV_REPLY_TIMEOUT_MS);
      });
      try {
        socket.send(JSON.stringify({ id, ...command }));
      } catch {
        this.pending.delete(id);
        return Response.json({ ok: false, code: "tv_offline" } satisfies TvReply);
      }
      return Response.json(await reply);
    }

    return new Response("not found", { status: 404 });
  }

  async webSocketMessage(_ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") return;
    let parsed: { id?: unknown; ok?: unknown; code?: unknown };
    try {
      parsed = JSON.parse(message);
    } catch {
      return;
    }
    if (typeof parsed.id !== "string") return;
    const resolve = this.pending.get(parsed.id);
    if (!resolve) return;
    this.pending.delete(parsed.id);
    resolve({ ok: parsed.ok === true, code: String(parsed.code ?? "") });
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    try {
      ws.close(code, reason);
    } catch {
      /* already closed */
    }
  }
}
