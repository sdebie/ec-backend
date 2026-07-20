package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.CustomerDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Direct unit test for {@link OrderMapper#toCustomerDto}, the single source of truth
 * for CustomerEntity → {@link CustomerDto} (previously duplicated inline in OrderService).
 * Pure field copy — no DB access — so it runs without Quarkus.
 */
@DisplayName("OrderMapper.toCustomerDto")
class OrderMapperCustomerDtoTest {

    private final OrderMapper mapper = new OrderMapper();

    @Test
    @DisplayName("null customer maps to null")
    void nullCustomer() {
        assertNull(mapper.toCustomerDto(null));
    }

    @Test
    @DisplayName("email is taken from the customer's user")
    void emailFromUser() {
        CustomerEntity customer = new CustomerEntity();
        UserEntity user = new UserEntity();
        user.email = "buyer@test.co";
        customer.user = user;

        CustomerDto dto = mapper.toCustomerDto(customer);
        assertEquals("buyer@test.co", dto.getEmail());
    }

    @Test
    @DisplayName("email is null when the customer has no user")
    void emailNullWhenNoUser() {
        CustomerDto dto = mapper.toCustomerDto(new CustomerEntity());
        assertNull(dto.getEmail());
    }
}
