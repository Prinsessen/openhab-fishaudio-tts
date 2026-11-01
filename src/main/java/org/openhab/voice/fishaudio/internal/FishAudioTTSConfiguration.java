/*
 * Copyright (c) 2026 Nanna Agesen
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.voice.fishaudio.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration of the fish.audio TTS service (Settings -> Voice -> fish.audio Text-to-Speech).
 * The API key and the voices normally come from the secrets file; {@code apiKey} here overrides the file.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class FishAudioTTSConfiguration {

    /** JSON file with api_key, reference_id (or voices[]), model, language, speed - never in the UI. */
    public String secretsFile = "/etc/openhab/secrets/fish-audio.json";
    /** Overrides api_key from the secrets file when set. */
    public String apiKey = "";
    public String apiUrl = "https://api.fish.audio/v1/tts";
    /** Sent as the "model" header; the secrets file's "model" wins when present. */
    public String model = "s2.1-pro";
    /** Prosody speed; the secrets file's "speed" wins when present. 0.9 = HAL's unhurried delivery. */
    public Double speed = 0.9;
    public Integer mp3Bitrate = 128;
    public Integer timeoutSeconds = 30;
    /** When fish.audio fails: speak through Google Translate instead, so no message is lost. */
    public Boolean fallbackGoogle = true;
    public String googleUrl = "https://translate.google.com/translate_tts";
    /** Language of the Google fallback for a fish.audio voice (the voice's own language is not used on purpose). */
    public String fallbackLanguage = "da";
    /** String item that receives "HTTP <code> at <time>" when a fish.audio call fails; empty = off. */
    public String errorItem = "FishAudio_LastError";
    /** Item whose commands are spoken on the add-on's own thread (fire and forget for rules); empty = off. */
    public String sayItem = "FishAudio_Say";
}
