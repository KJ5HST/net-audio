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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * TCP server for bidirectional audio streaming with multi-client support.
 * <p>
 * Captures audio from the FTX-1 USB audio interface and streams it to
 * all connected clients. Also receives audio from clients and plays it to
 * the FTX-1 for transmission using priority-based TX arbitration.
 * </p>
 * <p>
 * Key features:
 * <ul>
 *   <li>Multiple simultaneous clients (configurable max, default 4)</li>
 *   <li>Single capture thread broadcasts RX audio to all clients</li>
 *   <li>TX arbitration with priority levels and idle timeout</li>
 *   <li>Backward compatible with single-client deployments</li>
 * </ul>
 * </p>
 */
public class AudioStreamServer {

    private static final Logger logger = Logger.getLogger(AudioStreamServer.class.getName());

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
    private volatile boolean injectOnlyMode = false;

    // Shared audio resources
    private TargetDataLine captureLine;
    private SourceDataLine playbackLine;
    private AudioBroadcaster broadcaster;
    private AudioMixer mixer;

    // Client sessions (supports multiple concurrent clients)
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    // Raw audio listeners (for FFT/waterfall processing)
    private final List<java.util.function.Consumer<byte[]>> audioListeners = new CopyOnWriteArrayList<>();
    private AudioBroadcaster.BroadcastTarget audioListenerTarget;

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
     * Enables inject-only mode where audio comes from injectAudio() instead of a capture device.
     * This is useful for demo/test mode without physical hardware.
     */
    public void setInjectOnlyMode(boolean injectOnly) {
        this.injectOnlyMode = injectOnly;
    }

    /**
     * Checks if inject-only mode is enabled.
     */
    public boolean isInjectOnlyMode() {
        return injectOnlyMode;
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

        // Initialize shared audio resources
        initializeSharedAudio();

        // Accept connections in a thread
        executor.submit(this::acceptLoop);

        notifyServerStarted(port);
    }

