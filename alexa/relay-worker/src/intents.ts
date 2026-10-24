/**
 * Alexa intent -> TV command mapping, and TV result code -> spoken reply.
 * Intent and slot names are shared by every locale's interaction model; only the spoken
 * text differs. The language follows the request's `locale` (it-* or en-*).
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
  | { cmd: "search"; query: string }
  | { cmd: "play"; query: string };

export interface TvReply {
  ok: boolean;
  code: string;
  /** For "play": the name of the title the TV opened. */
  title?: string;
}

export interface AlexaResponse {
  version: "1.0";
  response: {
    outputSpeech?: { type: "PlainText"; text: string };
    reprompt?: { outputSpeech: { type: "PlainText"; text: string } };
    shouldEndSession: boolean;
  };
}

export type Lang = "it" | "en";

type Slots = Record<string, { value?: string } | undefined>;

const DEFAULT_SEEK_FORWARD_MS = 30_000;
const DEFAULT_SEEK_BACK_MS = 10_000;

export function langOf(locale: string | undefined): Lang {
  return locale?.toLowerCase().startsWith("en") ? "en" : "it";
}

// ---------------------------------------------------------------- strings

const UNITS: Record<Lang, { h: [string, string]; m: [string, string]; s: [string, string]; and: string }> = {
  it: { h: ["ora", "ore"], m: ["minuto", "minuti"], s: ["secondo", "secondi"], and: "e" },
  en: { h: ["hour", "hours"], m: ["minute", "minutes"], s: ["second", "seconds"], and: "and" },
};

const T = {
  it: {
    help:
      "Puoi dire: voglio vedere seguito da un titolo, metti in pausa, riprendi, vai avanti di trenta secondi, " +
      "torna indietro di un minuto, vai al minuto venti, prossimo episodio, salta l'intro, chiudi il video, " +
      "oppure cerca seguito da un titolo.",
    ready: "Nuvio è pronto.",
    whatNext: "Cosa vuoi fare?",
    didntUnderstand: "Non ho capito.",
    ok: "Ok.",
    askMinute: "A che minuto vuoi andare?",
    askMinuteReprompt: "Dimmi il minuto, per esempio vai al minuto venti.",
    askQuery: "Cosa vuoi cercare?",
    askQueryReprompt: "Dimmi il titolo da cercare.",
    askPlay: "Cosa vuoi guardare?",
    askPlayReprompt: "Dimmi il titolo, per esempio voglio vedere The Bear.",
    done: "Fatto.",
    failed: "Non ci sono riuscita.",
    codes: {
      paused: "In pausa.",
      already_paused: "È già in pausa.",
      buffering: "Sta ancora caricando, riprova tra un attimo.",
      resumed: "Riprendo.",
      already_playing: "Sta già andando.",
      beyond_end: "Il video è più corto di così.",
      live: "In una diretta non posso spostarmi.",
      next_episode: "Metto il prossimo episodio.",
      no_next_episode: "Non c'è un episodio successivo.",
      next_not_aired: "Il prossimo episodio non è ancora uscito.",
      next_episode_starting: "Il prossimo episodio sta già partendo.",
      next_episode_pick_source: "Scegli la fonte del prossimo episodio sulla TV.",
      party_locked: "Durante il watch party solo chi lo gestisce può cambiare episodio.",
      skipped_intro: "Saltata.",
      no_intro: "Adesso non c'è niente da saltare.",
      stopped: "Video chiuso.",
      stop_playback_first: "Prima chiudi il video, poi chiedimelo di nuovo.",
      nothing_playing: "Non c'è niente in riproduzione.",
      not_ready: "Nuvio non è ancora pronto.",
      tv_offline: "Nuvio non è aperto sulla TV, oppure non è connesso.",
      timeout: "La TV non ha risposto.",
    } as Record<string, string>,
    playing: (title: string) => `Avvio ${title}.`,
    notFound: (q: string) => `Non ho trovato ${q}, ti mostro i risultati della ricerca.`,
    notFoundKeepPlaying: (q: string) => `Non ho trovato ${q}.`,
    forward: (d: string) => `Avanti di ${d}.`,
    back: (d: string) => `Indietro di ${d}.`,
    toMinute: (m: number) => `Vado al minuto ${m}.`,
    toTime: (d: string) => `Vado a ${d}.`,
    searching: (q: string) => `Cerco ${q}.`,
  },
  en: {
    help:
      "You can say: play followed by a title, pause, resume, skip forward thirty seconds, go back one minute, " +
      "go to minute twenty, next episode, skip the intro, close the video, or search for followed by a title.",
    ready: "Nuvio is ready.",
    whatNext: "What would you like to do?",
    didntUnderstand: "Sorry, I didn't get that.",
    ok: "Okay.",
    askMinute: "Which minute do you want to go to?",
    askMinuteReprompt: "Tell me the minute, for example go to minute twenty.",
    askQuery: "What do you want to search for?",
    askQueryReprompt: "Tell me the title to search for.",
    askPlay: "What do you want to watch?",
    askPlayReprompt: "Tell me the title, for example play The Bear.",
    done: "Done.",
    failed: "Sorry, I couldn't do that.",
    codes: {
      paused: "Paused.",
      already_paused: "It's already paused.",
      buffering: "It's still loading, try again in a moment.",
      resumed: "Resuming.",
      already_playing: "It's already playing.",
      beyond_end: "The video isn't that long.",
      live: "I can't seek in a live stream.",
      next_episode: "Playing the next episode.",
      no_next_episode: "There's no next episode.",
      next_not_aired: "The next episode hasn't aired yet.",
      next_episode_starting: "The next episode is already starting.",
      next_episode_pick_source: "Pick a source for the next episode on the TV.",
      party_locked: "In a watch party only the host can change the episode.",
      skipped_intro: "Skipped.",
      no_intro: "There's nothing to skip right now.",
      stopped: "Video closed.",
      stop_playback_first: "Close the video first, then ask me again.",
      nothing_playing: "Nothing is playing.",
      not_ready: "Nuvio isn't ready yet.",
      tv_offline: "Nuvio isn't open on the TV, or it isn't connected.",
      timeout: "The TV didn't respond.",
    } as Record<string, string>,
    playing: (title: string) => `Starting ${title}.`,
    notFound: (q: string) => `I couldn't find ${q}, so I've opened the search results.`,
    notFoundKeepPlaying: (q: string) => `I couldn't find ${q}.`,
    forward: (d: string) => `Skipped forward ${d}.`,
    back: (d: string) => `Went back ${d}.`,
    toMinute: (m: number) => `Going to minute ${m}.`,
    toTime: (d: string) => `Going to ${d}.`,
    searching: (q: string) => `Searching for ${q}.`,
  },
} satisfies Record<Lang, unknown>;

