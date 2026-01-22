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
import java.util.logging.Logger;

/**
 * Thread-safe ring buffer for audio streaming with jitter compensation.
 * <p>
 * Provides a circular buffer for audio data with configurable target,
 * minimum, and maximum buffer levels. Supports blocking reads with
 * timeout and adaptive buffer level management.
 * </p>
 */
public class AudioRingBuffer {

    private static final Logger logger = Logger.getLogger(AudioRingBuffer.class.getName());

    /** Log warning after this many overruns/underruns per minute */
    private static final int WARN_THRESHOLD_PER_MINUTE = 10;

    private final byte[] buffer;
    private final int capacity;
    private AudioStreamConfig config;
    private final String name;

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

    // Rate tracking for logging
    private long lastOverrunLogTime = 0;
    private long lastUnderrunLogTime = 0;
    private int overrunsSinceLastLog = 0;
    private int underrunsSinceLastLog = 0;
    private boolean firstOverrun = true;
    private boolean firstUnderrun = true;

    /**
     * Creates a new ring buffer with capacity based on the config's max buffer setting.
     *
     * @param config the audio stream configuration
     */
    public AudioRingBuffer(AudioStreamConfig config) {
        this(config, config.msToBytes(config.getBufferMaxMs() * 2), "AudioRingBuffer");
    }

    /**
     * Creates a new ring buffer with explicit capacity.
     *
     * @param config the audio stream configuration
     * @param capacityBytes the buffer capacity in bytes
     */
    public AudioRingBuffer(AudioStreamConfig config, int capacityBytes) {
        this(config, capacityBytes, "AudioRingBuffer");
    }

    /**
     * Creates a new ring buffer with explicit capacity and name for logging.
     *
     * @param config the audio stream configuration
     * @param capacityBytes the buffer capacity in bytes
     * @param name name for this buffer (used in log messages)
     */
    public AudioRingBuffer(AudioStreamConfig config, int capacityBytes, String name) {
        this.config = config;
        this.capacity = capacityBytes;
        this.buffer = new byte[capacity];
        this.name = name;
    }

    /**
     * Updates the buffer threshold configuration.
     * <p>
     * This allows adjusting buffer timing thresholds (target, min, max) after
     * the buffer has been created, typically during connection handshake when
     * the client requests specific buffer settings.
     * </p>
     *
     * @param newConfig the new configuration with updated buffer thresholds
     */
    public void updateConfig(AudioStreamConfig newConfig) {
        lock.lock();
        try {
            this.config = newConfig;
        } finally {
            lock.unlock();
        }
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
                logOverrun(toDrop);
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
     * If data is available but less than requested, returns the available data
     * rather than blocking (partial read).
     * </p>
     *
     * @param data the buffer to read into
     * @param offset the offset in the buffer
     * @param length the number of bytes to read
     * @param timeoutMs the timeout in milliseconds (0 for non-blocking)
     * @return the number of bytes read (may be less than requested),
     *         0 if timeout with no data, or -1 if interrupted
     */
    public int read(byte[] data, int offset, int length, long timeoutMs) {
        lock.lock();
        try {
            long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;

            // Wait for at least some data
            while (available == 0) {
                if (timeoutMs == 0) {
                    // Non-blocking mode and no data
                    return 0;
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    // Timeout with no data - this is an underrun
                    underrunCount++;
                    logUnderrun();
                    return 0;
                }

                try {
                    notEmpty.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }

            // Have some data - return as much as we can (up to requested length)
            // This is the key fix: return partial data immediately rather than waiting
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

    /**
     * Logs overrun events with rate limiting to avoid log spam.
     */
    private void logOverrun(int bytesDropped) {
        overrunsSinceLastLog++;
        long now = System.currentTimeMillis();

        // Log first overrun immediately
        if (firstOverrun) {
            firstOverrun = false;
            logger.warning(String.format("[%s] Buffer overrun: dropped %d bytes (%d ms). " +
                "This may indicate network jitter or insufficient buffer size.",
                name, bytesDropped, config.bytesToMs(bytesDropped)));
            lastOverrunLogTime = now;
            overrunsSinceLastLog = 0;
            return;
        }

        // Log rate every minute if threshold exceeded
        if (now - lastOverrunLogTime >= 60000) {
            if (overrunsSinceLastLog >= WARN_THRESHOLD_PER_MINUTE) {
                logger.warning(String.format("[%s] Buffer overruns: %d in last minute. " +
                    "Consider increasing buffer size or improving network stability.",
                    name, overrunsSinceLastLog));
            }
            lastOverrunLogTime = now;
            overrunsSinceLastLog = 0;
        }
    }

    /**
     * Logs underrun events with rate limiting to avoid log spam.
     */
    private void logUnderrun() {
        underrunsSinceLastLog++;
        long now = System.currentTimeMillis();

        // Log first underrun immediately
        if (firstUnderrun) {
            firstUnderrun = false;
            logger.warning(String.format("[%s] Buffer underrun: no data available. " +
                "This may indicate network issues or audio source problems.",
                name));
            lastUnderrunLogTime = now;
            underrunsSinceLastLog = 0;
            return;
        }

        // Log rate every minute if threshold exceeded
        if (now - lastUnderrunLogTime >= 60000) {
            if (underrunsSinceLastLog >= WARN_THRESHOLD_PER_MINUTE) {
                logger.warning(String.format("[%s] Buffer underruns: %d in last minute. " +
                    "Consider increasing buffer size or checking audio source.",
                    name, underrunsSinceLastLog));
            }
            lastUnderrunLogTime = now;
            underrunsSinceLastLog = 0;
        }
    }

    /**
     * Gets the overrun rate (overruns per minute) since last reset.
     * Returns 0 if less than a minute has elapsed.
     */
    public float getOverrunRate() {
        lock.lock();
        try {
            long elapsed = System.currentTimeMillis() - lastOverrunLogTime;
            if (elapsed < 60000) {
                return 0;
            }
            return (overrunsSinceLastLog * 60000f) / elapsed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the underrun rate (underruns per minute) since last reset.
     * Returns 0 if less than a minute has elapsed.
     */
    public float getUnderrunRate() {
        lock.lock();
        try {
            long elapsed = System.currentTimeMillis() - lastUnderrunLogTime;
            if (elapsed < 60000) {
                return 0;
            }
            return (underrunsSinceLastLog * 60000f) / elapsed;
        } finally {
            lock.unlock();
        }
    }
}
