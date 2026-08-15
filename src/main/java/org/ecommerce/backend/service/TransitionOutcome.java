package org.ecommerce.backend.service;

import org.ecommerce.common.enums.OrderStatusEn;

/**
 * What happened when a transition was attempted.
 * <p>
 * Losing the claim is an outcome, not an error: it means another writer moved the
 * order between the caller reading its status and this attempt to change it. What
 * to do about that is a per-caller decision — the abandoned-order sweep skips the
 * order, the staff mutation asks the operator to refresh, the ITN handler tells a
 * human that money arrived against an order that had moved on — so this reports it
 * rather than choosing for them.
 * <p>
 * It deliberately does not report what happened to the stock or whether an email went
 * out. Both follow from the destination status alone, so a caller reading them back
 * would be re-deriving something it cannot influence.
 */
public record TransitionOutcome(
        boolean claimed,
        OrderStatusEn from,
        OrderStatusEn to)
{
    static TransitionOutcome won(OrderStatusEn from, OrderStatusEn to)
    {
        return new TransitionOutcome(true, from, to);
    }

    static TransitionOutcome lost(OrderStatusEn from, OrderStatusEn to)
    {
        return new TransitionOutcome(false, from, to);
    }
}
