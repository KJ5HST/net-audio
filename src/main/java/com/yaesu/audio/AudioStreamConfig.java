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

    @Override
    public String toString() {
        return String.format("AudioStreamConfig[%dHz, %d-bit, %dch, %dms frames, buffer %d-%d-%dms]",
            sampleRate, bitsPerSample, channels, frameDurationMs,
            bufferMinMs, bufferTargetMs, bufferMaxMs);
    }
}
