# Changelog

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
