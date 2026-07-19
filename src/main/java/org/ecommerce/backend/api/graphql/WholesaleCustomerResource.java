package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.backend.exception.RateLimitExceededException;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.service.WholesaleCustomerService;
import org.ecommerce.backend.utils.CurrentRequestClientIp;
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

    @Inject
    RateLimiterService rateLimiterService;

    @Inject
    CurrentRequestClientIp currentRequestClientIp;

    @Query("allWholesaleApplications")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
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
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public long wholesaleApplicationCount(@Name("filterRequest") FilterRequest filterRequest) {
        try {
            return wholesaleCustomerService.wholesaleApplicationCount(filterRequest);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Query("wholesaleApplication")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public WholesaleApplicationDetailsDto getWholesaleApplication(@Name("id") UUID id) {
        try {
            return wholesaleCustomerService.getWholesaleApplicationById(id);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("createWholesaleApplication")
    public WholesaleCustomerDto createWholesaleApplication(@Name("customer") WholesaleCustomerDto customerDto) {
        String clientIp = currentRequestClientIp.resolve();
        RateLimitDecision decision = rateLimiterService.check("wholesale-application", clientIp, 3, 3600);
        if (!decision.allowed()) {
            throw new RateLimitExceededException();
        }

        try {
            return wholesaleCustomerService.createWholesaleApplication(customerDto);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("updateWholesaleCustomer")
    @RolesAllowed("SUPER_ADMIN")
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
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public WholesaleApplicationDetailsDto approveWholesaleApplication(@Name("id") UUID id) {
        try {
            return wholesaleCustomerService.approveWholesaleApplication(id);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("rejectWholesaleApplication")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
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

