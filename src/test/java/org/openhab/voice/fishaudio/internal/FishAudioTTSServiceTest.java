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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.voice.TTSCache;
import org.openhab.core.voice.Voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The pure parts: the secrets file, the request body (the shape scripts/tts-fetch.sh sent), the voice list, the
 * Google fallback URL and the error text the credit monitor reads. The HTTP calls are not exercised here; the
 * device's answer is read in the log after deploy (house rule).
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class FishAudioTTSServiceTest {

    private static final String SECRETS = "{\"api_key\": \"k-1\", \"reference_id\": \"abc123\", \"model\": \"s2.1-pro\","
            + " \"language\": \"en\", \"speed\": 0.9}";

    private FishAudioTTSService service(FishAudioTTSConfiguration cfg, String secretsJson) {
        FishAudioTTSService s = new FishAudioTTSService(mock(HttpClient.class), mock(EventPublisher.class),
                mock(TTSCache.class), cfg);
        s.setSecrets(FishAudioSecrets.parse(secretsJson));
        return s;
    }

    @Test
    public void bareReferenceIdBecomesTheHalVoice() {
        FishAudioSecrets s = FishAudioSecrets.parse(SECRETS);
        assertEquals("k-1", s.apiKey);
        assertEquals(1, s.voices.size());
        FishAudioTTSVoice v = s.voices.get(0);
        assertEquals("fishaudio:hal9000", v.getUID());
        assertEquals("HAL 9000", v.getLabel());
        assertEquals("abc123", v.getReferenceId());
        assertEquals(Locale.forLanguageTag("en-US"), v.getLocale());
        assertEquals("en", v.getLanguage());
        assertFalse(v.isGoogle());
    }

    @Test
    public void aVoicesListReplacesTheBareId() {
        FishAudioSecrets s = FishAudioSecrets.parse("{\"api_key\": \"k\", \"reference_id\": \"old\", \"voices\": ["
                + "{\"id\": \"hal9000\", \"label\": \"HAL 9000\", \"reference_id\": \"h1\", \"locale\": \"en-US\"},"
                + "{\"id\": \"lauge\", \"label\": \"Lauge\", \"reference_id\": \"l1\", \"locale\": \"da-DK\", \"language\": \"da\"},"
                + "{\"id\": \"broken\"}]}");
        assertEquals(2, s.voices.size(), "an entry without reference_id is skipped");
        assertEquals("h1", s.voices.get(0).getReferenceId());
        assertEquals("da", s.voices.get(1).getLanguage());
        assertEquals(Locale.forLanguageTag("da-DK"), s.voices.get(1).getLocale());
    }

    @Test
    public void aMalformedFileIsEmptyNotAnException() {
        assertTrue(FishAudioSecrets.parse("not json").voices.isEmpty());
        assertTrue(FishAudioSecrets.parse("[1,2]").voices.isEmpty());
        assertEquals("", FishAudioSecrets.parse("{}").apiKey);
    }

    @Test
    public void theRequestBodyIsWhatTheScriptSent() {
        FishAudioTTSService s = service(new FishAudioTTSConfiguration(), SECRETS);
        FishAudioTTSVoice hal = (FishAudioTTSVoice) s.getAvailableVoices().iterator().next();
        JsonObject b = JsonParser.parseString(s.fishBody("Good evening, Nanna.", hal)).getAsJsonObject();
        assertEquals("Good evening, Nanna.", b.get("text").getAsString());
        assertEquals("abc123", b.get("reference_id").getAsString());
        assertEquals("mp3", b.get("format").getAsString());
        assertEquals(128, b.get("mp3_bitrate").getAsInt());
        assertEquals("normal", b.get("latency").getAsString());
        assertEquals("en", b.get("language").getAsString());
        assertEquals(0.9, b.getAsJsonObject("prosody").get("speed").getAsDouble(), 1e-9);
        assertEquals(0, b.getAsJsonObject("prosody").get("volume").getAsInt());
        assertEquals("s2.1-pro", s.model());
        assertEquals("k-1", s.apiKey());
    }

    @Test
    public void theSecretsFileWinsOverTheConfigForModelAndSpeedAndTheConfigKeyOverridesTheFile() {
        FishAudioTTSConfiguration cfg = new FishAudioTTSConfiguration();
        cfg.model = "s1";
        cfg.speed = 1.0;
        FishAudioTTSService s = service(cfg, SECRETS);
        assertEquals("s2.1-pro", s.model());
        assertEquals(0.9, s.speed(), 1e-9);
        FishAudioTTSService noFile = service(cfg, "{\"api_key\": \"k\", \"reference_id\": \"r\"}");
        assertEquals("s1", noFile.model());
        assertEquals(1.0, noFile.speed(), 1e-9);
        cfg.apiKey = "override";
        assertEquals("override", s.apiKey());
    }

    @Test
    public void theVoiceListHasTheFishVoicesFirstThenTheTwoGoogleVoices() {
        FishAudioTTSService s = service(new FishAudioTTSConfiguration(), SECRETS);
        Set<Voice> voices = s.getAvailableVoices();
        assertEquals(3, voices.size());
        Voice[] v = voices.toArray(new Voice[0]);
        assertEquals("fishaudio:hal9000", v[0].getUID());
        assertEquals("fishaudio:google-da", v[1].getUID());
        assertEquals(Locale.forLanguageTag("da"), v[1].getLocale());
        assertTrue(((FishAudioTTSVoice) v[1]).isGoogle());
        assertEquals("fishaudio:google-en", v[2].getUID());
    }

    @Test
    public void withoutASecretsFileOnlyTheGoogleVoicesRemain() {
        FishAudioTTSService s = service(new FishAudioTTSConfiguration(), "{}");
        assertEquals(2, s.getAvailableVoices().size());
    }

    @Test
    public void theGoogleUrlIsTheScriptsWithTheTextEncoded() {
        FishAudioTTSService s = service(new FishAudioTTSConfiguration(), SECRETS);
        assertEquals("https://translate.google.com/translate_tts?ie=UTF-8&tl=da&client=tw-ob&q=Bedstemor%2C+det+er+Lauge",
                s.googleUrl("Bedstemor, det er Lauge", "da"));
    }

    @Test
    public void theErrorTextIsTheScriptsShape() {
        assertEquals("HTTP 402 at 2026-11-01T10:57:14",
                FishAudioTTSService.errorText("HTTP 402", LocalDateTime.of(2026, 11, 1, 10, 57, 14)));
    }

    @Test
    public void aFailedCallWritesTheErrorItemAndFallsBackOrThrows() throws Exception {
        // no api_key: the fish call fails before any HTTP; fallback off -> TTSException and one event posted
        FishAudioTTSConfiguration cfg = new FishAudioTTSConfiguration();
        cfg.fallbackGoogle = false;
        EventPublisher publisher = mock(EventPublisher.class);
        FishAudioTTSService s = new FishAudioTTSService(mock(HttpClient.class), publisher, mock(TTSCache.class), cfg);
        s.setSecrets(FishAudioSecrets.parse("{\"reference_id\": \"r\"}"));
        FishAudioTTSVoice hal = (FishAudioTTSVoice) s.getAvailableVoices().iterator().next();
        assertThrows(org.openhab.core.voice.TTSException.class, () -> s.synthesizeForCache("x", hal,
                s.getSupportedFormats().iterator().next()));
        verify(publisher, times(1)).post(any(Event.class));
    }
}
