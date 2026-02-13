/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio.protocol;

import com.yaesu.audio.AudioStreamConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Control messages for audio streaming protocol.
 * <p>
 * Control messages are sent as the payload of CONTROL type AudioPackets.
 * </p>
 */
public class ControlMessage {

    /**
     * Control message types.
     */
    public enum Type {
        /** Client requesting connection */
        CONNECT_REQUEST((byte) 0x01),
        /** Server accepting connection */
        CONNECT_ACCEPT((byte) 0x02),
        /** Server rejecting connection */
        CONNECT_REJECT((byte) 0x03),
        /** Audio configuration negotiation */
        AUDIO_CONFIG((byte) 0x04),
        /** Start streaming audio */
        STREAM_START((byte) 0x10),
        /** Stop streaming audio */
        STREAM_STOP((byte) 0x11),
        /** Pause streaming (e.g., during PTT transition) */
        STREAM_PAUSE((byte) 0x12),
        /** Resume streaming */
        STREAM_RESUME((byte) 0x13),
        /** Heartbeat/keepalive */
        HEARTBEAT((byte) 0x20),
        /** Heartbeat response */
        HEARTBEAT_ACK((byte) 0x21),
        /** Latency probe request */
        LATENCY_PROBE((byte) 0x22),
        /** Latency probe response */
        LATENCY_RESPONSE((byte) 0x23),
        /** Statistics update */
        STATS_UPDATE((byte) 0x30),
        /** TX channel granted to this client */
        TX_GRANTED((byte) 0x40),
        /** TX channel request denied (another client has it) */
        TX_DENIED((byte) 0x41),
        /** Client was preempted by higher priority client */
        TX_PREEMPTED((byte) 0x42),
        /** TX channel released (idle timeout or explicit) */
        TX_RELEASED((byte) 0x43),
        /** Client list update (broadcast to all clients) */
        CLIENTS_UPDATE((byte) 0x44),
        /** Error notification */
        ERROR((byte) 0xFE),
        /** Graceful disconnect */
        DISCONNECT((byte) 0xFF);

        private final byte value;

        Type(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }

        public static Type fromValue(byte value) {
            for (Type t : values()) {
                if (t.value == value) {
                    return t;
                }
            }
            return null;
        }
    }

    /**
     * Client identification information.
     * <p>
     * Allows clients to identify themselves with callsign, name, and location
     * so other connected clients can see who they're sharing the radio with.
     * </p>
     */
    public static class ClientInfo {
        private final String callsign;
        private final String name;
        private final String location;

        public ClientInfo(String callsign, String name, String location) {
            this.callsign = callsign != null ? callsign : "";
            this.name = name != null ? name : "";
            this.location = location != null ? location : "";
        }

        public String getCallsign() { return callsign; }
        public String getName() { return name; }
        public String getLocation() { return location; }

        public boolean isEmpty() {
            return callsign.isEmpty() && name.isEmpty() && location.isEmpty();
        }

        /**
         * Gets a display string for this client.
         * <p>
         * Returns the most specific identification available:
         * callsign (name, location), or just callsign, or just name, etc.
         * </p>
         */
        public String getDisplayString() {
            StringBuilder sb = new StringBuilder();
            if (!callsign.isEmpty()) {
                sb.append(callsign);
                if (!name.isEmpty() || !location.isEmpty()) {
                    sb.append(" (");
                    if (!name.isEmpty()) {
                        sb.append(name);
                        if (!location.isEmpty()) {
                            sb.append(", ");
                        }
                    }
                    if (!location.isEmpty()) {
                        sb.append(location);
                    }
                    sb.append(")");
                }
            } else if (!name.isEmpty()) {
                sb.append(name);
                if (!location.isEmpty()) {
                    sb.append(" (").append(location).append(")");
                }
            } else if (!location.isEmpty()) {
                sb.append(location);
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            String display = getDisplayString();
            return display.isEmpty() ? "Unknown" : display;
        }
    }

    /**
     * Rejection reasons.
     */
    public enum RejectReason {
        /** Server is busy with another client */
        BUSY((byte) 0x01),
        /** Protocol version mismatch */
        VERSION_MISMATCH((byte) 0x02),
        /** Audio format not supported */
        FORMAT_NOT_SUPPORTED((byte) 0x03),
        /** Authentication failed */
        AUTH_FAILED((byte) 0x04),
        /** Generic rejection */
        REJECTED((byte) 0xFF);

