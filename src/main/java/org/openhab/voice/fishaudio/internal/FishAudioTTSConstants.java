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
 * Constants of the fish.audio TTS service.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class FishAudioTTSConstants {

    public static final String TTS_SERVICE_ID = "fishaudio";
    public static final String TTS_SERVICE_PID = "org.openhab.voice.fishaudio";

    /** The voice id used when the secrets file has a bare {@code reference_id} and no {@code voices} list. */
    public static final String DEFAULT_VOICE_ID = "hal9000";
    public static final String DEFAULT_VOICE_LABEL = "HAL 9000";

    /** Built-in voice that speaks through Google Translate's unofficial endpoint (Danish), no fish.audio call. */
    public static final String GOOGLE_VOICE_PREFIX = "google-";
}
