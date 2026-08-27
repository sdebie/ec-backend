package org.ecommerce.backend.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Cluster-wide mutual exclusion for a scheduled job, backed by the same Redis
 * {@link RateLimiterService} uses. Exists so a job like {@code StockRecoveryJob} runs
 * once per tick across every backend replica, not once per replica.
 * <p>
 * The lease is a safety net, not the release mechanism — a caller that finishes
 * normally is expected to always call {@link #release}; the lease only bounds how
 * long the lock stays held if a caller crashes mid-work and never gets the chance to.
 * Redis's own {@code SET ... NX} is what makes acquisition atomic across replicas:
 * two instances racing to acquire the same lock name can never both succeed, the same
 * property {@code RateLimiterService} relies on for its counters.
 */
@ApplicationScoped
public class DistributedLockService
{
    @Inject
    RedisDataSource redisDataSource;

    /**
     * Tries to acquire {@code lockName} for up to {@code lease}. Returns the token to
     * pass to {@link #release} on success, or empty if another holder already has it.
     */
    public Optional<String> tryAcquire(String lockName, Duration lease)
    {
        String token = UUID.randomUUID().toString();
        boolean acquired = redisDataSource.value(String.class)
                .setAndChanged(lockKey(lockName), token, new SetArgs().nx().px(lease));
        return acquired ? Optional.of(token) : Optional.empty();
    }

    /**
     * Best-effort release: deletes the key only if it still holds the exact token this
     * caller was given, so a lease that already expired and was re-acquired by another
     * holder is never deleted out from under them. The read-then-delete has a narrow
     * window where the lease could expire between the two calls — acceptable here
     * because every real caller also protects its actual work with an atomic
     * conditional write of its own; this lock only bounds wasted duplicate work; it is
     * never the source of truth for correctness.
     */
    public void release(String lockName, String token)
    {
        String key = lockKey(lockName);
        if (token.equals(redisDataSource.value(String.class).get(key))) {
            redisDataSource.key(String.class).del(key);
        }
    }

    private String lockKey(String lockName)
    {
        return "lock:" + lockName;
    }
}
