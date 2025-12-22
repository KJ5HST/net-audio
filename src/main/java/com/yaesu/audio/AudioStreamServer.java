/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License.
 */
package com.yaesu.audio;

import com.yaesu.audio.protocol.*;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TCP server for bidirectional audio streaming.
 * <p>
 * Captures audio from the FTX-1 USB audio interface and streams it to
 * connected clients. Also receives audio from clients and plays it to
 * the FTX-1 for transmission.
 * </p>
 */
public class AudioStreamServer {

    private final int port;
    private final AudioStreamConfig config;
    private final AudioDeviceManager deviceManager;
    private final List<AudioStreamListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger clientIdCounter = new AtomicInteger(1);

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;

    // Audio devices
    private AudioDeviceInfo captureDevice;
    private AudioDeviceInfo playbackDevice;

    // Current client session (single client supported for now)
    // Using AtomicReference to prevent race condition when multiple clients connect simultaneously
    private final AtomicReference<ClientSession> currentSession = new AtomicReference<>();

    /**
     * Creates a new audio stream server.
     *
     * @param port the port to listen on
     */
    public AudioStreamServer(int port) {
        this(port, new AudioStreamConfig());
    }

    /**
     * Creates a new audio stream server with custom configuration.
     *
     * @param port the port to listen on
     * @param config the audio stream configuration
     */
    public AudioStreamServer(int port, AudioStreamConfig config) {
        this.port = port;
        this.config = config;
        this.deviceManager = new AudioDeviceManager(config);
    }

    /**
     * Sets the capture device (radio RX audio).
     */
    public void setCaptureDevice(AudioDeviceInfo device) {
        this.captureDevice = device;
    }

    /**
     * Sets the playback device (radio TX audio).
     */
    public void setPlaybackDevice(AudioDeviceInfo device) {
        this.playbackDevice = device;
    }

    /**
     * Gets the capture device.
     */
    public AudioDeviceInfo getCaptureDevice() {
        return captureDevice;
    }

    /**
     * Gets the playback device.
     */
    public AudioDeviceInfo getPlaybackDevice() {
        return playbackDevice;
    }

    /**
     * Gets the audio device manager.
     */
    public AudioDeviceManager getDeviceManager() {
        return deviceManager;
    }

    /**
     * Gets the configuration.
     */
    public AudioStreamConfig getConfig() {
        return config;
    }

