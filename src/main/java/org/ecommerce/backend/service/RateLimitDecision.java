package org.ecommerce.backend.service;

/**
 * Result of a rate-limit check.
 *
 * @param allowed          {@code true} if the request is within limits
 * @param retryAfterSeconds seconds remaining in the current window when denied (minimum 1); 0 when allowed
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
}
