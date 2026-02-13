/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio;

/**
 * Statistics for an audio stream.
 * <p>
 * Provides metrics about audio streaming performance including
 * buffer levels, throughput, latency, and error counts.
 * </p>
 */
public class AudioStreamStats {

    private long bytesReceived;
    private long bytesSent;
    private long packetsReceived;
    private long packetsSent;
    private int bufferLevelMs;
    private int bufferFillPercent;
    private int underrunCount;
    private int overrunCount;
    private int crcErrors;
    private long latencyMs;
    private long connectionTimeMs;
    private boolean streaming;

    /**
     * Creates empty statistics.
     */
    public AudioStreamStats() {
    }

    /**
     * Gets the number of bytes received.
     */
    public long getBytesReceived() {
        return bytesReceived;
    }

    /**
     * Sets the number of bytes received.
     */
    public AudioStreamStats setBytesReceived(long bytesReceived) {
        this.bytesReceived = bytesReceived;
        return this;
    }

    /**
     * Gets the number of bytes sent.
     */
    public long getBytesSent() {
        return bytesSent;
    }

    /**
     * Sets the number of bytes sent.
     */
    public AudioStreamStats setBytesSent(long bytesSent) {
        this.bytesSent = bytesSent;
        return this;
    }

    /**
     * Gets the number of packets received.
     */
    public long getPacketsReceived() {
        return packetsReceived;
    }

    /**
     * Sets the number of packets received.
     */
    public AudioStreamStats setPacketsReceived(long packetsReceived) {
        this.packetsReceived = packetsReceived;
        return this;
    }

    /**
     * Gets the number of packets sent.
     */
    public long getPacketsSent() {
        return packetsSent;
    }

    /**
     * Sets the number of packets sent.
     */
    public AudioStreamStats setPacketsSent(long packetsSent) {
        this.packetsSent = packetsSent;
        return this;
    }

    /**
     * Gets the current buffer level in milliseconds.
     */
    public int getBufferLevelMs() {
        return bufferLevelMs;
    }

    /**
     * Sets the buffer level in milliseconds.
     */
    public AudioStreamStats setBufferLevelMs(int bufferLevelMs) {
        this.bufferLevelMs = bufferLevelMs;
        return this;
    }

    /**
     * Gets the buffer fill percentage (0-100).
     */
    public int getBufferFillPercent() {
        return bufferFillPercent;
    }

    /**
     * Sets the buffer fill percentage.
     */
    public AudioStreamStats setBufferFillPercent(int bufferFillPercent) {
        this.bufferFillPercent = bufferFillPercent;
        return this;
    }

    /**
     * Gets the number of buffer underruns.
     */
    public int getUnderrunCount() {
        return underrunCount;
    }

    /**
     * Sets the underrun count.
     */
    public AudioStreamStats setUnderrunCount(int underrunCount) {
        this.underrunCount = underrunCount;
        return this;
    }

    /**
     * Gets the number of buffer overruns.
     */
    public int getOverrunCount() {
        return overrunCount;
    }

    /**
     * Sets the overrun count.
     */
    public AudioStreamStats setOverrunCount(int overrunCount) {
        this.overrunCount = overrunCount;
        return this;
    }

    /**
     * Gets the number of CRC errors.
     */
    public int getCrcErrors() {
        return crcErrors;
    }

    /**
     * Sets the CRC error count.
     */
    public AudioStreamStats setCrcErrors(int crcErrors) {
        this.crcErrors = crcErrors;
        return this;
    }

    /**
     * Gets the measured latency in milliseconds.
     */
    public long getLatencyMs() {
        return latencyMs;
    }

    /**
     * Sets the latency in milliseconds.
     */
    public AudioStreamStats setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
        return this;
    }

    /**
     * Gets the connection duration in milliseconds.
     */
    public long getConnectionTimeMs() {
        return connectionTimeMs;
    }

    /**
     * Sets the connection duration in milliseconds.
     */
    public AudioStreamStats setConnectionTimeMs(long connectionTimeMs) {
        this.connectionTimeMs = connectionTimeMs;
        return this;
    }

    /**
     * Checks if streaming is active.
     */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * Sets the streaming state.
     */
    public AudioStreamStats setStreaming(boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    /**
     * Gets the receive throughput in bytes per second.
     * Requires connectionTimeMs to be set.
     */
    public double getRxBytesPerSecond() {
        if (connectionTimeMs <= 0) return 0;
        return (bytesReceived * 1000.0) / connectionTimeMs;
    }

    /**
     * Gets the send throughput in bytes per second.
     * Requires connectionTimeMs to be set.
     */
    public double getTxBytesPerSecond() {
        if (connectionTimeMs <= 0) return 0;
        return (bytesSent * 1000.0) / connectionTimeMs;
    }

    /**
     * Gets the receive throughput in kilobytes per second.
     */
    public double getRxKBps() {
        return getRxBytesPerSecond() / 1024.0;
    }

    /**
     * Gets the send throughput in kilobytes per second.
     */
    public double getTxKBps() {
        return getTxBytesPerSecond() / 1024.0;
    }

    @Override
    public String toString() {
        return String.format("AudioStreamStats[rx=%.1f kB/s, tx=%.1f kB/s, buffer=%d%%, latency=%dms]",
            getRxKBps(), getTxKBps(), bufferFillPercent, latencyMs);
    }
}
