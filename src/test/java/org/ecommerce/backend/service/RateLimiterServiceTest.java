package org.ecommerce.backend.service;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RateLimiterService}.
 * <p>
 * Covers: allowance under the limit, denial over the limit, window rollover restoring
 * allowance, per-(name, key) isolation, retry-after calculation, the expired-bucket sweep,
 * and "unknown" key still limited.
 *
 */
class RateLimiterServiceTest
{
    private TestableRateLimiterService service;
    private Config config;

    @BeforeEach
    void setUp() throws Exception
    {
        config = mock(Config.class);
        // Default: no config overrides — fall back to code defaults
        when(config.getOptionalValue(anyString(), any())).thenReturn(Optional.empty());

        service = new TestableRateLimiterService();
        // Inject mock config via reflection (CDI field injection)
        Field configField = RateLimiterService.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(service, config);
    }

    // ── Allowance under the limit ───────────────────────────────────────────

    @Test
    void check_shouldAllowFirstRequest()
    {
        RateLimitDecision decision = service.check("test", "192.168.1.1", 5, 3600);
        assertTrue(decision.allowed());
        assertEquals(0, decision.retryAfterSeconds());
    }

    @Test
    void check_shouldAllowRequestsUpToLimit()
    {
        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = service.check("test", "10.0.0.1", 5, 3600);
            assertTrue(decision.allowed(),
                    "Request " + (i + 1) + " should be allowed (within limit of 5)");
        }
    }

    @Test
    void check_shouldAllowExactlyMaxRequests()
    {
        for (int i = 0; i < 3; i++) {
            assertTrue(service.check("test", "10.0.0.2", 3, 60).allowed());
        }
    }

    // ── Denial over the limit ───────────────────────────────────────────────

    @Test
    void check_shouldDenyWhenLimitExceeded()
    {
        for (int i = 0; i < 3; i++) {
            service.check("test", "10.0.0.3", 3, 60);
        }
        RateLimitDecision decision = service.check("test", "10.0.0.3", 3, 60);
        assertFalse(decision.allowed());
    }

    @Test
    void check_shouldKeepDenyingAfterLimitExceeded()
    {
        for (int i = 0; i < 2; i++) {
            service.check("test", "10.0.0.4", 2, 60);
        }
        assertFalse(service.check("test", "10.0.0.4", 2, 60).allowed());
        assertFalse(service.check("test", "10.0.0.4", 2, 60).allowed());
        assertFalse(service.check("test", "10.0.0.4", 2, 60).allowed());
    }

    // ── Window rollover restoring allowance ─────────────────────────────────

    @Test
    void check_shouldReAllowAfterWindowExpires()
    {
        // Exhaust the limit
        for (int i = 0; i < 3; i++) {
            service.check("test", "10.0.0.5", 3, 60);
        }
        assertFalse(service.check("test", "10.0.0.5", 3, 60).allowed());

        // Advance time past the window
        service.advanceTime(61_000);

        // Should be allowed again
        RateLimitDecision decision = service.check("test", "10.0.0.5", 3, 60);
        assertTrue(decision.allowed());
    }

    @Test
    void check_shouldNotResetBeforeWindowExpires()
    {
        for (int i = 0; i < 2; i++) {
            service.check("test", "10.0.0.6", 2, 60);
        }
        assertFalse(service.check("test", "10.0.0.6", 2, 60).allowed());

        // Only 30 seconds pass — still within the 60s window
        service.advanceTime(30_000);

        assertFalse(service.check("test", "10.0.0.6", 2, 60).allowed());
    }

    // ── Per-(name, key) isolation ───────────────────────────────────────────

    @Test
    void check_shouldIsolateDifferentKeys()
    {
        String key1 = "192.168.1.100";
        String key2 = "192.168.1.200";

        // Exhaust key1
        for (int i = 0; i < 2; i++) {
            service.check("login", key1, 2, 60);
        }
        assertFalse(service.check("login", key1, 2, 60).allowed());

        // key2 is unaffected
        assertTrue(service.check("login", key2, 2, 60).allowed());
        assertTrue(service.check("login", key2, 2, 60).allowed());
    }

    @Test
    void check_shouldIsolateDifferentNames()
    {
        String key = "10.0.0.1";

        // Exhaust "enquiry" limit
        for (int i = 0; i < 2; i++) {
            service.check("enquiry", key, 2, 60);
        }
        assertFalse(service.check("enquiry", key, 2, 60).allowed());

        // "login" limiter for the same key is unaffected
        assertTrue(service.check("login", key, 2, 60).allowed());
        assertTrue(service.check("login", key, 2, 60).allowed());
    }

    @Test
    void check_shouldNotCrossContaminateBetweenNamesAndKeys()
    {
        // Use 2 of enquiry/ipA budget
        assertTrue(service.check("enquiry", "ipA", 3, 60).allowed());
        assertTrue(service.check("enquiry", "ipA", 3, 60).allowed());

        // Exhaust login/ipB
        for (int i = 0; i < 2; i++) {
            service.check("login", "ipB", 2, 60);
        }
        assertFalse(service.check("login", "ipB", 2, 60).allowed());

        // enquiry/ipA still has 1 left
        assertTrue(service.check("enquiry", "ipA", 3, 60).allowed());
        assertFalse(service.check("enquiry", "ipA", 3, 60).allowed());

        // login/ipA is fresh
        assertTrue(service.check("login", "ipA", 2, 60).allowed());
    }

    // ── Retry-after calculation ─────────────────────────────────────────────

    @Test
    void check_shouldReturnRetryAfterOnDenial()
    {
        // Window is 60 seconds
        for (int i = 0; i < 3; i++) {
            service.check("test", "ip1", 3, 60);
        }

        // Advance 20 seconds into the window
        service.advanceTime(20_000);

        RateLimitDecision decision = service.check("test", "ip1", 3, 60);
        assertFalse(decision.allowed());
        // 60 - 20 = 40 seconds remaining
        assertEquals(40, decision.retryAfterSeconds());
    }

    @Test
    void check_shouldReturnMinimumOneSecondRetryAfter()
    {
        // Window is 2 seconds
        for (int i = 0; i < 2; i++) {
            service.check("test", "ip2", 2, 2);
        }

        // Advance to nearly the end of the window (1900ms)
        service.advanceTime(1_900);

        RateLimitDecision decision = service.check("test", "ip2", 2, 2);
        assertFalse(decision.allowed());
        // 2 - 1 (1900ms / 1000 = 1) = 1 second remaining
        assertTrue(decision.retryAfterSeconds() >= 1,
                "retryAfterSeconds should be at least 1, was: " + decision.retryAfterSeconds());
    }

    @Test
    void check_shouldReturnZeroRetryAfterWhenAllowed()
    {
        RateLimitDecision decision = service.check("test", "ip3", 5, 60);
        assertTrue(decision.allowed());
        assertEquals(0, decision.retryAfterSeconds());
    }

    // ── Expired-bucket sweep ────────────────────────────────────────────────

    @Test
    void check_shouldSweepExpiredBucketsWhenThresholdExceeded() throws Exception
    {
        // Fill the map beyond CLEANUP_THRESHOLD with expired buckets
        injectExpiredBuckets();

        // Next check triggers the sweep
        service.check("test", "sweep-trigger", 5, 60);

        // Expired buckets should have been removed; only the new one remains
        assertTrue(service.bucketCount() <= 2,
                "Expected most expired buckets to be swept, but bucketCount=" + service.bucketCount());
    }

    @Test
    void check_shouldNotSweepActiveBuckets() throws Exception
    {
        // Fill with active buckets (recent windowStart, long window)
        injectActiveBuckets();

        // Next check triggers sweep logic but active buckets should survive
        service.check("test", "sweep-active-trigger", 5, 3600);

        // Active buckets + the new one should all remain
        assertTrue(service.bucketCount() > RateLimiterService.CLEANUP_THRESHOLD,
                "Active buckets should not be swept, but bucketCount=" + service.bucketCount());
    }

    // ── "unknown" key still limited ─────────────────────────────────────────

    @Test
    void check_shouldLimitUnknownKey()
    {
        for (int i = 0; i < 3; i++) {
            assertTrue(service.check("login", "unknown", 3, 60).allowed());
        }
        assertFalse(service.check("login", "unknown", 3, 60).allowed());
    }

    // ── Key masking for denial logs (no plaintext PII) ──────────────────────

    @Test
    void maskKey_shouldMaskEmailLocalPart()
    {
        assertEquals("c***@example.com", RateLimiterService.maskKey("customer@example.com"));
    }

    @Test
    void maskKey_shouldMaskSingleCharacterLocalPart()
    {
        assertEquals("a***@test.com", RateLimiterService.maskKey("a@test.com"));
    }

    @Test
    void maskKey_shouldMaskEmptyLocalPart()
    {
        assertEquals("***@test.com", RateLimiterService.maskKey("@test.com"));
    }

    @Test
    void maskKey_shouldPassThroughIpKeys()
    {
        assertEquals("192.168.1.1", RateLimiterService.maskKey("192.168.1.1"));
        assertEquals("unknown", RateLimiterService.maskKey("unknown"));
        assertNull(RateLimiterService.maskKey(null));
    }

    // ── Config override ─────────────────────────────────────────────────────

    @Test
    void check_shouldUseConfigOverrideForMax()
    {
        when(config.getOptionalValue("ratelimit.enquiry.max", Integer.class))
                .thenReturn(Optional.of(2));

        // Code default is 5, but config says 2
        assertTrue(service.check("enquiry", "ip-cfg", 5, 60).allowed());   // 1
        assertTrue(service.check("enquiry", "ip-cfg", 5, 60).allowed());   // 2
        assertFalse(service.check("enquiry", "ip-cfg", 5, 60).allowed());  // 3 — denied by config override
    }

    @Test
    void check_shouldUseConfigOverrideForWindow()
    {
        when(config.getOptionalValue("ratelimit.fast.window-seconds", Long.class))
                .thenReturn(Optional.of(10L));

        // Code default window is 3600, config override is 10 seconds
        for (int i = 0; i < 3; i++) {
            service.check("fast", "ip-cfg-win", 3, 3600);
        }
        assertFalse(service.check("fast", "ip-cfg-win", 3, 3600).allowed());

        // Advance 11 seconds — should reset with the config window of 10s
        service.advanceTime(11_000);
        assertTrue(service.check("fast", "ip-cfg-win", 3, 3600).allowed());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Injects expired buckets directly into the map via reflection.
     */
    @SuppressWarnings("unchecked")
    private void injectExpiredBuckets() throws Exception
    {
        Field bucketsField = RateLimiterService.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        ConcurrentHashMap<String, RateLimiterService.WindowBucket> map =
                (ConcurrentHashMap<String, RateLimiterService.WindowBucket>) bucketsField.get(service);

        long expiredStart = service.currentTimeMillis() - ((long) 60 * 1000 + 1000); // 1s past expiry
        for (int i = 0; i < 10001; i++) {
            map.put("expired:" + i, new RateLimiterService.WindowBucket(
                    expiredStart, new AtomicInteger(1), 60));
        }
    }

    /**
     * Injects active (non-expired) buckets directly into the map via reflection.
     */
    @SuppressWarnings("unchecked")
    private void injectActiveBuckets() throws Exception
    {
        Field bucketsField = RateLimiterService.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        ConcurrentHashMap<String, RateLimiterService.WindowBucket> map =
                (ConcurrentHashMap<String, RateLimiterService.WindowBucket>) bucketsField.get(service);

        long recentStart = service.currentTimeMillis() - 1000; // 1 second ago — well within any window
        for (int i = 0; i < 10001; i++) {
            map.put("active:" + i, new RateLimiterService.WindowBucket(
                    recentStart, new AtomicInteger(1), 3600));
        }
    }

    /**
     * Testable subclass that lets us control time without Thread.sleep.
     */
    private static class TestableRateLimiterService extends RateLimiterService
    {
        private long timeOffset = 0;

        @Override
        long currentTimeMillis()
        {
            return System.currentTimeMillis() + timeOffset;
        }

        void advanceTime(long millis)
        {
            timeOffset += millis;
        }
    }
}
