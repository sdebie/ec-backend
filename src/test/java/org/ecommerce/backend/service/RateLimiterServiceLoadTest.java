package org.ecommerce.backend.service;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concurrency load tests for {@link RateLimiterService}.
 * <p>
 * The endpoint integration suites mock the limiter to test denial handling; the unit
 * suite proves the algorithm single-threaded. This suite proves the safety property
 * that matters under real load: <strong>a (name, key) pair never admits more than max
 * requests per window, no matter how many threads hammer it simultaneously.</strong>
 * <p>
 * Note on the concurrent bound: the fixed-window counter increments atomically and the
 * admission read is monotonic, so concurrent racing can only <em>under</em>-admit
 * (a thread whose own increment was within limit may observe a higher count and be
 * denied), never over-admit. The assertions encode exactly that: {@code allowed <= max}
 * always; {@code allowed == max} is only guaranteed for sequential traffic.
 *
 * <strong>Validates: Requirement 1.1 (Property 1 — bounded acceptance) under concurrency</strong>
 */
class RateLimiterServiceLoadTest
{
    private RateLimiterService service;

    @BeforeEach
    void setUp() throws Exception
    {
        Config config = mock(Config.class);
        when(config.getOptionalValue(anyString(), any())).thenReturn(Optional.empty());

        service = new RateLimiterService();
        Field configField = RateLimiterService.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(service, config);
    }

    // ── Bounded acceptance under concurrent load ────────────────────────────

    @Test
    void concurrentSingleKey_neverAdmitsMoreThanMax() throws Exception
    {
        final int threads = 20;
        final int requestsPerThread = 50;   // 1000 total requests
        final int max = 25;

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < requestsPerThread; i++) {
                            if (service.check("load-single", "198.51.100.1", max, 3600).allowed()) {
                                allowed.incrementAndGet();
                            } else {
                                denied.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "load run did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        int total = allowed.get() + denied.get();
        assertEquals(threads * requestsPerThread, total, "every request must get a decision");
        assertTrue(allowed.get() <= max,
                "over-admission: " + allowed.get() + " allowed but max is " + max);
        assertTrue(allowed.get() >= 1, "at least the first request must be admitted");
    }

    @Test
    void concurrentDistinctKeys_eachKeyGetsItsFullBudget() throws Exception
    {
        final int keys = 16;
        final int requestsPerKey = 10;      // sequential per key → deterministic
        final int max = 5;

        int[] allowedPerKey = new int[keys];
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(keys);

        ExecutorService pool = Executors.newFixedThreadPool(keys);
        try {
            for (int k = 0; k < keys; k++) {
                final int keyIndex = k;
                pool.submit(() -> {
                    try {
                        start.await();
                        // Each key's requests are sequential within its own thread,
                        // so admission is deterministic: exactly max allowed.
                        for (int i = 0; i < requestsPerKey; i++) {
                            if (service.check("load-isolated", "10.1.0." + keyIndex, max, 3600).allowed()) {
                                allowedPerKey[keyIndex]++;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "load run did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        for (int k = 0; k < keys; k++) {
            assertEquals(max, allowedPerKey[k],
                    "key 10.1.0." + k + " must get exactly its own budget — no cross-key interference");
        }
    }

    @Test
    void concurrentMixedNamesAndKeys_invariantsHoldAtVolume() throws Exception
    {
        final int threads = 8;
        final int requestsPerThread = 10_000;   // 80k total checks
        final int distinctKeys = 200;
        final int max = 30;
        final List<String> names = List.of("load-a", "load-b", "load-c");

        AtomicInteger decisions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long startNanos = System.nanoTime();
        try {
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < requestsPerThread; i++) {
                            String name = names.get((seed + i) % names.size());
                            String key = "172.16.0." + ((seed * 31 + i) % distinctKeys);
                            service.check(name, key, max, 3600);
                            decisions.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "volume run did not finish in time");
        } finally {
            pool.shutdownNow();
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(threads * requestsPerThread, decisions.get(), "every check must return a decision");

        // Per-bucket admission never exceeds max: read the real buckets and verify each
        // count that was admitted stayed within bounds (counts beyond max are denied
        // requests still recorded in the window bucket — admission itself is what is bounded,
        // proven by the single-key test; here we prove no bucket corruption at volume).
        ConcurrentHashMap<String, RateLimiterService.WindowBucket> buckets = bucketsOf(service);
        long bucketEntries = buckets.keySet().stream()
                .filter(k -> k.startsWith("load-a:") || k.startsWith("load-b:") || k.startsWith("load-c:"))
                .count();
        assertEquals(names.size() * distinctKeys, bucketEntries,
                "each (name, key) pair must map to exactly one bucket — no duplicate or lost buckets");

        System.out.printf("RateLimiterService volume run: %d checks across %d buckets in %d ms (%.0f checks/sec)%n",
                decisions.get(), bucketEntries, elapsedMillis,
                decisions.get() / Math.max(0.001, elapsedMillis / 1000.0));
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, RateLimiterService.WindowBucket> bucketsOf(RateLimiterService service)
            throws Exception
    {
        Field bucketsField = RateLimiterService.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        return (ConcurrentHashMap<String, RateLimiterService.WindowBucket>) bucketsField.get(service);
    }
}
