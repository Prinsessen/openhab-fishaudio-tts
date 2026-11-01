# fish.audio Text-to-Speech (voice add-on)

A TTS service for openHAB 5.2 that speaks through [fish.audio](https://fish.audio)'s API with the voice
models you name in a secrets file, falls back to Google Translate's voice when fish.audio does not answer,
and gives rules a fire-and-forget way to speak through any audio sink. Written for a house that wanted
HAL 9000 on its Sonos players; it works with any fish.audio voice model and any openHAB audio sink.

## What is new in 1.0.1

- **Sink guard.** With a Sonos player on a music-service queue (Spotify on a Playbar, measured), the Sonos
  binding's notification sometimes leaves the player stopped at the announcement volume, or resumes it at
  volume 2. The add-on now reads the player's state and volume before the clip (through the items linked to
  the sink thing's `state`, `volume` and `control` channels), waits two seconds after it, and then puts the
  volume back and sends PLAY if the music did not come back by itself. On by default (`sinkGuard`); a sink
  that is not a thing is left alone.

## Features

- **fish.audio voices**: every model in the secrets file's `voices` list becomes a voice `fishaudio:<id>`,
  each with its own `language`, `speed` and `model` when they differ from the file's; mp3 at 128 kbit
- **Google Translate fallback**: when fish.audio fails (no credit, token, outage) the same text is spoken by
  Google's voice in a configurable language, so no announcement is lost; `fishaudio:google-da` and
  `fishaudio:google-en` are also voices of their own, with no fish.audio call
- **Say item**: a command to a String item - JSON `{"text", "voice", "sink", "volume"}` or plain text - is
  spoken on the add-on's own worker thread, in order. A rule is free at once. (`Voice.say()` itself returns
  only when a Sonos sink has *played* the clip and restored the volume, which holds the rule file's lock for
  the whole clip; this item is the way around that)
- **Error item**: `HTTP <code> at <time>` / `no api_key at ...` / `no answer at ...` on a failed call, so a
  rule can mail you
- **openHAB's TTS cache**: every clip is kept per text and voice; a repeated line costs no credit
- **Nothing secret in the UI**: the API key and voice ids live in a file openHAB can read; the file is
  re-read when it changes, so a new voice is one edit, no restart
- 23 JUnit tests

## Requirements

- openHAB 5.2, Java 21
- A fish.audio account with **prepaid API credit** (the API is paid, pay-as-you-go, no subscription - see Cost), an API key, and the `reference_id` of at least one voice model
- An audio sink (the Sonos binding's players, `webaudio`, `enhancedjavasound`, ...)

## Configuration

Create the secrets file (default `/etc/openhab/secrets/fish-audio.json`, readable by the openhab user,
`640`):

```json
{"api_key": "...", "reference_id": "<voice model id>", "model": "s2.1-pro", "language": "en", "speed": 0.9}
```

or, for more than one voice:

```json
{"api_key": "...", "model": "s2.1-pro", "speed": 0.9, "voices": [
  {"id": "hal9000", "label": "HAL 9000", "reference_id": "...", "locale": "en-US", "language": "en"},
  {"id": "nanna",   "label": "Nanna",    "reference_id": "...", "locale": "da-DK", "language": "da", "speed": 1.0}]}
```

A bare `reference_id` becomes the voice `fishaudio:hal9000` (en-US). The file wins over the UI for `model`
and `speed`; a UI `apiKey` wins over the file.

Settings -> Voice -> fish.audio Text-to-Speech:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `secretsFile` | `/etc/openhab/secrets/fish-audio.json` | The file above |
| `apiKey` | - | Overrides the file's key |
| `apiUrl` | `https://api.fish.audio/v1/tts` | |
| `model` | `s2.1-pro` | fish.audio model, unless the file or the voice says otherwise |
| `speed` | 0.9 | Prosody speed, same precedence |
| `mp3Bitrate` | 128 | |
| `timeoutSeconds` | 30 | |
| `fallbackGoogle` | true | Speak through Google Translate when fish.audio fails |
| `fallbackLanguage` | `da` | Google's language for the fallback |
| `errorItem` | `FishAudio_LastError` | String item that gets the error text |
| `sayItem` | `FishAudio_Say` | String item whose commands are spoken |
| `sinkGuard` | true | Put the sink's volume back and resume its music after the clip |
| `sinkGuardSettleSeconds` | 2 | How long to give the sink's own restore first |

The service can be the default TTS (Settings -> Voice).

## Quick Start

### fishaudio.items

```java
String FishAudio_Say       "Say [%s]"        <speaker> { autoupdate="false" }
String FishAudio_LastError "TTS error [%s]"  <error>
```

### Rule (JS Scripting)

```javascript
// fire and forget: the add-on speaks it on its own thread; the rule is free at once
items.getItem('FishAudio_Say').sendCommand(JSON.stringify({
  text: 'Good evening. The front door is open.',
  voice: 'fishaudio:hal9000',
  sink: 'sonos:One:kitchen',
  volume: 45
}));
```

A plain text command uses openHAB's default voice and sink. From a terminal, the voice REST works too:

```bash
curl -s -X POST -H 'Content-Type: text/plain' --data 'Hello' \
  'http://localhost:8080/rest/voice/say?voiceid=fishaudio:hal9000&sinkid=sonos:One:kitchen&volume=45'
```

`curl -s http://localhost:8080/rest/voice/voices` lists the voices the add-on registered.

## Cost

fish.audio's API is **paid, pay-as-you-go**: no subscription is needed, but the account must hold
prepaid API credit (the web plans' monthly credits are a different purse). The price list
(docs.fish.audio, read 2026-11-01) bills text-to-speech per million UTF-8 bytes of input text:
`s2.1-pro` and `s2-pro` 15 USD, `s1` 15 USD (retires 2026-12-31), `drama-3` 50 USD, and a
`s2.1-pro-free` model listed at 0 USD (what it limits is not stated on that page; set `model` to try
it). One English character is one byte, Danish `æøå` are two, so a one-sentence announcement of
about 100 characters costs around 0.15 US cent with `s2.1-pro`; openHAB's TTS cache keeps every
clip, so a line that repeats costs nothing the second time. When the credit is gone, fish.audio
answers HTTP 402; the add-on writes it to the error item and speaks through Google Translate instead
(free, no key). Watch the balance from a rule if it matters: `GET /wallet/self/api-credit` with the
same API key returns it.

## What it does not do

- It does not split long texts for the Google fallback (about 200 characters per request). Keep lines short.
- It does not choose rooms or quiet hours; that is your rules.

## Resources

- **Download JAR (v1.0.1):** [org.openhab.voice.fishaudio-1.0.1.jar](https://github.com/Prinsessen/openhab-fishaudio-tts/releases/download/v1.0.1/org.openhab.voice.fishaudio-1.0.1.jar) (about 32 KB)
- **Install:** copy the JAR into the openHAB `addons` folder (`/usr/share/openhab/addons/` on apt installs,
  `$OPENHAB_HOME/addons/` otherwise); it is picked up without a restart. Remove an older fish.audio JAR first.
- **Source code:** [github.com/Prinsessen/openhab-fishaudio-tts](https://github.com/Prinsessen/openhab-fishaudio-tts)
- **Documentation:** [README.md](https://github.com/Prinsessen/openhab-fishaudio-tts/blob/main/README.md)
- **Changelog:** [CHANGELOG.md](https://github.com/Prinsessen/openhab-fishaudio-tts/blob/main/CHANGELOG.md)
- **License:** EPL-2.0
- **Maintainer:** Nanna Agesen (GitHub: Prinsessen)