        private final byte value;

        RejectReason(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }

        public static RejectReason fromValue(byte value) {
            for (RejectReason r : values()) {
                if (r.value == value) {
                    return r;
                }
            }
            return REJECTED;
        }
    }

    private Type type;
    private byte[] data;

    /**
     * Creates a control message.
     */
    public ControlMessage(Type type, byte[] data) {
        this.type = type;
        this.data = data != null ? data : new byte[0];
    }

    /**
     * Creates a control message with no data.
     */
    public ControlMessage(Type type) {
        this(type, null);
    }

    /**
     * Serializes the control message.
     */
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(1 + data.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(type.getValue());
        if (data.length > 0) {
            buffer.put(data);
        }
        return buffer.array();
    }

    /**
     * Deserializes a control message.
     */
    public static ControlMessage deserialize(byte[] payload) {
        if (payload == null || payload.length < 1) {
            return null;
        }

        Type type = Type.fromValue(payload[0]);
        if (type == null) {
            return null;
        }

        byte[] data = new byte[payload.length - 1];
        if (data.length > 0) {
            System.arraycopy(payload, 1, data, 0, data.length);
        }

        return new ControlMessage(type, data);
    }

    // Factory methods for common messages

    /**
     * Creates a connect request message.
     *
     * @param clientName optional client name
     * @param protocolVersion protocol version
     */
    public static ControlMessage connectRequest(String clientName, byte protocolVersion) {
        return connectRequest(clientName, protocolVersion, null);
    }

    /**
     * Creates a connect request message with requested audio configuration.
     *
     * @param clientName optional client name
     * @param protocolVersion protocol version
     * @param requestedConfig optional requested audio configuration (buffer settings)
     */
    public static ControlMessage connectRequest(String clientName, byte protocolVersion, AudioStreamConfig requestedConfig) {
        return connectRequest(clientName, protocolVersion, requestedConfig, null);
    }

