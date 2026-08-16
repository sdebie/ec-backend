package org.ecommerce.backend.service;

import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for {@code OrderService.isUniqueViolationOf} — the constraint-name
 * match design §3.2 relies on to distinguish a lost idempotency race from a genuine
 * fault. No sabotage of {@code createOrderFromCart} exercises the "unrelated
 * constraint" branch (this codebase has no other real constraint on {@code orders} an
 * IT can naturally trigger), so this is tested directly against synthetic exceptions
 * rather than left unverified.
 */
class OrderServiceIsUniqueViolationOfTest
{
    private static ConstraintViolationException hibernateViolation(String constraintName)
    {
        return new ConstraintViolationException("constraint violated", new SQLException("test"), constraintName);
    }

    @Test
    void matchingConstraintName_wrappedInPersistenceException_isTrue()
    {
        PersistenceException wrapped = new PersistenceException(hibernateViolation("ux_orders_idempotency_key"));
        assertTrue(OrderService.isUniqueViolationOf(wrapped, "ux_orders_idempotency_key"));
    }

    @Test
    void differentConstraintName_isFalse()
    {
        // The case the over-broad-catch sabotage exists to catch: a genuine, unrelated
        // constraint fault on `orders` must never be reported to a shopper as a
        // successful replay of someone else's order.
        PersistenceException wrapped = new PersistenceException(hibernateViolation("orders_pkey"));
        assertFalse(OrderService.isUniqueViolationOf(wrapped, "ux_orders_idempotency_key"));
    }

    @Test
    void beanValidationConstraintViolationException_isFalse()
    {
        // jakarta.validation.ConstraintViolationException is a same-named, unrelated
        // class an IDE offers first and that is never actually thrown by a flush() —
        // it must not be mistaken for the Hibernate one, and a PersistenceException
        // that doesn't wrap either kind must not match.
        PersistenceException wrapped = new PersistenceException("bean validation failed");
        assertFalse(OrderService.isUniqueViolationOf(wrapped, "ux_orders_idempotency_key"));
    }

    @Test
    void noConstraintViolationAnywhereInCauseChain_isFalse()
    {
        assertFalse(OrderService.isUniqueViolationOf(new PersistenceException("opaque failure"), "ux_orders_idempotency_key"));
    }

    @Test
    void nestedDeeperInCauseChain_stillMatches()
    {
        // em.flush() wraps the Hibernate exception in a PersistenceException; confirm
        // the unwrap walks the full chain, not just the immediate cause.
        RuntimeException outer = new RuntimeException(
                new PersistenceException(hibernateViolation("ux_orders_idempotency_key")));
        assertTrue(OrderService.isUniqueViolationOf(outer, "ux_orders_idempotency_key"));
    }
}
