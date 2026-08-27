package org.ecommerce.backend.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Proves the Redis added for RateLimiterService's distributed store (see its
 * own class Javadoc) is actually reachable, not just that the dependency
 * compiles. Dev Services provisions a throwaway container for this test —
 * nothing here depends on an externally running Redis.
 */
@QuarkusTest
class RedisConnectivityTest
{
    @Inject
    RedisDataSource redisDataSource;

    @Test
    void setAndGetRoundTrips()
    {
        ValueCommands<String, String> commands = redisDataSource.value(String.class);
        String key = "redis-connectivity-check";

        commands.set(key, "ok");
        assertEquals("ok", commands.get(key));

        redisDataSource.key(String.class).del(key);
        assertNull(commands.get(key));
    }
}
