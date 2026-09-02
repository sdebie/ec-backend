package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.repository.ShippingMethodRepository;

import java.math.BigDecimal;
import java.util.List;

@ApplicationScoped
public class ShippingService
{
    @Inject
    ShippingMethodRepository shippingMethodRepository;

    /**
     * Returns the base fee of the first active shipping method.
     * Returns BigDecimal.ZERO if no active shipping method exists.
     */
    public BigDecimal estimateShipping()
    {
        List<ShippingMethodEntity> activeMethods = shippingMethodRepository.findAllActive();
        if (activeMethods == null || activeMethods.isEmpty()) {
            return BigDecimal.ZERO;
        }
        ShippingMethodEntity method = activeMethods.getFirst();
        return method.getBaseFee() != null ? method.getBaseFee() : BigDecimal.ZERO;
    }
}
