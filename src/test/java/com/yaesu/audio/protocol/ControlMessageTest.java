/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio.protocol;

import com.yaesu.audio.AudioStreamConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ControlMessage.
 */
class ControlMessageTest {

    @Test
    void testConnectRequest() {
        ControlMessage msg = ControlMessage.connectRequest("test-client", (byte) 1);

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.CONNECT_REQUEST, msg.getType());
    }

    @Test
    void testConnectAccept() {
        ControlMessage msg = ControlMessage.connectAccept();

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.CONNECT_ACCEPT, msg.getType());
    }

    @Test
    void testConnectReject() {
        ControlMessage msg = ControlMessage.connectReject(
            ControlMessage.RejectReason.BUSY,
            "Server is busy"
        );

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.CONNECT_REJECT, msg.getType());
    }

    @Test
    void testDisconnect() {
        ControlMessage msg = ControlMessage.disconnect();

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.DISCONNECT, msg.getType());
    }

    @Test
    void testError() {
        ControlMessage msg = ControlMessage.error("Test error message");

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.ERROR, msg.getType());

        // Parse error message back
        byte[] serialized = msg.serialize();
        ControlMessage parsed = ControlMessage.deserialize(serialized);
        assertNotNull(parsed);
        assertEquals("Test error message", parsed.parseErrorMessage());
    }

    @Test
    void testHeartbeat() {
        ControlMessage msg = ControlMessage.heartbeat();

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.HEARTBEAT, msg.getType());
    }

    @Test
    void testHeartbeatAck() {
        ControlMessage msg = ControlMessage.heartbeatAck();

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.HEARTBEAT_ACK, msg.getType());
    }

    @Test
    void testAudioConfig() {
        AudioStreamConfig config = new AudioStreamConfig();
        ControlMessage msg = ControlMessage.audioConfig(config);

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.AUDIO_CONFIG, msg.getType());
    }

    @Test
    void testLatencyProbe() {
        long timestamp = System.nanoTime();
        ControlMessage msg = ControlMessage.latencyProbe(timestamp);

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.LATENCY_PROBE, msg.getType());

        // Verify timestamp can be parsed back
        byte[] serialized = msg.serialize();
        ControlMessage parsed = ControlMessage.deserialize(serialized);
        assertNotNull(parsed);
        assertEquals(timestamp, parsed.parseLatencyTimestamp());
    }

    @Test
    void testLatencyResponse() {
        long timestamp = 123456789L;
        ControlMessage msg = ControlMessage.latencyResponse(timestamp);

        assertNotNull(msg);
        assertEquals(ControlMessage.Type.LATENCY_RESPONSE, msg.getType());
    }

    @Test
    void testSerializeAndDeserialize() {
        ControlMessage original = ControlMessage.connectRequest("test", (byte) 1);
        byte[] serialized = original.serialize();

        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        ControlMessage deserialized = ControlMessage.deserialize(serialized);
        assertNotNull(deserialized);
        assertEquals(original.getType(), deserialized.getType());
    }

    @Test
    void testRejectReasons() {
        // Verify all reject reasons exist
        assertNotNull(ControlMessage.RejectReason.BUSY);
        assertNotNull(ControlMessage.RejectReason.REJECTED);
        assertNotNull(ControlMessage.RejectReason.VERSION_MISMATCH);
    }

    @Test
    void testMessageTypes() {
        // Verify all message types exist
        assertNotNull(ControlMessage.Type.CONNECT_REQUEST);
        assertNotNull(ControlMessage.Type.CONNECT_ACCEPT);
        assertNotNull(ControlMessage.Type.CONNECT_REJECT);
        assertNotNull(ControlMessage.Type.DISCONNECT);
        assertNotNull(ControlMessage.Type.ERROR);
        assertNotNull(ControlMessage.Type.HEARTBEAT);
        assertNotNull(ControlMessage.Type.HEARTBEAT_ACK);
        assertNotNull(ControlMessage.Type.AUDIO_CONFIG);
        assertNotNull(ControlMessage.Type.LATENCY_PROBE);
        assertNotNull(ControlMessage.Type.LATENCY_RESPONSE);
    }

    @Test
    void testAudioConfigParsing() {
        AudioStreamConfig original = new AudioStreamConfig();
        ControlMessage msg = ControlMessage.audioConfig(original);
        byte[] serialized = msg.serialize();

        ControlMessage parsed = ControlMessage.deserialize(serialized);
        assertNotNull(parsed);

        AudioStreamConfig parsedConfig = parsed.parseAudioConfig();
        assertNotNull(parsedConfig);
        assertEquals(original.getSampleRate(), parsedConfig.getSampleRate());
        assertEquals(original.getBitsPerSample(), parsedConfig.getBitsPerSample());
        assertEquals(original.getChannels(), parsedConfig.getChannels());
    }
}
