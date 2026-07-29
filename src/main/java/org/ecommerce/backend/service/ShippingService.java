package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.common.entity.ShippingMethodEntity;

import java.math.BigDecimal;
import java.util.List;

@ApplicationScoped
public class ShippingService
{
    /**
     * Returns the base fee of the first active shipping method.
     * Returns BigDecimal.ZERO if no active shipping method exists.
     */
    public BigDecimal estimateShipping()
    {
        List<ShippingMethodEntity> activeMethods = ShippingMethodEntity.list("isActive", true);
        if (activeMethods == null || activeMethods.isEmpty()) {
            return BigDecimal.ZERO;
        }
        ShippingMethodEntity method = activeMethods.getFirst();
        return method.getBaseFee() != null ? method.getBaseFee() : BigDecimal.ZERO;
    }
}
