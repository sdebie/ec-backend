package org.ecommerce.backend.health;

import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Redis backs the rate limiter and the stock-recovery sweep lock — both silently
 * degrade if it is unreachable (the rate limiter throws on every check, the sweep
 * lock can never be acquired). {@code @Readiness} rather than {@code @Liveness}:
 * the JVM itself is fine, restarting it fixes nothing, so this should pull the
 * instance out of load-balancer rotation until Redis recovers, not restart it.
 */
@Readiness
@ApplicationScoped
public class RedisHealthCheck implements HealthCheck
{
    @Inject
    RedisDataSource redisDataSource;

    @Override
    public HealthCheckResponse call()
    {
        try {
            // The key itself is irrelevant — a real round trip to Redis is the probe,
            // not whichever boolean it happens to return.
            redisDataSource.key(String.class).exists("health-check-probe");
            return HealthCheckResponse.up("redis");
        } catch (RuntimeException e) {
            return HealthCheckResponse.down("redis");
        }
    }
}
