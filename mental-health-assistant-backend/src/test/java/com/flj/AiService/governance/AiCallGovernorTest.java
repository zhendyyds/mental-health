package com.flj.AiService.governance;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AiCallGovernorTest {
    @Test
    void initializationFailureRefundsReservationAndCloseIsIdempotent() {
        AiProtectionProperties limits = defaults();
        limits.setMaxConcurrent(1);
        limits.setUserDailyCalls(1);
        limits.setGlobalDailyCalls(1);
        AiCallGovernor governor = new AiCallGovernor(limits);
        AiCallGovernor.Lease first = governor.acquire(1L, "consultation");
        assertEquals("429", assertThrows(AiCallRejectedException.class,
                () -> governor.acquire(2L, "diary-analysis")).getCode());
        first.close();
        first.close();
        try (var next = governor.acquire(1L, "consultation-analysis")) {
            next.validateInput("system", "question");
            next.markStarted();
        }
        assertEquals("429", assertThrows(AiCallRejectedException.class,
                () -> governor.acquire(2L, "consultation")).getCode());
    }

    @Test
    void dispatchedFailureStillConsumesBudgetAcrossScenes() {
        AiProtectionProperties limits = defaults();
        limits.setUserDailyCalls(1);
        AiCallGovernor governor = new AiCallGovernor(limits);
        try (var lease = governor.acquire(1L, "consultation")) { lease.markStarted(); }
        AiCallRejectedException error = assertThrows(AiCallRejectedException.class,
                () -> governor.acquire(1L, "diary-analysis"));
        assertEquals("429", error.getCode());
        assertNotNull(error.getRequestId());
        assertTrue(error.getRetryAfterSeconds() > 0);
        try (var otherUser = governor.acquire(2L, "consultation-analysis")) { otherUser.markStarted(); }
    }

    @Test
    void rateWindowIsSharedByScenesAndHasDeterministicRetryAfter() {
        AiProtectionProperties limits = defaults();
        limits.setUserRequestsPerMinute(1);
        MutableClock clock = new MutableClock("2026-11-06T04:00:10Z");
        AiCallGovernor governor = new AiCallGovernor(limits, clock);
        try (var lease = governor.acquire(1L, "consultation")) { lease.markStarted(); }
        AiCallRejectedException error = assertThrows(AiCallRejectedException.class,
                () -> governor.acquire(1L, "diary-analysis"));
        assertEquals(50, error.getRetryAfterSeconds());
        clock.set("2026-11-06T04:01:00Z");
        try (var lease = governor.acquire(1L, "diary-analysis")) { lease.markStarted(); }
    }

    @Test
    void shanghaiMidnightResetsBudgetButKeepsActualRunningCapacity() {
        AiProtectionProperties limits = defaults();
        limits.setMaxConcurrent(1);
        limits.setGlobalDailyCalls(1);
        MutableClock clock = new MutableClock("2026-11-05T15:59:59Z");
        AiCallGovernor governor = new AiCallGovernor(limits, clock);
        var running = governor.acquire(1L, "consultation");
        running.markStarted();
        clock.set("2026-11-05T16:00:00Z");
        assertThrows(AiCallRejectedException.class, () -> governor.acquire(2L, "diary-analysis"));
        running.close();
        try (var nextDay = governor.acquire(2L, "diary-analysis")) { nextDay.markStarted(); }
        assertThrows(AiCallRejectedException.class, () -> governor.acquire(3L, "consultation"));
    }

    @Test
    void refundOfOldDayReservationCannotRefundNewDayBudget() {
        AiProtectionProperties limits = defaults();
        limits.setGlobalDailyCalls(1);
        MutableClock clock = new MutableClock("2026-11-05T15:59:59Z");
        AiCallGovernor governor = new AiCallGovernor(limits, clock);
        var old = governor.acquire(1L, "consultation");
        clock.set("2026-11-05T16:00:00Z");
        var nextDay = governor.acquire(2L, "diary-analysis");
        nextDay.markStarted();
        old.close();
        assertThrows(AiCallRejectedException.class, () -> governor.acquire(3L, "consultation"));
        nextDay.close();
    }

    @Test
    void inputBudgetIncludesSystemHistoryQuestionAndProtocolReserve() {
        AiProtectionProperties limits = defaults();
        limits.setMaxInputUnits(8);
        limits.setReservedInputUnits(2);
        limits.setGlobalDailyCalls(1);
        AiCallGovernor governor = new AiCallGovernor(limits);
        assertEquals(8, AiCallGovernor.utf8Bytes("a中😀"));
        try (var lease = governor.acquire(1L, "consultation")) {
            lease.validateInput("a中", "ab"); // 4 + 2 + reserved 2 equals the boundary.
            assertEquals("400", assertThrows(AiCallRejectedException.class,
                    () -> lease.validateInput("a中", "ab", "!")).getCode());
        }
        try (var refunded = governor.acquire(1L, "consultation")) { refunded.validateInput("ok"); }
    }

    @Test
    void closedLeaseCannotDispatchOrValidate() {
        AiCallGovernor governor = new AiCallGovernor(defaults());
        var lease = governor.acquire(1L, "consultation");
        lease.close();
        assertThrows(IllegalStateException.class, lease::markStarted);
        assertThrows(IllegalStateException.class, () -> lease.validateInput("text"));
    }

    @Test
    void activeCapacitySurvivesRepeatedCloseOfAnotherLease() {
        AiProtectionProperties limits = defaults();
        limits.setMaxConcurrent(2);
        AiCallGovernor governor = new AiCallGovernor(limits);
        var first = governor.acquire(1L, "consultation");
        var second = governor.acquire(2L, "consultation");
        first.close();
        first.close();
        try (var third = governor.acquire(3L, "consultation")) {
            assertThrows(AiCallRejectedException.class, () -> governor.acquire(4L, "consultation"));
        }
        second.close();
    }

    @Test
    void userTrackingIsBoundedAndDoesNotEvictDailyUsage() {
        AiProtectionProperties limits = defaults();
        limits.setMaxTrackedUsers(1);
        AiCallGovernor governor = new AiCallGovernor(limits);
        try (var lease = governor.acquire(1L, "consultation")) { lease.markStarted(); }
        assertThrows(AiCallRejectedException.class, () -> governor.acquire(2L, "diary-analysis"));
        try (var existing = governor.acquire(1L, "consultation-analysis")) { existing.markStarted(); }
    }

    @Test
    void concurrentAdmissionNeverExceedsGlobalCapacity() throws Exception {
        AiProtectionProperties limits = defaults();
        limits.setMaxConcurrent(4);
        AiCallGovernor governor = new AiCallGovernor(limits);
        var executor = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(24);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (long userId = 1; userId <= 24; userId++) {
                long id = userId;
                futures.add(executor.submit(() -> {
                    try {
                        assertTrue(start.await(3, TimeUnit.SECONDS));
                        AiCallGovernor.Lease lease;
                        try { lease = governor.acquire(id, "consultation"); }
                        catch (AiCallRejectedException ignored) { attempted.countDown(); return; }
                        try (lease) {
                            admitted.incrementAndGet();
                            attempted.countDown();
                            assertTrue(release.await(3, TimeUnit.SECONDS));
                        }
                    } catch (InterruptedException error) { throw new AssertionError(error); }
                }));
            }
            start.countDown();
            assertTrue(attempted.await(3, TimeUnit.SECONDS));
            assertEquals(4, admitted.get());
            release.countDown();
            for (Future<?> future : futures) future.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        try (var after = governor.acquire(25L, "diary-analysis")) { after.markStarted(); }
    }

    @Test
    void invalidLimitsAndMissingIdentityAreRejected() {
        AiProtectionProperties limits = defaults();
        limits.setReservedInputUnits(limits.getMaxInputUnits());
        assertThrows(IllegalArgumentException.class, () -> new AiCallGovernor(limits));
        AiCallGovernor governor = new AiCallGovernor(defaults());
        assertThrows(AiCallRejectedException.class, () -> governor.acquire(null, "consultation"));
        assertThrows(AiCallRejectedException.class, () -> governor.acquire(1L, ""));
    }

    private static AiProtectionProperties defaults() { return new AiProtectionProperties(); }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(String instant) { set(instant); }
        private void set(String value) { instant = Instant.parse(value); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
