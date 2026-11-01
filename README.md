# fish.audio Text-to-Speech for openHAB (`fishaudio`)

A **voice add-on** (TTS service) that speaks through [fish.audio](https://fish.audio)'s API with the
voice models named in a secrets file - in this house the HAL 9000 voice - and falls back to Google
Translate's voice when fish.audio does not answer, so no announcement is lost.

Why it exists, how it is wired into the house and how to check it: **[docs/SONOS-HAL.md](../../docs/SONOS-HAL.md)**
(the operating record) and [docs/FISHAUDIO-TTS.md](../../docs/FISHAUDIO-TTS.md) (this add-on).

## Use

```javascript
// a GraalJS rule; the Sonos audio sink saves the volume, plays and restores it
actions.Voice.say('Good evening, Nanna.', 'fishaudio:hal9000', 'sonos:One:koekken', new PercentType(45));
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

Optional `voices` list for more than one model:

```json
{"api_key": "...", "voices": [
  {"id": "hal9000", "label": "HAL 9000", "reference_id": "...", "locale": "en-US", "language": "en"}]}
```

UI parameters (Settings -> Voice -> fish.audio Text-to-Speech): secrets file path, model and speed
defaults (the file wins), timeout, Google fallback on/off and its language (`da`), the error item
(`FishAudio_LastError`, written as `HTTP <code> at <time>` on a failed call - `monitor-fish-audio-credit.js`
mails on it).

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
