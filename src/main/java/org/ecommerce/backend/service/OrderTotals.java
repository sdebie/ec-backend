package org.ecommerce.backend.service;

import java.math.BigDecimal;

/**
 * The money on an order, as the server computed it.
 *
 * Produced by {@link OrderService#computeTotals} — the single place the
 * subtotal, VAT, delivery fee and grand total are assembled, so an order created
 * from the cart and an order repriced after the shopper picks a delivery method
 * can never disagree about how a total is built.
 */
public record OrderTotals(
        BigDecimal subtotal,
        BigDecimal vatAmount,
        BigDecimal shippingEstimate,
        BigDecimal grandTotal
)
{
}
