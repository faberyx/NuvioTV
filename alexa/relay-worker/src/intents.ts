/**
 * Alexa (it-IT) intent -> TV command mapping, and TV result code -> spoken reply.
 * Pure functions so they can be unit-tested outside the Workers runtime.
 */

export type TvCommand =
  | { cmd: "pause" }
  | { cmd: "resume" }
  | { cmd: "seek_by"; ms: number }
  | { cmd: "seek_to"; ms: number }
  | { cmd: "next_episode" }
  | { cmd: "skip_intro" }
  | { cmd: "stop" }
  | { cmd: "search"; query: string };

export interface TvReply {
  ok: boolean;
  code: string;
}

export interface AlexaResponse {
  version: "1.0";
  response: {
    outputSpeech?: { type: "PlainText"; text: string };
    reprompt?: { outputSpeech: { type: "PlainText"; text: string } };
    shouldEndSession: boolean;
  };
}

type Slots = Record<string, { value?: string } | undefined>;

const DEFAULT_SEEK_FORWARD_MS = 30_000;
const DEFAULT_SEEK_BACK_MS = 10_000;

const HELP =
  "Puoi dire: metti in pausa, riprendi, vai avanti di trenta secondi, torna indietro di un minuto, " +
  "vai al minuto venti, prossimo episodio, salta l'intro, chiudi il video, oppure cerca seguito da un titolo.";

