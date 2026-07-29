package org.ecommerce.backend.api.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.ecommerce.common.dto.ShippingMethodDto;
import org.ecommerce.common.entity.ShippingMethodEntity;

import java.util.List;

/**
 * Public storefront endpoint for retrieving active shipping methods.
 * No authentication required.
 */
@Path("/api/storefront/shipping-methods")
@Produces(MediaType.APPLICATION_JSON)
public class StorefrontShippingResource
{

    @GET
    public List<ShippingMethodDto> getActiveShippingMethods()
    {
        return ShippingMethodEntity.<ShippingMethodEntity>list("isActive", true)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private ShippingMethodDto toDto(ShippingMethodEntity entity)
    {
        ShippingMethodDto dto = new ShippingMethodDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setBaseFee(entity.getBaseFee());
        dto.setEstimatedDays(entity.getEstimatedDays());
        return dto;
    }
}
