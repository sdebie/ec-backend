package org.ecommerce.backend.api.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.ecommerce.backend.service.SettingsService;
import org.ecommerce.common.dto.ShippingMethodDto;

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

    @jakarta.inject.Inject
    SettingsService settingsService;


    @GET
    public List<ShippingMethodDto> getActiveShippingMethods()
    {
        return settingsService.getActiveShippingMethodEntities()
                .stream()
                .map(shippingMethodMapper::toDto)
                .toList();
    }

}
