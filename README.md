# fish.audio Text-to-Speech for openHAB (`fishaudio`)

A **voice add-on** (TTS service) that speaks through [fish.audio](https://fish.audio)'s API with the
voice models named in a secrets file - in this house the HAL 9000 voice - and falls back to Google
Translate's voice when fish.audio does not answer, so no announcement is lost.

Why it exists, how it is wired into the house and how to check it: **[docs/SONOS-HAL.md](../../docs/SONOS-HAL.md)**
(the operating record) and [docs/FISHAUDIO-TTS.md](../../docs/FISHAUDIO-TTS.md) (this add-on).

## Use

```javascript
// a GraalJS rule - fire and forget: the add-on speaks it on its own thread, the Sonos audio sink
// saves the volume, plays and restores it, and the rule is free at once
items.getItem('FishAudio_Say').sendCommand(JSON.stringify({
  text: 'Good evening, Nanna.', voice: 'fishaudio:hal9000', sink: 'sonos:One:koekken', volume: 45 }));
```

`FishAudio_Say` is any String item named in the `sayItem` setting (`{ autoupdate="false" }` keeps it
stateless). Do not call `actions.Voice.say()` from a rule for a Sonos sink: it returns only when the
clip has *played*, and holds the rule file's lock meanwhile. From a terminal, `say` is fine:

```bash
curl -s -X POST -H 'Content-Type: text/plain' --data 'Hello' \
  'http://localhost:8080/rest/voice/say?voiceid=fishaudio:hal9000&sinkid=sonos:One:koekken&volume=45'
```

Voices: `fishaudio:hal9000` (or every entry of the secrets file's `voices` list), `fishaudio:google-da`,
`fishaudio:google-en` (Google Translate, no fish.audio call). Settings -> Voice shows the service; it can
be the default TTS.

## Configuration

Nothing is typed into the UI. The service reads `/etc/openhab/secrets/fish-audio.json` (the same file
`scripts/tts-fetch.sh` uses) and re-reads it when it changes:

```json
{"api_key": "...", "reference_id": "<voice model id>", "model": "s2.1-pro", "language": "en", "speed": 0.9}
```

Optional `voices` list for more than one model, each with its own `language`, `speed` and `model`
when they differ from the file's:

```json
{"api_key": "...", "model": "s2.1-pro", "speed": 0.9, "voices": [
  {"id": "hal9000", "label": "HAL 9000", "reference_id": "...", "locale": "en-US", "language": "en"},
  {"id": "nanna",   "label": "Nanna",    "reference_id": "...", "locale": "da-DK", "language": "da", "speed": 1.0}]}
```

Voice uids are `fishaudio:<id>`. `fishaudio:google-da` and `fishaudio:google-en` are always there
(Google Translate, no fish.audio call).

**Sink guard.** With a Sonos player that is playing music from a service queue (Spotify), the Sonos
binding's notification does not reliably resume the music or restore the volume afterwards. The add-on
reads the player's state and volume before the clip (through the items linked to the sink thing's
`state`, `volume` and `control` channels), waits two seconds after it, and then puts the volume back and
sends PLAY if needed. On by default (`sinkGuard`); a sink that is not a thing is left alone.

UI parameters (Settings -> Voice -> fish.audio Text-to-Speech): secrets file path, model and speed
defaults (the file wins), timeout, Google fallback on/off and its language (`da`), the error item
(`FishAudio_LastError`, written as `HTTP <code> at <time>` on a failed call - `monitor-fish-audio-credit.js`
mails on it) and the say item (`FishAudio_Say`).

## Build and deploy

```bash
scripts/deploy-binding.sh /etc/openhab/source-code/fishaudio-voice            # build in the reactor + hot-deploy
BUILD_ONLY=1 scripts/deploy-binding.sh /etc/openhab/source-code/fishaudio-voice   # build only
```

JUnit tests (run by hand, the deploy script skips them):

```bash
cd /etc/openhab-addons && mvn -o -q -pl bundles/org.openhab.voice.fishaudio test -Dspotless.check.skip=true
```

Author: Nanna Agesen. License: EPL-2.0.
