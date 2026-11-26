# Changelog

## 2.0.0-beta.1 - 2026-11-26

For **openHAB 5.3** (snapshots and milestones from 2026-11-22 on). Same features as 1.0.1, which stays the
version for openHAB 5.0 - 5.2.

- openHAB 5.3 moved to Jetty 12, and the TTS API to `org.openhab.core.voice.tts`; 1.0.1 does not start there.
  The two HTTP calls use the Jetty 12 client API, the imports the new package.
- `FishAudioHttpTest`: both calls against a local server through a real Jetty client (headers, body, the audio
  back, the Google fallback) - passes on 1.0.1 (Jetty 9) and on this one. Not yet run on a live openHAB 5.3.

## 1.0.1 - 2026-11-01

- Sink guard: after the clip, the player's volume is put back to what it was before and the music is
  resumed if the binding did not do it (the Sonos binding with a Spotify queue on a Playbar: once it
  left the player stopped at the announcement volume, once it resumed at volume 2). Through the items
  linked to the sink thing's state/volume/control channels; `sinkGuard` / `sinkGuardSettleSeconds`.
- 23 JUnit tests.

## 1.0.0 - 2026-11-01

First release, for openHAB 5.2 (built in the 5.2.0-SNAPSHOT add-ons reactor).

- TTS service `fishaudio`: fish.audio `POST /v1/tts` with the voice models of a secrets file
  (`api_key`, `reference_id` or a `voices` list; per-voice `language`, `speed`, `model`), mp3 128 kbit.
- Google Translate fallback when fish.audio fails (configurable language), so no announcement is lost;
  `fishaudio:google-da` / `fishaudio:google-en` as voices of their own.
- Error item (`FishAudio_LastError`): `HTTP <code> at <time>` on a failed call, for a watch rule.
- Say item (`FishAudio_Say`): a command - JSON `{text, voice, sink, volume}` or plain text - is spoken on
  the add-on's own worker thread, so a rule never waits for the clip (`Voice.say()` blocks until a
  Sonos sink has played and restored the volume).
- The secrets file is re-read when it changes; nothing secret in the UI configuration.
- 17 JUnit tests.
