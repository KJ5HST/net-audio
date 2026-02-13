/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AudioMixer.
 */
class AudioMixerTest {

    private AudioMixer mixer;
    private AudioStreamConfig config;

    @BeforeEach
    void setUp() {
        config = new AudioStreamConfig()
            .setTxIdleTimeoutMs(100);  // Short timeout for testing
        mixer = new AudioMixer(config);
    }

    @Test
    void testInitialState() {
        assertNull(mixer.getCurrentTxOwner());
        assertFalse(mixer.isRunning());
        assertFalse(mixer.isTxOwner("anyone"));
    }

    @Test
    void testRegisterClient() {
        TestTxClient client = new TestTxClient("client-1");
        mixer.registerClient(client);

        // Registration doesn't grant TX channel
        assertNull(mixer.getCurrentTxOwner());
    }

    @Test
    void testFirstClientClaimsTx() {
        TestTxClient client = new TestTxClient("client-1");
        mixer.registerClient(client);

        byte[] audioData = {1, 2, 3, 4};
        AudioMixer.TxResult result = mixer.submitTxAudio("client-1", audioData);

        assertEquals(AudioMixer.TxResult.ACCEPTED, result);
        assertEquals("client-1", mixer.getCurrentTxOwner());
        assertTrue(mixer.isTxOwner("client-1"));
        assertTrue(client.wasGranted());
    }

    @Test
    void testSecondClientRejected() {
        TestTxClient client1 = new TestTxClient("client-1");
        TestTxClient client2 = new TestTxClient("client-2");
        mixer.registerClient(client1);
        mixer.registerClient(client2);

        // Client 1 claims the channel
        mixer.submitTxAudio("client-1", new byte[]{1, 2, 3});
        assertEquals("client-1", mixer.getCurrentTxOwner());

        // Client 2 should be rejected
        AudioMixer.TxResult result = mixer.submitTxAudio("client-2", new byte[]{4, 5, 6});
        assertEquals(AudioMixer.TxResult.REJECTED, result);
        assertEquals("client-1", mixer.getCurrentTxOwner());
    }

    @Test
    void testHighPriorityPreempts() {
        TestTxClient normalClient = new TestTxClient("normal", AudioMixer.TxPriority.NORMAL);
        TestTxClient highClient = new TestTxClient("high", AudioMixer.TxPriority.HIGH);
        mixer.registerClient(normalClient);
        mixer.registerClient(highClient);

        // Normal client claims channel
        mixer.submitTxAudio("normal", new byte[]{1, 2, 3});
        assertEquals("normal", mixer.getCurrentTxOwner());

        // High priority client should preempt
        AudioMixer.TxResult result = mixer.submitTxAudio("high", new byte[]{4, 5, 6});
        assertEquals(AudioMixer.TxResult.ACCEPTED, result);
        assertEquals("high", mixer.getCurrentTxOwner());

        // Normal client should have been notified of preemption
        assertTrue(normalClient.wasPreempted());
        assertEquals("high", normalClient.getPreemptingClientId());
    }

    @Test
    void testEqualPriorityNoPreemption() {
        TestTxClient client1 = new TestTxClient("client-1", AudioMixer.TxPriority.NORMAL);
        TestTxClient client2 = new TestTxClient("client-2", AudioMixer.TxPriority.NORMAL);
        mixer.registerClient(client1);
        mixer.registerClient(client2);

        // Client 1 claims channel
        mixer.submitTxAudio("client-1", new byte[]{1, 2, 3});

        // Client 2 with same priority cannot preempt
        AudioMixer.TxResult result = mixer.submitTxAudio("client-2", new byte[]{4, 5, 6});
        assertEquals(AudioMixer.TxResult.REJECTED, result);
        assertEquals("client-1", mixer.getCurrentTxOwner());
        assertFalse(client1.wasPreempted());
    }

    @Test
    void testExplicitRelease() {
        TestTxClient client = new TestTxClient("client-1");
        mixer.registerClient(client);

        // Claim channel
        mixer.submitTxAudio("client-1", new byte[]{1, 2, 3});
        assertEquals("client-1", mixer.getCurrentTxOwner());

        // Explicitly release
        mixer.releaseTx("client-1");
        assertNull(mixer.getCurrentTxOwner());
        assertTrue(client.wasReleased());
    }

    @Test
    void testUnregisterReleasesChannel() {
        TestTxClient client = new TestTxClient("client-1");
        mixer.registerClient(client);

        // Claim channel
        mixer.submitTxAudio("client-1", new byte[]{1, 2, 3});
        assertEquals("client-1", mixer.getCurrentTxOwner());

        // Unregister should release channel
        mixer.unregisterClient("client-1");
        assertNull(mixer.getCurrentTxOwner());
    }

    @Test
    void testUnregisteredClientRejected() {
        // Try to submit without registering
        AudioMixer.TxResult result = mixer.submitTxAudio("unknown", new byte[]{1, 2, 3});
        assertEquals(AudioMixer.TxResult.REJECTED, result);
        assertNull(mixer.getCurrentTxOwner());
    }

