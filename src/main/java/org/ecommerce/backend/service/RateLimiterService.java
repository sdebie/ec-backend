package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared, config-driven, in-memory fixed-window rate limiter.
 * <p>
 * Buckets are keyed by {@code "<limiterName>:<key>"} so each (name, key) pair
 * gets its own independent counter. Limits are resolved per limiter name from
 * MicroProfile Config ({@code ratelimit.<name>.max} / {@code ratelimit.<name>.window-seconds}),
 * falling back to code defaults passed by the caller.
 * <p>
 * <strong>Known limitation:</strong> this limiter is single-node only — it is NOT shared
 * across replicas. If the platform scales to multiple backend instances, a distributed
 * store (e.g. Redis) must replace this implementation. Do not mistake this for
 * distributed protection.
 * <p>
 * The window resets lazily: the first request in a new window replaces the old bucket.
 */
@ApplicationScoped
public class RateLimiterService
{
    private static final Logger LOG = Logger.getLogger(RateLimiterService.class);

    /**
     * When the map grows beyond this many entries, expired windows are swept on the
     * next request. Bounds memory on public endpoints where every distinct source IP
     * would otherwise leave a permanent entry.
     */
    static final int CLEANUP_THRESHOLD = 10_000;

    @Inject
    Config config;

    private final ConcurrentHashMap<String, WindowBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Check whether a request identified by (name, key) is within the rate limit.
     *
     * @param name                 logical limiter name (e.g. "enquiry", "customer-login")
     * @param key                  per-caller discriminator (typically client IP or normalised email)
     * @param defaultMax           maximum requests per window (used when no config override exists)
     * @param defaultWindowSeconds window length in seconds (used when no config override exists)
     * @return a {@link RateLimitDecision} indicating whether the request is allowed and,
     * if denied, how many seconds remain in the current window
     */
    public RateLimitDecision check(String name, String key, int defaultMax, long defaultWindowSeconds)
    {
        int max = resolveMax(name, defaultMax);
        long windowSeconds = resolveWindowSeconds(name, defaultWindowSeconds);
        long windowMillis = Duration.ofSeconds(windowSeconds).toMillis();
        long now = currentTimeMillis();

        // Bound memory: sweep expired windows once the map grows large.
        if (buckets.size() > CLEANUP_THRESHOLD) {
            buckets.entrySet().removeIf(entry -> {
                long bucketWindowMillis = Duration.ofSeconds(entry.getValue().windowSeconds).toMillis();
                return now - entry.getValue().windowStart >= bucketWindowMillis;
            });
        }

        String compositeKey = name + ":" + key;

        WindowBucket bucket = buckets.compute(compositeKey, (k, existing) -> {
            if (existing == null || now - existing.windowStart >= windowMillis) {
                // New window — start fresh
                return new WindowBucket(now, new AtomicInteger(1), windowSeconds);
            }
            existing.count.incrementAndGet();
            return existing;
        });

        boolean allowed = bucket.count.get() <= max;
        if (!allowed) {
            long elapsedMillis = now - bucket.windowStart;
            long remainingSeconds = Math.max(1, windowSeconds - (elapsedMillis / 1000));
            // Denials are logged, not persisted: there is no ops-visible record of who was
            // limited and how often. Tracked as rate-limit-denial-audit in the backlog.
            LOG.warnf("Rate limit denied: limiter=%s, key=%s, count=%d/%d",
                    name, maskKey(key), bucket.count.get(), max);
            return new RateLimitDecision(false, remainingSeconds);
        }
        return new RateLimitDecision(true, 0);
    }

    /**
     * Masks email-shaped keys for logging so denial logs carry no plaintext PII:
     * the local part is reduced to its first character (e.g. {@code j***@example.com}).
     * Non-email keys (IPs, "unknown") pass through unchanged. Bucket keys themselves
     * are never masked — only the log line.
     */
    static String maskKey(String key)
    {
        if (key == null) {
            return null;
        }
        int at = key.indexOf('@');
        if (at < 0) {
            return key;
        }
        String maskedLocal = at == 0 ? "***" : key.charAt(0) + "***";
        return maskedLocal + key.substring(at);
    }

    private int resolveMax(String name, int defaultMax)
    {
        return config.getOptionalValue("ratelimit." + name + ".max", Integer.class)
                .orElse(defaultMax);
    }

    private long resolveWindowSeconds(String name, long defaultWindowSeconds)
    {
        return config.getOptionalValue("ratelimit." + name + ".window-seconds", Long.class)
                .orElse(defaultWindowSeconds);
    }

    /**
     * Visible for testing — allows subclasses/mocks to override the clock.
     */
    long currentTimeMillis()
    {
        return System.currentTimeMillis();
    }

    /**
     * Visible for testing — exposes the bucket map size.
     */
    int bucketCount()
    {
        return buckets.size();
    }

    static class WindowBucket
    {
        final long windowStart;
        final AtomicInteger count;
        final long windowSeconds;

        WindowBucket(long windowStart, AtomicInteger count, long windowSeconds)
        {
            this.windowStart = windowStart;
            this.count = count;
            this.windowSeconds = windowSeconds;
        }
    }
}
