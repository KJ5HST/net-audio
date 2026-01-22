/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License.
 */
package com.yaesu.audio;

import javax.sound.sampled.AudioFormat;

/**
 * Configuration for audio streaming.
 * <p>
 * Defines audio format parameters and buffer settings for
 * network audio streaming between ftx1-audio server and clients.
 * </p>
 */
public class AudioStreamConfig {

    /** Default sample rate for WSJT-X compatibility */
    public static final int DEFAULT_SAMPLE_RATE = 48000;

    /** Low bandwidth sample rate option */
    public static final int LOW_BANDWIDTH_SAMPLE_RATE = 12000;

    /** Default bits per sample */
    public static final int DEFAULT_BITS_PER_SAMPLE = 16;

    /** Mono audio for digital modes */
    public static final int DEFAULT_CHANNELS = 1;

    /** Default frame duration in milliseconds */
    public static final int DEFAULT_FRAME_MS = 20;

    /** Default target buffer level in milliseconds */
    public static final int DEFAULT_BUFFER_TARGET_MS = 100;

    /** Minimum buffer level in milliseconds */
    public static final int DEFAULT_BUFFER_MIN_MS = 40;

    /** Maximum buffer level in milliseconds */
    public static final int DEFAULT_BUFFER_MAX_MS = 300;

    /** FT8-optimized target buffer - low latency for digital modes */
    public static final int FT8_BUFFER_TARGET_MS = 40;

    /** FT8-optimized minimum buffer */
    public static final int FT8_BUFFER_MIN_MS = 20;

    /** FT8-optimized maximum buffer */
    public static final int FT8_BUFFER_MAX_MS = 100;

    /** Voice-optimized target buffer - balanced latency/stability for SSB */
    public static final int VOICE_BUFFER_TARGET_MS = 120;

    /** Voice-optimized minimum buffer */
    public static final int VOICE_BUFFER_MIN_MS = 60;

    /** Voice-optimized maximum buffer */
    public static final int VOICE_BUFFER_MAX_MS = 300;

    /** Maximum time to wait for initial buffering before starting playback (ms) */
    public static final int MAX_INITIAL_BUFFERING_MS = 500;

    /** Default audio server port */
    public static final int DEFAULT_PORT = 4533;

    private int sampleRate;
    private int bitsPerSample;
    private int channels;
    private int frameDurationMs;
    private int bufferTargetMs;
    private int bufferMinMs;
    private int bufferMaxMs;

    /**
     * Creates a default audio stream configuration.
     */
    public AudioStreamConfig() {
        this.sampleRate = DEFAULT_SAMPLE_RATE;
        this.bitsPerSample = DEFAULT_BITS_PER_SAMPLE;
        this.channels = DEFAULT_CHANNELS;
        this.frameDurationMs = DEFAULT_FRAME_MS;
        this.bufferTargetMs = DEFAULT_BUFFER_TARGET_MS;
        this.bufferMinMs = DEFAULT_BUFFER_MIN_MS;
        this.bufferMaxMs = DEFAULT_BUFFER_MAX_MS;
    }

    /**
     * Gets the sample rate in Hz.
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Sets the sample rate in Hz.
     */
    public AudioStreamConfig setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    /**
     * Gets the bits per sample.
     */
    public int getBitsPerSample() {
        return bitsPerSample;
    }

    /**
     * Sets the bits per sample.
     */
    public AudioStreamConfig setBitsPerSample(int bitsPerSample) {
        this.bitsPerSample = bitsPerSample;
        return this;
    }

    /**
     * Gets the number of channels.
     */
    public int getChannels() {
        return channels;
    }

    /**
     * Sets the number of channels.
     */
    public AudioStreamConfig setChannels(int channels) {
        this.channels = channels;
        return this;
    }

    /**
     * Gets the frame duration in milliseconds.
     */
    public int getFrameDurationMs() {
        return frameDurationMs;
    }

    /**
     * Sets the frame duration in milliseconds.
     */
    public AudioStreamConfig setFrameDurationMs(int frameDurationMs) {
        this.frameDurationMs = frameDurationMs;
        return this;
    }

    /**
     * Gets the target buffer level in milliseconds.
     */
    public int getBufferTargetMs() {
        return bufferTargetMs;
    }

    /**
     * Sets the target buffer level in milliseconds.
     */
    public AudioStreamConfig setBufferTargetMs(int bufferTargetMs) {
        this.bufferTargetMs = bufferTargetMs;
        return this;
    }

    /**
     * Gets the minimum buffer level in milliseconds.
     */
    public int getBufferMinMs() {
        return bufferMinMs;
    }

