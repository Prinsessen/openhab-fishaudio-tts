/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.voice.fishaudio.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.voice.tts.TTSCache;
import org.openhab.core.voice.tts.TTSException;

import com.sun.net.httpserver.HttpServer;

/**
 * The two HTTP calls against a local server, through a real Jetty client: what reaches fish.audio (method,
 * headers, body) and what comes back, and the Google fallback when fish.audio fails. Written for the move to
 * Jetty 12 (openHAB 5.3), which changed exactly these calls: the same tests pass on the Jetty 9 client
 * (openHAB 5.2) and the Jetty 12 one.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class FishAudioHttpTest {

    private static final String SECRETS = "{\"api_key\": \"k-1\", \"reference_id\": \"abc123\", \"model\": \"s2.1-pro\","
            + " \"language\": \"en\", \"speed\": 0.9}";
    private static final byte[] FISH_MP3 = "ID3-fish-audio".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GOOGLE_MP3 = "ID3-google".getBytes(StandardCharsets.US_ASCII);

    private @Nullable HttpServer server;
    private @Nullable HttpClient client;
    private final List<Map<String, String>> requests = new ArrayList<>();
    private int fishStatus = 200;
    private int port;

    @TempDir
    @Nullable
    Path tmp;

    @BeforeEach
    void start() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", exchange -> {
            Map<String, String> r = new ConcurrentHashMap<>();
            r.put("method", exchange.getRequestMethod());
            r.put("path", exchange.getRequestURI().getPath());
            String q = exchange.getRequestURI().getRawQuery();
            r.put("query", q == null ? "" : URLDecoder.decode(q, StandardCharsets.UTF_8));
            exchange.getRequestHeaders().forEach((k, v) -> r.put("h:" + k.toLowerCase(), String.join(",", v)));
            r.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            synchronized (requests) {
                requests.add(r);
            }
            boolean fish = r.get("path").startsWith("/v1/tts");
            byte[] answer = fish ? FISH_MP3 : GOOGLE_MP3;
            int status = fish ? fishStatus : 200;
            exchange.sendResponseHeaders(status, status == 200 ? answer.length : -1);
            if (status == 200) {
                exchange.getResponseBody().write(answer);
            }
            exchange.close();
        });
        s.start();
        server = s;
        port = s.getAddress().getPort();
        HttpClient c = new HttpClient();
        c.start();
        client = c;
    }

    @AfterEach
    void stop() throws Exception {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
        }
        HttpClient c = client;
        if (c != null) {
            c.stop();
        }
    }

    private FishAudioTTSService service(boolean fallback) throws Exception {
        Path dir = tmp;
        assertNotNull(dir);
        Path secrets = dir.resolve("fish-audio.json");
        Files.writeString(secrets, SECRETS);
        FishAudioTTSConfiguration cfg = new FishAudioTTSConfiguration();
        cfg.secretsFile = secrets.toString(); // never the house's real key
        cfg.apiUrl = "http://127.0.0.1:" + port + "/v1/tts";
        cfg.googleUrl = "http://127.0.0.1:" + port + "/translate_tts";
        cfg.fallbackGoogle = fallback;
        cfg.timeoutSeconds = 10;
        HttpClient c = client;
        assertNotNull(c);
        FishAudioTTSService s = new FishAudioTTSService(c, mock(EventPublisher.class), mock(TTSCache.class), cfg);
        s.setSecrets(FishAudioSecrets.parse(SECRETS));
        return s;
    }

    private static FishAudioTTSVoice hal(FishAudioTTSService s) {
        return (FishAudioTTSVoice) s.getAvailableVoices().iterator().next();
    }

    @Test
    void fishAudioGetsTheKeyTheModelAndTheBodyAndItsAudioComesBack() throws Exception {
        FishAudioTTSService s = service(false);
        FishAudioTTSVoice hal = hal(s);
        AudioStream audio = s.synthesizeForCache("I am HAL.", hal, s.getSupportedFormats().iterator().next());
        assertArrayEquals(FISH_MP3, audio.readAllBytes());
        assertEquals(1, requests.size());
        Map<String, String> r = requests.get(0);
        assertEquals("POST", r.get("method"));
        assertEquals("/v1/tts", r.get("path"));
        assertEquals("Bearer k-1", r.get("h:authorization"));
        assertTrue(r.getOrDefault("h:content-type", "").startsWith("application/json"), r.toString());
        assertEquals("s2.1-pro", r.get("h:model"));
        assertEquals(s.fishBody("I am HAL.", hal), r.get("body"));
    }

    @Test
    void whenFishAudioFailsGoogleSpeaksTheText() throws Exception {
        fishStatus = 500;
        FishAudioTTSService s = service(true);
        AudioStream audio = s.synthesizeForCache("Bedstemor, det er Lauge", hal(s),
                s.getSupportedFormats().iterator().next());
        assertArrayEquals(GOOGLE_MP3, audio.readAllBytes());
        assertEquals(2, requests.size());
        Map<String, String> g = requests.get(1);
        assertEquals("GET", g.get("method"));
        assertEquals("/translate_tts", g.get("path"));
        // Jetty 9's header() adds to the client's own agent ("Jetty/9.4.58,Mozilla/5.0" - two headers, the way
        // it has always gone out); Jetty 12's put() replaces it. Google answers both.
        assertTrue(g.getOrDefault("h:user-agent", "").contains("Mozilla/5.0"), g.toString());
        assertTrue(g.get("query").contains("q=Bedstemor, det er Lauge"), g.get("query"));
        assertTrue(g.get("query").contains("tl=da"), g.get("query"));
    }

    @Test
    void anHttpErrorWithoutFallbackIsATtsException() throws Exception {
        fishStatus = 500;
        FishAudioTTSService s = service(false);
        TTSException e = assertThrows(TTSException.class,
                () -> s.synthesizeForCache("x", hal(s), s.getSupportedFormats().iterator().next()));
        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
    }
}
