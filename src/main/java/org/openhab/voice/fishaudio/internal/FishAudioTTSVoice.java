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

import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.voice.Voice;

/**
 * One voice: a fish.audio voice model ({@code referenceId} set) or a built-in Google Translate voice
 * ({@code referenceId} empty, id {@code google-<lang>}).
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class FishAudioTTSVoice implements Voice {

    private final String id;
    private final String label;
    private final String referenceId;
    private final Locale locale;
    /** Language sent to fish.audio ("en") or Google Translate ("da"). */
    private final String language;

    public FishAudioTTSVoice(String id, String label, String referenceId, Locale locale, String language) {
        this.id = id;
        this.label = label;
        this.referenceId = referenceId;
        this.locale = locale;
        this.language = language;
    }

    public static FishAudioTTSVoice google(String language, String label) {
        return new FishAudioTTSVoice(FishAudioTTSConstants.GOOGLE_VOICE_PREFIX + language, label, "",
                Locale.forLanguageTag(language), language);
    }

    @Override
    public String getUID() {
        return FishAudioTTSConstants.TTS_SERVICE_ID + ":" + id;
    }

    public String getId() {
        return id;
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getLanguage() {
        return language;
    }

    public boolean isGoogle() {
        return referenceId.isEmpty();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof FishAudioTTSVoice v && v.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return getUID() + " (" + label + ", " + locale + ")";
    }
}
