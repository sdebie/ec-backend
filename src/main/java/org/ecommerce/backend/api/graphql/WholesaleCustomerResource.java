package org.ecommerce.backend.api.graphql;

import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.backend.service.WholesaleCustomerService;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.dto.WholesaleApplicationListItemDto;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;

import java.util.List;
import java.util.UUID;

@GraphQLApi
public class WholesaleCustomerResource {

    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Query("allWholesaleApplications")
    public List<WholesaleApplicationListItemDto> getAllWholesaleApplications(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest
    ) {
        try {
            return wholesaleCustomerService.getWholesaleApplications(pageRequest, filterRequest);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Query("wholesaleApplicationCount")
    public long wholesaleApplicationCount(@Name("filterRequest") FilterRequest filterRequest) {
        try {
            return wholesaleCustomerService.wholesaleApplicationCount(filterRequest);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Query("wholesaleApplication")
    public WholesaleApplicationDetailsDto getWholesaleApplication(@Name("id") UUID id) {
        try {
            return wholesaleCustomerService.getWholesaleApplicationById(id);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("createWholesaleCustomer")
    public WholesaleCustomerDto createWholesaleCustomer(@Name("applicationId") UUID applicationId) {
        try {
            return wholesaleCustomerService.createWholesaleCustomer(applicationId);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("createWholesaleApplication")
    public WholesaleCustomerDto createWholesaleApplication(@Name("customer") WholesaleCustomerDto customerDto) {
        try {
            return wholesaleCustomerService.createWholesaleApplication(customerDto);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("updateWholesaleCustomer")
    public WholesaleCustomerDto updateWholesaleCustomer(
            @Name("id") UUID id,
            @Name("customer") WholesaleCustomerDto customerDto
    ) {
        try {
            return wholesaleCustomerService.updateWholesaleCustomer(id, customerDto);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("approveWholesaleApplication")
    public WholesaleApplicationDetailsDto approveWholesaleApplication(@Name("id") UUID id) {
        try {
            return wholesaleCustomerService.approveWholesaleApplication(id);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("rejectWholesaleApplication")
    public WholesaleApplicationDetailsDto rejectWholesaleApplication(@Name("id") UUID id, @Name("reason") String reason) {
        try {
            return wholesaleCustomerService.rejectWholesaleApplication(id, reason);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    private RuntimeException toGraphQlException(RuntimeException ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = "wholesale mutation failed";
        }
        return new IllegalArgumentException(message, ex);
    }
}

