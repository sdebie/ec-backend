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
 *
 * @param stockReturned whether this transition put the order's items back into
 *                      inventory. Always false when the claim was lost: the writer
 *                      that won owns the stock.
 */
public record TransitionOutcome(
        boolean claimed,
        OrderStatusEn from,
        OrderStatusEn to,
        boolean stockReturned)
{
    static TransitionOutcome won(OrderStatusEn from, OrderStatusEn to, boolean stockReturned)
    {
        return new TransitionOutcome(true, from, to, stockReturned);
    }

    static TransitionOutcome lost(OrderStatusEn from, OrderStatusEn to)
    {
        return new TransitionOutcome(false, from, to, false);
    }
}
