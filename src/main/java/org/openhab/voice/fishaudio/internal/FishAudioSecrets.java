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

import static org.openhab.voice.fishaudio.internal.FishAudioTTSConstants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The secrets file ({@code /etc/openhab/secrets/fish-audio.json}, the same one {@code scripts/tts-fetch.sh} reads):
 *
 * <pre>
 * {"api_key": "...", "reference_id": "&lt;voice model id&gt;", "model": "s2.1-pro", "language": "en", "speed": 0.9,
 *  "voices": [{"id": "hal9000", "label": "HAL 9000", "reference_id": "...", "locale": "en-US", "language": "en",
 *              "speed": 0.9, "model": "s2.1-pro"}]}
 * </pre>
 *
 * {@code voices} is optional: without it the bare {@code reference_id} becomes the voice {@code hal9000}.
 * Parsing is pure so it can be tested without a file.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class FishAudioSecrets {

    public final String apiKey;
    public final @Nullable String model;
    public final String language;
    public final @Nullable Double speed;
    public final List<FishAudioTTSVoice> voices;

    private FishAudioSecrets(String apiKey, @Nullable String model, String language, @Nullable Double speed,
            List<FishAudioTTSVoice> voices) {
        this.apiKey = apiKey;
        this.model = model;
        this.language = language;
        this.speed = speed;
        this.voices = voices;
    }

    public static FishAudioSecrets empty() {
        return new FishAudioSecrets("", null, "en", null, List.of());
    }

    /** Parses the JSON text; a malformed file yields {@link #empty()} rather than an exception. */
    public static FishAudioSecrets parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return empty();
        }
        if (!root.isJsonObject()) {
            return empty();
        }
        JsonObject o = root.getAsJsonObject();
        String apiKey = str(o, "api_key", "");
        String model = o.has("model") ? str(o, "model", "") : null;
        String language = str(o, "language", "en");
        Double speed = o.has("speed") && o.get("speed").isJsonPrimitive() ? o.get("speed").getAsDouble() : null;
        List<FishAudioTTSVoice> voices = new ArrayList<>();
        if (o.has("voices") && o.get("voices").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("voices");
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject v = e.getAsJsonObject();
                String ref = str(v, "reference_id", "");
                String id = str(v, "id", "");
                if (ref.isEmpty() || id.isEmpty()) {
                    continue;
                }
                String lang = str(v, "language", language);
                Double vSpeed = v.has("speed") && v.get("speed").isJsonPrimitive() ? v.get("speed").getAsDouble() : null;
                String vModel = v.has("model") ? str(v, "model", "") : null;
                voices.add(new FishAudioTTSVoice(id, str(v, "label", id), ref,
                        Locale.forLanguageTag(str(v, "locale", lang)), lang, vSpeed,
                        vModel == null || vModel.isEmpty() ? null : vModel));
            }
        }
        String ref = str(o, "reference_id", "");
        if (voices.isEmpty() && !ref.isEmpty()) {
            voices.add(new FishAudioTTSVoice(DEFAULT_VOICE_ID, DEFAULT_VOICE_LABEL, ref,
                    Locale.forLanguageTag(language.equals("en") ? "en-US" : language), language));
        }
        return new FishAudioSecrets(apiKey, model, language, speed, List.copyOf(voices));
    }

    private static String str(JsonObject o, String key, String dflt) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : dflt;
    }
}
