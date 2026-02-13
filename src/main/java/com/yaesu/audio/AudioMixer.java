/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio;

import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages TX audio arbitration for multiple clients sharing a single playback device.
 * <p>
 * Uses priority-based arbitration with an idle timeout for channel release. Only one
 * client can transmit at a time - the first client to claim the channel holds it until:
 * <ul>
 *   <li>They stop transmitting and the idle timeout expires</li>
 *   <li>A higher-priority client preempts them</li>
 *   <li>They disconnect</li>
 * </ul>
 * </p>
 */
public class AudioMixer {

    private static final Logger logger = Logger.getLogger(AudioMixer.class.getName());

    /**
     * TX priority levels for arbitration.
     */
    public enum TxPriority {
        /** Low priority - yields to all others */
        LOW(0),
        /** Normal priority - default for most clients */
        NORMAL(1),
        /** High priority - preempts normal/low */
        HIGH(2),
        /** Exclusive - preempts all others */
        EXCLUSIVE(3);

        private final int level;

        TxPriority(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        public boolean canPreempt(TxPriority other) {
            return this.level > other.level;
        }
    }

    /**
     * Result of a TX submission attempt.
     */
    public enum TxResult {
        /** Audio was accepted and will be played */
        ACCEPTED,
        /** Audio was rejected - another client holds the channel */
        REJECTED,
        /** Client was preempted by a higher priority client */
        PREEMPTED
    }

    /**
     * Interface for TX clients.
     */
    public interface TxClient {
        /**
         * Gets the client identifier.
         */
        String getClientId();

        /**
         * Gets the TX priority for this client.
         */
        TxPriority getTxPriority();

        /**
         * Called when the client is preempted by a higher priority client.
         */
        void onPreempted(String preemptingClientId);

        /**
         * Called when the TX channel is granted to this client.
         */
        void onTxGranted();

        /**
         * Called when the TX channel is released (idle timeout or explicit release).
         */
        void onTxReleased();
    }

    /**
     * Listener for mixer events.
     */
    public interface MixerListener {
        /**
         * Called when a TX conflict occurs.
         */
        void onTxConflict(String holdingClientId, String requestingClientId);

        /**
         * Called when TX ownership changes.
         */
        void onTxOwnerChanged(String newOwnerClientId);
    }

    private final AudioStreamConfig config;
    private final ReentrantLock txLock = new ReentrantLock();
    private final ConcurrentHashMap<String, TxClient> clients = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private SourceDataLine playbackLine;
    private Thread playbackThread;
    private MixerListener listener;

    // TX ownership tracking
    private volatile String currentTxOwner = null;
    private volatile TxPriority currentTxPriority = null;
    private volatile long lastTxActivityTime = 0;

    // Audio buffer for mixing (single-client for now, just pass through)
    private final AudioRingBuffer txBuffer;

    /**
     * Creates a new AudioMixer.
     *
     * @param config the audio stream configuration
     */
    public AudioMixer(AudioStreamConfig config) {
        this.config = config;
        this.txBuffer = new AudioRingBuffer(config, config.msToBytes(config.getBufferMaxMs() * 2), "TxMixer");
    }

    /**
     * Sets the mixer listener for event notifications.
     */
    public void setMixerListener(MixerListener listener) {
        this.listener = listener;
    }

    /**
     * Registers a TX client.
     */
    public void registerClient(TxClient client) {
        if (client != null) {
            clients.put(client.getClientId(), client);
            logger.fine("Registered TX client: " + client.getClientId());
        }
    }

    /**
     * Unregisters a TX client.
     * <p>
     * If the client currently holds the TX channel, it will be released.
     * </p>
     */
    public void unregisterClient(String clientId) {
        TxClient removed = clients.remove(clientId);
        if (removed != null) {
            txLock.lock();
            try {
                if (clientId.equals(currentTxOwner)) {
                    releaseTxChannel(clientId);
                }
            } finally {
                txLock.unlock();
            }
            logger.fine("Unregistered TX client: " + clientId);
        }
    }

    /**
     * Submits TX audio from a client.
     *
     * @param clientId the client identifier
     * @param data the audio data
     * @param offset the offset in the data array
     * @param length the number of bytes
     * @return the result of the submission
     */
    public TxResult submitTxAudio(String clientId, byte[] data, int offset, int length) {
        TxClient client = clients.get(clientId);
        if (client == null) {
            return TxResult.REJECTED;
        }

        txLock.lock();
        try {
            // Check if we can claim or maintain the TX channel
            if (currentTxOwner == null) {
                // Channel is free - claim it
                claimTxChannel(clientId, client.getTxPriority());
            } else if (currentTxOwner.equals(clientId)) {
                // We already own it - update activity time
                lastTxActivityTime = System.currentTimeMillis();
            } else {
                // Someone else owns it - check if we can preempt
                TxPriority ourPriority = client.getTxPriority();
                if (ourPriority.canPreempt(currentTxPriority)) {
                    // Preempt the current owner
                    preemptCurrentOwner(clientId, ourPriority);
                } else {
                    // Cannot preempt - notify of conflict
                    notifyTxConflict(currentTxOwner, clientId);
                    return TxResult.REJECTED;
                }
            }

            // Write to the TX buffer
            txBuffer.write(data, offset, length);
            return TxResult.ACCEPTED;

        } finally {
            txLock.unlock();
        }
    }

    /**
     * Submits TX audio from a client.
     */
    public TxResult submitTxAudio(String clientId, byte[] data) {
        return submitTxAudio(clientId, data, 0, data.length);
    }

    /**
     * Gets the current TX channel owner.
     *
     * @return the client ID of the current owner, or null if no one owns it
     */
    public String getCurrentTxOwner() {
        return currentTxOwner;
    }

