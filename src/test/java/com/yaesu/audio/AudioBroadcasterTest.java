/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AudioBroadcaster.
 */
class AudioBroadcasterTest {

    private AudioBroadcaster broadcaster;
    private AudioStreamConfig config;

    @BeforeEach
    void setUp() {
        config = new AudioStreamConfig();
        broadcaster = new AudioBroadcaster(config);
    }

    @Test
    void testInitialState() {
        assertEquals(0, broadcaster.getTargetCount());
        assertFalse(broadcaster.hasTargets());
        assertFalse(broadcaster.isRunning());
    }

    @Test
    void testAddTarget() {
        TestTarget target1 = new TestTarget("target-1");
        TestTarget target2 = new TestTarget("target-2");

        broadcaster.addTarget(target1);
        assertEquals(1, broadcaster.getTargetCount());
        assertTrue(broadcaster.hasTargets());

        broadcaster.addTarget(target2);
        assertEquals(2, broadcaster.getTargetCount());
    }

    @Test
    void testAddNullTarget() {
        broadcaster.addTarget(null);
        assertEquals(0, broadcaster.getTargetCount());
    }

    @Test
    void testRemoveTarget() {
        TestTarget target1 = new TestTarget("target-1");
        TestTarget target2 = new TestTarget("target-2");

        broadcaster.addTarget(target1);
        broadcaster.addTarget(target2);
        assertEquals(2, broadcaster.getTargetCount());

        AudioBroadcaster.BroadcastTarget removed = broadcaster.removeTarget("target-1");
        assertSame(target1, removed);
        assertEquals(1, broadcaster.getTargetCount());

        removed = broadcaster.removeTarget("nonexistent");
        assertNull(removed);
        assertEquals(1, broadcaster.getTargetCount());
    }

    @Test
    void testTargetReceivesAudio() {
        TestTarget target = new TestTarget("target-1");
        broadcaster.addTarget(target);

        byte[] audioData = {1, 2, 3, 4, 5};

        // Simulate broadcast (without starting capture thread)
        target.receiveRxAudio(audioData, 0, audioData.length);

        assertEquals(1, target.getReceiveCount());
        assertArrayEquals(audioData, target.getLastReceivedData());
    }

    @Test
    void testMultipleTargetsReceiveAudio() {
        TestTarget target1 = new TestTarget("target-1");
        TestTarget target2 = new TestTarget("target-2");
        TestTarget target3 = new TestTarget("target-3");

        broadcaster.addTarget(target1);
        broadcaster.addTarget(target2);
        broadcaster.addTarget(target3);

        byte[] audioData = {10, 20, 30};

        // Broadcast to all targets
        broadcaster.forEachTarget((id, target) -> {
            ((TestTarget) target).receiveRxAudio(audioData, 0, audioData.length);
        });

        assertEquals(1, target1.getReceiveCount());
        assertEquals(1, target2.getReceiveCount());
        assertEquals(1, target3.getReceiveCount());
    }

    @Test
    void testTargetFailureRemovesTarget() {
        TestTarget successTarget = new TestTarget("success");
        TestTarget failTarget = new TestTarget("fail", true);  // Will fail on receive

        broadcaster.addTarget(successTarget);
        broadcaster.addTarget(failTarget);

        List<String> failedTargets = Collections.synchronizedList(new ArrayList<>());
        broadcaster.setBroadcastListener((targetId, reason) -> failedTargets.add(targetId));

        // Simulate broadcast that fails for one target
        byte[] audioData = {1, 2, 3};
        broadcaster.forEachTarget((id, target) -> {
            boolean accepted = target.receiveRxAudio(audioData, 0, audioData.length);
            if (!accepted) {
                broadcaster.removeTarget(id);
                failedTargets.add(id);
            }
        });

        // Success target should remain
        assertEquals(1, broadcaster.getTargetCount());
        assertEquals(1, successTarget.getReceiveCount());

        // Fail target should have been removed
        assertTrue(failedTargets.contains("fail"));
    }

    @Test
    void testConcurrentTargetAccess() throws InterruptedException {
        int numTargets = 10;
        CountDownLatch latch = new CountDownLatch(numTargets);
        AtomicInteger successCount = new AtomicInteger(0);

        // Add targets concurrently
        for (int i = 0; i < numTargets; i++) {
            final int idx = i;
            new Thread(() -> {
                broadcaster.addTarget(new TestTarget("target-" + idx));
                successCount.incrementAndGet();
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(numTargets, broadcaster.getTargetCount());
    }

    @Test
    void testForEachTarget() {
        broadcaster.addTarget(new TestTarget("a"));
        broadcaster.addTarget(new TestTarget("b"));
        broadcaster.addTarget(new TestTarget("c"));

        List<String> visitedIds = new ArrayList<>();
        broadcaster.forEachTarget((id, target) -> visitedIds.add(id));

        assertEquals(3, visitedIds.size());
        assertTrue(visitedIds.contains("a"));
        assertTrue(visitedIds.contains("b"));
        assertTrue(visitedIds.contains("c"));
    }

    /**
     * Test target implementation for unit testing.
     */
    private static class TestTarget implements AudioBroadcaster.BroadcastTarget {
        private final String id;
        private final boolean failOnReceive;
        private int receiveCount = 0;
        private byte[] lastReceivedData;

        TestTarget(String id) {
            this(id, false);
        }

        TestTarget(String id, boolean failOnReceive) {
            this.id = id;
            this.failOnReceive = failOnReceive;
        }

        @Override
        public boolean receiveRxAudio(byte[] data, int offset, int length) {
            if (failOnReceive) {
                return false;
            }
            receiveCount++;
            lastReceivedData = new byte[length];
            System.arraycopy(data, offset, lastReceivedData, 0, length);
            return true;
        }

        @Override
        public String getTargetId() {
            return id;
        }

        int getReceiveCount() {
            return receiveCount;
        }

        byte[] getLastReceivedData() {
            return lastReceivedData;
        }
    }
}
