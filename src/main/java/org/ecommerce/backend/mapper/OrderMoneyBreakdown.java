package org.ecommerce.backend.mapper;

import java.math.BigDecimal;

/**
 * The money components staff see behind an order's grand total.
 * <p>
 * Grouped rather than passed as three loose {@code BigDecimal} arguments so they cannot be
 * transposed at a call site — the compiler cannot tell subtotal from VAT when both are
 * positional {@code BigDecimal}s.
 *
 * @param subtotal     sum of the order's own line totals
 * @param shippingCost delivery for the method selected on the order
 * @param vatAmount    VAT on the subtotal
 */
public record OrderMoneyBreakdown(BigDecimal subtotal, BigDecimal shippingCost, BigDecimal vatAmount)
{
}
