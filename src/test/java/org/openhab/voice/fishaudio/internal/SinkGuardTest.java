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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The two things the Sonos binding's notification left undone on 2026-11-01 (a Playbar with a Spotify queue):
 * 10:36 - music stopped, volume left at 45; 10:43 - music resumed, volume set to 2. The guard puts the volume back
 * to what it was before the clip and resumes what was playing - and does nothing when the binding did its job.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class SinkGuardTest {

    /** A fake openHAB: items by name, links by "<sink>:<channel>", commands recorded. */
    static class Fake implements SinkGuard.Io {
        final Map<String, String> state = new HashMap<>();
        final Map<String, String> links = new HashMap<>();
        final List<String> sent = new ArrayList<>();

        Fake sonos(String sink, String prefix) {
            links.put(sink + ":state", prefix + "_State");
            links.put(sink + ":volume", prefix + "_Volume");
            links.put(sink + ":control", prefix + "_Control");
            return this;
        }

        @Override
        public @Nullable String linkedItem(String sink, String channel) {
            return links.get(sink + ":" + channel);
        }

        @Override
        public @Nullable String read(String item) {
            return state.get(item);
        }

        @Override
        public void send(String item, String command) {
            sent.add(item + " <- " + command);
            if (item.endsWith("_Volume")) {
                state.put(item, command);
            }
            if (item.endsWith("_Control") && "PLAY".equals(command)) {
                state.put(item.replace("_Control", "_State"), "PLAYING");
            }
        }
    }

    private static final String SINK = "sonos:PLAYBAR:livingroom";

    @Test
    public void the1036Case_musicStoppedAndVolumeLeftAt45_isResumedAt24() {
        Fake io = new Fake().sonos(SINK, "Sonos_LivingRoom");
        io.state.put("Sonos_LivingRoom_State", "PLAYING");
        io.state.put("Sonos_LivingRoom_Volume", "24");
        SinkGuard g = new SinkGuard(io);
        SinkGuard.Before b = g.before(SINK);
        assertTrue(b.wasPlaying());
        assertEquals(24, b.volume());
        // the clip played; the binding left the player STOPPED at 45
        io.state.put("Sonos_LivingRoom_State", "STOPPED");
        io.state.put("Sonos_LivingRoom_Volume", "45");
        assertEquals("volume 45 -> 24, resumed", g.after(b));
        assertEquals(List.of("Sonos_LivingRoom_Volume <- 24", "Sonos_LivingRoom_Control <- PLAY"), io.sent);
    }

    @Test
    public void the1043Case_musicResumedButVolumeSetTo2_getsItsVolumeBackOnly() {
        Fake io = new Fake().sonos(SINK, "Sonos_LivingRoom");
        io.state.put("Sonos_LivingRoom_State", "PLAYING");
        io.state.put("Sonos_LivingRoom_Volume", "24");
        SinkGuard g = new SinkGuard(io);
        SinkGuard.Before b = g.before(SINK);
        io.state.put("Sonos_LivingRoom_Volume", "2");
        assertEquals("volume 2 -> 24", g.after(b));
        assertEquals(List.of("Sonos_LivingRoom_Volume <- 24"), io.sent);
    }

    @Test
    public void whenTheBindingDidItsJobNothingIsSent() {
        Fake io = new Fake().sonos(SINK, "Sonos_LivingRoom");
        io.state.put("Sonos_LivingRoom_State", "PLAYING");
        io.state.put("Sonos_LivingRoom_Volume", "24");
        SinkGuard g = new SinkGuard(io);
        SinkGuard.Before b = g.before(SINK);
        assertEquals("", g.after(b));
        assertTrue(io.sent.isEmpty());
    }

    @Test
    public void anIdlePlayerIsNotStartedButItsVolumeIsPutBack() {
        Fake io = new Fake().sonos("sonos:One:koekken", "Sonos_Kitchen");
        io.state.put("Sonos_Kitchen_State", "STOPPED");
        io.state.put("Sonos_Kitchen_Volume", "26");
        SinkGuard g = new SinkGuard(io);
        SinkGuard.Before b = g.before("sonos:One:koekken");
        assertFalse(b.wasPlaying());
        io.state.put("Sonos_Kitchen_Volume", "45");
        assertEquals("volume 45 -> 26", g.after(b));
        assertEquals(List.of("Sonos_Kitchen_Volume <- 26"), io.sent);
    }

    @Test
    public void aSinkWithoutAThingOrItemsIsLeftAlone() {
        Fake io = new Fake();
        SinkGuard g = new SinkGuard(io);
        assertTrue(g.before("enhancedjavasound").nothing());
        assertTrue(g.before(null).nothing());
        SinkGuard.Before b = g.before("sonos:One:unknown");   // a thing, but no items linked
        assertTrue(b.nothing());
        assertEquals("", g.after(b));
        assertTrue(io.sent.isEmpty());
    }

    @Test
    public void aQuantityVolumeIsReadAsItsNumber() {
        assertEquals(24, SinkGuard.asInt("24"));
        assertEquals(24, SinkGuard.asInt("24 %"));
        assertEquals(24, SinkGuard.asInt("23.6"));
        assertNull(SinkGuard.asInt("NULL"));
        assertNull(SinkGuard.asInt(null));
    }
}
