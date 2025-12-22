/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License.
 */
package com.yaesu.audio.client;

import com.yaesu.audio.*;
import com.yaesu.audio.protocol.*;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.*;

/**
 * Client for connecting to ftx1-audio server.
 * <p>
 * Receives RX audio from the server and plays it to a virtual audio device.
 * Captures TX audio from a virtual audio device and sends it to the server.
 * </p>
 */
public class AudioStreamClient {

    /** Connection timeout in milliseconds */
    private static final int CONNECT_TIMEOUT_MS = 10000;

    private final String serverHost;
    private final int serverPort;
    private final String clientName;
    private final AudioDeviceManager deviceManager;
    private final List<AudioStreamListener> listeners = new CopyOnWriteArrayList<>();

    private AudioStreamConfig config;
    private Socket socket;
    private AudioProtocolHandler protocol;
    private ExecutorService executor;

    // Audio devices (virtual audio on client side)
    private AudioDeviceInfo captureDevice;  // Captures from WSJT-X (TX audio)
    private AudioDeviceInfo playbackDevice; // Plays to WSJT-X (RX audio)

    private TargetDataLine captureLine;
    private SourceDataLine playbackLine;
    private AudioRingBuffer rxBuffer;
    private AudioRingBuffer txBuffer;

    private volatile boolean connected = false;
    private volatile boolean streaming = false;
    private volatile boolean closed = false;
    private volatile long measuredLatencyMs = 0;
    private long connectTime;

    // Worker threads
    private Thread captureThread;
    private Thread playbackThread;
    private Thread receiveThread;
    private Thread sendThread;
    private Thread heartbeatThread;

    /**
     * Creates a new audio stream client.
     *
     * @param serverHost the server hostname or IP
     * @param serverPort the server port
     */
    public AudioStreamClient(String serverHost, int serverPort) {
        this(serverHost, serverPort, "ftx1-audio-client");
    }

