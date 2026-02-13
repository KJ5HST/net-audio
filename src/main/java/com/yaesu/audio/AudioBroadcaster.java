/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio;

import javax.sound.sampled.TargetDataLine;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Broadcasts RX audio from a single capture source to multiple clients.
 * <p>
 * Uses a single TargetDataLine capture thread shared by the server. Audio data
 * is broadcast to all registered targets without per-client buffering at the
 * server level - each target receives data directly and handles its own buffering.
 * </p>
 * <p>
 * Key design decisions:
 * <ul>
 *   <li>Non-blocking broadcast: a slow client cannot block other clients</li>
 *   <li>Failed targets are marked for cleanup but not removed during iteration</li>
 *   <li>Single capture thread minimizes CPU and audio resource usage</li>
 * </ul>
 * </p>
 */
public class AudioBroadcaster {

    private static final Logger logger = Logger.getLogger(AudioBroadcaster.class.getName());

    /**
     * Interface for broadcast targets that receive RX audio data.
     */
    public interface BroadcastTarget {
        /**
         * Receives RX audio data. This method should not block.
         *
         * @param data the audio data buffer
         * @param offset the offset in the buffer
         * @param length the number of bytes of audio data
         * @return true if data was accepted, false if the target should be removed
         */
        boolean receiveRxAudio(byte[] data, int offset, int length);

        /**
         * Gets the target identifier.
         */
        String getTargetId();
    }

    /**
     * Listener for broadcast events.
     */
    public interface BroadcastListener {
        /**
         * Called when a target fails to receive data and is marked for removal.
         */
        void onTargetFailed(String targetId, String reason);
    }

    private final AudioStreamConfig config;
    private final ConcurrentHashMap<String, BroadcastTarget> targets = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private TargetDataLine captureLine;
    private Thread captureThread;
    private BroadcastListener listener;

    /**
     * Creates a new AudioBroadcaster.
     *
     * @param config the audio stream configuration
     */
    public AudioBroadcaster(AudioStreamConfig config) {
        this.config = config;
    }

    /**
     * Sets the broadcast listener for failure notifications.
     */
    public void setBroadcastListener(BroadcastListener listener) {
        this.listener = listener;
    }

    /**
     * Adds a target to receive broadcast audio.
     *
     * @param target the target to add
     */
    public void addTarget(BroadcastTarget target) {
        if (target != null) {
            targets.put(target.getTargetId(), target);
            logger.fine("Added broadcast target: " + target.getTargetId());
        }
    }

    /**
     * Removes a target from the broadcast.
     *
     * @param targetId the target identifier
     * @return the removed target, or null if not found
     */
    public BroadcastTarget removeTarget(String targetId) {
        BroadcastTarget removed = targets.remove(targetId);
        if (removed != null) {
            logger.fine("Removed broadcast target: " + targetId);
        }
        return removed;
    }

    /**
     * Gets the number of registered targets.
     */
    public int getTargetCount() {
        return targets.size();
    }

    /**
     * Checks if any targets are registered.
     */
    public boolean hasTargets() {
        return !targets.isEmpty();
    }

    /**
     * Starts the broadcast with the specified capture line.
     * <p>
     * The capture line should already be opened but not started.
     * This method will start the line and begin capturing.
     * </p>
     *
     * @param captureLine the capture line to use
     */
    public void start(TargetDataLine captureLine) {
        if (running.getAndSet(true)) {
            return; // Already running
        }

        this.captureLine = captureLine;

        captureThread = new Thread(this::captureLoop, "AudioBroadcaster");
        captureThread.start();

        logger.info("AudioBroadcaster started");
    }

    /**
     * Stops the broadcast.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return; // Not running
        }

        if (captureThread != null) {
            captureThread.interrupt();
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        if (captureLine != null) {
            captureLine.stop();
            captureLine = null;
        }

        logger.info("AudioBroadcaster stopped");
    }

    /**
     * Checks if the broadcaster is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Iterates over all targets.
     *
     * @param action the action to perform for each target
     */
    public void forEachTarget(BiConsumer<String, BroadcastTarget> action) {
        targets.forEach(action);
    }

