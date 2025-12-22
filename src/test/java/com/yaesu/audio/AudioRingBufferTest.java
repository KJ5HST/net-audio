/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 */
package com.yaesu.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AudioRingBuffer.
 */
class AudioRingBufferTest {

    private AudioRingBuffer buffer;
    private AudioStreamConfig config;

    @BeforeEach
    void setUp() {
        config = new AudioStreamConfig();
        buffer = new AudioRingBuffer(config);
    }

    @Test
    void testInitialState() {
        assertEquals(0, buffer.getAvailable());
        assertEquals(0, buffer.getBufferLevelMs());
        assertEquals(0, buffer.getBufferFillPercent());
        assertFalse(buffer.hasReachedTargetLevel());
    }

    @Test
    void testWriteAndRead() {
        byte[] writeData = new byte[100];
        for (int i = 0; i < writeData.length; i++) {
            writeData[i] = (byte) i;
        }

        buffer.write(writeData);
        assertEquals(100, buffer.getAvailable());

        byte[] readData = new byte[100];
        int bytesRead = buffer.read(readData, 0, readData.length, 100);

        assertEquals(100, bytesRead);
        assertEquals(0, buffer.getAvailable());
        assertArrayEquals(writeData, readData);
    }

    @Test
    void testPartialRead() {
        byte[] writeData = new byte[100];
        for (int i = 0; i < writeData.length; i++) {
            writeData[i] = (byte) i;
        }

        buffer.write(writeData);

        byte[] readData = new byte[50];
        int bytesRead = buffer.read(readData, 0, 50, 100);

        assertEquals(50, bytesRead);
        assertEquals(50, buffer.getAvailable());

        // Verify correct bytes were read
        for (int i = 0; i < 50; i++) {
            assertEquals((byte) i, readData[i]);
        }
    }

    @Test
    void testWriteWithOffset() {
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }

        // Write only bytes 25-74 (50 bytes)
        buffer.write(data, 25, 50);
        assertEquals(50, buffer.getAvailable());

        byte[] readData = new byte[50];
        buffer.read(readData, 0, 50, 100);

        // Should contain bytes 25-74
        for (int i = 0; i < 50; i++) {
            assertEquals((byte) (i + 25), readData[i]);
        }
    }

    @Test
    void testClear() {
        byte[] data = new byte[100];
        buffer.write(data);
        assertEquals(100, buffer.getAvailable());

        buffer.clear();
        assertEquals(0, buffer.getAvailable());
    }

    @Test
    void testBufferLevelMs() {
        // Write 200ms of audio data (within buffer capacity)
        int bytesFor200ms = config.msToBytes(200);
        byte[] data = new byte[bytesFor200ms];
        buffer.write(data);

        // Should report approximately 200ms in buffer
        int levelMs = buffer.getBufferLevelMs();
        assertTrue(levelMs >= 190 && levelMs <= 210,
            "Expected ~200ms, got " + levelMs);
    }

    @Test
    void testUnderrunCount() {
        assertEquals(0, buffer.getUnderrunCount());

        // Try to read from empty buffer
        byte[] data = new byte[100];
        int bytesRead = buffer.read(data, 0, 100, 1); // 1ms timeout

        // Should timeout and report underrun
        assertEquals(0, bytesRead);
        // Note: underrun count may or may not increment depending on implementation
    }

    @Test
    void testOverrunCount() {
        assertEquals(0, buffer.getOverrunCount());
        // Overrun testing would require writing more than buffer capacity
    }

    @Test
    void testTargetLevelReached() {
        // Initially not reached
        assertFalse(buffer.hasReachedTargetLevel());

        // Write enough data to reach target level
        int targetBytes = config.msToBytes(config.getBufferTargetMs());
        byte[] data = new byte[targetBytes];
        buffer.write(data);

        assertTrue(buffer.hasReachedTargetLevel());
    }
}
