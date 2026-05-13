package org.ecommerce.backend.api.graphql;

import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.ecommerce.backend.service.WholesaleCustomerService;
import org.ecommerce.common.dto.WholesaleCustomerDto;

import java.util.UUID;

@GraphQLApi
public class WholesaleCustomerResource {

    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Mutation("createWholesaleCustomer")
    public WholesaleCustomerDto createWholesaleCustomer(@Name("customer") WholesaleCustomerDto customerDto) {
        return wholesaleCustomerService.createWholesaleCustomer(customerDto);
    }

    @Mutation("updateWholesaleCustomer")
    public WholesaleCustomerDto updateWholesaleCustomer(
            @Name("id") UUID id,
            @Name("customer") WholesaleCustomerDto customerDto
    ) {
        return wholesaleCustomerService.updateWholesaleCustomer(id, customerDto);
    }
}

