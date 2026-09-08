package org.ecommerce.backend.api.rest;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.CustomerAddressService;
import org.ecommerce.backend.service.CustomerAuthService;
import org.ecommerce.backend.service.CustomerPortalService;
import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerResourceUpdateProfileTest
{
    @Test
    void updateProfileReturnsThePortalReadPath()
    {
        JsonWebToken jwt = mock(JsonWebToken.class);
        CustomerAuthService customerAuthService = mock(CustomerAuthService.class);
        CustomerAddressService customerAddressService = mock(CustomerAddressService.class);
        CustomerPortalService customerPortalService = mock(CustomerPortalService.class);

        UserEntity user = new UserEntity();
        CustomerEntity customer = new CustomerEntity();
        user.setCustomer(customer);
        when(jwt.getSubject()).thenReturn("shopper@example.com");
        when(customerAuthService.findUserByEmail("shopper@example.com")).thenReturn(user);

        CustomerProfileDto portalProfile = new CustomerProfileDto();
        portalProfile.setEmail("shopper@example.com");
        when(customerPortalService.getPortalProfile("shopper@example.com")).thenReturn(portalProfile);

        CustomerResource resource = new CustomerResource();
        resource.jwt = jwt;
        resource.customerAuthService = customerAuthService;
        resource.customerAddressService = customerAddressService;
        resource.customerPortalService = customerPortalService;

        Response response = resource.updateProfile(new CustomerResource.ProfileUpdateRequest());

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertSame(portalProfile, response.getEntity());
        verify(customerPortalService).getPortalProfile("shopper@example.com");
    }
}
