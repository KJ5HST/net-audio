/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio;

/**
 * Listener interface for audio stream events.
 * <p>
 * Allows observers to monitor audio streaming activity.
 * </p>
 */
public interface AudioStreamListener {

    /**
     * Called when a client connects to the audio server.
     *
     * @param clientId identifier for the client connection
     * @param address the client's address
     */
    void onClientConnected(String clientId, String address);

    /**
     * Called when a client disconnects from the audio server.
     *
     * @param clientId identifier for the client connection
     */
    void onClientDisconnected(String clientId);

    /**
     * Called when audio streaming starts.
     *
     * @param clientId identifier for the client connection
     * @param config the audio configuration being used
     */
    void onStreamStarted(String clientId, AudioStreamConfig config);

    /**
     * Called when audio streaming stops.
     *
     * @param clientId identifier for the client connection
     */
    void onStreamStopped(String clientId);

    /**
     * Called periodically with updated statistics.
     *
     * @param clientId identifier for the client connection
     * @param stats the current stream statistics
     */
    void onStatisticsUpdate(String clientId, AudioStreamStats stats);

    /**
     * Called when an error occurs.
     *
     * @param clientId identifier for the client connection (may be null for server errors)
     * @param error description of the error
     */
    void onError(String clientId, String error);

    /**
     * Called when the audio server starts.
     *
     * @param port the port the server is listening on
     */
    default void onServerStarted(int port) {}

    /**
     * Called when the audio server stops.
     */
    default void onServerStopped() {}

    /**
     * Called when the client is attempting to reconnect after connection loss.
     *
     * @param clientId identifier for the client connection
     * @param attempt the current reconnection attempt number (1-based)
     * @param maxAttempts the maximum number of attempts that will be made
     */
    default void onReconnecting(String clientId, int attempt, int maxAttempts) {}

    /**
     * Called when the client successfully reconnects after connection loss.
     *
     * @param clientId identifier for the client connection
     */
    default void onReconnected(String clientId) {}

    // ========== Multi-client events (client-side only) ==========

    /**
     * Called when the server broadcasts an update about connected clients.
     * <p>
     * This allows clients to know how many other clients are connected and
     * who currently owns the TX channel.
     * </p>
     *
     * @param clientCount current number of connected clients
     * @param maxClients maximum allowed clients
     * @param txOwner client ID of current TX owner (null if no one is transmitting)
     * @param clientIds list of connected client IDs
     */
    default void onClientsUpdate(int clientCount, int maxClients, String txOwner,
            java.util.List<String> clientIds) {}

    /**
     * Called when this client is granted the TX channel.
     */
    default void onTxGranted() {}

    /**
     * Called when this client's TX audio is denied because another client
     * holds the TX channel.
     *
     * @param holdingClientId the client ID that currently holds the channel
     */
    default void onTxDenied(String holdingClientId) {}

    /**
     * Called when this client is preempted by a higher priority client.
     *
     * @param preemptingClientId the client ID that preempted this client
     */
    default void onTxPreempted(String preemptingClientId) {}

    /**
     * Called when this client's TX channel is released (idle timeout or explicit).
     */
    default void onTxReleased() {}
}
