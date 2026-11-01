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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.ByteArrayAudioStream;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.StringType;
import org.openhab.core.voice.AbstractCachedTTSService;
import org.openhab.core.voice.TTSCache;
import org.openhab.core.voice.TTSException;
import org.openhab.core.voice.TTSService;
import org.openhab.core.voice.Voice;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Text-to-speech through fish.audio (https://fish.audio, API https://api.fish.audio/v1/tts) with the voice models
 * named in the secrets file, plus a Google Translate fallback so no announcement is lost when the cloud or the
 * credit fails. Replaces the house's scripts/tts-fetch.sh + sonos_tts.sh chain: a rule now calls
 * {@code actions.Voice.say(text, "fishaudio:hal9000", "sonos:One:koekken", volume)} and the Sonos audio sink does
 * the volume save/restore. openHAB's TTS cache keeps the mp3 per text and voice.
 *
 * @author Nanna Agesen - Initial contribution
 */
@Component(configurationPid = TTS_SERVICE_PID, property = Constants.SERVICE_PID + "="
        + TTS_SERVICE_PID, service = TTSService.class)
@ConfigurableService(category = "voice", label = "fish.audio Text-to-Speech", description_uri = "voice:"
        + TTS_SERVICE_ID)
@NonNullByDefault
public class FishAudioTTSService extends AbstractCachedTTSService {

    private static final DateTimeFormatter ERROR_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Set<AudioFormat> FORMATS = Set
            .of(new AudioFormat(AudioFormat.CONTAINER_NONE, AudioFormat.CODEC_MP3, null, 16, 128000, 44100L));

    private final Logger logger = LoggerFactory.getLogger(FishAudioTTSService.class);
    private final HttpClient httpClient;
    private final EventPublisher eventPublisher;
    private final Gson gson = new Gson();

    private FishAudioTTSConfiguration config = new FishAudioTTSConfiguration();
    private FishAudioSecrets secrets = FishAudioSecrets.empty();
    private long secretsMtime = -1;
    /** Tests pin their secrets; the file is then never read. */
    private boolean secretsPinned = false;

    @Activate
    public FishAudioTTSService(@Reference HttpClientFactory httpClientFactory, @Reference TTSCache ttsCache,
            @Reference EventPublisher eventPublisher, Map<String, Object> config) {
        super(ttsCache);
        this.httpClient = httpClientFactory.getCommonHttpClient();
        this.eventPublisher = eventPublisher;
        configure(config);
    }

    /** For tests: no OSGi, no HTTP client factory. */
    protected FishAudioTTSService(HttpClient httpClient, EventPublisher eventPublisher, TTSCache ttsCache,
            FishAudioTTSConfiguration config) {
        super(ttsCache);
        this.httpClient = httpClient;
        this.eventPublisher = eventPublisher;
        this.config = config;
    }

    @Modified
    protected void modified(Map<String, Object> config) {
        configure(config);
    }

    private void configure(Map<String, Object> config) {
        this.config = new Configuration(config).as(FishAudioTTSConfiguration.class);
        this.secretsMtime = -1;
        reloadSecretsIfChanged();
        logger.info("fish.audio TTS: {} voice(s) from {}, model {}, fallback {}", secrets.voices.size(),
                this.config.secretsFile, model(), this.config.fallbackGoogle ? "Google Translate" : "off");
    }

    // --- TTSService ------------------------------------------------------------------------------

    @Override
    public String getId() {
        return TTS_SERVICE_ID;
    }

    @Override
    public String getLabel(@Nullable Locale locale) {
        return "fish.audio Text-to-Speech";
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return FORMATS;
    }

    @Override
    public Set<Voice> getAvailableVoices() {
        reloadSecretsIfChanged();
        Set<Voice> voices = new LinkedHashSet<>(secrets.voices);
        voices.add(FishAudioTTSVoice.google("da", "Google Translate (Danish)"));
        voices.add(FishAudioTTSVoice.google("en", "Google Translate (English)"));
        return voices;
    }

    @Override
    public AudioStream synthesizeForCache(String text, Voice voice, AudioFormat requestedFormat) throws TTSException {
        if (!(voice instanceof FishAudioTTSVoice v)) {
            throw new TTSException("Not a fish.audio voice: " + voice.getUID());
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new TTSException("Nothing to say");
        }
        if (v.isGoogle()) {
            return new ByteArrayAudioStream(google(trimmed, v.getLanguage()), requestedFormat);
        }
        reloadSecretsIfChanged();
        try {
            return new ByteArrayAudioStream(fish(trimmed, v), requestedFormat);
        } catch (TTSException e) {
            if (!config.fallbackGoogle) {
                throw e;
            }
            logger.warn("fish.audio failed ({}) - speaking through Google Translate ({}) instead", e.getMessage(),
                    config.fallbackLanguage);
            return new ByteArrayAudioStream(google(trimmed, config.fallbackLanguage), requestedFormat);
        }
    }

    // --- fish.audio ------------------------------------------------------------------------------

    /** The request body, exactly as scripts/tts-fetch.sh sent it (2026-10-30). */
    String fishBody(String text, FishAudioTTSVoice voice) {
        JsonObject prosody = new JsonObject();
        prosody.addProperty("speed", speed(voice));
        prosody.addProperty("volume", 0);
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("reference_id", voice.getReferenceId());
        body.addProperty("format", "mp3");
        body.addProperty("mp3_bitrate", config.mp3Bitrate);
        body.addProperty("latency", "normal");
        body.addProperty("language", voice.getLanguage());
        body.add("prosody", prosody);
        return gson.toJson(body);
    }

    /** The voice's own model, else the file's, else the service's. */
    String model(@Nullable FishAudioTTSVoice voice) {
        String vm = voice != null ? voice.getModel() : null;
        if (vm != null) {
            return vm;
        }
        String m = secrets.model;
        return m != null && !m.isEmpty() ? m : config.model;
    }

    String model() {
        return model(null);
    }

    /** The voice's own speed, else the file's, else the service's. */
    double speed(@Nullable FishAudioTTSVoice voice) {
        Double vs = voice != null ? voice.getSpeed() : null;
        if (vs != null) {
            return vs;
        }
        Double s = secrets.speed;
        return s != null ? s : config.speed;
    }

    double speed() {
        return speed(null);
    }

    String apiKey() {
        return config.apiKey.isEmpty() ? secrets.apiKey : config.apiKey;
    }

    private byte[] fish(String text, FishAudioTTSVoice voice) throws TTSException {
        String key = apiKey();
        if (key.isEmpty()) {
            reportError("no api_key");
            throw new TTSException("fish.audio: no api_key in " + config.secretsFile);
        }
        String body = fishBody(text, voice);
        logger.debug("fish.audio request for voice {}: {} chars", voice.getId(), text.length());
        try {
            ContentResponse response = httpClient.newRequest(config.apiUrl).method(HttpMethod.POST)
                    .timeout(config.timeoutSeconds, TimeUnit.SECONDS).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json").header("model", model(voice))
                    .content(new StringContentProvider(body, StandardCharsets.UTF_8)).send();
            int status = response.getStatus();
            if (status == 200 && response.getContent().length > 0) {
                return response.getContent();
            }
            reportError("HTTP " + status);
            throw new TTSException("fish.audio answered HTTP " + status);
        } catch (InterruptedException | java.util.concurrent.TimeoutException
                | java.util.concurrent.ExecutionException e) {
            reportError("no answer");
            throw new TTSException("fish.audio: " + e.getMessage());
        }
    }

    // --- Google Translate fallback -----------------------------------------------------------------

    String googleUrl(String text, String language) {
        return config.googleUrl + "?ie=UTF-8&tl=" + language + "&client=tw-ob&q="
                + URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private byte[] google(String text, String language) throws TTSException {
        try {
            ContentResponse response = httpClient.newRequest(googleUrl(text, language)).method(HttpMethod.GET)
                    .timeout(config.timeoutSeconds, TimeUnit.SECONDS).header("User-Agent", "Mozilla/5.0").send();
            if (response.getStatus() == 200 && response.getContent().length > 0) {
                return response.getContent();
            }
            throw new TTSException("Google Translate answered HTTP " + response.getStatus());
        } catch (InterruptedException | java.util.concurrent.TimeoutException
                | java.util.concurrent.ExecutionException e) {
            throw new TTSException("Google Translate: " + e.getMessage());
        }
    }

    // --- the error item (monitor-fish-audio-credit.js mails on it) ----------------------------------

    /** The text written to the error item: the same shape as tts-fetch.sh wrote, so the monitor rule is unchanged. */
    static String errorText(String what, LocalDateTime now) {
        return what + " at " + ERROR_TIME.format(now);
    }

    private void reportError(String what) {
        String item = config.errorItem;
        if (item.isEmpty()) {
            return;
        }
        try {
            eventPublisher.post(ItemEventFactory.createStateEvent(item,
                    new StringType(errorText(what, LocalDateTime.now())), TTS_SERVICE_ID));
        } catch (RuntimeException e) {
            logger.debug("could not update {}: {}", item, e.getMessage());
        }
    }

    // --- the secrets file -------------------------------------------------------------------------

    private synchronized void reloadSecretsIfChanged() {
        if (secretsPinned) {
            return;
        }
        Path p = Path.of(config.secretsFile);
        try {
            long mtime = Files.getLastModifiedTime(p).toMillis();
            if (mtime == secretsMtime) {
                return;
            }
            secrets = FishAudioSecrets.parse(Files.readString(p, StandardCharsets.UTF_8));
            secretsMtime = mtime;
            if (secrets.voices.isEmpty()) {
                logger.warn("{} has no reference_id / voices - only the Google voices are available", p);
            }
        } catch (IOException e) {
            if (secretsMtime != -2) {
                logger.warn("cannot read {}: {} - only the Google voices are available", p, e.getMessage());
            }
            secrets = FishAudioSecrets.empty();
            secretsMtime = -2;
        }
    }

    /** For tests: these secrets, and the file is never read. */
    void setSecrets(FishAudioSecrets s) {
        this.secrets = s;
        this.secretsPinned = true;
    }
}
