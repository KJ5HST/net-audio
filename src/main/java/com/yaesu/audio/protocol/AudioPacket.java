/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License.
 */
package com.yaesu.audio.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * Represents an audio streaming packet for network transmission.
 * <p>
 * Packet format:
 * <pre>
 * Offset  Size  Field
 * 0       2     Magic (0xAF01)
 * 2       1     Version
 * 3       1     Type
 * 4       1     Flags
 * 5       4     Sequence number
 * 9       8     Timestamp (nanos)
 * 17      2     Payload length
 * 19      N     Payload
 * 19+N    4     CRC32
 * </pre>
 * </p>
 */
public class AudioPacket {

    /** Magic bytes identifying audio packets */
    public static final short MAGIC = (short) 0xAF01;

    /** Current protocol version */
    public static final byte VERSION = 1;

    /** Header size in bytes (without payload and CRC) */
    public static final int HEADER_SIZE = 19;

    /** CRC size in bytes */
    public static final int CRC_SIZE = 4;

    /** Maximum payload size */
    public static final int MAX_PAYLOAD = 8192;

    /**
     * Packet types.
     */
    public enum Type {
        /** Audio data from radio to client (RX) */
        AUDIO_RX((byte) 0x00),
        /** Audio data from client to radio (TX) */
        AUDIO_TX((byte) 0x01),
        /** Control message */
        CONTROL((byte) 0x02),
        /** Heartbeat/keepalive */
        HEARTBEAT((byte) 0x03);

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
     * Packet flags.
     */
    public static class Flags {
        /** Payload is compressed */
        public static final byte COMPRESSED = 0x01;
        /** Low bandwidth mode (12kHz sample rate) */
        public static final byte LOW_BANDWIDTH = 0x02;

        private Flags() {}
    }

    private byte version = VERSION;
    private Type type;
    private byte flags;
    private int sequence;
    private long timestamp;
    private byte[] payload;

    /**
     * Creates an empty packet.
     */
    public AudioPacket() {
    }

    /**
     * Creates an audio packet with the specified data.
     *
     * @param type the packet type
     * @param sequence the sequence number
     * @param payload the payload data
     */
    public AudioPacket(Type type, int sequence, byte[] payload) {
        this.type = type;
        this.sequence = sequence;
        this.timestamp = System.nanoTime();
        this.payload = payload != null ? payload : new byte[0];
    }

    /**
     * Creates an RX audio packet.
     */
    public static AudioPacket createRxAudio(int sequence, byte[] audioData) {
        return new AudioPacket(Type.AUDIO_RX, sequence, audioData);
    }

    /**
     * Creates a TX audio packet.
     */
    public static AudioPacket createTxAudio(int sequence, byte[] audioData) {
        return new AudioPacket(Type.AUDIO_TX, sequence, audioData);
    }

    /**
     * Creates a control packet.
     */
    public static AudioPacket createControl(int sequence, byte[] controlData) {
        return new AudioPacket(Type.CONTROL, sequence, controlData);
    }

    /**
     * Creates a heartbeat packet.
     */
    public static AudioPacket createHeartbeat(int sequence) {
        return new AudioPacket(Type.HEARTBEAT, sequence, null);
    }

    /**
     * Serializes the packet to a byte array.
     *
     * @return the serialized packet
     */
    public byte[] serialize() {
        int payloadLen = payload != null ? payload.length : 0;
        int totalLen = HEADER_SIZE + payloadLen + CRC_SIZE;

        ByteBuffer buffer = ByteBuffer.allocate(totalLen);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Header
        buffer.putShort(MAGIC);
        buffer.put(version);
        buffer.put(type.getValue());
        buffer.put(flags);
        buffer.putInt(sequence);
        buffer.putLong(timestamp);
        buffer.putShort((short) payloadLen);

        // Payload
        if (payloadLen > 0) {
            buffer.put(payload);
        }

        // Calculate CRC over header + payload
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, HEADER_SIZE + payloadLen);
        buffer.putInt((int) crc.getValue());

        return buffer.array();
    }

    /**
     * Deserializes a packet from a byte array.
     *
     * @param data the serialized packet data
     * @return the deserialized packet, or null if invalid
     */
    public static AudioPacket deserialize(byte[] data) {
        return deserialize(data, 0, data.length);
    }

    /**
     * Deserializes a packet from a byte array.
     *
     * @param data the data array
     * @param offset the offset in the array
     * @param length the length of data
     * @return the deserialized packet, or null if invalid
     */
    public static AudioPacket deserialize(byte[] data, int offset, int length) {
        if (length < HEADER_SIZE + CRC_SIZE) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Check magic
        short magic = buffer.getShort();
        if (magic != MAGIC) {
            return null;
        }

        AudioPacket packet = new AudioPacket();
        packet.version = buffer.get();
        packet.type = Type.fromValue(buffer.get());
        packet.flags = buffer.get();
        packet.sequence = buffer.getInt();
        packet.timestamp = buffer.getLong();
        int payloadLen = buffer.getShort() & 0xFFFF;

        if (packet.type == null) {
            return null;
        }

        // Validate payload length
        if (payloadLen > MAX_PAYLOAD || length < HEADER_SIZE + payloadLen + CRC_SIZE) {
            return null;
        }

        // Read payload
        if (payloadLen > 0) {
            packet.payload = new byte[payloadLen];
            buffer.get(packet.payload);
        } else {
            packet.payload = new byte[0];
        }

        // Verify CRC
        int receivedCrc = buffer.getInt();
        CRC32 crc = new CRC32();
        crc.update(data, offset, HEADER_SIZE + payloadLen);
        if ((int) crc.getValue() != receivedCrc) {
            return null;
        }

        return packet;
    }

    /**
     * Gets the total packet size for a given payload length.
     */
    public static int getPacketSize(int payloadLength) {
        return HEADER_SIZE + payloadLength + CRC_SIZE;
    }

    // Getters and setters

    public byte getVersion() {
        return version;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public byte getFlags() {
        return flags;
    }

    public void setFlags(byte flags) {
        this.flags = flags;
    }

    public boolean hasFlag(byte flag) {
        return (flags & flag) != 0;
    }

    public void setFlag(byte flag, boolean value) {
        if (value) {
            flags |= flag;
        } else {
            flags &= ~flag;
        }
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public int getPayloadLength() {
        return payload != null ? payload.length : 0;
    }

    @Override
    public String toString() {
        return String.format("AudioPacket[type=%s, seq=%d, flags=0x%02X, payload=%d bytes]",
            type, sequence, flags, getPayloadLength());
    }
}
