/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License.
 */
package com.yaesu.audio.protocol;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles protocol-level communication for audio streaming.
 * <p>
 * Manages sending and receiving of audio packets over a socket connection.
 * Provides methods for sending audio frames, control messages, and heartbeats.
 * </p>
 */
public class AudioProtocolHandler implements Closeable {

    /** Heartbeat interval in milliseconds */
    public static final int HEARTBEAT_INTERVAL_MS = 5000;

    /** Connection timeout for no response in milliseconds */
    public static final int CONNECTION_TIMEOUT_MS = 30000;

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    private volatile boolean closed = false;
    private volatile long lastSendTime = System.currentTimeMillis();
    private volatile long lastReceiveTime = System.currentTimeMillis();

    // Statistics
    private volatile long packetsSent = 0;
    private volatile long packetsReceived = 0;
    private volatile long bytesSent = 0;
    private volatile long bytesReceived = 0;
    private volatile int crcErrors = 0;

    /**
     * Creates a protocol handler for the given socket.
     *
     * @param socket the connected socket
     * @throws IOException if streams cannot be created
     */
    public AudioProtocolHandler(Socket socket) throws IOException {
        this.socket = socket;
        this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    /**
     * Sends an audio packet.
     *
     * @param packet the packet to send
     * @throws IOException if sending fails
     */
    public synchronized void sendPacket(AudioPacket packet) throws IOException {
        if (closed) {
            throw new IOException("Handler is closed");
        }

        byte[] data = packet.serialize();
        output.write(data);
        output.flush();

        lastSendTime = System.currentTimeMillis();
        packetsSent++;
        bytesSent += data.length;
    }

    /**
     * Sends an RX audio frame.
     *
     * @param audioData the audio data to send
     * @throws IOException if sending fails
     */
    public void sendRxAudio(byte[] audioData) throws IOException {
        AudioPacket packet = AudioPacket.createRxAudio(sequenceCounter.getAndIncrement(), audioData);
        sendPacket(packet);
    }

    /**
     * Sends a TX audio frame.
     *
     * @param audioData the audio data to send
     * @throws IOException if sending fails
     */
    public void sendTxAudio(byte[] audioData) throws IOException {
        AudioPacket packet = AudioPacket.createTxAudio(sequenceCounter.getAndIncrement(), audioData);
        sendPacket(packet);
    }

    /**
     * Sends a control message.
     *
     * @param message the control message
     * @throws IOException if sending fails
     */
    public void sendControl(ControlMessage message) throws IOException {
        AudioPacket packet = AudioPacket.createControl(sequenceCounter.getAndIncrement(), message.serialize());
        sendPacket(packet);
    }

    /**
     * Sends a heartbeat packet.
     *
     * @throws IOException if sending fails
     */
    public void sendHeartbeat() throws IOException {
        AudioPacket packet = AudioPacket.createHeartbeat(sequenceCounter.getAndIncrement());
        sendPacket(packet);
    }

    /**
     * Receives a packet with the specified timeout.
     *
     * @param timeoutMs timeout in milliseconds (0 for blocking)
     * @return the received packet, or null if timeout or closed
     * @throws IOException if reading fails
     */
    public AudioPacket receivePacket(int timeoutMs) throws IOException {
        if (closed) {
            return null;
        }

        try {
            socket.setSoTimeout(timeoutMs);

            // Read header first to get payload length
            byte[] header = new byte[AudioPacket.HEADER_SIZE];
            input.readFully(header);

            // Validate magic
            ByteBuffer headerBuf = ByteBuffer.wrap(header);
            headerBuf.order(ByteOrder.BIG_ENDIAN);
            short magic = headerBuf.getShort();
            if (magic != AudioPacket.MAGIC) {
                throw new IOException("Invalid packet magic: 0x" + Integer.toHexString(magic & 0xFFFF));
            }

            // Get payload length (at offset 17)
            int payloadLen = ((header[17] & 0xFF) << 8) | (header[18] & 0xFF);
            if (payloadLen > AudioPacket.MAX_PAYLOAD) {
                throw new IOException("Payload too large: " + payloadLen);
            }

            // Read payload and CRC
            byte[] payloadAndCrc = new byte[payloadLen + AudioPacket.CRC_SIZE];
            input.readFully(payloadAndCrc);

            // Combine into full packet
            byte[] fullPacket = new byte[header.length + payloadAndCrc.length];
            System.arraycopy(header, 0, fullPacket, 0, header.length);
            System.arraycopy(payloadAndCrc, 0, fullPacket, header.length, payloadAndCrc.length);

            // Deserialize and validate
            AudioPacket packet = AudioPacket.deserialize(fullPacket);
            if (packet == null) {
                crcErrors++;
                throw new IOException("Packet CRC validation failed");
            }

            lastReceiveTime = System.currentTimeMillis();
            packetsReceived++;
            bytesReceived += fullPacket.length;

            return packet;

        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    /**
     * Receives a packet (blocking).
     *
     * @return the received packet
     * @throws IOException if reading fails
     */
    public AudioPacket receivePacket() throws IOException {
        return receivePacket(0);
    }

    /**
     * Checks if a heartbeat should be sent based on the interval.
     */
    public boolean shouldSendHeartbeat() {
        return System.currentTimeMillis() - lastSendTime > HEARTBEAT_INTERVAL_MS;
    }

    /**
     * Checks if the connection has timed out.
     */
    public boolean isConnectionTimedOut() {
        return System.currentTimeMillis() - lastReceiveTime > CONNECTION_TIMEOUT_MS;
    }

    /**
     * Gets the time since last receive in milliseconds.
     */
    public long getTimeSinceLastReceive() {
        return System.currentTimeMillis() - lastReceiveTime;
    }

    /**
     * Gets the current sequence number.
     */
    public int getCurrentSequence() {
        return sequenceCounter.get();
    }

    /**
     * Checks if the handler is closed.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Gets the number of packets sent.
     */
    public long getPacketsSent() {
        return packetsSent;
    }

    /**
     * Gets the number of packets received.
     */
    public long getPacketsReceived() {
        return packetsReceived;
    }

    /**
     * Gets the number of bytes sent.
     */
    public long getBytesSent() {
        return bytesSent;
    }

    /**
     * Gets the number of bytes received.
     */
    public long getBytesReceived() {
        return bytesReceived;
    }

    /**
     * Gets the number of CRC errors.
     */
    public int getCrcErrors() {
        return crcErrors;
    }

    /**
     * Gets the remote address.
     */
    public String getRemoteAddress() {
        return socket.getRemoteSocketAddress().toString();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        try {
            input.close();
        } catch (IOException ignored) {}
        try {
            output.close();
        } catch (IOException ignored) {}
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    @Override
    public String toString() {
        return String.format("AudioProtocolHandler[%s, sent=%d, recv=%d]",
            getRemoteAddress(), packetsSent, packetsReceived);
    }
}