    /**
     * Injects audio data to be broadcast to all targets.
     * This allows playback of recordings or other sources without
     * going through the capture device.
     *
     * @param data the audio data to broadcast
     */
    public void injectAudio(byte[] data) {
        if (data != null && data.length > 0 && !targets.isEmpty()) {
            broadcastToTargets(data, 0, data.length);
        }
    }

    /**
     * Injects audio data to be broadcast to all targets.
     *
     * @param data the audio data buffer
     * @param offset the offset in the buffer
     * @param length the number of bytes to broadcast
     */
    public void injectAudio(byte[] data, int offset, int length) {
        if (data != null && length > 0 && !targets.isEmpty()) {
            broadcastToTargets(data, offset, length);
        }
    }

    private void captureLoop() {
        byte[] buffer = new byte[config.getBytesPerFrame()];
        captureLine.start();

        long lastLogTime = 0;
        long totalBytesRead = 0;

        while (running.get()) {
            try {
                int bytesRead = captureLine.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    totalBytesRead += bytesRead;
                    broadcastToTargets(buffer, 0, bytesRead);

                    // Log every 5 seconds
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime > 5000) {
                        // Analyze both stereo channels from raw captured bytes
                        int channels = config.getChannels();
                        int bytesPerSample = config.getBitsPerSample() / 8;
                        int frameSize = bytesPerSample * channels;
                        int framesToCheck = Math.min(bytesRead / frameSize, 100);
                        int leftMax = 0, rightMax = 0;
                        int leftNonZero = 0, rightNonZero = 0;
                        for (int f = 0; f < framesToCheck; f++) {
                            int o = f * frameSize;
                            if (channels >= 1 && bytesPerSample == 2) {
                                short left = (short) ((buffer[o + 1] << 8) | (buffer[o] & 0xFF));
                                leftMax = Math.max(leftMax, Math.abs(left));
                                if (left != 0) leftNonZero++;
                            }
                            if (channels >= 2 && bytesPerSample == 2) {
                                short right = (short) ((buffer[o + 3] << 8) | (buffer[o + 2] & 0xFF));
                                rightMax = Math.max(rightMax, Math.abs(right));
                                if (right != 0) rightNonZero++;
                            }
                        }
                        String captureFormat = captureLine.getFormat().toString();
                        // Print first 3 frames (12 bytes) for comparison with AudioWebSocket
                        StringBuilder firstBytes = new StringBuilder();
                        for (int b = 0; b < Math.min(12, bytesRead); b++) {
                            firstBytes.append(String.format("%02X ", buffer[b] & 0xFF));
                        }
                        logger.info("Broadcaster: captured " + totalBytesRead + " bytes, " + targets.size() + " targets" +
                            ", format=" + captureFormat +
                            ", L_max=" + leftMax + " L_nz=" + leftNonZero + "/" + framesToCheck +
                            ", R_max=" + rightMax + " R_nz=" + rightNonZero + "/" + framesToCheck +
                            ", first12=" + firstBytes.toString().trim());
                        lastLogTime = now;
                        totalBytesRead = 0;
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    logger.log(Level.WARNING, "Capture error: " + e.getMessage(), e);
                }
            }
        }

        captureLine.stop();
    }

    private void broadcastToTargets(byte[] data, int offset, int length) {
        // Iterate over all targets and send data
        // Failed targets are collected for removal after iteration
        targets.forEach((id, target) -> {
            try {
                if (!target.receiveRxAudio(data, offset, length)) {
                    // Target indicated it should be removed
                    targets.remove(id);
                    notifyTargetFailed(id, "Target indicated removal");
                }
            } catch (Exception e) {
                // Target failed - remove it
                targets.remove(id);
                notifyTargetFailed(id, e.getMessage());
            }
        });
    }

    private void notifyTargetFailed(String targetId, String reason) {
        logger.warning("Broadcast target failed: " + targetId + " - " + reason);
        if (listener != null) {
            try {
                listener.onTargetFailed(targetId, reason);
            } catch (Exception ignored) {}
        }
    }
}
