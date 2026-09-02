package org.ecommerce.backend.health;

import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Redis backs the rate limiter and the sweep lock. {@code @Readiness}, not
 * {@code @Liveness} — an outage should pull the instance from rotation, not restart it.
 */
//@Readiness
@ApplicationScoped
public class RedisHealthCheck implements HealthCheck
{
    @Inject
    RedisDataSource redisDataSource;

    @Override
    public HealthCheckResponse call()
    {
        try {
            redisDataSource.key(String.class).exists("health-check-probe");
            return HealthCheckResponse.up("redis");
        } catch (RuntimeException e) {
            return HealthCheckResponse.down("redis");
        }
    }
}