    /**
     * Creates a connect request message with client identification and audio configuration.
     *
     * @param clientName optional client name (legacy, use clientInfo instead)
     * @param protocolVersion protocol version
     * @param requestedConfig optional requested audio configuration (buffer settings)
     * @param clientInfo optional client identification info (callsign, name, location)
     */
    public static ControlMessage connectRequest(String clientName, byte protocolVersion,
            AudioStreamConfig requestedConfig, ClientInfo clientInfo) {
        byte[] nameBytes = clientName != null ?
            clientName.getBytes(StandardCharsets.UTF_8) : new byte[0];

        // Serialize client info
        byte[] clientInfoBytes = serializeClientInfo(clientInfo);

        // If config provided, include buffer settings (6 bytes: target, min, max as shorts)
        int configSize = requestedConfig != null ? 6 : 0;
        // Format: version(1) + nameLen(1) + name + configFlag(1) + [config(6)] + clientInfoLen(1) + clientInfo
        ByteBuffer buffer = ByteBuffer.allocate(4 + nameBytes.length + configSize + clientInfoBytes.length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.put(protocolVersion);
        buffer.put((byte) nameBytes.length);
        if (nameBytes.length > 0) {
            buffer.put(nameBytes);
        }

        // Flag indicating if config is included
        buffer.put((byte) (requestedConfig != null ? 1 : 0));

        if (requestedConfig != null) {
            buffer.putShort((short) requestedConfig.getBufferTargetMs());
            buffer.putShort((short) requestedConfig.getBufferMinMs());
            buffer.putShort((short) requestedConfig.getBufferMaxMs());
        }

        // Client info (length-prefixed)
        buffer.put((byte) clientInfoBytes.length);
        if (clientInfoBytes.length > 0) {
            buffer.put(clientInfoBytes);
        }

        return new ControlMessage(Type.CONNECT_REQUEST, buffer.array());
    }

    /**
     * Serializes ClientInfo to bytes.
     */
    private static byte[] serializeClientInfo(ClientInfo info) {
        if (info == null || info.isEmpty()) {
            return new byte[0];
        }

        byte[] callsignBytes = info.getCallsign().getBytes(StandardCharsets.UTF_8);
        byte[] nameBytes = info.getName().getBytes(StandardCharsets.UTF_8);
        byte[] locationBytes = info.getLocation().getBytes(StandardCharsets.UTF_8);

        // Limit each field to 255 bytes
        int callsignLen = Math.min(callsignBytes.length, 255);
        int nameLen = Math.min(nameBytes.length, 255);
        int locationLen = Math.min(locationBytes.length, 255);

        ByteBuffer buffer = ByteBuffer.allocate(3 + callsignLen + nameLen + locationLen);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.put((byte) callsignLen);
        if (callsignLen > 0) buffer.put(callsignBytes, 0, callsignLen);

        buffer.put((byte) nameLen);
        if (nameLen > 0) buffer.put(nameBytes, 0, nameLen);

        buffer.put((byte) locationLen);
        if (locationLen > 0) buffer.put(locationBytes, 0, locationLen);

        return buffer.array();
    }

    /**
     * Deserializes ClientInfo from bytes.
     */
    private static ClientInfo deserializeClientInfo(ByteBuffer buffer) {
        if (buffer.remaining() < 3) {
            return null;
        }

        int callsignLen = buffer.get() & 0xFF;
        String callsign = "";
        if (callsignLen > 0 && buffer.remaining() >= callsignLen) {
            byte[] bytes = new byte[callsignLen];
            buffer.get(bytes);
            callsign = new String(bytes, StandardCharsets.UTF_8);
        }

        if (buffer.remaining() < 1) return new ClientInfo(callsign, "", "");
        int nameLen = buffer.get() & 0xFF;
        String name = "";
        if (nameLen > 0 && buffer.remaining() >= nameLen) {
            byte[] bytes = new byte[nameLen];
            buffer.get(bytes);
            name = new String(bytes, StandardCharsets.UTF_8);
        }

        if (buffer.remaining() < 1) return new ClientInfo(callsign, name, "");
        int locationLen = buffer.get() & 0xFF;
        String location = "";
        if (locationLen > 0 && buffer.remaining() >= locationLen) {
            byte[] bytes = new byte[locationLen];
            buffer.get(bytes);
            location = new String(bytes, StandardCharsets.UTF_8);
        }

        return new ClientInfo(callsign, name, location);
    }

    /**
     * Parses requested audio configuration from a connect request message.
     *
     * @return the requested config, or null if none was included
     */
    public AudioStreamConfig parseConnectRequestConfig() {
        if (type != Type.CONNECT_REQUEST || data.length < 3) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Skip version
        buffer.get();

        // Skip client name
        int nameLen = buffer.get() & 0xFF;
        if (buffer.remaining() < nameLen + 1) {
            return null;  // Malformed or old protocol version
        }
        buffer.position(buffer.position() + nameLen);

        // Check if config flag is present
        if (buffer.remaining() < 1) {
            return null;  // Old protocol version without config
        }

        byte hasConfig = buffer.get();
        if (hasConfig == 0 || buffer.remaining() < 6) {
            return null;  // No config included
        }

        // Parse buffer settings
        AudioStreamConfig config = new AudioStreamConfig();
        config.setBufferTargetMs(buffer.getShort() & 0xFFFF);
        config.setBufferMinMs(buffer.getShort() & 0xFFFF);
        config.setBufferMaxMs(buffer.getShort() & 0xFFFF);

        return config;
    }

    /**
     * Parses client identification info from a connect request message.
     *
     * @return the client info, or null if none was included
     */
    public ClientInfo parseConnectRequestClientInfo() {
        if (type != Type.CONNECT_REQUEST || data.length < 3) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Skip version
        buffer.get();

        // Skip client name
        int nameLen = buffer.get() & 0xFF;
        if (buffer.remaining() < nameLen + 1) {
            return null;
        }
        buffer.position(buffer.position() + nameLen);

        // Skip config flag and config data if present
        if (buffer.remaining() < 1) {
            return null;
        }
        byte hasConfig = buffer.get();
        if (hasConfig != 0) {
            if (buffer.remaining() < 6) {
                return null;
            }
            buffer.position(buffer.position() + 6);
        }

        // Check for client info
        if (buffer.remaining() < 1) {
            return null;  // No client info
        }
        int clientInfoLen = buffer.get() & 0xFF;
        if (clientInfoLen == 0 || buffer.remaining() < clientInfoLen) {
            return null;
        }

        return deserializeClientInfo(buffer);
    }

    /**
     * Creates a connect accept message.
     */
    public static ControlMessage connectAccept() {
        return new ControlMessage(Type.CONNECT_ACCEPT);
    }

    /**
     * Creates a connect reject message.
     */
    public static ControlMessage connectReject(RejectReason reason, String message) {
        byte[] msgBytes = message != null ?
            message.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(2 + msgBytes.length);
        buffer.put(reason.getValue());
        buffer.put((byte) msgBytes.length);
        if (msgBytes.length > 0) {
            buffer.put(msgBytes);
        }
        return new ControlMessage(Type.CONNECT_REJECT, buffer.array());
    }

    /**
     * Creates an audio config message.
     * <p>
     * Includes both audio format parameters and buffer settings so the
     * client knows exactly what configuration the server is using.
     * </p>
     */
    public static ControlMessage audioConfig(AudioStreamConfig config) {
        // Extended format: 8 bytes audio format + 6 bytes buffer settings
        ByteBuffer buffer = ByteBuffer.allocate(14);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(config.getSampleRate());
        buffer.put((byte) config.getBitsPerSample());
        buffer.put((byte) config.getChannels());
        buffer.putShort((short) config.getFrameDurationMs());
        // Buffer settings
        buffer.putShort((short) config.getBufferTargetMs());
        buffer.putShort((short) config.getBufferMinMs());
        buffer.putShort((short) config.getBufferMaxMs());
        return new ControlMessage(Type.AUDIO_CONFIG, buffer.array());
    }

    /**
     * Parses audio config from message data.
     * <p>
     * Handles both old format (8 bytes, no buffer settings) and new format
     * (14 bytes, includes buffer settings) for backward compatibility.
     * </p>
     */
    public AudioStreamConfig parseAudioConfig() {
        if (type != Type.AUDIO_CONFIG || data.length < 8) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        AudioStreamConfig config = new AudioStreamConfig();
        config.setSampleRate(buffer.getInt());
        config.setBitsPerSample(buffer.get() & 0xFF);
        config.setChannels(buffer.get() & 0xFF);
        config.setFrameDurationMs(buffer.getShort() & 0xFFFF);

        // Parse buffer settings if present (new format)
        if (data.length >= 14) {
            config.setBufferTargetMs(buffer.getShort() & 0xFFFF);
            config.setBufferMinMs(buffer.getShort() & 0xFFFF);
            config.setBufferMaxMs(buffer.getShort() & 0xFFFF);
        }

        return config;
    }

    /**
     * Creates a stream start message.
     */
    public static ControlMessage streamStart() {
        return new ControlMessage(Type.STREAM_START);
    }

    /**
     * Creates a stream stop message.
     */
    public static ControlMessage streamStop() {
        return new ControlMessage(Type.STREAM_STOP);
    }

    /**
     * Creates a stream pause message.
     */
    public static ControlMessage streamPause() {
        return new ControlMessage(Type.STREAM_PAUSE);
    }

    /**
     * Creates a stream resume message.
     */
    public static ControlMessage streamResume() {
        return new ControlMessage(Type.STREAM_RESUME);
    }

    /**
     * Creates a heartbeat message.
     */
    public static ControlMessage heartbeat() {
        return new ControlMessage(Type.HEARTBEAT);
    }

    /**
     * Creates a heartbeat ack message.
     */
    public static ControlMessage heartbeatAck() {
        return new ControlMessage(Type.HEARTBEAT_ACK);
    }

    /**
     * Creates a latency probe message.
     *
     * @param probeTimestamp the timestamp when probe was sent
     */
    public static ControlMessage latencyProbe(long probeTimestamp) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(probeTimestamp);
        return new ControlMessage(Type.LATENCY_PROBE, buffer.array());
    }

