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
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The safety net around a Sonos audio sink: what the binding's notification promises and sometimes does not do.
 * The Sonos binding saves the player's volume and source, plays the clip and restores them - but with a queue of
 * music-service tracks (Spotify, measured 2026-11-01 on a Playbar) it puts the queue back and then neither resumes
 * nor restores the volume: the music stops and the player stays at the announcement volume. So, through the items
 * linked to the sink's thing ({@code <sink>:state}, {@code <sink>:volume}, {@code <sink>:control}):
 * <ul>
 * <li>before the clip: remember whether the player was PLAYING, and its volume;</li>
 * <li>after the clip (plus a short settle): put the volume back if it is not, and send PLAY if it was playing and
 * is not any more.</li>
 * </ul>
 * Pure decision logic over a tiny {@link Io}; the listener provides the openHAB side. A sink that is not a thing
 * (no such items) is left alone.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class SinkGuard {

    /** What the guard needs from openHAB: read an item's state, send a command. */
    public interface Io {
        /** The name of the item linked to channel {@code <sink>:<channel>}, or null. */
        @Nullable
        String linkedItem(String sink, String channel);

        /** The item's state as text, or null when the item does not exist. */
        @Nullable
        String read(String item);

        void send(String item, String command);
    }

    /** What was there before the clip. */
    public record Before(@Nullable String stateItem, @Nullable String volumeItem, @Nullable String controlItem,
            boolean wasPlaying, @Nullable Integer volume) {
        public boolean nothing() {
            return stateItem == null && volumeItem == null;
        }
    }

    private final Logger logger = LoggerFactory.getLogger(SinkGuard.class);
    private final Io io;

    public SinkGuard(Io io) {
        this.io = io;
    }

    public Before before(@Nullable String sink) {
        if (sink == null || sink.split(":").length < 3) {
            return new Before(null, null, null, false, null);
        }
        String stateItem = io.linkedItem(sink, "state");
        String volumeItem = io.linkedItem(sink, "volume");
        String controlItem = io.linkedItem(sink, "control");
        boolean playing = stateItem != null && "PLAYING".equals(io.read(stateItem));
        Integer volume = volumeItem != null ? asInt(io.read(volumeItem)) : null;
        return new Before(stateItem, volumeItem, controlItem, playing, volume);
    }

    /** Returns what it did, for the log: "", "volume 45 -> 24", "resumed", or both. */
    public String after(Before b) {
        StringBuilder did = new StringBuilder();
        Integer v = b.volume();
        String volumeItem = b.volumeItem();
        if (volumeItem != null && v != null) {
            Integer now = asInt(io.read(volumeItem));
            if (now == null || !now.equals(v)) {
                io.send(volumeItem, String.valueOf(v));
                did.append("volume ").append(now).append(" -> ").append(v);
            }
        }
        String stateItem = b.stateItem();
        String controlItem = b.controlItem();
        if (b.wasPlaying() && stateItem != null && controlItem != null && !"PLAYING".equals(io.read(stateItem))) {
            io.send(controlItem, "PLAY");
            did.append(did.length() > 0 ? ", " : "").append("resumed");
        }
        if (did.length() > 0) {
            logger.info("sink guard: {}", did);
        }
        return did.toString();
    }

    static @Nullable Integer asInt(@Nullable String s) {
        if (s == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(s.trim().split("\\s+")[0]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
