package org.ecommerce.backend.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link RateLimiterService} against a real (Dev Services) Redis and the real
 * MicroProfile {@code Config}. {@code Config} turned out not to be mockable via
 * {@code @InjectMock} here — Quarkus rejects it: the bean is {@code @Dependent}-scoped,
 * not a CDI normal scope, which {@code @InjectMock}'s proxy-swap requires.
 * <p>
 * Per-test config control instead uses JVM system properties, the mechanism this
 * codebase already established outranks {@code %test.*}
 * ({@code SysPropConfigSource} ordinal 400 beats {@code PropertiesConfigSource} 250).
 * That precedence cuts both ways here: {@code %test.ratelimit.default.max=100000} /
 * {@code .window-seconds=2} (application.properties) are real and active for every
 * limiter name that has no override of its own — there is no way to make a real
 * {@code Config} resolve a key to "absent" once any source defines it, only to
 * override it to something else. So every test below pins its OWN limiter name's
 * {@code ratelimit.<name>.max}/{@code .window-seconds} explicitly to the exact
 * values it means to exercise via {@link #pinLimit}, rather than relying on no
 * shared default existing. The two tests about the shared-default mechanism itself
 * are the exception: they deliberately set {@code ratelimit.default.*} instead, using
 * a limiter name nothing else in this class ever touches.
 * <p>
 * Redis is flushed before every test for the same isolation the old fresh-map-per-test
 * setup gave for free. Covers: allowance under the limit, denial over the limit,
 * window rollover restoring allowance, per-(name, key) isolation, retry-after
 * calculation, expiry set once per window (not per request), and "unknown" key still
 * limited.
 */
@QuarkusTest
class RateLimiterServiceTest
{
    @Inject
    RateLimiterService service;

    @Inject
    RedisDataSource redisDataSource;

    private final Set<String> pinnedPropertyKeys = new HashSet<>();

    @BeforeEach
    void setUp()
    {
        redisDataSource.flushall();
    }

    @AfterEach
    void tearDown()
    {
        pinnedPropertyKeys.forEach(System::clearProperty);
        pinnedPropertyKeys.clear();
    }

    /**
     * Pins a limiter name's max/window via system properties, so the per-limiter
     * override (highest precedence) governs regardless of what
     * {@code ratelimit.default.*} happens to resolve to in this environment.
     */
    private void pinLimit(String name, int max, long windowSeconds)
    {
        String maxKey = "ratelimit." + name + ".max";
        String windowKey = "ratelimit." + name + ".window-seconds";
        System.setProperty(maxKey, String.valueOf(max));
        System.setProperty(windowKey, String.valueOf(windowSeconds));
        pinnedPropertyKeys.add(maxKey);
        pinnedPropertyKeys.add(windowKey);
    }

    private void pinSharedDefault(int max, long windowSeconds)
    {
        System.setProperty("ratelimit.default.max", String.valueOf(max));
        System.setProperty("ratelimit.default.window-seconds", String.valueOf(windowSeconds));
        pinnedPropertyKeys.add("ratelimit.default.max");
        pinnedPropertyKeys.add("ratelimit.default.window-seconds");
    }

    // ── Allowance under the limit ───────────────────────────────────────────

    @Test
    void check_shouldAllowFirstRequest()
    {
        pinLimit("test", 5, 3600);
        RateLimitDecision decision = service.check("test", "192.168.1.1", 5, 3600);
        assertTrue(decision.allowed());
        assertEquals(0, decision.retryAfterSeconds());
    }

    @Test
    void check_shouldAllowRequestsUpToLimit()
    {
        pinLimit("test", 5, 3600);
        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = service.check("test", "10.0.0.1", 5, 3600);
            assertTrue(decision.allowed(),
                    "Request " + (i + 1) + " should be allowed (within limit of 5)");
        }
    }

    @Test
    void check_shouldAllowExactlyMaxRequests()
    {
        pinLimit("test", 3, 60);
        for (int i = 0; i < 3; i++) {
            assertTrue(service.check("test", "10.0.0.2", 3, 60).allowed());
        }
    }

    // ── Denial over the limit ───────────────────────────────────────────────

    @Test
    void check_shouldDenyWhenLimitExceeded()
    {
        pinLimit("test", 3, 60);
        for (int i = 0; i < 3; i++) {
            service.check("test", "10.0.0.3", 3, 60);
        }
        RateLimitDecision decision = service.check("test", "10.0.0.3", 3, 60);
        assertFalse(decision.allowed());
    }

    @Test
    void check_shouldKeepDenyingAfterLimitExceeded()
    {
        pinLimit("test", 2, 60);
        for (int i = 0; i < 2; i++) {
            service.check("test", "10.0.0.4", 2, 60);
        }
        assertFalse(service.check("test", "10.0.0.4", 2, 60).allowed());
        assertFalse(service.check("test", "10.0.0.4", 2, 60).allowed());
        assertFalse(service.check("test", "10.0.0.4", 2, 60).allowed());
    }

    // ── Window rollover restoring allowance ─────────────────────────────────
    // Redis owns the clock now (its own TTL, not a Java-side timestamp we can
    // fake-advance) — these use short real windows and a short real sleep
    // instead of the old instant time-travel.

    @Test
    void check_shouldReAllowAfterWindowExpires() throws InterruptedException
    {
        pinLimit("test", 2, 1);
        for (int i = 0; i < 2; i++) {
            service.check("test", "10.0.0.5", 2, 1);
        }
        assertFalse(service.check("test", "10.0.0.5", 2, 1).allowed());

        Thread.sleep(1100);

        assertTrue(service.check("test", "10.0.0.5", 2, 1).allowed());
    }

    @Test
    void check_shouldNotResetBeforeWindowExpires() throws InterruptedException
    {
        pinLimit("test", 2, 5);
        for (int i = 0; i < 2; i++) {
            service.check("test", "10.0.0.6", 2, 5);
        }
        assertFalse(service.check("test", "10.0.0.6", 2, 5).allowed());

        // Well short of the 5-second window
        Thread.sleep(300);

        assertFalse(service.check("test", "10.0.0.6", 2, 5).allowed());
    }

    // ── Per-(name, key) isolation ───────────────────────────────────────────

    @Test
    void check_shouldIsolateDifferentKeys()
    {
        pinLimit("login", 2, 60);
        String key1 = "192.168.1.100";
        String key2 = "192.168.1.200";

        for (int i = 0; i < 2; i++) {
            service.check("login", key1, 2, 60);
        }
        assertFalse(service.check("login", key1, 2, 60).allowed());

        assertTrue(service.check("login", key2, 2, 60).allowed());
        assertTrue(service.check("login", key2, 2, 60).allowed());
    }

    @Test
    void check_shouldIsolateDifferentNames()
    {
        pinLimit("enquiry", 2, 60);
        pinLimit("login", 2, 60);
        String key = "10.0.0.1";

        for (int i = 0; i < 2; i++) {
            service.check("enquiry", key, 2, 60);
        }
        assertFalse(service.check("enquiry", key, 2, 60).allowed());

        assertTrue(service.check("login", key, 2, 60).allowed());
        assertTrue(service.check("login", key, 2, 60).allowed());
    }

    @Test
    void check_shouldNotCrossContaminateBetweenNamesAndKeys()
    {
        pinLimit("enquiry", 3, 60);
        pinLimit("login", 2, 60);

        assertTrue(service.check("enquiry", "ipA", 3, 60).allowed());
        assertTrue(service.check("enquiry", "ipA", 3, 60).allowed());

        for (int i = 0; i < 2; i++) {
            service.check("login", "ipB", 2, 60);
        }
        assertFalse(service.check("login", "ipB", 2, 60).allowed());

        assertTrue(service.check("enquiry", "ipA", 3, 60).allowed());
        assertFalse(service.check("enquiry", "ipA", 3, 60).allowed());

        assertTrue(service.check("login", "ipA", 2, 60).allowed());
    }

    // ── Retry-after calculation ─────────────────────────────────────────────

    @Test
    void check_shouldReturnRetryAfterOnDenial() throws InterruptedException
    {
        pinLimit("test", 3, 3);
        for (int i = 0; i < 3; i++) {
            service.check("test", "ip1", 3, 3);
        }

        Thread.sleep(1000);

        RateLimitDecision decision = service.check("test", "ip1", 3, 3);
        assertFalse(decision.allowed());
        // Roughly 2 seconds remain of the 3-second window; assert a tolerant
        // range rather than an exact figure, since real elapsed time jitters.
        assertTrue(decision.retryAfterSeconds() >= 1 && decision.retryAfterSeconds() <= 3,
                "expected 1-3 seconds remaining, was: " + decision.retryAfterSeconds());
    }

    @Test
    void clampRetryAfter_shouldReturnMinimumOneSecond()
    {
        // Redis's TTL truncates to whole seconds, so a key a heartbeat from expiry
        // reports 0 — decoupled here from any real timing so it's exact and instant.
        assertEquals(1, RateLimiterService.clampRetryAfter(0));
        assertEquals(1, RateLimiterService.clampRetryAfter(1));
        assertEquals(5, RateLimiterService.clampRetryAfter(5));
    }

    @Test
    void check_shouldReturnZeroRetryAfterWhenAllowed()
    {
        pinLimit("test", 5, 60);
        RateLimitDecision decision = service.check("test", "ip3", 5, 60);
        assertTrue(decision.allowed());
        assertEquals(0, decision.retryAfterSeconds());
    }

    // ── Expiry set once per window, not per request ─────────────────────────

    @Test
    void check_setsExpiryOnlyOnFirstRequestInWindow() throws InterruptedException
    {
        pinLimit("test", 10, 60);
        service.check("test", "10.0.0.9", 10, 60);
        long ttlAfterFirst = redisDataSource.key(String.class).ttl("test:10.0.0.9");
        assertTrue(ttlAfterFirst > 0 && ttlAfterFirst <= 60,
                "expected a fresh TTL in (0, 60], was: " + ttlAfterFirst);

        Thread.sleep(1100);

        service.check("test", "10.0.0.9", 10, 60);
        long ttlAfterSecond = redisDataSource.key(String.class).ttl("test:10.0.0.9");
        // A second request in the same window must not push the expiry back out —
        // it should have counted down, not reset to a fresh ~60.
        assertTrue(ttlAfterSecond <= ttlAfterFirst,
                "second request in the same window must not extend its TTL: first="
                        + ttlAfterFirst + " second=" + ttlAfterSecond);
    }

    // ── "unknown" key still limited ─────────────────────────────────────────

    @Test
    void check_shouldLimitUnknownKey()
    {
        pinLimit("login", 3, 60);
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
        System.setProperty("ratelimit.enquiry.max", "2");
        pinnedPropertyKeys.add("ratelimit.enquiry.max");

        // Code default is 5, but config says 2
        assertTrue(service.check("enquiry", "ip-cfg", 5, 60).allowed());   // 1
        assertTrue(service.check("enquiry", "ip-cfg", 5, 60).allowed());   // 2
        assertFalse(service.check("enquiry", "ip-cfg", 5, 60).allowed());  // 3 — denied by config override
    }

    @Test
    void check_shouldUseConfigOverrideForWindow() throws InterruptedException
    {
        // Max is pinned too — otherwise the real %test shared default (100000)
        // would win over the code default of 3 and this could never deny.
        pinLimit("fast", 3, 3600);
        System.setProperty("ratelimit.fast.window-seconds", "1");

        // Code default window is 3600, config override is 1 second
        for (int i = 0; i < 3; i++) {
            service.check("fast", "ip-cfg-win", 3, 3600);
        }
        assertFalse(service.check("fast", "ip-cfg-win", 3, 3600).allowed());

        Thread.sleep(1100);
        assertTrue(service.check("fast", "ip-cfg-win", 3, 3600).allowed());
    }

    // ── Shared "ratelimit.default.*" fallback ────────────────────────────────
    // A new limiter should not have to add its own config just to avoid
    // interfering with unrelated tests — see application.properties' comment on
    // this key. Resolution order: per-limiter override → shared default → the
    // caller's own code-provided default. Both tests use a limiter name nothing
    // else in this class touches, and pin BOTH shared-default axes explicitly —
    // the real %test.ratelimit.default.* values are still live otherwise, and
    // would defeat whichever axis this test isn't deliberately setting.

    @Test
    void check_shouldUseSharedDefaultMaxWhenNoPerLimiterOverrideExists()
    {
        pinSharedDefault(2, 60);

        // Code default is 5, no "ratelimit.brand-new-limiter.max" override exists,
        // but the shared default of 2 applies.
        assertTrue(service.check("brand-new-limiter", "ip-default", 5, 60).allowed());  // 1
        assertTrue(service.check("brand-new-limiter", "ip-default", 5, 60).allowed());  // 2
        assertFalse(service.check("brand-new-limiter", "ip-default", 5, 60).allowed()); // 3 — denied by shared default
    }

    @Test
    void check_perLimiterOverrideWinsOverSharedDefault()
    {
        System.setProperty("ratelimit.specific.max", "4");
        pinnedPropertyKeys.add("ratelimit.specific.max");
        pinSharedDefault(1, 60);

        // If the shared default (1) won, this would already be denied on request 2.
        assertTrue(service.check("specific", "ip-precedence", 5, 60).allowed());  // 1
        assertTrue(service.check("specific", "ip-precedence", 5, 60).allowed());  // 2
        assertTrue(service.check("specific", "ip-precedence", 5, 60).allowed());  // 3
        assertTrue(service.check("specific", "ip-precedence", 5, 60).allowed());  // 4
        assertFalse(service.check("specific", "ip-precedence", 5, 60).allowed()); // 5 — denied by the per-limiter override
    }

    @Test
    void check_shouldUseSharedDefaultWindowWhenNoPerLimiterOverrideExists() throws InterruptedException
    {
        pinSharedDefault(3, 1);

        // Code default window is 3600, shared default window is 1 second.
        for (int i = 0; i < 3; i++) {
            service.check("another-new-limiter", "ip-default-win", 3, 3600);
        }
        assertFalse(service.check("another-new-limiter", "ip-default-win", 3, 3600).allowed());

        Thread.sleep(1100);
        assertTrue(service.check("another-new-limiter", "ip-default-win", 3, 3600).allowed());
    }

    @Test
    void check_perLimiterWindowOverrideWinsOverSharedDefaultWindow() throws InterruptedException
    {
        pinLimit("specific-win", 3, 3600);
        pinSharedDefault(3, 1);

        for (int i = 0; i < 3; i++) {
            service.check("specific-win", "ip-win-precedence", 3, 60);
        }
        assertFalse(service.check("specific-win", "ip-win-precedence", 3, 60).allowed());

        // If the shared 1-second default had won, this would already be re-allowed here.
        Thread.sleep(1100);
        assertFalse(service.check("specific-win", "ip-win-precedence", 3, 60).allowed());
    }

    // ── enforce() — Response-returning convenience wrapper ──────────────────

    @Test
    void enforce_shouldReturnNullWhenAllowed()
    {
        pinLimit("test", 5, 60);
        assertNull(service.enforce("test", "enforce-ip-1", 5, 60));
    }

    @Test
    void enforce_shouldReturn429WhenDenied()
    {
        pinLimit("test", 3, 60);
        for (int i = 0; i < 3; i++) {
            service.enforce("test", "enforce-ip-2", 3, 60);
        }
        Response limited = service.enforce("test", "enforce-ip-2", 3, 60);
        assertNotNull(limited);
        assertEquals(429, limited.getStatus());
    }

    @Test
    void enforce_shouldSetRetryAfterHeaderFromCheckDecision() throws InterruptedException
    {
        pinLimit("test", 3, 3);
        // Same tolerant-range reasoning as check_shouldReturnRetryAfterOnDenial —
        // proving enforce()'s header is derived from the identical calculation.
        for (int i = 0; i < 3; i++) {
            service.enforce("test", "enforce-ip-3", 3, 3);
        }

        Thread.sleep(1000);

        Response limited = service.enforce("test", "enforce-ip-3", 3, 3);
        assertNotNull(limited);
        int retryAfter = Integer.parseInt(limited.getHeaderString("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 3,
                "expected 1-3 seconds remaining, was: " + retryAfter);
    }

    @Test
    void enforce_shouldComposeNameAndKeyLikeCheck()
    {
        pinLimit("enforce-a", 2, 60);
        pinLimit("enforce-b", 2, 60);

        for (int i = 0; i < 2; i++) {
            service.enforce("enforce-a", "shared-key", 2, 60);
        }
        assertNotNull(service.enforce("enforce-a", "shared-key", 2, 60));

        // A different limiter name for the same key is unaffected — proves enforce()
        // buckets on (name, key) exactly like check(), not a separate scheme.
        assertNull(service.enforce("enforce-b", "shared-key", 2, 60));
    }
}
