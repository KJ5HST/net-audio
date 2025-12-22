/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 */
package com.yaesu.audio.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AudioPacket.
 */
class AudioPacketTest {

    @Test
    void testMagicNumber() {
        assertEquals((short) 0xAF01, AudioPacket.MAGIC);
    }

    @Test
    void testVersion() {
        assertEquals(1, AudioPacket.VERSION);
    }

    @Test
    void testMaxPayloadSize() {
        assertTrue(AudioPacket.MAX_PAYLOAD > 0);
        assertTrue(AudioPacket.MAX_PAYLOAD >= 8192);
    }

    @Test
    void testCreateRxAudio() {
        byte[] audioData = new byte[960];
        for (int i = 0; i < audioData.length; i++) {
            audioData[i] = (byte) i;
        }

        AudioPacket packet = AudioPacket.createRxAudio(1, audioData);

        assertNotNull(packet);
        assertEquals(AudioPacket.Type.AUDIO_RX, packet.getType());
        assertEquals(1, packet.getSequence());
        assertArrayEquals(audioData, packet.getPayload());
    }

    @Test
    void testCreateTxAudio() {
        byte[] audioData = new byte[960];
        AudioPacket packet = AudioPacket.createTxAudio(42, audioData);

        assertNotNull(packet);
        assertEquals(AudioPacket.Type.AUDIO_TX, packet.getType());
        assertEquals(42, packet.getSequence());
    }

    @Test
    void testCreateHeartbeat() {
        AudioPacket packet = AudioPacket.createHeartbeat(100);

        assertNotNull(packet);
        assertEquals(AudioPacket.Type.HEARTBEAT, packet.getType());
        assertEquals(100, packet.getSequence());
    }

    @Test
    void testCreateControl() {
        byte[] controlData = new byte[]{0x01, 0x02, 0x03};
        AudioPacket packet = AudioPacket.createControl(5, controlData);

        assertNotNull(packet);
        assertEquals(AudioPacket.Type.CONTROL, packet.getType());
        assertEquals(5, packet.getSequence());
        assertArrayEquals(controlData, packet.getPayload());
    }

    @Test
    void testSerializeAndDeserialize() {
        byte[] audioData = new byte[100];
        for (int i = 0; i < audioData.length; i++) {
            audioData[i] = (byte) (i * 2);
        }

        AudioPacket original = AudioPacket.createRxAudio(12345, audioData);
        byte[] serialized = original.serialize();

        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        AudioPacket deserialized = AudioPacket.deserialize(serialized);

        assertNotNull(deserialized);
        assertEquals(original.getType(), deserialized.getType());
        assertEquals(original.getSequence(), deserialized.getSequence());
        assertArrayEquals(original.getPayload(), deserialized.getPayload());
    }

    @Test
    void testDeserializeInvalidMagic() {
        byte[] badData = new byte[50];
        badData[0] = 0x00; // Wrong magic byte
        badData[1] = 0x00;

        AudioPacket packet = AudioPacket.deserialize(badData);
        assertNull(packet);
    }

    @Test
    void testDeserializeCorruptedCrc() {
        byte[] audioData = new byte[100];
        AudioPacket original = AudioPacket.createRxAudio(1, audioData);
        byte[] serialized = original.serialize();

        // Corrupt the CRC (last 4 bytes)
        serialized[serialized.length - 1] ^= 0xFF;

        AudioPacket packet = AudioPacket.deserialize(serialized);
        assertNull(packet);
    }

    @Test
    void testPacketTypes() {
        // Verify all packet types exist
        assertNotNull(AudioPacket.Type.AUDIO_RX);
        assertNotNull(AudioPacket.Type.AUDIO_TX);
        assertNotNull(AudioPacket.Type.CONTROL);
        assertNotNull(AudioPacket.Type.HEARTBEAT);
    }

    @Test
    void testEmptyPayload() {
        AudioPacket packet = AudioPacket.createHeartbeat(0);
        byte[] serialized = packet.serialize();

        AudioPacket deserialized = AudioPacket.deserialize(serialized);
        assertNotNull(deserialized);
        assertEquals(AudioPacket.Type.HEARTBEAT, deserialized.getType());
    }
}
