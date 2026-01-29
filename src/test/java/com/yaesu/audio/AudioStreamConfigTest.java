/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 */
package com.yaesu.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AudioStreamConfig.
 */
class AudioStreamConfigTest {

    @Test
    void testDefaultConfiguration() {
        AudioStreamConfig config = new AudioStreamConfig();

        assertEquals(48000, config.getSampleRate());
        assertEquals(16, config.getBitsPerSample());
        assertEquals(2, config.getChannels());  // Stereo for USB Audio Device compatibility
        assertEquals(20, config.getFrameDurationMs());
    }

    @Test
    void testBytesPerFrameCalculation() {
        AudioStreamConfig config = new AudioStreamConfig();

        // 48000 Hz * 16 bits * 2 channels * 20ms = 3840 bytes
        // Sample rate / 1000 * frame duration * bytes per sample * channels
        // 48000 / 1000 * 20 * 2 * 2 = 3840
        assertEquals(3840, config.getBytesPerFrame());
    }

    @Test
    void testSamplesPerFrameCalculation() {
        AudioStreamConfig config = new AudioStreamConfig();

        // 48000 Hz * 20ms = 960 samples
        assertEquals(960, config.getSamplesPerFrame());
    }

    @Test
    void testBytesPerSecondCalculation() {
        AudioStreamConfig config = new AudioStreamConfig();

        // 48000 Hz * 2 bytes * 2 channels = 192000 bytes/sec
        assertEquals(192000, config.getBytesPerSecond());
    }

    @Test
    void testAudioFormat() {
        AudioStreamConfig config = new AudioStreamConfig();
        AudioFormat format = config.toAudioFormat();

        assertNotNull(format);
        assertEquals(48000, format.getSampleRate(), 0.01);
        assertEquals(16, format.getSampleSizeInBits());
        assertEquals(2, format.getChannels());  // Stereo for USB Audio Device compatibility
        assertTrue(format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED);
    }

    @Test
    void testMsToBytes() {
        AudioStreamConfig config = new AudioStreamConfig();

        // 100ms at 192000 bytes/sec = 19200 bytes
        assertEquals(19200, config.msToBytes(100));
        assertEquals(0, config.msToBytes(0));
    }

    @Test
    void testBytesToMs() {
        AudioStreamConfig config = new AudioStreamConfig();

        // 19200 bytes at 192000 bytes/sec = 100ms
        assertEquals(100, config.bytesToMs(19200));
        assertEquals(0, config.bytesToMs(0));
    }

    @Test
    void testDefaultPort() {
        assertEquals(4533, AudioStreamConfig.DEFAULT_PORT);
    }

    @Test
    void testBufferConfiguration() {
        AudioStreamConfig config = new AudioStreamConfig();

        assertTrue(config.getBufferMaxMs() > 0);
        assertTrue(config.getBufferTargetMs() > 0);
        assertTrue(config.getBufferTargetMs() <= config.getBufferMaxMs());
    }

    @Test
    void testDefaultMaxClients() {
        AudioStreamConfig config = new AudioStreamConfig();
        assertEquals(4, config.getMaxClients());
        assertEquals(AudioStreamConfig.DEFAULT_MAX_CLIENTS, config.getMaxClients());
    }

    @Test
    void testSetMaxClients() {
        AudioStreamConfig config = new AudioStreamConfig();
        config.setMaxClients(10);
        assertEquals(10, config.getMaxClients());
    }

    @Test
    void testDefaultTxIdleTimeout() {
        AudioStreamConfig config = new AudioStreamConfig();
        assertEquals(500, config.getTxIdleTimeoutMs());
        assertEquals(AudioStreamConfig.DEFAULT_TX_IDLE_TIMEOUT_MS, config.getTxIdleTimeoutMs());
    }

    @Test
    void testSetTxIdleTimeout() {
        AudioStreamConfig config = new AudioStreamConfig();
        config.setTxIdleTimeoutMs(1000);
        assertEquals(1000, config.getTxIdleTimeoutMs());
    }

    @Test
    void testChainedConfiguration() {
        AudioStreamConfig config = new AudioStreamConfig()
            .setMaxClients(8)
            .setTxIdleTimeoutMs(750)
            .setSampleRate(12000);

        assertEquals(8, config.getMaxClients());
        assertEquals(750, config.getTxIdleTimeoutMs());
        assertEquals(12000, config.getSampleRate());
    }

    @Test
    void testToStringIncludesMaxClients() {
        AudioStreamConfig config = new AudioStreamConfig().setMaxClients(6);
        String str = config.toString();
        assertTrue(str.contains("6 clients"), "toString should include max clients: " + str);
    }
}
