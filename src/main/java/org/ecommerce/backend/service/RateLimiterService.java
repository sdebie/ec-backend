package org.ecommerce.backend.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.RedisKeyNotFoundException;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * Config-driven, Redis-backed fixed-window rate limiter. Buckets are keyed
 * by {@code "<limiterName>:<key>"}, stored as a Redis integer whose TTL is the
 * window — {@code INCR} is atomic across every replica sharing this Redis, which is
 * what makes this safe with more than one backend instance.
 */
@ApplicationScoped
public class RateLimiterService
{
    private static final Logger LOG = Logger.getLogger(RateLimiterService.class);

    @Inject
    Config config;

    @Inject
    RedisDataSource redisDataSource;

    /**
     * Check whether a request identified by (name, key) is within the rate limit.
     *
     * @param name                 limiter name (e.g. "enquiry")
     * @param key                  per-caller key (IP or email)
     * @param defaultMax           max requests per window if unconfigured
     * @param defaultWindowSeconds window length if unconfigured
     * @return allowed/denied, plus retry-after seconds when denied
     */
    public RateLimitDecision check(String name, String key, int defaultMax, long defaultWindowSeconds)
    {
        int max = resolveMax(name, defaultMax);
        long windowSeconds = resolveWindowSeconds(name, defaultWindowSeconds);
        String compositeKey = name + ":" + key;

        ValueCommands<String, Long> counters = redisDataSource.value(Long.class);
        long count = counters.incr(compositeKey);
        if (count == 1) {
            // Only the creating request sets expiry, or the window would never end.
            redisDataSource.key(String.class).expire(compositeKey, windowSeconds);
        }

        boolean allowed = count <= max;
        if (!allowed) {
            long remainingSeconds = clampRetryAfter(remainingTtlSeconds(compositeKey, windowSeconds));
            // Denials are logged, not persisted: there is no ops-visible record of who was
            // limited and how often. Tracked as rate-limit-denial-audit in the backlog.
            LOG.warnf("Rate limit denied: limiter=%s, key=%s, count=%d/%d",
                    name, maskKey(key), count, max);
            return new RateLimitDecision(false, remainingSeconds);
        }
        return new RateLimitDecision(true, 0);
    }

    /**
     * Convenience wrapper around {@link #check} for the common REST call-site shape:
     * check the limit and, on denial, build the 429 response inline instead of every
     * caller re-deriving {@code Response.status(429).header("Retry-After", ...)} from a
     * {@link RateLimitDecision} by hand. Returns {@code null} when the request is
     * allowed, so callers short-circuit with {@code if (result != null) return result;}.
     * <p>
     * An endpoint that must respond differently on denial — e.g. the anti-enumeration
     * generic 200 on password-reset-request — calls {@link #check} directly instead.
     */
    public Response enforce(String name, String key, int defaultMax, long defaultWindowSeconds)
    {
        RateLimitDecision decision = check(name, key, defaultMax, defaultWindowSeconds);
        if (decision.allowed()) {
            return null;
        }
        return Response.status(429).header("Retry-After", decision.retryAfterSeconds()).build();
    }

    /** Falls back to a full window on the vanishingly rare expiry-mid-read race. */
    private long remainingTtlSeconds(String compositeKey, long windowSeconds)
    {
        try {
            return redisDataSource.key(String.class).ttl(compositeKey);
        } catch (RedisKeyNotFoundException e) {
            return windowSeconds;
        }
    }

    /** Redis's TTL can report 0 a moment before expiry — never retry-after-zero. */
    static long clampRetryAfter(long ttlSeconds)
    {
        return Math.max(1, ttlSeconds);
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

    /**
     * Resolution order: the limiter's own override, then the shared
     * {@code ratelimit.default.*} fallback, then the caller's code-provided default.
     * The shared fallback exists so a new limiter needs no config of its own just to
     * avoid interfering with unrelated tests — see the comment on that key in
     * {@code application.properties}.
     */
    private int resolveMax(String name, int defaultMax)
    {
        return config.getOptionalValue("ratelimit." + name + ".max", Integer.class)
                .or(() -> config.getOptionalValue("ratelimit.default.max", Integer.class))
                .orElse(defaultMax);
    }

    private long resolveWindowSeconds(String name, long defaultWindowSeconds)
    {
        return config.getOptionalValue("ratelimit." + name + ".window-seconds", Long.class)
                .or(() -> config.getOptionalValue("ratelimit.default.window-seconds", Long.class))
                .orElse(defaultWindowSeconds);
    }
}
