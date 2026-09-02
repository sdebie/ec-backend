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
 * {@link RateLimiterService} uses — {@code SET ... NX} makes acquisition atomic
 * across replicas. The lease is a crash safety net, not the release mechanism; a
 * caller that finishes normally always calls {@link #release}.
 */
@ApplicationScoped
public class DistributedLockService
{
    @Inject
    RedisDataSource redisDataSource;

    /** Returns a token to pass to {@link #release}, or empty if already held. */
    public Optional<String> tryAcquire(String lockName, Duration lease)
    {
        String token = UUID.randomUUID().toString();
        boolean acquired = redisDataSource.value(String.class)
                .setAndChanged(lockKey(lockName), token, new SetArgs().nx().px(lease));
        return acquired ? Optional.of(token) : Optional.empty();
    }

    /** Deletes only if {@code token} still matches — never clears another holder's lock. */
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
