package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory fixed-window rate limiter for enquiry submissions, keyed by client IP.
 * <p>
 * <strong>Known limitation:</strong> this limiter is single-node only — it is NOT shared
 * across replicas. If the platform scales to multiple instances, a distributed store
 * (e.g. Redis) must replace this implementation. Do not mistake this for distributed
 * protection.
 * <p>
 * The window resets lazily: the first request in a new window replaces the old bucket.
 */
@ApplicationScoped
public class EnquiryRateLimiter {

    private static final Logger LOG = Logger.getLogger(EnquiryRateLimiter.class);

    @ConfigProperty(name = "contact.enquiry.rate-limit.max", defaultValue = "5")
    int maxRequests;

    @ConfigProperty(name = "contact.enquiry.rate-limit.window-seconds", defaultValue = "3600")
    long windowSeconds;

    /**
     * When the map grows beyond this many entries, expired windows are swept on the
     * next request. Bounds memory on a public endpoint where every distinct source IP
     * would otherwise leave a permanent entry.
     */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, WindowBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Check whether the given client IP is allowed to make another enquiry request.
     *
     * @param clientIp the proxy-aware client IP
     * @return {@code true} if the request is within the rate limit, {@code false} if it exceeds
     */
    public boolean isAllowed(String clientIp) {
        long now = System.currentTimeMillis();
        long windowMillis = Duration.ofSeconds(windowSeconds).toMillis();

        // Bound memory: sweep expired windows once the map grows large. Cheap amortised
        // cost — only runs when the map is already oversized.
        if (buckets.size() > CLEANUP_THRESHOLD) {
            buckets.entrySet().removeIf(entry -> now - entry.getValue().windowStart >= windowMillis);
        }

        WindowBucket bucket = buckets.compute(clientIp, (key, existing) -> {
            if (existing == null || now - existing.windowStart >= windowMillis) {
                // New window — start fresh
                return new WindowBucket(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        boolean allowed = bucket.count.get() <= maxRequests;
        if (!allowed) {
            LOG.debugf("[ContactEnquiry] rate limit exceeded for IP %s (%d/%d in window)",
                    clientIp, bucket.count.get(), maxRequests);
        }
        return allowed;
    }

    private static class WindowBucket {
        final long windowStart;
        final AtomicInteger count;

        WindowBucket(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