    /**
     * Starts the audio server.
     */
    public void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Server already running");
        }

        serverSocket = new ServerSocket(port);
        executor = Executors.newCachedThreadPool();
        running = true;

        // Accept connections in a thread
        executor.submit(this::acceptLoop);

        notifyServerStarted(port);
    }

    /**
     * Stops the audio server.
     */
    public void stop() {
        // Close current session
        ClientSession session = currentSession.getAndSet(null);
        if (session != null) {
            session.close();
        }

        // Close server socket to unblock accept()
        ServerSocket socketToClose = serverSocket;
        serverSocket = null;

        if (socketToClose != null) {
            try {
                socketToClose.close();
            } catch (IOException ignored) {}
        }

        running = false;

        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        notifyServerStopped();
    }

    /**
     * Checks if the server is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Checks if a client is connected.
     */
    public boolean hasClient() {
        ClientSession session = currentSession.get();
        return session != null && !session.isClosed();
    }

    /**
     * Gets the current client statistics.
     */
    public AudioStreamStats getClientStats() {
        ClientSession session = currentSession.get();
        if (session != null) {
            return session.getStats();
        }
        return null;
    }

    /**
     * Adds a stream listener.
     */
    public void addStreamListener(AudioStreamListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a stream listener.
     */
    public void removeStreamListener(AudioStreamListener listener) {
        listeners.remove(listener);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                handleNewClient(clientSocket);
            } catch (IOException e) {
                if (running) {
                    notifyError(null, "Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleNewClient(Socket socket) {
        String clientId = "audio-" + clientIdCounter.getAndIncrement();
        String address = socket.getRemoteSocketAddress().toString();

        // Check if audio devices are configured
        if (captureDevice == null || playbackDevice == null) {
            try {
                AudioProtocolHandler handler = new AudioProtocolHandler(socket);
                handler.sendControl(ControlMessage.connectReject(
                    ControlMessage.RejectReason.REJECTED, "Audio devices not configured"));
                handler.close();
            } catch (IOException ignored) {}
            return;
        }

        // Create and start session - use atomic compareAndSet to prevent race condition
        try {
            ClientSession newSession = new ClientSession(clientId, socket);

            // Atomically check and set the session
            // Loop to handle case where old session was closed
            while (true) {
                ClientSession existing = currentSession.get();
                if (existing != null && !existing.isClosed()) {
                    // Active session exists - reject new client
                    try {
                        AudioProtocolHandler handler = new AudioProtocolHandler(socket);
                        handler.sendControl(ControlMessage.connectReject(
                            ControlMessage.RejectReason.BUSY, "Another client is already connected"));
                        handler.close();
                    } catch (IOException ignored) {}
                    return;
                }

                // Try to atomically set the new session
                if (currentSession.compareAndSet(existing, newSession)) {
                    // Success - we own this slot
                    notifyClientConnected(clientId, address);
                    executor.submit(newSession);
                    return;
                }
                // CAS failed - another thread beat us, retry the check
            }
        } catch (Exception e) {
            notifyError(clientId, "Failed to create session: " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    // Notification methods

    private void notifyServerStarted(int port) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onServerStarted(port);
            } catch (Exception ignored) {}
        }
    }

    private void notifyServerStopped() {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onServerStopped();
            } catch (Exception ignored) {}
        }
    }

    private void notifyClientConnected(String clientId, String address) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onClientConnected(clientId, address);
            } catch (Exception ignored) {}
        }
    }

    private void notifyClientDisconnected(String clientId) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onClientDisconnected(clientId);
            } catch (Exception ignored) {}
        }
    }

    private void notifyStreamStarted(String clientId, AudioStreamConfig config) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onStreamStarted(clientId, config);
            } catch (Exception ignored) {}
        }
    }

    private void notifyStreamStopped(String clientId) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onStreamStopped(clientId);
            } catch (Exception ignored) {}
        }
    }

    private void notifyStatisticsUpdate(String clientId, AudioStreamStats stats) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onStatisticsUpdate(clientId, stats);
            } catch (Exception ignored) {}
        }
    }

    private void notifyError(String clientId, String error) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onError(clientId, error);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Handles a single client session with bidirectional audio streaming.
     */
    private class ClientSession implements Runnable {
        private final String clientId;
        private final Socket socket;
        private final AudioProtocolHandler protocol;
        private final AudioRingBuffer rxBuffer;
        private final AudioRingBuffer txBuffer;
        private final long connectTime;

        private TargetDataLine captureLine;
        private SourceDataLine playbackLine;
        private volatile boolean closed = false;
        private volatile boolean streaming = false;
        private volatile long measuredLatencyMs = 0;

        // Worker threads
        private Thread captureThread;
        private Thread playbackThread;
        private Thread receiveThread;

        ClientSession(String clientId, Socket socket) throws IOException {
            this.clientId = clientId;
            this.socket = socket;
            this.protocol = new AudioProtocolHandler(socket);
            this.rxBuffer = new AudioRingBuffer(config);
            this.txBuffer = new AudioRingBuffer(config);
            this.connectTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            try {
                // Perform handshake
                if (!performHandshake()) {
                    close();
                    return;
                }

                // Open audio lines
                if (!openAudioLines()) {
                    protocol.sendControl(ControlMessage.error("Failed to open audio devices"));
                    close();
                    return;
                }

                // Send config and accept
                protocol.sendControl(ControlMessage.audioConfig(config));
                protocol.sendControl(ControlMessage.connectAccept());

                // Start worker threads
                startWorkerThreads();

                notifyStreamStarted(clientId, config);
                streaming = true;

                // Main loop - handle heartbeats and statistics
                while (!closed && running) {
                    if (protocol.shouldSendHeartbeat()) {
                        protocol.sendHeartbeat();
                    }

                    if (protocol.isConnectionTimedOut()) {
                        notifyError(clientId, "Connection timeout");
                        break;
                    }

                    // Update statistics periodically
                    notifyStatisticsUpdate(clientId, getStats());

                    Thread.sleep(1000);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (!closed) {
                    notifyError(clientId, "Connection error: " + e.getMessage());
                }
            } finally {
                streaming = false;
                notifyStreamStopped(clientId);
                close();
                notifyClientDisconnected(clientId);
                currentSession.compareAndSet(this, null);
            }
        }

        private boolean performHandshake() throws IOException {
            // Wait for connect request
            AudioPacket packet = protocol.receivePacket(10000);
            if (packet == null || packet.getType() != AudioPacket.Type.CONTROL) {
                return false;
            }

            ControlMessage msg = ControlMessage.deserialize(packet.getPayload());
            if (msg == null || msg.getType() != ControlMessage.Type.CONNECT_REQUEST) {
                return false;
            }

            return true;
        }

        private boolean openAudioLines() {
            try {
                captureLine = deviceManager.openCaptureLine(captureDevice);
                playbackLine = deviceManager.openPlaybackLine(playbackDevice);
                return true;
            } catch (LineUnavailableException e) {
                notifyError(clientId, "Audio line unavailable: " + e.getMessage());
                return false;
            }
        }

        private void startWorkerThreads() {
            // Capture thread - reads from radio, sends to client
            captureThread = new Thread(() -> {
                byte[] buffer = new byte[config.getBytesPerFrame()];
                captureLine.start();

                while (!closed && running) {
                    int bytesRead = captureLine.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        try {
                            protocol.sendRxAudio(buffer);
                        } catch (IOException e) {
                            if (!closed) {
                                notifyError(clientId, "Send error: " + e.getMessage());
                                close();
                            }
                            break;
                        }
                    }
                }

                captureLine.stop();
            }, "AudioCapture-" + clientId);
            captureThread.start();

            // Receive thread - receives from client, puts in TX buffer
            receiveThread = new Thread(() -> {
                while (!closed && running) {
                    try {
                        AudioPacket packet = protocol.receivePacket(100);
                        if (packet == null) continue;

                        switch (packet.getType()) {
                            case AUDIO_TX:
                                txBuffer.write(packet.getPayload());
                                break;
                            case CONTROL:
                                handleControlMessage(packet);
                                break;
                            case HEARTBEAT:
                                // Heartbeat received, connection is alive
                                break;
                            default:
                                break;
                        }
                    } catch (IOException e) {
                        if (!closed) {
                            notifyError(clientId, "Receive error: " + e.getMessage());
                            close();
                        }
                        break;
                    }
                }
            }, "AudioReceive-" + clientId);
            receiveThread.start();

            // Playback thread - reads from TX buffer, plays to radio
            playbackThread = new Thread(() -> {
                byte[] buffer = new byte[config.getBytesPerFrame()];
                playbackLine.start();

                // Initial buffering
                while (!closed && running && !txBuffer.hasReachedTargetLevel()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                while (!closed && running) {
                    int bytesRead = txBuffer.read(buffer, 0, buffer.length, config.getFrameDurationMs() * 2);
                    if (bytesRead > 0) {
                        playbackLine.write(buffer, 0, bytesRead);
                    } else if (bytesRead == 0 && txBuffer.getAvailable() == 0) {
                        // Underrun - insert silence
                        playbackLine.write(new byte[buffer.length], 0, buffer.length);
                    }
                }

                playbackLine.stop();
            }, "AudioPlayback-" + clientId);
            playbackThread.start();
        }

        private void handleControlMessage(AudioPacket packet) throws IOException {
            ControlMessage msg = ControlMessage.deserialize(packet.getPayload());
            if (msg == null) return;

            switch (msg.getType()) {
                case LATENCY_PROBE:
                    long timestamp = msg.parseLatencyTimestamp();
                    protocol.sendControl(ControlMessage.latencyResponse(timestamp));
                    break;
                case LATENCY_RESPONSE:
                    long sent = msg.parseLatencyTimestamp();
                    measuredLatencyMs = (System.nanoTime() - sent) / 1_000_000 / 2;
                    break;
                case DISCONNECT:
                    close();
                    break;
                case HEARTBEAT:
                case HEARTBEAT_ACK:
                    // Just receiving these resets the timeout
                    break;
                default:
                    break;
            }
        }

        AudioStreamStats getStats() {
            return new AudioStreamStats()
                .setBytesReceived(protocol.getBytesReceived())
                .setBytesSent(protocol.getBytesSent())
                .setPacketsReceived(protocol.getPacketsReceived())
                .setPacketsSent(protocol.getPacketsSent())
                .setBufferLevelMs(txBuffer.getBufferLevelMs())
                .setBufferFillPercent(txBuffer.getBufferFillPercent())
                .setUnderrunCount(txBuffer.getUnderrunCount())
                .setOverrunCount(txBuffer.getOverrunCount())
                .setCrcErrors(protocol.getCrcErrors())
                .setLatencyMs(measuredLatencyMs)
                .setConnectionTimeMs(System.currentTimeMillis() - connectTime)
                .setStreaming(streaming);
        }

        boolean isClosed() {
            return closed;
        }

        void close() {
            if (closed) return;
            closed = true;

            // Stop threads
            if (captureThread != null) captureThread.interrupt();
            if (receiveThread != null) receiveThread.interrupt();
            if (playbackThread != null) playbackThread.interrupt();

            // Close audio lines
            if (captureLine != null) {
                captureLine.stop();
                captureLine.close();
            }
            if (playbackLine != null) {
                playbackLine.stop();
                playbackLine.close();
            }

            // Close protocol handler
            try {
                protocol.close();
            } catch (IOException ignored) {}

            // Close socket
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