// ---------------------------------------------------------------- helpers

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

/** 90000 -> "1 minuto e 30 secondi" / "1 minute and 30 seconds" */
export function speakDuration(ms: number, lang: Lang = "it"): string {
  const u = UNITS[lang];
  const total = Math.round(Math.abs(ms) / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const unit = (n: number, [one, many]: [string, string]) => `${n} ${n === 1 ? one : many}`;
  const parts: string[] = [];
  if (h) parts.push(unit(h, u.h));
  if (m) parts.push(unit(m, u.m));
  if (s || parts.length === 0) parts.push(unit(s, u.s));
  return parts.length > 1 ? `${parts.slice(0, -1).join(", ")} ${u.and} ${parts[parts.length - 1]}` : parts[0];
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

// ---------------------------------------------------------------- mapping

/** Decides what to do with an Alexa request envelope's `request` object. */
export function mapRequest(request: {
  type: string;
  locale?: string;
  intent?: { name: string; slots?: Slots };
}): IntentOutcome {
  const t = T[langOf(request.locale)];
  const reply = (response: AlexaResponse): IntentOutcome => ({ kind: "reply", response });

  if (request.type === "LaunchRequest") return reply(say(`${t.ready} ${t.help}`, false, t.whatNext));
  if (request.type === "SessionEndedRequest") return reply(silent());
  if (request.type !== "IntentRequest" || !request.intent) return reply(say(t.didntUnderstand));

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
        return reply(say(t.askMinute, false, t.askMinuteReprompt));
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
      if (!query) return reply(say(t.askQuery, false, t.askQueryReprompt));
      return cmd({ cmd: "search", query });
    }
    case "GuardaIntent": {
      const query = slots.titolo?.value?.trim();
      if (!query) return reply(say(t.askPlay, false, t.askPlayReprompt));
      return cmd({ cmd: "play", query });
    }
    case "AMAZON.HelpIntent":
      return reply(say(t.help, false, t.whatNext));
    case "AMAZON.StopIntent":
    case "AMAZON.CancelIntent":
    case "AMAZON.NavigateHomeIntent":
      return reply(say(t.ok));
    case "AMAZON.FallbackIntent":
    default:
      return reply(say(`${t.didntUnderstand} ${t.help}`, false, t.whatNext));
  }
}

/** TV result code -> spoken reply in the request's language. */
export function speakResult(command: TvCommand, result: TvReply, lang: Lang = "it"): string {
  const t = T[lang];
  switch (result.code) {
    case "seeked_forward":
      return command.cmd === "seek_by" ? t.forward(speakDuration(command.ms, lang)) : t.done;
    case "seeked_back":
      return command.cmd === "seek_by" ? t.back(speakDuration(command.ms, lang)) : t.done;
    case "seeked_to":
      if (command.cmd !== "seek_to") return t.done;
      if (command.ms < 3_600_000 && command.ms % 60_000 === 0) return t.toMinute(command.ms / 60_000);
      return t.toTime(speakDuration(command.ms, lang));
    case "searching":
      return command.cmd === "search" ? t.searching(command.query) : t.done;
    case "playing":
      return t.playing(result.title || (command.cmd === "play" ? command.query : ""));
    case "not_found_searching":
      return command.cmd === "play" ? t.notFound(command.query) : t.failed;
    case "not_found":
      return command.cmd === "play" ? t.notFoundKeepPlaying(command.query) : t.failed;
    default:
      return t.codes[result.code] ?? (result.ok ? t.done : t.failed);
  }
}
