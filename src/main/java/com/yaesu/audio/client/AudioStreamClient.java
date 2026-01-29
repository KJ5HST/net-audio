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

    /** Default initial reconnect delay in milliseconds */
    private static final int DEFAULT_RECONNECT_DELAY_MS = 1000;

    /** Default maximum reconnect delay in milliseconds */
    private static final int DEFAULT_MAX_RECONNECT_DELAY_MS = 30000;

    /** Default maximum reconnection attempts */
    private static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 10;

    /** Minimum stable connection time (ms) before resetting reconnect counter */
    private static final int MIN_STABLE_CONNECTION_MS = 5000;

    private final String serverHost;
    private final int serverPort;
    private final String clientName;
    private final AudioDeviceManager deviceManager;
    private final List<AudioStreamListener> listeners = new CopyOnWriteArrayList<>();

    // Raw audio listeners (for FFT/waterfall processing)
    private final List<java.util.function.Consumer<byte[]>> audioListeners = new CopyOnWriteArrayList<>();

    // Client identification
    private String callsign;
    private String operatorName;
    private String location;

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
    private volatile boolean captureIsMono = false; // Track mono capture for conversion

    private volatile boolean connected = false;
    private volatile boolean streaming = false;
    private volatile boolean closed = false;
    private volatile long measuredLatencyMs = 0;
    private long connectTime;

    // Mute controls for PTT operation
    private volatile boolean captureMuted = true;   // Start muted (RX mode)
    private volatile boolean playbackMuted = false; // Start unmuted (hear RX)

    // Auto-reconnection settings
    private boolean autoReconnect = true;
    private int maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;
    private int reconnectDelayMs = DEFAULT_RECONNECT_DELAY_MS;
    private int maxReconnectDelayMs = DEFAULT_MAX_RECONNECT_DELAY_MS;
    private volatile boolean reconnecting = false;
    private volatile int reconnectAttempt = 0;
    private Thread reconnectThread;

    // Server client info (from CLIENTS_UPDATE messages)
    private volatile ControlMessage.ClientsUpdateInfo serverClientsInfo;

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
     * Sets the client's callsign for identification to other clients.
     *
     * @param callsign the callsign (e.g., "KJ5HST")
     */
    public void setCallsign(String callsign) {
        this.callsign = callsign;
    }

    /**
     * Gets the client's callsign.
     */
    public String getCallsign() {
        return callsign;
    }

    /**
     * Sets the operator's name for identification to other clients.
     *
     * @param name the operator name (e.g., "John")
     */
    public void setOperatorName(String name) {
        this.operatorName = name;
    }

    /**
     * Gets the operator's name.
     */
    public String getOperatorName() {
        return operatorName;
    }

    /**
     * Sets the client's location for identification to other clients.
     *
     * @param location the location (e.g., "Austin, TX" or "EM10")
     */
    public void setLocation(String location) {
        this.location = location;
    }

    /**
     * Gets the client's location.
     */
    public String getLocation() {
        return location;
    }

    /**
     * Gets the audio device manager.
     */
    public AudioDeviceManager getDeviceManager() {
        return deviceManager;
    }

    /**
     * Gets the current audio stream configuration.
     */
    public AudioStreamConfig getConfig() {
        return config;
    }

    /**
     * Sets the audio stream configuration.
     * <p>
     * Must be called before {@link #connect()}. Use factory methods like
     * {@link AudioStreamConfig#ft8Optimized()} or {@link AudioStreamConfig#voiceOptimized()}
     * to get pre-configured settings for specific use cases.
     * </p>
     *
     * @param config the configuration to use
     * @throws IllegalStateException if already connected
     */
    public void setConfig(AudioStreamConfig config) {
        if (connected) {
            throw new IllegalStateException("Cannot change config while connected");
        }
        this.config = config;
    }

    /**
     * Connects to the server.
     */
    public void connect() throws IOException {
        if (connected) {
            throw new IllegalStateException("Already connected");
        }

        // Playback device is required for receiving audio
        // Capture device is optional (only needed for TX)
        if (playbackDevice == null) {
            throw new IllegalStateException("Playback device not configured");
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
        // Send disconnect message if connected
        if (connected && protocol != null) {
            try {
                protocol.sendControl(ControlMessage.disconnect());
            } catch (IOException ignored) {}
        }

        // Always call close() to stop reconnection attempts
        close();
    }

    /**
     * Closes all resources and stops any reconnection attempts.
     */
    private void close() {
        if (closed) return;
        closed = true;
        reconnecting = false;

        // Stop reconnection thread if running
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }

        closeResources();
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
     * Gets the number of clients connected to the server.
     * <p>
     * This information is updated via CLIENTS_UPDATE messages from the server.
     * </p>
     *
     * @return the number of connected clients, or -1 if not yet received
     */
    public int getServerClientCount() {
        ControlMessage.ClientsUpdateInfo info = serverClientsInfo;
        return info != null ? info.clientCount : -1;
    }

    /**
     * Gets the maximum number of clients allowed by the server.
     *
     * @return the max clients, or -1 if not yet received
     */
    public int getServerMaxClients() {
        ControlMessage.ClientsUpdateInfo info = serverClientsInfo;
        return info != null ? info.maxClients : -1;
    }

    /**
     * Gets the client ID of the current TX channel owner on the server.
     *
     * @return the TX owner client ID, or null if no one is transmitting
     */
    public String getServerTxOwner() {
        ControlMessage.ClientsUpdateInfo info = serverClientsInfo;
        return info != null ? info.txOwner : null;
    }

    /**
     * Gets the list of client IDs connected to the server.
     *
     * @return the list of client IDs, or null if not yet received
     */
    public java.util.List<String> getServerClientIds() {
        ControlMessage.ClientsUpdateInfo info = serverClientsInfo;
        return info != null ? info.clientIds : null;
    }

    /**
     * Gets the full server clients info.
     *
     * @return the clients update info, or null if not yet received
     */
    public ControlMessage.ClientsUpdateInfo getServerClientsInfo() {
        return serverClientsInfo;
    }

    /**
     * Enables or disables automatic reconnection on connection loss.
     * Default is enabled.
     *
     * @param autoReconnect true to enable auto-reconnection
     */
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    /**
     * Checks if auto-reconnection is enabled.
     */
    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    /**
     * Sets the maximum number of reconnection attempts before giving up.
     * Default is 10.
     *
     * @param maxAttempts maximum reconnection attempts
     */
    public void setMaxReconnectAttempts(int maxAttempts) {
        this.maxReconnectAttempts = maxAttempts;
    }

    /**
     * Sets the initial reconnection delay in milliseconds.
     * Delay doubles with each attempt up to maxReconnectDelayMs.
     * Default is 1000ms.
     *
     * @param delayMs initial delay in milliseconds
     */
    public void setReconnectDelayMs(int delayMs) {
        this.reconnectDelayMs = delayMs;
    }

    /**
     * Sets the maximum reconnection delay in milliseconds.
     * Default is 30000ms (30 seconds).
     *
     * @param maxDelayMs maximum delay in milliseconds
     */
    public void setMaxReconnectDelayMs(int maxDelayMs) {
        this.maxReconnectDelayMs = maxDelayMs;
    }

    /**
     * Checks if the client is currently attempting to reconnect.
     */
    public boolean isReconnecting() {
        return reconnecting;
    }

    /**
     * Gets the current reconnection attempt number.
     */
    public int getReconnectAttempt() {
        return reconnectAttempt;
    }

    /**
     * Sets whether capture (TX) audio is muted.
     * When muted, no audio is sent to the server.
     * Use for PTT operation - unmute when transmitting.
     */
    public void setCaptureMuted(boolean muted) {
        this.captureMuted = muted;
    }

    /**
     * Checks if capture (TX) audio is muted.
     */
    public boolean isCaptureMuted() {
        return captureMuted;
    }

    /**
     * Sets whether playback (RX) audio is muted.
     * When muted, silence is played instead of received audio.
     * Use for PTT operation - mute when transmitting to prevent feedback.
     */
    public void setPlaybackMuted(boolean muted) {
        this.playbackMuted = muted;
    }

    /**
     * Checks if playback (RX) audio is muted.
     */
    public boolean isPlaybackMuted() {
        return playbackMuted;
    }

    /**
     * Sets PTT mode for voice operation.
     * When PTT is active: capture unmuted (send voice), playback muted (no feedback).
     * When PTT is inactive: capture muted (no send), playback unmuted (hear RX).
     *
     * @param pttActive true when PTT is pressed, false when released
     */
    public void setPTT(boolean pttActive) {
        this.captureMuted = !pttActive;  // Unmute capture when PTT pressed
        this.playbackMuted = pttActive;   // Mute playback when PTT pressed
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

    /**
     * Adds a raw audio listener that receives PCM audio data received from the server.
     * This is useful for FFT/waterfall processing.
     *
     * @param listener the listener to add (receives byte[] PCM data)
     */
    public void addAudioListener(java.util.function.Consumer<byte[]> listener) {
        if (listener != null && !audioListeners.contains(listener)) {
            audioListeners.add(listener);
        }
    }

    /**
     * Removes a raw audio listener.
     *
     * @param listener the listener to remove
     */
    public void removeAudioListener(java.util.function.Consumer<byte[]> listener) {
        audioListeners.remove(listener);
    }

    private boolean performHandshake() throws IOException {
        // Build client info if any identification is provided
        ControlMessage.ClientInfo clientInfo = null;
        if ((callsign != null && !callsign.isEmpty()) ||
            (operatorName != null && !operatorName.isEmpty()) ||
            (location != null && !location.isEmpty())) {
            clientInfo = new ControlMessage.ClientInfo(callsign, operatorName, location);
        }

        // Send connect request
        protocol.sendControl(ControlMessage.connectRequest(clientName, AudioPacket.VERSION, config, clientInfo));

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

            // Capture line is optional (only needed for TX)
            if (captureDevice != null) {
                captureLine = mgr.openCaptureLine(captureDevice);
                // Check if capture is mono (we'll need to convert to stereo)
                captureIsMono = captureLine.getFormat().getChannels() == 1;
                if (captureIsMono) {
                    System.out.println("[AudioStreamClient] Capture device is mono - will convert to stereo for TX");
                }
            }
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
            long lastLogTime = 0;
            long rxPackets = 0;
            long rxBytes = 0;

            try {
                while (!closed && connected) {
                    try {
                        AudioPacket packet = protocol.receivePacket(100);
                        if (packet == null) continue;

                        switch (packet.getType()) {
                            case AUDIO_RX:
                                byte[] payload = packet.getPayload();
                                rxBuffer.write(payload);
                                rxPackets++;
                                rxBytes += payload.length;

                                // Notify audio listeners (for FFT/waterfall)
                                if (!audioListeners.isEmpty()) {
                                    for (var listener : audioListeners) {
                                        try {
                                            listener.accept(payload);
                                        } catch (Exception e) {
                                            // Ignore listener errors
                                        }
                                    }
                                }

                                // Log every 5 seconds
                                long now = System.currentTimeMillis();
                                if (now - lastLogTime > 5000) {
                                    System.out.println("[AudioStreamClient] Received " + rxPackets + " packets, " + rxBytes + " bytes in last 5s");
                                    lastLogTime = now;
                                    rxPackets = 0;
                                    rxBytes = 0;
                                }
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
                            handleConnectionLost();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                if (!closed) {
                    notifyError("local", "Receive thread error: " + e.getMessage());
                    handleConnectionLost();
                }
            }
        }, "AudioReceive");
        receiveThread.start();

        // Playback thread - plays RX audio to virtual device (for WSJT-X)
        playbackThread = new Thread(() -> {
            byte[] buffer = new byte[config.getBytesPerFrame()];
            long lastLogTime = 0;
            long bytesWritten = 0;

            try {
                System.out.println("[AudioStreamClient] Playback thread starting, device: " + playbackDevice.getName() + ", muted: " + playbackMuted);
                playbackLine.start();

                // Initial buffering with timeout for FT8 timing requirements
                long bufferingStart = System.currentTimeMillis();
                long maxBufferingTime = AudioStreamConfig.MAX_INITIAL_BUFFERING_MS;

                while (!closed && connected && !rxBuffer.hasReachedTargetLevel()) {
                    long elapsed = System.currentTimeMillis() - bufferingStart;
                    if (elapsed >= maxBufferingTime) {
                        // Timeout - start playback with whatever buffer we have
                        // This is critical for FT8 where RX audio must be delivered on time
                        System.out.println("[AudioStreamClient] Initial buffering timeout after " + elapsed + "ms, buffer: " + rxBuffer.getAvailable() + " bytes");
                        break;
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                System.out.println("[AudioStreamClient] Starting playback loop");

                while (!closed && connected) {
                    int bytesRead = rxBuffer.read(buffer, 0, buffer.length, config.getFrameDurationMs() * 2);
                    if (bytesRead > 0) {
                        if (playbackMuted) {
                            // Muted - write silence to keep audio flowing but no sound
                            playbackLine.write(new byte[bytesRead], 0, bytesRead);
                        } else {
                            playbackLine.write(buffer, 0, bytesRead);
                            bytesWritten += bytesRead;

                            // Check if data is silence (all zeros)
                            boolean allZeros = true;
                            for (int i = 0; i < Math.min(bytesRead, 100); i++) {
                                if (buffer[i] != 0) {
                                    allZeros = false;
                                    break;
                                }
                            }
                            if (allZeros && bytesWritten < 10000) {
                                System.out.println("[AudioStreamClient] WARNING: Audio data appears to be silence!");
                            }
                        }

                        // Log every 5 seconds
                        long now = System.currentTimeMillis();
                        if (now - lastLogTime > 5000) {
                            // Sample some bytes to verify non-silence
                            int maxVal = 0;
                            for (int i = 0; i < Math.min(buffer.length, 100); i++) {
                                maxVal = Math.max(maxVal, Math.abs(buffer[i]));
                            }
                            System.out.println("[AudioStreamClient] Playback: wrote " + bytesWritten + " bytes, muted: " + playbackMuted + ", maxSample: " + maxVal);
                            lastLogTime = now;
                            bytesWritten = 0;
                        }
                    } else if (bytesRead == 0 && rxBuffer.getAvailable() == 0) {
                        // Underrun - insert silence
                        playbackLine.write(new byte[buffer.length], 0, buffer.length);
                    }
                }
            } catch (Exception e) {
                if (!closed) {
                    notifyError("local", "Playback thread error: " + e.getMessage());
                    handleConnectionLost();
                }
            } finally {
                try {
                    playbackLine.stop();
                } catch (Exception ignored) {}
            }
        }, "AudioPlayback");
        playbackThread.start();

        // Capture thread - captures TX audio from virtual device (from WSJT-X or mic)
        // Only start if capture device is configured (TX support is optional)
        if (captureLine != null) {
            captureThread = new Thread(() -> {
                // For mono capture, we read half the bytes and expand to stereo
                int readSize = captureIsMono ? config.getBytesPerFrame() / 2 : config.getBytesPerFrame();
                byte[] readBuffer = new byte[readSize];
                byte[] stereoBuffer = captureIsMono ? new byte[config.getBytesPerFrame()] : null;

                try {
                    captureLine.start();

                    while (!closed && connected) {
                        int bytesRead = captureLine.read(readBuffer, 0, readBuffer.length);
                        if (bytesRead > 0 && !captureMuted) {
                            if (captureIsMono) {
                                // Convert mono to stereo by duplicating each sample
                                // For 16-bit audio: each sample is 2 bytes
                                int stereoBytes = 0;
                                for (int i = 0; i < bytesRead; i += 2) {
                                    // Copy sample to left channel
                                    stereoBuffer[stereoBytes++] = readBuffer[i];
                                    stereoBuffer[stereoBytes++] = readBuffer[i + 1];
                                    // Copy same sample to right channel
                                    stereoBuffer[stereoBytes++] = readBuffer[i];
                                    stereoBuffer[stereoBytes++] = readBuffer[i + 1];
                                }
                                txBuffer.write(stereoBuffer, 0, stereoBytes);
                            } else {
                                // Already stereo, write directly
                                txBuffer.write(readBuffer, 0, bytesRead);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (!closed) {
                        notifyError("local", "Capture thread error: " + e.getMessage());
                        handleConnectionLost();
                    }
                } finally {
                    try {
                        captureLine.stop();
                    } catch (Exception ignored) {}
                }
            }, "AudioCapture");
            captureThread.start();

            // Send thread - sends TX audio to server
            sendThread = new Thread(() -> {
                byte[] buffer = new byte[config.getBytesPerFrame()];
                try {
                    while (!closed && connected) {
                        int bytesRead = txBuffer.read(buffer, 0, buffer.length, config.getFrameDurationMs() * 2);
                        if (bytesRead > 0) {
                            try {
                                protocol.sendTxAudio(buffer);
                            } catch (IOException e) {
                                if (!closed) {
                                    notifyError("local", "Send error: " + e.getMessage());
                                    handleConnectionLost();
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (!closed) {
                        notifyError("local", "Send thread error: " + e.getMessage());
                        handleConnectionLost();
                    }
                }
            }, "AudioSend");
            sendThread.start();
        }

        // Heartbeat thread
        heartbeatThread = new Thread(() -> {
            try {
                while (!closed && connected) {
                    try {
                        Thread.sleep(AudioProtocolHandler.HEARTBEAT_INTERVAL_MS);

                        if (protocol.shouldSendHeartbeat()) {
                            protocol.sendHeartbeat();
                        }

                        if (protocol.isConnectionTimedOut()) {
                            notifyError("local", "Connection timeout");
                            handleConnectionLost();
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
            } catch (Exception e) {
                if (!closed) {
                    notifyError("local", "Heartbeat thread error: " + e.getMessage());
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
            case CLIENTS_UPDATE:
                ControlMessage.ClientsUpdateInfo info = msg.parseClientsUpdate();
                if (info != null) {
                    serverClientsInfo = info;
                    notifyClientsUpdate(info);
                }
                break;
            case TX_GRANTED:
                notifyTxGranted();
                break;
            case TX_DENIED:
                notifyTxDenied(msg.parseTxClientId());
                break;
            case TX_PREEMPTED:
                notifyTxPreempted(msg.parseTxClientId());
                break;
            case TX_RELEASED:
                notifyTxReleased();
                break;
            case DISCONNECT:
            case ERROR:
                String error = msg.parseErrorMessage();
                if (error != null) {
                    notifyError("local", error);
                }
                handleConnectionLost();
                break;
            default:
                break;
        }
    }

    /**
     * Handles connection loss - triggers reconnection if enabled.
     */
    private void handleConnectionLost() {
        if (closed || reconnecting) {
            return;
        }

        // Check if connection was short-lived (unstable)
        long connectionDuration = System.currentTimeMillis() - connectTime;
        boolean wasShortLived = connectionDuration < MIN_STABLE_CONNECTION_MS;

        // Clean up current connection
        closeResources();

        if (autoReconnect && !closed) {
            if (wasShortLived) {
                // Short-lived connection - increment attempt counter
                // This prevents endless reconnect loops when connection keeps failing immediately
                reconnectAttempt++;
                if (reconnectAttempt >= maxReconnectAttempts) {
                    closed = true;
                    notifyError("local", "Connection unstable - failed " + reconnectAttempt +
                        " times within " + MIN_STABLE_CONNECTION_MS + "ms of connecting");
                    notifyClientDisconnected("local");
                    return;
                }
            } else {
                // Connection was stable - reset attempt counter for fresh start
                reconnectAttempt = 0;
            }
            startReconnection();
        } else {
            // Not auto-reconnecting - fully close
            closed = true;
            notifyClientDisconnected("local");
        }
    }

    /**
     * Starts the reconnection process in a background thread.
     */
    private void startReconnection() {
        if (closed || reconnecting) {
            return;
        }

        reconnecting = true;
        // Don't reset reconnectAttempt - it may have been incremented by handleConnectionLost
        // for short-lived connections

        reconnectThread = new Thread(() -> {
            int currentDelay = reconnectDelayMs;

            while (!closed && reconnecting && reconnectAttempt < maxReconnectAttempts) {
                reconnectAttempt++;

                notifyReconnecting("local", reconnectAttempt, maxReconnectAttempts);

                try {
                    // Wait before attempting
                    Thread.sleep(currentDelay);

                    if (closed) {
                        break;
                    }

                    // Attempt to reconnect
                    reconnectInternal();

                    // Success - exit loop but don't reset reconnectAttempt yet.
                    // If connection fails quickly, handleConnectionLost will track it.
                    // If connection stays stable > MIN_STABLE_CONNECTION_MS,
                    // handleConnectionLost will reset reconnectAttempt to 0.
                    reconnecting = false;
                    notifyReconnected("local");
                    return;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    notifyError("local", "Reconnect attempt " + reconnectAttempt +
                        "/" + maxReconnectAttempts + " failed: " + e.getMessage());

                    // Exponential backoff
                    currentDelay = Math.min(currentDelay * 2, maxReconnectDelayMs);
                }
            }

            // Failed to reconnect
            reconnecting = false;
            if (!closed) {
                closed = true;
                notifyError("local", "Failed to reconnect after " + reconnectAttempt + " attempts");
                notifyClientDisconnected("local");
            }
        }, "AudioClient-Reconnect");
        reconnectThread.start();
    }

    /**
     * Internal reconnection - creates new socket and protocol handler.
     */
    private void reconnectInternal() throws IOException {
        // Create new socket connection
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(serverHost, serverPort), CONNECT_TIMEOUT_MS);
        protocol = new AudioProtocolHandler(socket);
        executor = Executors.newCachedThreadPool();
        connectTime = System.currentTimeMillis();

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
    }

    /**
     * Closes resources without triggering reconnection.
     */
    private void closeResources() {
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
            try {
                captureLine.stop();
                captureLine.close();
            } catch (Exception ignored) {}
            captureLine = null;
        }
        if (playbackLine != null) {
            try {
                playbackLine.stop();
                playbackLine.close();
            } catch (Exception ignored) {}
            playbackLine = null;
        }

        // Close protocol handler
        if (protocol != null) {
            try {
                protocol.close();
            } catch (IOException ignored) {}
            protocol = null;
        }

        // Close socket
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
            socket = null;
        }

        // Shutdown executor
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        // Clear buffers
        rxBuffer = null;
        txBuffer = null;
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

    private void notifyReconnecting(String clientId, int attempt, int maxAttempts) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onReconnecting(clientId, attempt, maxAttempts);
            } catch (Exception ignored) {}
        }
    }

    private void notifyReconnected(String clientId) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onReconnected(clientId);
            } catch (Exception ignored) {}
        }
    }

    private void notifyClientsUpdate(ControlMessage.ClientsUpdateInfo info) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onClientsUpdate(info.clientCount, info.maxClients, info.txOwner, info.clientIds);
            } catch (Exception ignored) {}
        }
    }

    private void notifyTxGranted() {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onTxGranted();
            } catch (Exception ignored) {}
        }
    }

    private void notifyTxDenied(String holdingClientId) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onTxDenied(holdingClientId);
            } catch (Exception ignored) {}
        }
    }

    private void notifyTxPreempted(String preemptingClientId) {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onTxPreempted(preemptingClientId);
            } catch (Exception ignored) {}
        }
    }

    private void notifyTxReleased() {
        for (AudioStreamListener listener : listeners) {
            try {
                listener.onTxReleased();
            } catch (Exception ignored) {}
        }
    }
}