    /**
     * Stops the audio server.
     */
    public void stop() {
        running = false;

        // Close all sessions
        for (ClientSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();

        // Stop shared audio resources
        stopSharedAudio();

        // Close server socket to unblock accept()
        ServerSocket socketToClose = serverSocket;
        serverSocket = null;

        if (socketToClose != null) {
            try {
                socketToClose.close();
            } catch (IOException ignored) {}
        }

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
     * Checks if any client is connected.
     */
    public boolean hasClient() {
        return !sessions.isEmpty();
    }

    /**
     * Gets the number of connected clients.
     */
    public int getClientCount() {
        return sessions.size();
    }

    /**
     * Gets the list of connected client IDs.
     */
    public List<String> getConnectedClientIds() {
        return new ArrayList<>(sessions.keySet());
    }

    /**
     * Gets statistics for a specific client.
     *
     * @param clientId the client identifier
     * @return the statistics, or null if client not found
     */
    public AudioStreamStats getClientStats(String clientId) {
        ClientSession session = sessions.get(clientId);
        if (session != null) {
            return session.getStats();
        }
        return null;
    }

    /**
     * Gets statistics for the first connected client (backward compatibility).
     *
     * @deprecated Use {@link #getClientStats(String)} or {@link #getAllClientStats()} instead
     */
    @Deprecated
    public AudioStreamStats getClientStats() {
        if (sessions.isEmpty()) {
            return null;
        }
        return sessions.values().iterator().next().getStats();
    }

    /**
     * Gets statistics for all connected clients.
     *
     * @return map of client ID to statistics
     */
    public Map<String, AudioStreamStats> getAllClientStats() {
        ConcurrentHashMap<String, AudioStreamStats> stats = new ConcurrentHashMap<>();
        sessions.forEach((id, session) -> stats.put(id, session.getStats()));
        return stats;
    }

    /**
     * Gets the current TX channel owner.
     *
     * @return the client ID holding the TX channel, or null if none
     */
    public String getTxOwner() {
        return mixer != null ? mixer.getCurrentTxOwner() : null;
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
     * Adds a raw audio listener that receives PCM audio data from the capture device.
     * This is useful for FFT/waterfall processing without being a full client.
     *
     * @param listener the listener to add (receives byte[] PCM data)
     */
    public void addAudioListener(java.util.function.Consumer<byte[]> listener) {
        if (listener != null && !audioListeners.contains(listener)) {
            audioListeners.add(listener);

            // Create broadcast target on first listener
            if (audioListenerTarget == null && broadcaster != null) {
                audioListenerTarget = new AudioBroadcaster.BroadcastTarget() {
                    @Override
                    public boolean receiveRxAudio(byte[] data, int offset, int length) {
                        byte[] copy = new byte[length];
                        System.arraycopy(data, offset, copy, 0, length);
                        for (var l : audioListeners) {
                            try {
                                l.accept(copy);
                            } catch (Exception e) {
                                logger.warning("Audio listener error: " + e.getMessage());
                            }
                        }
                        return true;
                    }

                    @Override
                    public String getTargetId() {
                        return "audio-listener-target";
                    }
                };
                broadcaster.addTarget(audioListenerTarget);
                logger.info("Added audio listener target to broadcaster");
            }
        }
    }

    /**
     * Removes a raw audio listener.
     *
     * @param listener the listener to remove
     */
    public void removeAudioListener(java.util.function.Consumer<byte[]> listener) {
        audioListeners.remove(listener);

        // Remove broadcast target when no more listeners
        if (audioListeners.isEmpty() && audioListenerTarget != null && broadcaster != null) {
            broadcaster.removeTarget(audioListenerTarget.getTargetId());
            audioListenerTarget = null;
            logger.info("Removed audio listener target from broadcaster");
        }
    }

    /**
     * Injects audio data to be broadcast to all connected clients.
     * This allows playback of recordings without going through the capture device.
     *
     * @param data the PCM audio data to broadcast
     */
    public void injectAudio(byte[] data) {
        if (broadcaster != null && data != null && data.length > 0) {
            broadcaster.injectAudio(data);
        }
    }

    /**
     * Plays audio to the local playback device (e.g., radio USB audio input for digital TX).
     * Routes through the mixer's TX buffer so it doesn't conflict with the mixer's playback loop.
     *
     * @param data the PCM audio data to play to the radio
     */
    public void playLocalAudio(byte[] data) {
        if (mixer != null && mixer.isRunning() && data != null && data.length > 0) {
            mixer.getTxBuffer().write(data, 0, data.length);
        } else if (playbackLine != null && data != null && data.length > 0) {
            // Fallback: write directly if mixer not running
            playbackLine.write(data, 0, data.length);
        }
    }

    private void initializeSharedAudio() {
        // Create broadcaster and mixer (even if devices not configured yet)
        broadcaster = new AudioBroadcaster(config);
        broadcaster.setBroadcastListener((targetId, reason) -> {
            // Target failed - close the session
            ClientSession session = sessions.get(targetId);
            if (session != null) {
                session.close();
            }
        });

        mixer = new AudioMixer(config);
        mixer.setMixerListener(new AudioMixer.MixerListener() {
            @Override
            public void onTxConflict(String holdingClientId, String requestingClientId) {
                logger.fine("TX conflict: " + requestingClientId + " blocked by " + holdingClientId);
            }

            @Override
            public void onTxOwnerChanged(String newOwnerClientId) {
                logger.fine("TX owner changed to: " + newOwnerClientId);
                // Broadcast TX owner change to all clients
                broadcastClientsUpdate();
            }
        });

        // Open audio lines if capture device is configured (playback is optional)
        if (captureDevice != null) {
            try {
                openSharedAudioLines();
            } catch (LineUnavailableException e) {
                logger.warning("Failed to open audio lines: " + e.getMessage());
            }
        }
    }

    private void openSharedAudioLines() throws LineUnavailableException {
        if (captureLine == null && captureDevice != null) {
            captureLine = deviceManager.openCaptureLine(captureDevice);
            broadcaster.start(captureLine);
        }

        if (playbackLine == null && playbackDevice != null) {
            playbackLine = deviceManager.openPlaybackLine(playbackDevice);
            mixer.start(playbackLine);
        }
    }

    private void stopSharedAudio() {
        if (broadcaster != null) {
            broadcaster.stop();
            broadcaster = null;
        }

        if (mixer != null) {
            mixer.stop();
            mixer = null;
        }

        if (captureLine != null) {
            captureLine.close();
            captureLine = null;
        }

        if (playbackLine != null) {
            playbackLine.close();
            playbackLine = null;
        }
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

        // Check if capture device is configured (playback is optional for RX-only servers)
        // In inject-only mode, we don't need a capture device - audio comes via injectAudio()
        if (captureDevice == null && !injectOnlyMode) {
            rejectClient(socket, ControlMessage.RejectReason.REJECTED, "Capture device not configured");
            return;
        }

        // Check max clients limit
        if (sessions.size() >= config.getMaxClients()) {
            rejectClient(socket, ControlMessage.RejectReason.BUSY,
                "Maximum clients (" + config.getMaxClients() + ") reached");
            return;
        }

        // Ensure shared audio lines are open
        try {
            openSharedAudioLines();
        } catch (LineUnavailableException e) {
            rejectClient(socket, ControlMessage.RejectReason.REJECTED,
                "Audio devices unavailable: " + e.getMessage());
            return;
        }

        // Create and start session
        try {
            ClientSession session = new ClientSession(clientId, socket);
            sessions.put(clientId, session);
            notifyClientConnected(clientId, address);
            executor.submit(session);
        } catch (Exception e) {
            notifyError(clientId, "Failed to create session: " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void rejectClient(Socket socket, ControlMessage.RejectReason reason, String message) {
        try {
            AudioProtocolHandler handler = new AudioProtocolHandler(socket);
            handler.sendControl(ControlMessage.connectReject(reason, message));
            handler.close();
        } catch (IOException ignored) {}
    }

    /**
     * Broadcasts a clients update message to all connected clients.
     * <p>
     * Called when clients connect, disconnect, or TX ownership changes.
     * </p>
     */
    private void broadcastClientsUpdate() {
        if (sessions.isEmpty()) {
            return;
        }

        String txOwner = mixer != null ? mixer.getCurrentTxOwner() : null;
        List<String> clientIds = getConnectedClientIds();

        // Collect client info for all sessions
        java.util.Map<String, ControlMessage.ClientInfo> clientInfoMap = new java.util.HashMap<>();
        sessions.forEach((id, session) -> {
            ControlMessage.ClientInfo info = session.getClientInfo();
            if (info != null) {
                clientInfoMap.put(id, info);
            }
        });

        ControlMessage update = ControlMessage.clientsUpdate(
            sessions.size(),
            config.getMaxClients(),
            txOwner,
            clientIds,
            clientInfoMap
        );

        // Send to all sessions
        sessions.forEach((id, session) -> {
            session.sendControlMessage(update);
        });
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
     * <p>
     * In multi-client mode, sessions share the AudioBroadcaster for RX
     * and AudioMixer for TX rather than having their own audio lines.
     * </p>
     */
    private class ClientSession implements Runnable,
            AudioBroadcaster.BroadcastTarget, AudioMixer.TxClient {

        private final String clientId;
        private final Socket socket;
        private final AudioProtocolHandler protocol;
        private final long connectTime;

        private AudioStreamConfig sessionConfig;
        private volatile boolean closed = false;
        private volatile boolean streaming = false;
        private volatile long measuredLatencyMs = 0;
        private volatile AudioMixer.TxPriority txPriority = AudioMixer.TxPriority.NORMAL;

        // Client identification
        private volatile ControlMessage.ClientInfo clientInfo;

        // Statistics tracking
        private volatile long txBytesSubmitted = 0;
        private volatile long txBytesAccepted = 0;
        private volatile int txDeniedCount = 0;

        // Worker thread
        private Thread receiveThread;

        ClientSession(String clientId, Socket socket) throws IOException {
            this.clientId = clientId;
            this.socket = socket;
            this.protocol = new AudioProtocolHandler(socket);
            this.sessionConfig = config;  // Start with server default
            this.connectTime = System.currentTimeMillis();
        }

        ControlMessage.ClientInfo getClientInfo() {
            return clientInfo;
        }

        @Override
        public void run() {
            try {
                // Perform handshake
                if (!performHandshake()) {
                    close();
                    return;
                }

                // Send config and accept
                protocol.sendControl(ControlMessage.audioConfig(sessionConfig));
                protocol.sendControl(ControlMessage.connectAccept());

                // Register with broadcaster and mixer
                broadcaster.addTarget(this);
                mixer.registerClient(this);

                // Start receive thread
                startReceiveThread();

                notifyStreamStarted(clientId, sessionConfig);
                streaming = true;

                // Notify all clients about the new connection
                broadcastClientsUpdate();

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
                // Notify remaining clients about the disconnection
                broadcastClientsUpdate();
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

            // Check if client requested specific buffer settings
            AudioStreamConfig requestedConfig = msg.parseConnectRequestConfig();
            if (requestedConfig != null) {
                // Create a new config with server's audio format but client's buffer settings
                sessionConfig = new AudioStreamConfig()
                    .setSampleRate(config.getSampleRate())
                    .setBitsPerSample(config.getBitsPerSample())
                    .setChannels(config.getChannels())
                    .setFrameDurationMs(config.getFrameDurationMs())
                    .setBufferTargetMs(requestedConfig.getBufferTargetMs())
                    .setBufferMinMs(requestedConfig.getBufferMinMs())
                    .setBufferMaxMs(requestedConfig.getBufferMaxMs());
            }

            // Parse client identification info
            clientInfo = msg.parseConnectRequestClientInfo();
            if (clientInfo != null && !clientInfo.isEmpty()) {
                logger.info("Client " + clientId + " identified as: " + clientInfo);
            }

            return true;
        }

        private void startReceiveThread() {
            receiveThread = new Thread(() -> {
                try {
                    while (!closed && running) {
                        try {
                            AudioPacket packet = protocol.receivePacket(100);
                            if (packet == null) continue;

                            switch (packet.getType()) {
                                case AUDIO_TX:
                                    handleTxAudio(packet.getPayload());
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
                } catch (Exception e) {
                    if (!closed) {
                        notifyError(clientId, "Receive thread error: " + e.getMessage());
                        close();
                    }
                }
            }, "AudioReceive-" + clientId);
            receiveThread.start();
        }

        private void handleTxAudio(byte[] data) {
            txBytesSubmitted += data.length;

            AudioMixer.TxResult result = mixer.submitTxAudio(clientId, data);
            switch (result) {
                case ACCEPTED:
                    txBytesAccepted += data.length;
                    break;
                case REJECTED:
                    txDeniedCount++;
                    // Send TX_DENIED control message (first time only, to avoid spam)
                    if (txDeniedCount == 1) {
                        try {
                            protocol.sendControl(ControlMessage.txDenied(mixer.getCurrentTxOwner()));
                        } catch (IOException ignored) {}
                    }
                    break;
                case PREEMPTED:
                    // Notification handled via TxClient callback
                    break;
            }
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

        // BroadcastTarget implementation

        @Override
        public boolean receiveRxAudio(byte[] data, int offset, int length) {
            if (closed) {
                return false;
            }
            try {
                protocol.sendRxAudio(data, offset, length);
                return true;
            } catch (IOException e) {
                // Failed to send - mark for removal
                return false;
            }
        }

        @Override
        public String getTargetId() {
            return clientId;
        }

        // TxClient implementation

        @Override
        public String getClientId() {
            return clientId;
        }

        @Override
        public AudioMixer.TxPriority getTxPriority() {
            return txPriority;
        }

        @Override
        public void onPreempted(String preemptingClientId) {
            try {
                protocol.sendControl(ControlMessage.txPreempted(preemptingClientId));
            } catch (IOException ignored) {}
        }

        @Override
        public void onTxGranted() {
            try {
                protocol.sendControl(ControlMessage.txGranted());
            } catch (IOException ignored) {}
            // Reset denied count when we get the channel
            txDeniedCount = 0;
        }

        @Override
        public void onTxReleased() {
            try {
                protocol.sendControl(ControlMessage.txReleased());
            } catch (IOException ignored) {}
        }

        /**
         * Sends a control message to this client.
         */
        void sendControlMessage(ControlMessage message) {
            if (closed || !streaming) {
                return;
            }
            try {
                protocol.sendControl(message);
            } catch (IOException e) {
                // Failed to send - will be cleaned up by receive thread
            }
        }

        AudioStreamStats getStats() {
            AudioRingBuffer txBuffer = mixer.getTxBuffer();
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

            // Unregister from broadcaster and mixer
            if (broadcaster != null) {
                broadcaster.removeTarget(clientId);
            }
            if (mixer != null) {
                mixer.unregisterClient(clientId);
            }

            // Remove from sessions map
            sessions.remove(clientId);

            // Stop receive thread
            if (receiveThread != null) {
                receiveThread.interrupt();
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
