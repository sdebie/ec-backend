package org.ecommerce.backend.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DistributedLockService} against a real (Dev Services) Redis.
 * Covers: acquire when free, mutual exclusion while held, re-acquire after release,
 * self-healing after the lease expires, and that a stale release can never delete a
 * different holder's live lock.
 */
@QuarkusTest
class DistributedLockServiceTest
{
    @Inject
    DistributedLockService lockService;

    @Inject
    RedisDataSource redisDataSource;

    @BeforeEach
    void setUp()
    {
        redisDataSource.flushall();
    }

    @Test
    void tryAcquire_succeedsWhenLockIsFree()
    {
        Optional<String> token = lockService.tryAcquire("test-lock", Duration.ofSeconds(30));
        assertTrue(token.isPresent());
    }

    @Test
    void tryAcquire_failsWhileAnotherHolderHasIt()
    {
        Optional<String> first = lockService.tryAcquire("test-lock", Duration.ofSeconds(30));
        assertTrue(first.isPresent());

        Optional<String> second = lockService.tryAcquire("test-lock", Duration.ofSeconds(30));
        assertTrue(second.isEmpty(), "a second acquire while the first holder is live must fail");
    }

    @Test
    void tryAcquire_isIndependentPerLockName()
    {
        Optional<String> lockA = lockService.tryAcquire("lock-a", Duration.ofSeconds(30));
        Optional<String> lockB = lockService.tryAcquire("lock-b", Duration.ofSeconds(30));

        assertTrue(lockA.isPresent());
        assertTrue(lockB.isPresent(), "a different lock name must not be blocked by an unrelated held lock");
    }

    @Test
    void tryAcquire_succeedsAgainAfterRelease()
    {
        Optional<String> first = lockService.tryAcquire("test-lock", Duration.ofSeconds(30));
        assertTrue(first.isPresent());
        lockService.release("test-lock", first.get());

        Optional<String> second = lockService.tryAcquire("test-lock", Duration.ofSeconds(30));
        assertTrue(second.isPresent(), "the lock must be acquirable again once released");
    }

    @Test
    void tryAcquire_succeedsAgainAfterLeaseExpires() throws InterruptedException
    {
        Optional<String> first = lockService.tryAcquire("test-lock", Duration.ofMillis(300));
        assertTrue(first.isPresent());

        Thread.sleep(500);

        Optional<String> second = lockService.tryAcquire("test-lock", Duration.ofSeconds(30));
        assertTrue(second.isPresent(), "an expired lease must self-heal without an explicit release");
    }

    @Test
    void release_doesNotDeleteADifferentHoldersLock() throws InterruptedException
    {
        Optional<String> staleToken = lockService.tryAcquire("test-lock", Duration.ofMillis(300));
        assertTrue(staleToken.isPresent());

        // Let the first lease expire, then simulate a different instance winning
        // the now-free lock before the original (stale) holder calls release.
        Thread.sleep(500);
        Optional<String> currentToken = lockService.tryAcquire("test-lock", Duration.ofSeconds(30));
        assertTrue(currentToken.isPresent(), "the lock must be free again once the first lease expired");

        lockService.release("test-lock", staleToken.get());

        Optional<String> thirdAttempt = lockService.tryAcquire("test-lock", Duration.ofSeconds(30));
        assertTrue(thirdAttempt.isEmpty(),
                "a stale release must not delete a different holder's live lock");

        // The real holder's own release still works normally.
        lockService.release("test-lock", currentToken.get());
        assertTrue(lockService.tryAcquire("test-lock", Duration.ofSeconds(30)).isPresent());
    }

    @Test
    void tryAcquire_tokensAreUnique()
    {
        Optional<String> first = lockService.tryAcquire("test-lock", Duration.ofSeconds(1));
        lockService.release("test-lock", first.orElseThrow());
        Optional<String> second = lockService.tryAcquire("test-lock", Duration.ofSeconds(30));

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertTrue(!first.get().equals(second.get()), "each acquisition must mint its own token");
    }
}
