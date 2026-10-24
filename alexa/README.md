# Alexa voice control

Control Nuvio on the TV from an Echo while the app is open:

> "Alexa, chiedi a home tv di mettere in pausa"
> "Alexa, chiedi a home tv di andare avanti di due minuti"
> "Alexa, chiedi a home tv di cercare the bear"
> "Alexa, ask home tv to skip forward two minutes"

The skill ships Italian (it-IT) and English (en-US, en-GB) models, and Alexa answers in the
language of the request. **The Echo's language setting decides which model is used**
(Alexa app → Devices → your Echo → Language). An Echo set to Italian only matches the
it-IT model, and one set to English (UK) only matches en-GB.

```
Echo ─▶ Alexa cloud ─HTTPS─▶ relay Worker /alexa ─▶ Durable Object ─WebSocket─▶ Nuvio (TV)
                                          ◀── spoken reply ◀── result code ◀──
```

The TV opens the WebSocket in `MainActivity.onStart` and closes it in `onStop`. That means
voice control works only while Nuvio is on screen. It does not work with an external player
or with the app in the background.

## Commands

| Say (after "chiedi a home tv di…") | Does |
|---|---|
| mettere in pausa / riprendere | pause / resume |
| andare avanti di 30 secondi · tornare indietro di 2 minuti | seek by (defaults are +30 s and −10 s) |
| andare al minuto 20 · andare a 1 ora e 5 minuti | seek to |
| mettere il prossimo episodio | next episode (series) |
| saltare l'intro / la sigla / il riassunto | skip the active intro or recap |
| chiudere il video | exit the player |
| guardare *titolo* · riprodurre *titolo* · fammi vedere *titolo* | find the title and start it: series resume where you left off; sources follow your Stream auto-play setting (Manual = you pick) |
| cercare *titolo* | open Search with the query (only when no video is playing) |

English, after "ask home tv to…": play *title* · watch *title* · pause · resume · skip forward 30 seconds · go back 2 minutes ·
go to minute 20 · go to 1 hour and 5 minutes · play the next episode · skip the intro / recap ·
close the video · search for *title*.

You can also say "Alexa, apri home tv" (or "Alexa, open home tv") and then give the command
without the prefix.

## 1. Deploy the relay (Cloudflare Workers, Free plan is enough)

```bash
cd alexa/relay-worker
npm install
npx wrangler login
openssl rand -hex 32            # this is your TV token
npx wrangler secret put TV_TOKEN
npx wrangler deploy             # note the https://nuvio-alexa-relay.<sub>.workers.dev URL
```

## 2. Create the skill (developer.amazon.com → Alexa Skills Kit)

1. **Create Skill**: name "Nuvio TV", primary locale matching your Echo's language (e.g. **English (UK)**
   or **Italian (IT)**), model **Custom**, hosting **Provision your own**, template **Start from scratch**.
   You can add more languages afterwards from the language drop-down → *Language settings*.
2. **Interaction Model → JSON Editor**: for each language, paste the matching file
   (`skill/interaction-model.en-GB.json`, `…en-US.json` or `…it-IT.json`), then **Save** and **Build**.
3. **Endpoint**: choose **HTTPS** and enter `https://nuvio-alexa-relay.<sub>.workers.dev/alexa`.
   For the SSL type pick *"My development endpoint is a sub-domain of a domain that has a wildcard
   certificate from a certificate authority"*.
4. Copy the **Skill ID** (`amzn1.ask.skill.…`) into `ALEXA_SKILL_ID` in `wrangler.toml` and run `npx wrangler deploy` again.
5. **Test** tab: set it to **Development**. Skills in development work on every Echo
   registered to the same Amazon account. No certification is needed.

## 3. Build the app

Add to `local.properties`:

```properties
VOICE_RELAY_URL=https://nuvio-alexa-relay.<sub>.workers.dev
VOICE_RELAY_TOKEN=<the TV token from step 1>
```

Build and install as usual. With both values blank the feature stays off
(`VoiceCommandClient.isConfigured`) and no socket is opened.

## Checking it works

- `npx wrangler tail`: shows Alexa requests and any rejected signatures.
- `adb logcat -s VoiceCommandClient`: shows "connected to voice relay" and each command/result.
- If Alexa says "Nuvio non è aperto sulla TV", the TV socket isn't connected. Check the URL and token.

## Security

- Every Alexa request is verified: the cert-chain URL rules, a valid leaf with SAN
  `echo-api.amazon.com`, the chain signatures, `Signature-256` over the body, and a
  timestamp within 150 s. The skill ID must also match. See `src/alexa-verify.ts`.
- The TV authenticates with a bearer token (compared in constant time). There is a single TV
  per deployment, and a new connection replaces the old one.
- The token is compiled into your APK, so treat the APK as private. To rotate it, run
  `wrangler secret put TV_TOKEN` and rebuild the app.

## Code map

| File | Role |
|---|---|
| `core/voice/VoiceCommand.kt` | wire format, commands and result codes |
| `core/voice/VoiceCommandBus.kt` | routes commands to the screen that owns them (player > app) |
| `core/voice/VoiceCommandClient.kt` | OkHttp WebSocket with reconnect and backoff |
| `ui/screens/player/PlayerVoiceCommands.kt` | maps commands onto existing `PlayerEvent`s |
| `PlayerScreen.kt`, `MainActivity.kt`, `SearchViewModel.kt` | handler registration, socket lifecycle, voice search |
| `alexa/relay-worker/` | Worker and Durable Object relay |
| `alexa/skill/` | interaction models (it-IT, en-US, en-GB) |

To add a command: add it to `VoiceCommand` (Kotlin) and `TvCommand` (TS), handle it in
`PlayerVoiceCommands.kt`, add an intent to the model and `mapRequest`, and add a reply in `speakResult`.