    /**
     * Creates a latency response message.
     *
     * @param originalTimestamp the timestamp from the probe
     */
    public static ControlMessage latencyResponse(long originalTimestamp) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(originalTimestamp);
        return new ControlMessage(Type.LATENCY_RESPONSE, buffer.array());
    }

    /**
     * Parses the timestamp from a latency probe/response.
     */
    public long parseLatencyTimestamp() {
        if (data.length < 8) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getLong();
    }

    /**
     * Creates an error message.
     */
    public static ControlMessage error(String errorMessage) {
        byte[] msgBytes = errorMessage != null ?
            errorMessage.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return new ControlMessage(Type.ERROR, msgBytes);
    }

    /**
     * Parses error message text.
     */
    public String parseErrorMessage() {
        // Handle CONNECT_REJECT messages (format: reason byte, length byte, message)
        if (type == Type.CONNECT_REJECT && data.length >= 2) {
            int msgLen = data[1] & 0xFF;
            if (msgLen > 0 && data.length >= 2 + msgLen) {
                return new String(data, 2, msgLen, StandardCharsets.UTF_8);
            }
            // Return reason code name if no message
            RejectReason reason = RejectReason.fromValue(data[0]);
            return reason != null ? reason.name() : "REJECTED";
        }
        // Handle ERROR messages (format: just the message)
        if (type == Type.ERROR && data.length > 0) {
            return new String(data, StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Creates a disconnect message.
     */
    public static ControlMessage disconnect() {
        return new ControlMessage(Type.DISCONNECT);
    }

    /**
     * Creates a TX granted message.
     * <p>
     * Sent to a client when they successfully claim the TX channel.
     * </p>
     */
    public static ControlMessage txGranted() {
        return new ControlMessage(Type.TX_GRANTED);
    }

    /**
     * Creates a TX denied message.
     * <p>
     * Sent to a client when their TX audio is rejected because another
     * client currently holds the TX channel.
     * </p>
     *
     * @param holdingClientId the ID of the client holding the channel (may be null)
     */
    public static ControlMessage txDenied(String holdingClientId) {
        byte[] data = holdingClientId != null ?
            holdingClientId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return new ControlMessage(Type.TX_DENIED, data);
    }

    /**
     * Creates a TX preempted message.
     * <p>
     * Sent to a client when they lose the TX channel to a higher priority client.
     * </p>
     *
     * @param preemptingClientId the ID of the preempting client (may be null)
     */
    public static ControlMessage txPreempted(String preemptingClientId) {
        byte[] data = preemptingClientId != null ?
            preemptingClientId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return new ControlMessage(Type.TX_PREEMPTED, data);
    }

    /**
     * Creates a TX released message.
     * <p>
     * Sent to a client when the TX channel they held is released
     * (either due to idle timeout or explicit release).
     * </p>
     */
    public static ControlMessage txReleased() {
        return new ControlMessage(Type.TX_RELEASED);
    }

    /**
     * Parses the client ID from a TX_DENIED or TX_PREEMPTED message.
     *
     * @return the client ID, or null if not present
     */
    public String parseTxClientId() {
        if ((type != Type.TX_DENIED && type != Type.TX_PREEMPTED) || data.length == 0) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Creates a clients update message (legacy, without client info).
     */
    public static ControlMessage clientsUpdate(int clientCount, int maxClients,
            String txOwner, java.util.List<String> clientIds) {
        return clientsUpdate(clientCount, maxClients, txOwner, clientIds, null);
    }

    /**
     * Creates a clients update message with client identification info.
     * <p>
     * Broadcast to all connected clients when the client list changes
     * (connect or disconnect). Allows clients to know how many other
     * clients are connected, who they are, and who currently owns the TX channel.
     * </p>
     *
     * @param clientCount current number of connected clients
     * @param maxClients maximum allowed clients
     * @param txOwner client ID of current TX owner (may be null)
     * @param clientIds list of connected client IDs (may be null or empty)
     * @param clientInfoMap map of client ID to ClientInfo (may be null)
     */
    public static ControlMessage clientsUpdate(int clientCount, int maxClients,
            String txOwner, java.util.List<String> clientIds,
            java.util.Map<String, ClientInfo> clientInfoMap) {
        // Format: count (1 byte) + max (1 byte) + txOwner length (1 byte) + txOwner +
        //         numClients (1 byte) + [idLen (1 byte) + clientId + infoLen (1 byte) + clientInfo]...

        byte[] txOwnerBytes = txOwner != null ? txOwner.getBytes(StandardCharsets.UTF_8) : new byte[0];

        // Pre-serialize all client entries
        java.util.List<byte[]> clientEntries = new java.util.ArrayList<>();
        int entriesSize = 0;
        if (clientIds != null) {
            for (String id : clientIds) {
                byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
                byte[] infoBytes = new byte[0];
                if (clientInfoMap != null && clientInfoMap.containsKey(id)) {
                    infoBytes = serializeClientInfo(clientInfoMap.get(id));
                }
                // Entry: idLen(1) + id + infoLen(1) + info
                byte[] entry = new byte[2 + idBytes.length + infoBytes.length];
                entry[0] = (byte) idBytes.length;
                System.arraycopy(idBytes, 0, entry, 1, idBytes.length);
                entry[1 + idBytes.length] = (byte) infoBytes.length;
                if (infoBytes.length > 0) {
                    System.arraycopy(infoBytes, 0, entry, 2 + idBytes.length, infoBytes.length);
                }
                clientEntries.add(entry);
                entriesSize += entry.length;
            }
        }

        // Calculate total size
        int size = 4 + txOwnerBytes.length + entriesSize;

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.put((byte) Math.min(clientCount, 255));
        buffer.put((byte) Math.min(maxClients, 255));
        buffer.put((byte) txOwnerBytes.length);
        if (txOwnerBytes.length > 0) {
            buffer.put(txOwnerBytes);
        }

        buffer.put((byte) clientEntries.size());
        for (byte[] entry : clientEntries) {
            buffer.put(entry);
        }

        return new ControlMessage(Type.CLIENTS_UPDATE, buffer.array());
    }

    /**
     * Parsed data from a CLIENTS_UPDATE message.
     */
    public static class ClientsUpdateInfo {
        public final int clientCount;
        public final int maxClients;
        public final String txOwner;
        public final java.util.List<String> clientIds;
        public final java.util.Map<String, ClientInfo> clientInfoMap;

        public ClientsUpdateInfo(int clientCount, int maxClients, String txOwner,
                java.util.List<String> clientIds) {
            this(clientCount, maxClients, txOwner, clientIds, new java.util.HashMap<>());
        }

        public ClientsUpdateInfo(int clientCount, int maxClients, String txOwner,
                java.util.List<String> clientIds, java.util.Map<String, ClientInfo> clientInfoMap) {
            this.clientCount = clientCount;
            this.maxClients = maxClients;
            this.txOwner = txOwner;
            this.clientIds = clientIds;
            this.clientInfoMap = clientInfoMap;
        }

        /**
         * Gets the display string for a client ID.
         * <p>
         * Returns the ClientInfo display string if available, otherwise the client ID.
         * </p>
         */
        public String getClientDisplayString(String clientId) {
            ClientInfo info = clientInfoMap.get(clientId);
            if (info != null && !info.isEmpty()) {
                return info.getDisplayString();
            }
            return clientId;
        }
    }

    /**
     * Parses a CLIENTS_UPDATE message.
     *
     * @return the parsed info, or null if not a valid CLIENTS_UPDATE message
     */
    public ClientsUpdateInfo parseClientsUpdate() {
        if (type != Type.CLIENTS_UPDATE || data.length < 4) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int clientCount = buffer.get() & 0xFF;
        int maxClients = buffer.get() & 0xFF;

        int txOwnerLen = buffer.get() & 0xFF;
        String txOwner = null;
        if (txOwnerLen > 0 && buffer.remaining() >= txOwnerLen) {
            byte[] txOwnerBytes = new byte[txOwnerLen];
            buffer.get(txOwnerBytes);
            txOwner = new String(txOwnerBytes, StandardCharsets.UTF_8);
        }

        java.util.List<String> clientIds = new java.util.ArrayList<>();
        java.util.Map<String, ClientInfo> clientInfoMap = new java.util.HashMap<>();

        if (buffer.remaining() >= 1) {
            int numClients = buffer.get() & 0xFF;
            for (int i = 0; i < numClients && buffer.remaining() >= 1; i++) {
                // Read client ID
                int idLen = buffer.get() & 0xFF;
                if (buffer.remaining() < idLen) break;
                byte[] idBytes = new byte[idLen];
                buffer.get(idBytes);
                String clientId = new String(idBytes, StandardCharsets.UTF_8);
                clientIds.add(clientId);

                // Read client info if present
                if (buffer.remaining() >= 1) {
                    int infoLen = buffer.get() & 0xFF;
                    if (infoLen > 0 && buffer.remaining() >= infoLen) {
                        ClientInfo info = deserializeClientInfo(buffer);
                        if (info != null) {
                            clientInfoMap.put(clientId, info);
                        }
                    }
                }
            }
        }

        return new ClientsUpdateInfo(clientCount, maxClients, txOwner, clientIds, clientInfoMap);
    }

    // Getters

    public Type getType() {
        return type;
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public String toString() {
        return String.format("ControlMessage[type=%s, data=%d bytes]", type, data.length);
    }
}
