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
        assertEquals(1, config.getChannels());
        assertEquals(20, config.getFrameDurationMs());
    }

    @Test
    void testBytesPerFrameCalculation() {
        AudioStreamConfig config = new AudioStreamConfig();

        // 48000 Hz * 16 bits * 1 channel * 20ms = 1920 bytes
        // Sample rate / 1000 * frame duration * bytes per sample * channels
        // 48000 / 1000 * 20 * 2 * 1 = 1920
        assertEquals(1920, config.getBytesPerFrame());
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

        // 48000 Hz * 2 bytes * 1 channel = 96000 bytes/sec
        assertEquals(96000, config.getBytesPerSecond());
    }

    @Test
    void testAudioFormat() {
        AudioStreamConfig config = new AudioStreamConfig();
        AudioFormat format = config.toAudioFormat();

        assertNotNull(format);
        assertEquals(48000, format.getSampleRate(), 0.01);
        assertEquals(16, format.getSampleSizeInBits());
        assertEquals(1, format.getChannels());
        assertTrue(format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED);
    }

    @Test
    void testMsToBytes() {
        AudioStreamConfig config = new AudioStreamConfig();

        // 100ms at 96000 bytes/sec = 9600 bytes
        assertEquals(9600, config.msToBytes(100));
        assertEquals(0, config.msToBytes(0));
    }

    @Test
    void testBytesToMs() {
        AudioStreamConfig config = new AudioStreamConfig();

        // 9600 bytes at 96000 bytes/sec = 100ms
        assertEquals(100, config.bytesToMs(9600));
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
}
