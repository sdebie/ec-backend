package org.ecommerce.backend.exception;

import java.util.UUID;

/**
 * Thrown when {@code OrderService.createOrderFromCart}'s optimistic claim on an
 * {@code Idempotency-Key} loses a race — another delivery of the same intent won
 * (design §3.2). The caller re-resolves against the winner rather than treating
 * this as a fault; it is not a client error and not a server error.
 */
public class IdempotencyConflictException extends RuntimeException
{
    public IdempotencyConflictException(UUID key)
    {
        super("Idempotency key already claimed by another request: " + key);
    }
}
