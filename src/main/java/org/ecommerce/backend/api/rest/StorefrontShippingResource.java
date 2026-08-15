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
    @jakarta.inject.Inject
    org.ecommerce.backend.mapper.ShippingMethodMapper shippingMethodMapper;


    @GET
    public List<ShippingMethodDto> getActiveShippingMethods()
    {
        return ShippingMethodEntity.<ShippingMethodEntity>list("isActive", true)
                .stream()
                .map(shippingMethodMapper::toDto)
                .toList();
    }

}
