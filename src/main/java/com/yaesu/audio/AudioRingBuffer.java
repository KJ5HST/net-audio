/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License.
 */
package com.yaesu.audio;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe ring buffer for audio streaming with jitter compensation.
 * <p>
 * Provides a circular buffer for audio data with configurable target,
 * minimum, and maximum buffer levels. Supports blocking reads with
 * timeout and adaptive buffer level management.
 * </p>
 */
public class AudioRingBuffer {

    private final byte[] buffer;
    private final int capacity;
    private final AudioStreamConfig config;

    private int writePos = 0;
    private int readPos = 0;
    private int available = 0;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();

    // Statistics
    private long totalBytesWritten = 0;
    private long totalBytesRead = 0;
    private int underrunCount = 0;
    private int overrunCount = 0;

    /**
     * Creates a new ring buffer with capacity based on the config's max buffer setting.
     *
     * @param config the audio stream configuration
     */
    public AudioRingBuffer(AudioStreamConfig config) {
        this.config = config;
        // Allocate enough for max buffer plus some headroom
        this.capacity = config.msToBytes(config.getBufferMaxMs() * 2);
        this.buffer = new byte[capacity];
    }

    /**
     * Creates a new ring buffer with explicit capacity.
     *
     * @param config the audio stream configuration
     * @param capacityBytes the buffer capacity in bytes
     */
    public AudioRingBuffer(AudioStreamConfig config, int capacityBytes) {
        this.config = config;
        this.capacity = capacityBytes;
        this.buffer = new byte[capacity];
    }

    /**
     * Writes data to the buffer.
     * <p>
     * If the buffer is full, oldest data is overwritten (overrun).
     * This method never blocks.
     * </p>
     *
     * @param data the data to write
     * @param offset the offset in the data array
     * @param length the number of bytes to write
     * @return the number of bytes actually written
     */
    public int write(byte[] data, int offset, int length) {
        lock.lock();
        try {
            int bytesWritten = 0;

            // Check for potential overrun
            if (available + length > capacity) {
                // Calculate how much to drop
                int toDrop = (available + length) - capacity;
                readPos = (readPos + toDrop) % capacity;
                available -= toDrop;
                overrunCount++;
            }

            // Write data to buffer
            while (bytesWritten < length) {
                int spaceToEnd = capacity - writePos;
                int toWrite = Math.min(length - bytesWritten, spaceToEnd);

                System.arraycopy(data, offset + bytesWritten, buffer, writePos, toWrite);

                writePos = (writePos + toWrite) % capacity;
                bytesWritten += toWrite;
                available += toWrite;
            }

            totalBytesWritten += bytesWritten;
            notEmpty.signalAll();

            return bytesWritten;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes data to the buffer.
     *
     * @param data the data to write
     * @return the number of bytes written
     */
    public int write(byte[] data) {
        return write(data, 0, data.length);
    }

    /**
     * Reads data from the buffer.
     * <p>
     * Blocks until the requested number of bytes are available or timeout occurs.
     * </p>
     *
     * @param data the buffer to read into
     * @param offset the offset in the buffer
     * @param length the number of bytes to read
     * @param timeoutMs the timeout in milliseconds (0 for no timeout)
     * @return the number of bytes read, or -1 if interrupted
     */
    public int read(byte[] data, int offset, int length, long timeoutMs) {
        lock.lock();
        try {
            long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;

            // Wait for data
            while (available < length) {
                if (available == 0 && timeoutMs > 0) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        underrunCount++;
                        return 0; // Timeout with no data
                    }
                    try {
                        notEmpty.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                } else if (available > 0 && available < length) {
                    // Have some data but not enough - return what we have
                    break;
                } else if (timeoutMs == 0) {
                    // Non-blocking and no data
                    return 0;
                }
            }

            // Read available data (up to requested length)
            int toRead = Math.min(length, available);
            int bytesRead = 0;

            while (bytesRead < toRead) {
                int dataToEnd = capacity - readPos;
                int chunk = Math.min(toRead - bytesRead, dataToEnd);

                System.arraycopy(buffer, readPos, data, offset + bytesRead, chunk);

                readPos = (readPos + chunk) % capacity;
                bytesRead += chunk;
                available -= chunk;
            }

            totalBytesRead += bytesRead;
            notFull.signalAll();

            return bytesRead;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads data from the buffer with default timeout.
     *
     * @param data the buffer to read into
     * @return the number of bytes read
     */
    public int read(byte[] data) {
        return read(data, 0, data.length, config.getFrameDurationMs() * 2);
    }

    /**
     * Gets the current buffer level in bytes.
     */
    public int getAvailable() {
        lock.lock();
        try {
            return available;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the current buffer level in milliseconds.
     */
    public int getBufferLevelMs() {
        return config.bytesToMs(getAvailable());
    }

    /**
     * Gets the buffer fill percentage (0-100).
     */
    public int getBufferFillPercent() {
        lock.lock();
        try {
            return (available * 100) / capacity;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if the buffer has reached the target level.
     * Used to determine when to start playback after initial buffering.
     */
    public boolean hasReachedTargetLevel() {
        return getBufferLevelMs() >= config.getBufferTargetMs();
    }

    /**
     * Checks if the buffer is below minimum level (potential underrun).
     */
    public boolean isBelowMinimum() {
        return getBufferLevelMs() < config.getBufferMinMs();
    }

    /**
     * Checks if the buffer is above maximum level (potential overrun).
     */
    public boolean isAboveMaximum() {
        return getBufferLevelMs() > config.getBufferMaxMs();
    }

    /**
     * Clears the buffer.
     */
    public void clear() {
        lock.lock();
        try {
            writePos = 0;
            readPos = 0;
            available = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the total bytes written since creation or last reset.
     */
    public long getTotalBytesWritten() {
        lock.lock();
        try {
            return totalBytesWritten;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the total bytes read since creation or last reset.
     */
    public long getTotalBytesRead() {
        lock.lock();
        try {
            return totalBytesRead;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the number of underrun events (read when buffer empty).
     */
    public int getUnderrunCount() {
        lock.lock();
        try {
            return underrunCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the number of overrun events (write when buffer full).
     */
    public int getOverrunCount() {
        lock.lock();
        try {
            return overrunCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets statistics counters.
     */
    public void resetStatistics() {
        lock.lock();
        try {
            totalBytesWritten = 0;
            totalBytesRead = 0;
            underrunCount = 0;
            overrunCount = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the buffer capacity in bytes.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Gets the configuration.
     */
    public AudioStreamConfig getConfig() {
        return config;
    }

    @Override
    public String toString() {
        return String.format("AudioRingBuffer[%d/%d bytes, %d ms, %d underruns, %d overruns]",
            getAvailable(), capacity, getBufferLevelMs(), underrunCount, overrunCount);
    }
}