    /**
     * Checks if a specific client currently owns the TX channel.
     */
    public boolean isTxOwner(String clientId) {
        return clientId != null && clientId.equals(currentTxOwner);
    }

    /**
     * Explicitly releases the TX channel for a client.
     */
    public void releaseTx(String clientId) {
        txLock.lock();
        try {
            if (clientId.equals(currentTxOwner)) {
                releaseTxChannel(clientId);
            }
        } finally {
            txLock.unlock();
        }
    }

    /**
     * Starts the mixer with the specified playback line.
     *
     * @param playbackLine the playback line (should be opened but not started)
     */
    public void start(SourceDataLine playbackLine) {
        if (running.getAndSet(true)) {
            return;
        }

        this.playbackLine = playbackLine;

        playbackThread = new Thread(this::playbackLoop, "AudioMixer");
        playbackThread.start();

        logger.info("AudioMixer started");
    }

    /**
     * Stops the mixer.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playbackThread = null;
        }

        if (playbackLine != null) {
            playbackLine.stop();
            playbackLine = null;
        }

        // Clear state
        txLock.lock();
        try {
            currentTxOwner = null;
            currentTxPriority = null;
            txBuffer.clear();
        } finally {
            txLock.unlock();
        }

        logger.info("AudioMixer stopped");
    }

    /**
     * Checks if the mixer is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Gets the TX buffer statistics.
     */
    public AudioRingBuffer getTxBuffer() {
        return txBuffer;
    }

    private void playbackLoop() {
        byte[] buffer = new byte[config.getBytesPerFrame()];
        playbackLine.start();

        // Initial buffering with timeout
        long bufferingStart = System.currentTimeMillis();
        long maxBufferingTime = AudioStreamConfig.MAX_INITIAL_BUFFERING_MS;

        while (running.get() && !txBuffer.hasReachedTargetLevel()) {
            long elapsed = System.currentTimeMillis() - bufferingStart;
            if (elapsed >= maxBufferingTime) {
                if (txBuffer.getAvailable() > 0) {
                    logger.warning("Initial buffering timeout, starting with " +
                        txBuffer.getBufferLevelMs() + "ms buffer");
                }
                break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }

        while (running.get()) {
            try {
                // Check for TX idle timeout
                checkIdleTimeout();

                int bytesRead = txBuffer.read(buffer, 0, buffer.length, config.getFrameDurationMs() * 2);
                if (bytesRead > 0) {
                    playbackLine.write(buffer, 0, bytesRead);
                } else if (bytesRead == 0 && txBuffer.getAvailable() == 0) {
                    // Buffer empty - play silence to prevent audio glitches
                    playbackLine.write(new byte[buffer.length], 0, buffer.length);
                }
            } catch (Exception e) {
                if (running.get()) {
                    logger.log(Level.WARNING, "Playback error: " + e.getMessage(), e);
                }
            }
        }

        playbackLine.stop();
    }

    private void checkIdleTimeout() {
        if (currentTxOwner == null) {
            return;
        }

        long idleTime = System.currentTimeMillis() - lastTxActivityTime;
        if (idleTime >= config.getTxIdleTimeoutMs()) {
            txLock.lock();
            try {
                if (currentTxOwner != null &&
                    System.currentTimeMillis() - lastTxActivityTime >= config.getTxIdleTimeoutMs()) {
                    String owner = currentTxOwner;
                    releaseTxChannel(owner);
                    logger.fine("TX channel released due to idle timeout: " + owner);
                }
            } finally {
                txLock.unlock();
            }
        }
    }

    private void claimTxChannel(String clientId, TxPriority priority) {
        currentTxOwner = clientId;
        currentTxPriority = priority;
        lastTxActivityTime = System.currentTimeMillis();
        txBuffer.clear();

        TxClient client = clients.get(clientId);
        if (client != null) {
            try {
                client.onTxGranted();
            } catch (Exception ignored) {}
        }

        notifyTxOwnerChanged(clientId);
        logger.fine("TX channel claimed by: " + clientId + " (priority: " + priority + ")");
    }

    private void preemptCurrentOwner(String newClientId, TxPriority newPriority) {
        String previousOwner = currentTxOwner;

        // Notify the previous owner
        TxClient prevClient = clients.get(previousOwner);
        if (prevClient != null) {
            try {
                prevClient.onPreempted(newClientId);
            } catch (Exception ignored) {}
        }

        // Clear the buffer and claim for new owner
        txBuffer.clear();
        currentTxOwner = newClientId;
        currentTxPriority = newPriority;
        lastTxActivityTime = System.currentTimeMillis();

        TxClient newClient = clients.get(newClientId);
        if (newClient != null) {
            try {
                newClient.onTxGranted();
            } catch (Exception ignored) {}
        }

        notifyTxOwnerChanged(newClientId);
        logger.info("TX channel preempted: " + previousOwner + " -> " + newClientId);
    }

    private void releaseTxChannel(String clientId) {
        if (!clientId.equals(currentTxOwner)) {
            return;
        }

        TxClient client = clients.get(clientId);
        if (client != null) {
            try {
                client.onTxReleased();
            } catch (Exception ignored) {}
        }

        currentTxOwner = null;
        currentTxPriority = null;
        txBuffer.clear();

        notifyTxOwnerChanged(null);
        logger.fine("TX channel released by: " + clientId);
    }

    private void notifyTxConflict(String holdingClientId, String requestingClientId) {
        if (listener != null) {
            try {
                listener.onTxConflict(holdingClientId, requestingClientId);
            } catch (Exception ignored) {}
        }
    }

    private void notifyTxOwnerChanged(String newOwnerClientId) {
        if (listener != null) {
            try {
                listener.onTxOwnerChanged(newOwnerClientId);
            } catch (Exception ignored) {}
        }
    }
}
