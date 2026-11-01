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
import static org.mockito.Mockito.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.voice.VoiceManager;
import org.openhab.voice.fishaudio.internal.FishAudioSayListener.SayRequest;

/**
 * The say item: what a command means, and that only the configured item is heard. The worker thread is exercised
 * with a short wait; the point of the item is that the caller never waits.
 *
 * @author Nanna Agesen - Initial contribution
 */
@NonNullByDefault
public class FishAudioSayListenerTest {

    @Test
    public void aJsonCommandNamesTextVoiceSinkAndVolume() {
        SayRequest r = FishAudioSayListener
                .parse("{\"text\": \"Good evening, Nanna.\", \"voice\": \"fishaudio:hal9000\", \"sink\": \"sonos:One:koekken\", \"volume\": 45}");
        assertNotNull(r);
        assertEquals("Good evening, Nanna.", r.text());
        assertEquals("fishaudio:hal9000", r.voice());
        assertEquals("sonos:One:koekken", r.sink());
        assertEquals(new PercentType(45), r.volume());
    }

    @Test
    public void missingPartsAreNullSoOpenhabsDefaultsApply() {
        SayRequest r = FishAudioSayListener.parse("{\"text\": \"x\"}");
        assertNotNull(r);
        assertNull(r.voice());
        assertNull(r.sink());
        assertNull(r.volume());
    }

    @Test
    public void plainTextIsSpokenWithDefaults() {
        SayRequest r = FishAudioSayListener.parse("Just words");
        assertNotNull(r);
        assertEquals("Just words", r.text());
        assertNull(r.sink());
    }

    @Test
    public void emptyOrTextlessCommandsAreNothing() {
        assertNull(FishAudioSayListener.parse(""));
        assertNull(FishAudioSayListener.parse("   "));
        assertNull(FishAudioSayListener.parse("{\"voice\": \"fishaudio:hal9000\"}"));
        assertNull(FishAudioSayListener.parse("{\"text\": \"  \"}"));
    }

    @Test
    public void volumeIsClampedToPercent() {
        SayRequest r = FishAudioSayListener.parse("{\"text\": \"x\", \"volume\": 140}");
        assertNotNull(r);
        assertEquals(new PercentType(100), r.volume());
    }

    @Test
    public void onlyTheConfiguredItemIsHeardAndSayRunsOffTheCallersThread() throws Exception {
        VoiceManager vm = mock(VoiceManager.class);
        FishAudioSayListener l = new FishAudioSayListener(vm, "FishAudio_Say", null);
        l.receive(ItemEventFactory.createCommandEvent("Other_Item", new StringType("no")));
        l.receive(ItemEventFactory.createCommandEvent("FishAudio_Say",
                new StringType("{\"text\": \"hi\", \"voice\": \"fishaudio:hal9000\", \"sink\": \"s\", \"volume\": 45}")));
        verify(vm, timeout(2000).times(1)).say("hi", "fishaudio:hal9000", "s", new PercentType(45));
        verify(vm, never()).say(eq("no"), any(), any(), any());
    }
}