/** ISO-8601 duration from AMAZON.DURATION (e.g. PT30S, PT1H5M, PT1M30S) -> milliseconds. */
export function parseIsoDuration(value: string | undefined): number | null {
  if (!value) return null;
  const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(value.trim());
  if (!m || value.trim() === "P" || value.trim() === "PT") return null;
  const [, d, h, min, s] = m;
  const ms =
    (Number(d ?? 0) * 86_400 + Number(h ?? 0) * 3_600 + Number(min ?? 0) * 60 + Number(s ?? 0)) * 1000;
  return ms > 0 ? Math.round(ms) : null;
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** 90000 -> "1 minuto e 30 secondi" */
export function speakDuration(ms: number): string {
  const total = Math.round(Math.abs(ms) / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const parts: string[] = [];
  if (h) parts.push(plural(h, "ora", "ore"));
  if (m) parts.push(plural(m, "minuto", "minuti"));
  if (s || parts.length === 0) parts.push(plural(s, "secondo", "secondi"));
  return parts.length > 1 ? `${parts.slice(0, -1).join(", ")} e ${parts[parts.length - 1]}` : parts[0];
}

export type IntentOutcome =
  | { kind: "command"; command: TvCommand }
  | { kind: "reply"; response: AlexaResponse };

export function say(text: string, endSession = true, reprompt?: string): AlexaResponse {
  return {
    version: "1.0",
    response: {
      outputSpeech: { type: "PlainText", text },
      ...(reprompt ? { reprompt: { outputSpeech: { type: "PlainText", text: reprompt } } } : {}),
      shouldEndSession: endSession,
    },
  };
}

export function silent(): AlexaResponse {
  return { version: "1.0", response: { shouldEndSession: true } };
}

/** Decides what to do with an Alexa request envelope's `request` object. */
export function mapRequest(request: { type: string; intent?: { name: string; slots?: Slots } }): IntentOutcome {
  if (request.type === "LaunchRequest") {
    return { kind: "reply", response: say(`Nuvio è pronto. ${HELP}`, false, "Cosa vuoi fare?") };
  }
  if (request.type === "SessionEndedRequest") return { kind: "reply", response: silent() };
  if (request.type !== "IntentRequest" || !request.intent) {
    return { kind: "reply", response: say("Non ho capito.") };
  }

  const slots = request.intent.slots ?? {};
  const cmd = (command: TvCommand): IntentOutcome => ({ kind: "command", command });

  switch (request.intent.name) {
    case "PausaIntent":
      return cmd({ cmd: "pause" });
    case "RiprendiIntent":
      return cmd({ cmd: "resume" });
    case "AvantiIntent":
      return cmd({ cmd: "seek_by", ms: parseIsoDuration(slots.durata?.value) ?? DEFAULT_SEEK_FORWARD_MS });
    case "IndietroIntent":
      return cmd({ cmd: "seek_by", ms: -(parseIsoDuration(slots.durata?.value) ?? DEFAULT_SEEK_BACK_MS) });
    case "VaiAlMinutoIntent": {
      const minuti = Number(slots.minuti?.value);
      const ore = Number(slots.ore?.value ?? 0);
      if (!Number.isFinite(minuti) || minuti < 0 || !Number.isFinite(ore) || ore < 0) {
        return { kind: "reply", response: say("A che minuto vuoi andare?", false, "Dimmi il minuto, per esempio vai al minuto venti.") };
      }
      return cmd({ cmd: "seek_to", ms: (ore * 3600 + minuti * 60) * 1000 });
    }
    case "ProssimoEpisodioIntent":
      return cmd({ cmd: "next_episode" });
    case "SaltaIntroIntent":
      return cmd({ cmd: "skip_intro" });
    case "ChiudiVideoIntent":
      return cmd({ cmd: "stop" });
    case "CercaIntent": {
      const query = slots.titolo?.value?.trim();
      if (!query) return { kind: "reply", response: say("Cosa vuoi cercare?", false, "Dimmi il titolo da cercare.") };
      return cmd({ cmd: "search", query });
    }
    case "AMAZON.HelpIntent":
      return { kind: "reply", response: say(HELP, false, "Cosa vuoi fare?") };
    case "AMAZON.StopIntent":
    case "AMAZON.CancelIntent":
    case "AMAZON.NavigateHomeIntent":
      return { kind: "reply", response: say("Ok.") };
    case "AMAZON.FallbackIntent":
    default:
      return { kind: "reply", response: say(`Non ho capito. ${HELP}`, false, "Cosa vuoi fare?") };
  }
}

/** TV result code -> spoken Italian reply. */
export function speakResult(command: TvCommand, reply: TvReply): string {
  switch (reply.code) {
    case "paused":
      return "In pausa.";
    case "already_paused":
      return "È già in pausa.";
    case "buffering":
      return "Sta ancora caricando, riprova tra un attimo.";
    case "resumed":
      return "Riprendo.";
    case "already_playing":
      return "Sta già andando.";
    case "seeked_forward":
      return command.cmd === "seek_by" ? `Avanti di ${speakDuration(command.ms)}.` : "Fatto.";
    case "seeked_back":
      return command.cmd === "seek_by" ? `Indietro di ${speakDuration(command.ms)}.` : "Fatto.";
    case "seeked_to":
      if (command.cmd !== "seek_to") return "Fatto.";
      if (command.ms < 3_600_000 && command.ms % 60_000 === 0) return `Vado al minuto ${command.ms / 60_000}.`;
      return `Vado a ${speakDuration(command.ms)}.`;
    case "beyond_end":
      return "Il video è più corto di così.";
    case "live":
      return "In una diretta non posso spostarmi.";
    case "next_episode":
      return "Metto il prossimo episodio.";
    case "no_next_episode":
      return "Non c'è un episodio successivo.";
    case "skipped_intro":
      return "Saltata.";
    case "no_intro":
      return "Adesso non c'è niente da saltare.";
    case "stopped":
      return "Video chiuso.";
    case "searching":
      return command.cmd === "search" ? `Cerco ${command.query}.` : "Cerco.";
    case "stop_playback_first":
      return "Prima chiudi il video, poi chiedimi di cercare.";
    case "nothing_playing":
      return "Non c'è niente in riproduzione.";
    case "not_ready":
      return "Nuvio non è ancora pronto.";
    case "tv_offline":
      return "Nuvio non è aperto sulla TV, oppure non è connesso.";
    case "timeout":
      return "La TV non ha risposto.";
    default:
      return reply.ok ? "Fatto." : "Non ci sono riuscita.";
  }
}
