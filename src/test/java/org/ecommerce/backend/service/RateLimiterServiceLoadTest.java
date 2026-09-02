package org.ecommerce.backend.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency load tests for {@link RateLimiterService} against real Redis — proves
 * {@code INCR}'s atomicity holds under real concurrent callers, the same guarantee a
 * second backend replica would also rely on.
 * <p>
 * Note on the concurrent bound: the counter increments atomically and the admission
 * read is monotonic, so concurrent racing can only <em>under</em>-admit (a thread
 * whose own increment was within limit may observe a higher count and be denied),
 * never over-admit. The assertions encode exactly that: {@code allowed <= max}
 * always; {@code allowed == max} is only guaranteed for sequential traffic.
 */
@QuarkusTest
class RateLimiterServiceLoadTest
{
    @Inject
    RateLimiterService service;

    @Inject
    RedisDataSource redisDataSource;

    @BeforeEach
    void setUp()
    {
        redisDataSource.flushall();
    }

    @AfterEach
    void tearDown()
    {
        for (String name : List.of("load-single", "load-isolated", "load-a", "load-b", "load-c")) {
            System.clearProperty("ratelimit." + name + ".max");
            System.clearProperty("ratelimit." + name + ".window-seconds");
        }
    }

    /** See RateLimiterServiceTest for why this pins rather than relies on absence. */
    private void pinLimit(String name, int max, long windowSeconds)
    {
        System.setProperty("ratelimit." + name + ".max", String.valueOf(max));
        System.setProperty("ratelimit." + name + ".window-seconds", String.valueOf(windowSeconds));
    }

    // ── Bounded acceptance under concurrent load ────────────────────────────

    @Test
    void concurrentSingleKey_neverAdmitsMoreThanMax() throws Exception
    {
        final int threads = 20;
        final int requestsPerThread = 50;   // 1000 total requests
        final int max = 25;
        pinLimit("load-single", max, 3600);

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
        pinLimit("load-isolated", max, 3600);

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
        final int requestsPerThread = 500;   // 4000 total — real network round trips, kept
                                              // below the old 80k in-memory figure for speed.
        final int distinctKeys = 50;
        final int max = 30;
        final List<String> names = List.of("load-a", "load-b", "load-c");
        names.forEach(name -> pinLimit(name, max, 3600));

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

        // Per-bucket admission never exceeds max is proven by the single-key test;
        // here we prove no bucket corruption at volume — each (name, key) pair maps
        // to exactly one Redis key, none duplicated or lost.
        long bucketEntries = 0;
        for (String name : names) {
            bucketEntries += redisDataSource.key(String.class).keys(name + ":*").size();
        }
        assertEquals(names.size() * distinctKeys, bucketEntries,
                "each (name, key) pair must map to exactly one Redis key — no duplicate or lost buckets");

        System.out.printf("RateLimiterService volume run: %d checks across %d Redis keys in %d ms (%.0f checks/sec)%n",
                decisions.get(), bucketEntries, elapsedMillis,
                decisions.get() / Math.max(0.001, elapsedMillis / 1000.0));
    }
}
