/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License.
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
        byte[] nameBytes = clientName != null ?
            clientName.getBytes(StandardCharsets.UTF_8) : new byte[0];

        // If config provided, include buffer settings (6 bytes: target, min, max as shorts)
        int configSize = requestedConfig != null ? 6 : 0;
        ByteBuffer buffer = ByteBuffer.allocate(3 + nameBytes.length + configSize);
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

        return new ControlMessage(Type.CONNECT_REQUEST, buffer.array());
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
        if (type != Type.ERROR || data.length == 0) {
            return null;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Creates a disconnect message.
     */
    public static ControlMessage disconnect() {
        return new ControlMessage(Type.DISCONNECT);
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
