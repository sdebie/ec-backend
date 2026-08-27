package org.ecommerce.backend.health;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link RedisHealthCheck}'s decision logic — no {@code @QuarkusTest}
 * needed, so both branches (including a genuinely unreachable Redis) are instant and
 * deterministic rather than depending on actually breaking a real connection.
 * {@link HealthEndpointIT} covers the real HTTP surface separately.
 */
class RedisHealthCheckTest
{
    @SuppressWarnings("unchecked")
    private RedisHealthCheck newCheckWith(RedisDataSource redisDataSource) throws Exception
    {
        RedisHealthCheck check = new RedisHealthCheck();
        Field field = RedisHealthCheck.class.getDeclaredField("redisDataSource");
        field.setAccessible(true);
        field.set(check, redisDataSource);
        return check;
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsUp_whenRedisRespondsNormally() throws Exception
    {
        RedisDataSource redisDataSource = mock(RedisDataSource.class);
        KeyCommands<String> keyCommands = mock(KeyCommands.class);
        when(redisDataSource.key(String.class)).thenReturn(keyCommands);
        when(keyCommands.exists(anyString())).thenReturn(false);

        HealthCheckResponse response = newCheckWith(redisDataSource).call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("redis", response.getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsDown_whenRedisIsUnreachable() throws Exception
    {
        RedisDataSource redisDataSource = mock(RedisDataSource.class);
        KeyCommands<String> keyCommands = mock(KeyCommands.class);
        when(redisDataSource.key(String.class)).thenReturn(keyCommands);
        when(keyCommands.exists(anyString())).thenThrow(new RuntimeException("connection refused"));

        HealthCheckResponse response = newCheckWith(redisDataSource).call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }
}