    @Test
    void testTxConflictNotification() {
        AtomicReference<String> holdingClient = new AtomicReference<>();
        AtomicReference<String> requestingClient = new AtomicReference<>();

        mixer.setMixerListener(new AudioMixer.MixerListener() {
            @Override
            public void onTxConflict(String holdingClientId, String requestingClientId) {
                holdingClient.set(holdingClientId);
                requestingClient.set(requestingClientId);
            }

            @Override
            public void onTxOwnerChanged(String newOwnerClientId) {}
        });

        TestTxClient client1 = new TestTxClient("client-1");
        TestTxClient client2 = new TestTxClient("client-2");
        mixer.registerClient(client1);
        mixer.registerClient(client2);

        // Client 1 claims
        mixer.submitTxAudio("client-1", new byte[]{1});

        // Client 2 gets rejected - should trigger conflict notification
        mixer.submitTxAudio("client-2", new byte[]{2});

        assertEquals("client-1", holdingClient.get());
        assertEquals("client-2", requestingClient.get());
    }

    @Test
    void testTxOwnerChangedNotification() {
        AtomicReference<String> lastOwner = new AtomicReference<>();
        AtomicInteger changeCount = new AtomicInteger(0);

        mixer.setMixerListener(new AudioMixer.MixerListener() {
            @Override
            public void onTxConflict(String holdingClientId, String requestingClientId) {}

            @Override
            public void onTxOwnerChanged(String newOwnerClientId) {
                lastOwner.set(newOwnerClientId);
                changeCount.incrementAndGet();
            }
        });

        TestTxClient client = new TestTxClient("client-1");
        mixer.registerClient(client);

        // Claim
        mixer.submitTxAudio("client-1", new byte[]{1});
        assertEquals("client-1", lastOwner.get());
        assertEquals(1, changeCount.get());

        // Release
        mixer.releaseTx("client-1");
        assertNull(lastOwner.get());
        assertEquals(2, changeCount.get());
    }

    @Test
    void testPriorityLevels() {
        // Verify priority ordering
        assertTrue(AudioMixer.TxPriority.HIGH.canPreempt(AudioMixer.TxPriority.NORMAL));
        assertTrue(AudioMixer.TxPriority.HIGH.canPreempt(AudioMixer.TxPriority.LOW));
        assertTrue(AudioMixer.TxPriority.NORMAL.canPreempt(AudioMixer.TxPriority.LOW));
        assertTrue(AudioMixer.TxPriority.EXCLUSIVE.canPreempt(AudioMixer.TxPriority.HIGH));

        // Same priority cannot preempt
        assertFalse(AudioMixer.TxPriority.NORMAL.canPreempt(AudioMixer.TxPriority.NORMAL));
        assertFalse(AudioMixer.TxPriority.HIGH.canPreempt(AudioMixer.TxPriority.HIGH));

        // Lower cannot preempt higher
        assertFalse(AudioMixer.TxPriority.LOW.canPreempt(AudioMixer.TxPriority.NORMAL));
        assertFalse(AudioMixer.TxPriority.NORMAL.canPreempt(AudioMixer.TxPriority.HIGH));
    }

    @Test
    void testSameClientContinuesTransmission() {
        TestTxClient client = new TestTxClient("client-1");
        mixer.registerClient(client);

        // Multiple submissions from same client should all be accepted
        assertEquals(AudioMixer.TxResult.ACCEPTED, mixer.submitTxAudio("client-1", new byte[]{1}));
        assertEquals(AudioMixer.TxResult.ACCEPTED, mixer.submitTxAudio("client-1", new byte[]{2}));
        assertEquals(AudioMixer.TxResult.ACCEPTED, mixer.submitTxAudio("client-1", new byte[]{3}));

        // Should only be granted once
        assertEquals(1, client.getGrantCount());
    }

    @Test
    void testTxBufferReceivesData() {
        TestTxClient client = new TestTxClient("client-1");
        mixer.registerClient(client);

        byte[] audioData = new byte[100];
        for (int i = 0; i < audioData.length; i++) {
            audioData[i] = (byte) i;
        }

        mixer.submitTxAudio("client-1", audioData);

        AudioRingBuffer txBuffer = mixer.getTxBuffer();
        assertTrue(txBuffer.getAvailable() > 0);
    }

    /**
     * Test TX client implementation for unit testing.
     */
    private static class TestTxClient implements AudioMixer.TxClient {
        private final String clientId;
        private final AudioMixer.TxPriority priority;
        private AtomicBoolean granted = new AtomicBoolean(false);
        private AtomicBoolean released = new AtomicBoolean(false);
        private AtomicBoolean preempted = new AtomicBoolean(false);
        private AtomicReference<String> preemptingClientId = new AtomicReference<>();
        private AtomicInteger grantCount = new AtomicInteger(0);

        TestTxClient(String clientId) {
            this(clientId, AudioMixer.TxPriority.NORMAL);
        }

        TestTxClient(String clientId, AudioMixer.TxPriority priority) {
            this.clientId = clientId;
            this.priority = priority;
        }

        @Override
        public String getClientId() {
            return clientId;
        }

        @Override
        public AudioMixer.TxPriority getTxPriority() {
            return priority;
        }

        @Override
        public void onPreempted(String preemptingClientId) {
            preempted.set(true);
            this.preemptingClientId.set(preemptingClientId);
        }

        @Override
        public void onTxGranted() {
            granted.set(true);
            grantCount.incrementAndGet();
        }

        @Override
        public void onTxReleased() {
            released.set(true);
        }

        boolean wasGranted() {
            return granted.get();
        }

        boolean wasReleased() {
            return released.get();
        }

        boolean wasPreempted() {
            return preempted.get();
        }

        String getPreemptingClientId() {
            return preemptingClientId.get();
        }

        int getGrantCount() {
            return grantCount.get();
        }
    }
}