    /**
     * Creates a new audio stream client.
     *
     * @param serverHost the server hostname or IP
     * @param serverPort the server port
     * @param clientName the client name for identification
     */
    public AudioStreamClient(String serverHost, int serverPort, String clientName) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.clientName = clientName;
        this.config = new AudioStreamConfig();
        this.deviceManager = new AudioDeviceManager(config);
    }

    /**
     * Sets the capture device (virtual device input from WSJT-X).
     */
    public void setCaptureDevice(AudioDeviceInfo device) {
        this.captureDevice = device;
    }

    /**
     * Sets the playback device (virtual device output to WSJT-X).
     */
    public void setPlaybackDevice(AudioDeviceInfo device) {
        this.playbackDevice = device;
    }

    /**
     * Gets the audio device manager.
     */
    public AudioDeviceManager getDeviceManager() {
        return deviceManager;
    }

    /**
     * Connects to the server.
     */
    public void connect() throws IOException {
        if (connected) {
            throw new IllegalStateException("Already connected");
        }

        if (captureDevice == null || playbackDevice == null) {
            throw new IllegalStateException("Audio devices not configured");
        }

        // Connect socket
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(serverHost, serverPort), CONNECT_TIMEOUT_MS);
        protocol = new AudioProtocolHandler(socket);
        executor = Executors.newCachedThreadPool();
        connectTime = System.currentTimeMillis();

        try {
            // Perform handshake
            if (!performHandshake()) {
                throw new IOException("Handshake failed");
            }

            // Open audio lines
            if (!openAudioLines()) {
                throw new IOException("Failed to open audio devices");
            }

            connected = true;
            notifyClientConnected("local", serverHost + ":" + serverPort);

            // Start worker threads
            startWorkerThreads();

            streaming = true;
            notifyStreamStarted("local", config);

        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /**
     * Disconnects from the server.
     */
    public void disconnect() {
        if (!connected) return;

        try {
            protocol.sendControl(ControlMessage.disconnect());
        } catch (IOException ignored) {}

        close();
    }

    /**
     * Closes all resources.
     */
    private void close() {
        if (closed) return;
        closed = true;
        streaming = false;
        connected = false;

        notifyStreamStopped("local");

        // Stop threads
        if (captureThread != null) captureThread.interrupt();
        if (playbackThread != null) playbackThread.interrupt();
        if (receiveThread != null) receiveThread.interrupt();
        if (sendThread != null) sendThread.interrupt();
        if (heartbeatThread != null) heartbeatThread.interrupt();

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
        if (protocol != null) {
            try {
                protocol.close();
            } catch (IOException ignored) {}
        }

        // Close socket
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }

        // Shutdown executor
        if (executor != null) {
            executor.shutdownNow();
        }

        notifyClientDisconnected("local");
    }

    /**
     * Checks if connected to server.
     */
    public boolean isConnected() {
        return connected && !closed;
    }

    /**
     * Checks if streaming is active.
     */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * Gets current statistics.
     */
    public AudioStreamStats getStats() {
        if (protocol == null || rxBuffer == null) {
            return new AudioStreamStats();
        }

        return new AudioStreamStats()
            .setBytesReceived(protocol.getBytesReceived())
            .setBytesSent(protocol.getBytesSent())
            .setPacketsReceived(protocol.getPacketsReceived())
            .setPacketsSent(protocol.getPacketsSent())
            .setBufferLevelMs(rxBuffer.getBufferLevelMs())
            .setBufferFillPercent(rxBuffer.getBufferFillPercent())
            .setUnderrunCount(rxBuffer.getUnderrunCount())
            .setOverrunCount(rxBuffer.getOverrunCount())
            .setCrcErrors(protocol.getCrcErrors())
            .setLatencyMs(measuredLatencyMs)
            .setConnectionTimeMs(System.currentTimeMillis() - connectTime)
            .setStreaming(streaming);
    }

    /**
     * Measures round-trip latency.
     */
    public void measureLatency() {
        if (!connected || protocol == null) return;

        try {
            protocol.sendControl(ControlMessage.latencyProbe(System.nanoTime()));
        } catch (IOException ignored) {}
    }

    /**
     * Gets the measured latency in milliseconds.
     */
    public long getMeasuredLatencyMs() {
        return measuredLatencyMs;
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

    private boolean performHandshake() throws IOException {
        // Send connect request
        protocol.sendControl(ControlMessage.connectRequest(clientName, AudioPacket.VERSION));

        // Wait for response
        AudioPacket packet = protocol.receivePacket(CONNECT_TIMEOUT_MS);
        if (packet == null) {
            notifyError("local", "Handshake timeout");
            return false;
        }

        // Process responses until we get accept/reject
        while (packet != null) {
            if (packet.getType() != AudioPacket.Type.CONTROL) {
                packet = protocol.receivePacket(CONNECT_TIMEOUT_MS);
                continue;
            }

            ControlMessage msg = ControlMessage.deserialize(packet.getPayload());
            if (msg == null) {
                packet = protocol.receivePacket(CONNECT_TIMEOUT_MS);
                continue;
            }

            switch (msg.getType()) {
                case AUDIO_CONFIG:
                    // Server is telling us its config
                    AudioStreamConfig serverConfig = msg.parseAudioConfig();
                    if (serverConfig != null) {
                        this.config = serverConfig;
                    }
                    break;
                case CONNECT_ACCEPT:
                    return true;
                case CONNECT_REJECT:
                    String reason = msg.parseErrorMessage();
                    notifyError("local", "Connection rejected: " + (reason != null ? reason : "unknown"));
                    return false;
                default:
                    break;
            }

            packet = protocol.receivePacket(CONNECT_TIMEOUT_MS);
        }

        return false;
    }

    private boolean openAudioLines() {
        try {
            // Reinitialize device manager with server config
            AudioDeviceManager mgr = new AudioDeviceManager(config);

            captureLine = mgr.openCaptureLine(captureDevice);
            playbackLine = mgr.openPlaybackLine(playbackDevice);

            rxBuffer = new AudioRingBuffer(config);
            txBuffer = new AudioRingBuffer(config);

            return true;
        } catch (LineUnavailableException e) {
            notifyError("local", "Audio line unavailable: " + e.getMessage());
            return false;
        }
    }

    private void startWorkerThreads() {
        // Receive thread - receives RX audio from server, puts in buffer
        receiveThread = new Thread(() -> {
            while (!closed && connected) {
                try {
                    AudioPacket packet = protocol.receivePacket(100);
                    if (packet == null) continue;

                    switch (packet.getType()) {
                        case AUDIO_RX:
                            rxBuffer.write(packet.getPayload());
                            break;
                        case CONTROL:
                            handleControlMessage(packet);
                            break;
                        case HEARTBEAT:
                            // Server heartbeat, send ack
                            protocol.sendControl(ControlMessage.heartbeatAck());
                            break;
                        default:
                            break;
                    }
                } catch (IOException e) {
                    if (!closed) {
                        notifyError("local", "Receive error: " + e.getMessage());
                        close();
                    }
                    break;
                }
            }
        }, "AudioReceive");
        receiveThread.start();

        // Playback thread - plays RX audio to virtual device (for WSJT-X)
        playbackThread = new Thread(() -> {
            byte[] buffer = new byte[config.getBytesPerFrame()];
            playbackLine.start();

            // Initial buffering
            while (!closed && connected && !rxBuffer.hasReachedTargetLevel()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }

            while (!closed && connected) {
                int bytesRead = rxBuffer.read(buffer, 0, buffer.length, config.getFrameDurationMs() * 2);
                if (bytesRead > 0) {
                    playbackLine.write(buffer, 0, bytesRead);
                } else if (bytesRead == 0 && rxBuffer.getAvailable() == 0) {
                    // Underrun - insert silence
                    playbackLine.write(new byte[buffer.length], 0, buffer.length);
                }
            }

            playbackLine.stop();
        }, "AudioPlayback");
        playbackThread.start();

        // Capture thread - captures TX audio from virtual device (from WSJT-X)
        captureThread = new Thread(() -> {
            byte[] buffer = new byte[config.getBytesPerFrame()];
            captureLine.start();

            while (!closed && connected) {
                int bytesRead = captureLine.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    txBuffer.write(buffer, 0, bytesRead);
                }
            }

            captureLine.stop();
        }, "AudioCapture");
        captureThread.start();

        // Send thread - sends TX audio to server
        sendThread = new Thread(() -> {
            byte[] buffer = new byte[config.getBytesPerFrame()];

            while (!closed && connected) {
                int bytesRead = txBuffer.read(buffer, 0, buffer.length, config.getFrameDurationMs() * 2);
                if (bytesRead > 0) {
                    try {
                        protocol.sendTxAudio(buffer);
                    } catch (IOException e) {
                        if (!closed) {
                            notifyError("local", "Send error: " + e.getMessage());
                            close();
                        }
                        break;
                    }
                }
            }
        }, "AudioSend");
        sendThread.start();

        // Heartbeat thread
        heartbeatThread = new Thread(() -> {
            while (!closed && connected) {
                try {
                    Thread.sleep(AudioProtocolHandler.HEARTBEAT_INTERVAL_MS);

                    if (protocol.shouldSendHeartbeat()) {
                        protocol.sendHeartbeat();
                    }

                    if (protocol.isConnectionTimedOut()) {
                        notifyError("local", "Connection timeout");
                        close();
                        break;
                    }

                    // Periodic latency measurement
                    measureLatency();

                    // Update statistics
                    notifyStatisticsUpdate("local", getStats());

                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    if (!closed) {
                        notifyError("local", "Heartbeat error: " + e.getMessage());
                    }
                }
            }
        }, "Heartbeat");
        heartbeatThread.start();
    }

    private void handleControlMessage(AudioPacket packet) {
        ControlMessage msg = ControlMessage.deserialize(packet.getPayload());
        if (msg == null) return;

        switch (msg.getType()) {
            case LATENCY_RESPONSE:
                long sent = msg.parseLatencyTimestamp();
                measuredLatencyMs = (System.nanoTime() - sent) / 1_000_000 / 2;
                break;
            case DISCONNECT:
            case ERROR:
                String error = msg.parseErrorMessage();
                if (error != null) {
                    notifyError("local", error);
                }
                close();
                break;
            default:
                break;
        }
    }

    // Notification methods

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
}