    /**
     * Sets the minimum buffer level in milliseconds.
     */
    public AudioStreamConfig setBufferMinMs(int bufferMinMs) {
        this.bufferMinMs = bufferMinMs;
        return this;
    }

    /**
     * Gets the maximum buffer level in milliseconds.
     */
    public int getBufferMaxMs() {
        return bufferMaxMs;
    }

    /**
     * Sets the maximum buffer level in milliseconds.
     */
    public AudioStreamConfig setBufferMaxMs(int bufferMaxMs) {
        this.bufferMaxMs = bufferMaxMs;
        return this;
    }

    /**
     * Gets the number of samples per frame.
     */
    public int getSamplesPerFrame() {
        return (sampleRate * frameDurationMs) / 1000;
    }

    /**
     * Gets the number of bytes per frame.
     */
    public int getBytesPerFrame() {
        return getSamplesPerFrame() * (bitsPerSample / 8) * channels;
    }

    /**
     * Gets the bytes per second for this configuration.
     */
    public int getBytesPerSecond() {
        return sampleRate * (bitsPerSample / 8) * channels;
    }

    /**
     * Converts milliseconds to bytes for this configuration.
     */
    public int msToBytes(int ms) {
        return (getBytesPerSecond() * ms) / 1000;
    }

    /**
     * Converts bytes to milliseconds for this configuration.
     */
    public int bytesToMs(int bytes) {
        int bytesPerSecond = getBytesPerSecond();
        if (bytesPerSecond == 0) return 0;
        return (bytes * 1000) / bytesPerSecond;
    }

    /**
     * Creates a javax.sound.sampled.AudioFormat from this configuration.
     */
    public AudioFormat toAudioFormat() {
        return new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate,
            bitsPerSample,
            channels,
            (bitsPerSample / 8) * channels,  // frame size in bytes
            sampleRate,                       // frame rate = sample rate for PCM
            false                             // little-endian
        );
    }

    /**
     * Creates a configuration for low bandwidth mode (12kHz).
     */
    public static AudioStreamConfig lowBandwidth() {
        return new AudioStreamConfig()
            .setSampleRate(LOW_BANDWIDTH_SAMPLE_RATE);
    }

    /**
     * Creates a configuration optimized for FT8 and other digital modes.
     * <p>
     * Uses lower buffer targets (40ms vs 100ms default) to minimize latency,
     * which is critical for FT8 timing requirements. FT8 uses 15-second
     * cycles and 160ms symbol periods, so total system latency should be
     * kept under 200ms for reliable operation.
     * </p>
     */
    public static AudioStreamConfig ft8Optimized() {
        return new AudioStreamConfig()
            .setBufferTargetMs(FT8_BUFFER_TARGET_MS)
            .setBufferMinMs(FT8_BUFFER_MIN_MS)
            .setBufferMaxMs(FT8_BUFFER_MAX_MS);
    }

    /**
     * Checks if this configuration uses FT8-optimized buffer settings.
     */
    public boolean isFt8Optimized() {
        return bufferTargetMs <= FT8_BUFFER_TARGET_MS;
    }

    /**
     * Creates a configuration optimized for SSB voice operation.
     * <p>
     * Uses moderate buffer targets (120ms) to provide good stability on
     * WiFi and Internet connections while maintaining acceptable latency
     * for voice conversation. Voice can tolerate up to 300-400ms latency
     * before conversation becomes awkward.
     * </p>
     * <p>
     * Use this profile for:
     * <ul>
     *   <li>SSB voice operation</li>
     *   <li>AM/FM voice modes</li>
     *   <li>Operation over WiFi or Internet</li>
     *   <li>When experiencing buffer underruns with FT8 settings</li>
     * </ul>
     * </p>
     */
    public static AudioStreamConfig voiceOptimized() {
        return new AudioStreamConfig()
            .setBufferTargetMs(VOICE_BUFFER_TARGET_MS)
            .setBufferMinMs(VOICE_BUFFER_MIN_MS)
            .setBufferMaxMs(VOICE_BUFFER_MAX_MS);
    }

    /**
     * Checks if this configuration uses voice-optimized buffer settings.
     */
    public boolean isVoiceOptimized() {
        return bufferTargetMs >= VOICE_BUFFER_TARGET_MS;
    }

    @Override
    public String toString() {
        return String.format("AudioStreamConfig[%dHz, %d-bit, %dch, %dms frames, buffer %d-%d-%dms]",
            sampleRate, bitsPerSample, channels, frameDurationMs,
            bufferMinMs, bufferTargetMs, bufferMaxMs);
    }
}
