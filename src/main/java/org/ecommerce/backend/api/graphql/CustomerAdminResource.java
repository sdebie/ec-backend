package org.ecommerce.backend.api.graphql;

import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.backend.service.CustomerAdminService;
import org.ecommerce.common.dto.AdminCustomerDetailDto;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;

import java.util.List;
import java.util.UUID;

@GraphQLApi
public class CustomerAdminResource
{

    @Inject
    CustomerAdminService customerAdminService;

    @Query("allCustomers")
    public List<AdminCustomerListItemDto> allCustomers(@Name("pageRequest") PageRequest pageRequest, @Name("filterRequest") FilterRequest filterRequest)
    {
        try {
            return customerAdminService.allCustomers(pageRequest, filterRequest);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Query("customerCount")
    public long customerCount(@Name("filterRequest") FilterRequest filterRequest)
    {
        try {
            return customerAdminService.customerCount(filterRequest);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Query("adminCustomer")
    public AdminCustomerDetailDto adminCustomer(@Name("id") UUID id)
    {
        try {
            return customerAdminService.adminCustomer(id);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("updateCustomerStatus")
    public AdminCustomerListItemDto updateCustomerStatus(@Name("id") UUID id, @Name("status") String status)
    {
        try {
            return customerAdminService.updateCustomerStatus(id, status);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    private RuntimeException toGraphQlException(RuntimeException ex)
    {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = "customer admin operation failed";
        }
        return new IllegalArgumentException(message, ex);
    }
}
