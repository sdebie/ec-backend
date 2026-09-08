package org.ecommerce.backend.service;

import org.ecommerce.backend.mapper.CustomerProfileMapper;
import org.ecommerce.common.dto.CustomerProfileDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerPortalServiceProfileTest
{
    private CustomerRepository customerRepository;
    private CustomerProfileMapper customerProfileMapper;
    private CustomerPortalService service;

    @BeforeEach
    void setUp()
    {
        customerRepository = mock(CustomerRepository.class);
        customerProfileMapper = mock(CustomerProfileMapper.class);
        service = new CustomerPortalService();
        service.customerRepository = customerRepository;
        service.customerProfileMapper = customerProfileMapper;
    }

    @Test
    void getPortalProfileMapsThroughTheSharedMapper()
    {
        CustomerEntity customer = new CustomerEntity();
        CustomerProfileDto mapped = new CustomerProfileDto();
        when(customerRepository.findByEmail("shopper@example.com")).thenReturn(customer);
        when(customerProfileMapper.toProfileDto(customer)).thenReturn(mapped);

        assertSame(mapped, service.getPortalProfile("shopper@example.com"));
        verify(customerProfileMapper).toProfileDto(customer);
    }
}
