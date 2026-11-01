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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.items.events.ItemCommandEvent;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.voice.VoiceManager;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The fire-and-forget way to speak: a rule sends a command to the item named by {@code sayItem} (default
 * {@code FishAudio_Say}) and is free at once; this component runs {@code VoiceManager.say()} on its own thread.
 * {@code Voice.say()} itself blocks the calling rule until the clip has been *played* (the Sonos sink waits so it can
 * restore the volume), which held a GraalJS rule file's lock for ~25 s on 2026-11-01 and made another rule in the
 * file fail with "Failed to acquire the lock ... within 5 seconds". One worker thread, so announcements queue in
 * order instead of talking over each other.
 *
 * Command payload: a JSON object {@code {"text": "...", "voice": "fishaudio:hal9000", "sink": "sonos:One:koekken",
 * "volume": 45}} (voice, sink and volume optional - openHAB's defaults apply), or a plain text (defaults for all).
 *
 * @author Nanna Agesen - Initial contribution
 */
@Component(configurationPid = TTS_SERVICE_PID, service = EventSubscriber.class, immediate = true)
@NonNullByDefault
public class FishAudioSayListener implements EventSubscriber {

    /** What one command asks for. */
    public record SayRequest(String text, @Nullable String voice, @Nullable String sink, @Nullable PercentType volume) {
    }

    private final Logger logger = LoggerFactory.getLogger(FishAudioSayListener.class);
    private final VoiceManager voiceManager;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fishaudio-say");
        t.setDaemon(true);
        return t;
    });
    private volatile String sayItem = "";

    @Activate
    public FishAudioSayListener(@Reference VoiceManager voiceManager, Map<String, Object> config) {
        this.voiceManager = voiceManager;
        configure(config);
    }

    /** For tests. */
    FishAudioSayListener(VoiceManager voiceManager, String sayItem) {
        this.voiceManager = voiceManager;
        this.sayItem = sayItem;
    }

    @Modified
    protected void modified(Map<String, Object> config) {
        configure(config);
    }

    private void configure(Map<String, Object> config) {
        sayItem = new Configuration(config).as(FishAudioTTSConfiguration.class).sayItem.trim();
        logger.info("fish.audio say item: {}", sayItem.isEmpty() ? "off" : sayItem);
    }

    @Deactivate
    protected void deactivate() {
        worker.shutdownNow();
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return Set.of(ItemCommandEvent.TYPE);
    }

    @Override
    public @Nullable EventFilter getEventFilter() {
        return null;
    }

    @Override
    public void receive(Event event) {
        if (!(event instanceof ItemCommandEvent e) || sayItem.isEmpty() || !sayItem.equals(e.getItemName())) {
            return;
        }
        SayRequest req = parse(e.getItemCommand().toFullString());
        if (req == null) {
            logger.warn("{}: nothing to say in '{}'", sayItem, e.getItemCommand());
            return;
        }
        worker.execute(() -> say(req));
    }

    private void say(SayRequest req) {
        try {
            voiceManager.say(req.text(), req.voice(), req.sink(), req.volume());
            logger.debug("said on {} with {}: {}", req.sink(), req.voice(), req.text());
        } catch (RuntimeException ex) {
            logger.warn("could not say '{}' on {} with {}: {}", req.text(), req.sink(), req.voice(), ex.getMessage());
        }
    }

    /** JSON {@code {text, voice?, sink?, volume?}} or a plain text; null when there is no text. */
    static @Nullable SayRequest parse(String command) {
        String c = command.trim();
        if (c.isEmpty()) {
            return null;
        }
        if (c.startsWith("{")) {
            try {
                JsonElement el = JsonParser.parseString(c);
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    String text = str(o, "text");
                    if (text == null || text.isBlank()) {
                        return null;
                    }
                    PercentType volume = null;
                    JsonElement v = o.get("volume");
                    if (v != null && v.isJsonPrimitive()) {
                        int pct = Math.max(0, Math.min(100, v.getAsInt()));
                        volume = new PercentType(pct);
                    }
                    return new SayRequest(text.trim(), str(o, "voice"), str(o, "sink"), volume);
                }
            } catch (RuntimeException ignored) {
                // not JSON after all: spoken as plain text
            }
        }
        return new SayRequest(c, null, null, null);
    }

    private static @Nullable String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String s = e.getAsString().trim();
        return s.isEmpty() ? null : s;
    }
}
