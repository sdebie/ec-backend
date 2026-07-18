package org.ecommerce.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EnquiryRateLimiter}.
 * <p>
 * Tests the four core properties:
 * <ul>
 *   <li>Under-limit requests are allowed</li>
 *   <li>Over-limit requests are blocked</li>
 *   <li>Window reset re-allows requests</li>
 *   <li>Distinct IPs are isolated from each other</li>
 * </ul>
 *
 * Validates: Requirements 3.3
 */
class EnquiryRateLimiterTest {

    private EnquiryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new EnquiryRateLimiter();
        // Set config properties directly (package-visible fields)
        rateLimiter.maxRequests = 5;
        rateLimiter.windowSeconds = 3600; // 1 hour
    }

    // ── Under-limit requests are allowed ────────────────────────────────────

    @Test
    void isAllowed_shouldAllowFirstRequest() {
        assertTrue(rateLimiter.isAllowed("192.168.1.1"));
    }

    @Test
    void isAllowed_shouldAllowRequestsUpToLimit() {
        String ip = "10.0.0.1";
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.isAllowed(ip),
                    "Request " + (i + 1) + " should be allowed (within limit of 5)");
        }
    }

    @Test
    void isAllowed_shouldAllowExactlyMaxRequests() {
        String ip = "10.0.0.2";
        rateLimiter.maxRequests = 3;

        assertTrue(rateLimiter.isAllowed(ip));  // 1
        assertTrue(rateLimiter.isAllowed(ip));  // 2
        assertTrue(rateLimiter.isAllowed(ip));  // 3
    }

    // ── Over-limit requests are blocked ─────────────────────────────────────

    @Test
    void isAllowed_shouldBlockWhenLimitExceeded() {
        String ip = "10.0.0.3";
        rateLimiter.maxRequests = 3;

        assertTrue(rateLimiter.isAllowed(ip));   // 1
        assertTrue(rateLimiter.isAllowed(ip));   // 2
        assertTrue(rateLimiter.isAllowed(ip));   // 3
        assertFalse(rateLimiter.isAllowed(ip));  // 4 — over limit
    }

    @Test
    void isAllowed_shouldKeepBlockingAfterLimitExceeded() {
        String ip = "10.0.0.4";
        rateLimiter.maxRequests = 2;

        assertTrue(rateLimiter.isAllowed(ip));   // 1
        assertTrue(rateLimiter.isAllowed(ip));   // 2
        assertFalse(rateLimiter.isAllowed(ip));  // 3 — blocked
        assertFalse(rateLimiter.isAllowed(ip));  // 4 — still blocked
        assertFalse(rateLimiter.isAllowed(ip));  // 5 — still blocked
    }

    // ── Window reset re-allows requests ─────────────────────────────────────

    @Test
    void isAllowed_shouldReAllowAfterWindowResets() throws Exception {
        String ip = "10.0.0.5";
        rateLimiter.maxRequests = 2;
        rateLimiter.windowSeconds = 60; // 60-second window

        // Exhaust the limit
        assertTrue(rateLimiter.isAllowed(ip));   // 1
        assertTrue(rateLimiter.isAllowed(ip));   // 2
        assertFalse(rateLimiter.isAllowed(ip));  // 3 — blocked

        // Simulate window expiry by backdating the bucket's windowStart
        backdateBucket(ip, 61_000); // 61 seconds ago — beyond the 60s window

        // Next request should start a new window and be allowed
        assertTrue(rateLimiter.isAllowed(ip));
    }

    @Test
    void isAllowed_shouldNotResetBeforeWindowExpires() throws Exception {
        String ip = "10.0.0.6";
        rateLimiter.maxRequests = 2;
        rateLimiter.windowSeconds = 60;

        // Exhaust the limit
        assertTrue(rateLimiter.isAllowed(ip));
        assertTrue(rateLimiter.isAllowed(ip));
        assertFalse(rateLimiter.isAllowed(ip));

        // Backdate by 30 seconds — still within the 60s window
        backdateBucket(ip, 30_000);

        // Should still be blocked
        assertFalse(rateLimiter.isAllowed(ip));
    }

    // ── Distinct IPs are isolated ───────────────────────────────────────────

    @Test
    void isAllowed_shouldTrackDifferentIPsIndependently() {
        String ip1 = "192.168.1.100";
        String ip2 = "192.168.1.200";
        rateLimiter.maxRequests = 2;

        // Exhaust ip1's limit
        assertTrue(rateLimiter.isAllowed(ip1));
        assertTrue(rateLimiter.isAllowed(ip1));
        assertFalse(rateLimiter.isAllowed(ip1));

        // ip2 should be unaffected
        assertTrue(rateLimiter.isAllowed(ip2));
        assertTrue(rateLimiter.isAllowed(ip2));
    }

    @Test
    void isAllowed_shouldNotCrossContaminateBetweenIPs() {
        rateLimiter.maxRequests = 3;

        String ipA = "10.1.1.1";
        String ipB = "10.2.2.2";
        String ipC = "10.3.3.3";

        // Use 2 of ipA's budget
        assertTrue(rateLimiter.isAllowed(ipA));
        assertTrue(rateLimiter.isAllowed(ipA));

        // Use all of ipB's budget
        assertTrue(rateLimiter.isAllowed(ipB));
        assertTrue(rateLimiter.isAllowed(ipB));
        assertTrue(rateLimiter.isAllowed(ipB));
        assertFalse(rateLimiter.isAllowed(ipB));

        // ipA still has 1 left
        assertTrue(rateLimiter.isAllowed(ipA));
        assertFalse(rateLimiter.isAllowed(ipA));

        // ipC is completely fresh
        assertTrue(rateLimiter.isAllowed(ipC));
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    /**
     * Backdates the WindowBucket for a given IP by manipulating the internal
     * ConcurrentHashMap via reflection. This simulates time passing without
     * requiring Thread.sleep (which would make tests slow and flaky).
     */
    @SuppressWarnings("unchecked")
    private void backdateBucket(String ip, long millisToBackdate) throws Exception {
        Field bucketsField = EnquiryRateLimiter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        ConcurrentHashMap<String, Object> buckets =
                (ConcurrentHashMap<String, Object>) bucketsField.get(rateLimiter);

        Object bucket = buckets.get(ip);
        assertNotNull(bucket, "No bucket found for IP: " + ip);

        Field windowStartField = bucket.getClass().getDeclaredField("windowStart");
        windowStartField.setAccessible(true);

        // WindowBucket.windowStart is final, so we need to bypass the final modifier
        // We'll replace the bucket entirely with a new one that has the backdated start
        long currentStart = windowStartField.getLong(bucket);
        long newStart = currentStart - millisToBackdate;

        // Get the count from the existing bucket
        Field countField = bucket.getClass().getDeclaredField("count");
        countField.setAccessible(true);
        AtomicInteger count = (AtomicInteger) countField.get(bucket);

        // Create a new bucket with the backdated windowStart using the inner class constructor
        var constructor = bucket.getClass().getDeclaredConstructor(long.class, AtomicInteger.class);
        constructor.setAccessible(true);
        Object newBucket = constructor.newInstance(newStart, count);

        buckets.put(ip, newBucket);
    }
}
