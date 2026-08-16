package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.SortRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.query.enums.SortDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DB-backed tests proving {@code allCustomers}/{@code customerCount} honour a sort — the gap
 * found when consolidating admin table sorting onto {@code useTableSort} on the frontend:
 * {@code CustomerRepository} built its own JPQL by hand and never read
 * {@code FilterRequest.getSort()} at all, so any sort sent here was silently dropped.
 * <p>
 * These run against the shared local database, so every fixture is tagged with a per-test
 * UUID marker in its first name and every query filters on that marker via the same
 * {@code search} key the frontend sends — the one thing {@link CustomerRepository} still
 * translates by hand — which keeps every assertion exact regardless of what else is in the
 * database.
 */
@QuarkusTest
class CustomerAdminServiceSortingIT
{
    @Inject
    CustomerAdminService customerAdminService;

    private String marker;

    private CustomerEntity newCustomer(String firstNameSuffix, String email, CustomerStatusEn status)
    {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash("irrelevant-test-hash");
        user.persist();

        CustomerEntity customer = new CustomerEntity();
        customer.setUser(user);
        customer.setFirstName(marker + "-" + firstNameSuffix);
        customer.setLastName("Sort");
        customer.setShopperType(CustomerTypeEn.RETAILER);
        customer.setStatus(status);
        customer.persist();
        return customer;
    }

    private FilterRequest markerSearch(SortRequest sort)
    {
        FilterRequest request = new FilterRequest();
        request.setFilters(List.of(new Filter("search", FilterOperator.LIKE, marker)));
        if (sort != null) {
            request.setSort(List.of(sort));
        }
        return request;
    }

    private SortRequest sortBy(String field, SortDirection direction)
    {
        SortRequest sort = new SortRequest();
        sort.setField(field);
        sort.setDirection(direction);
        return sort;
    }

    @Test
    @TestTransaction
    @DisplayName("sorts by a plain entity field (firstName) in both directions")
    void allCustomers_sortByFirstName_ordersBothDirections()
    {
        marker = "sort-" + UUID.randomUUID();
        newCustomer("Beta", marker.toLowerCase() + "-b@test.example", CustomerStatusEn.ACTIVE);
        newCustomer("Alpha", marker.toLowerCase() + "-a@test.example", CustomerStatusEn.ACTIVE);

        List<AdminCustomerListItemDto> ascending = customerAdminService.allCustomers(
                new PageRequest(), markerSearch(sortBy("firstName", SortDirection.ASC)));
        assertEquals(List.of(marker + "-Alpha", marker + "-Beta"),
                ascending.stream().map(AdminCustomerListItemDto::getFirstName).toList());

        List<AdminCustomerListItemDto> descending = customerAdminService.allCustomers(
                new PageRequest(), markerSearch(sortBy("firstName", SortDirection.DESC)));
        assertEquals(List.of(marker + "-Beta", marker + "-Alpha"),
                descending.stream().map(AdminCustomerListItemDto::getFirstName).toList());
    }

    /**
     * The reason this whole fix exists: registeredAt is not a column on CustomerEntity, it is
     * user.createdAt through the linked account — a JOIN, not a schema change, is what makes
     * this sortable.
     */
    @Test
    @TestTransaction
    @DisplayName("sorts by registration date through the linked user account, newest first")
    void allCustomers_sortByRegisteredAt_ordersThroughUserJoin() throws InterruptedException
    {
        marker = "sort-" + UUID.randomUUID();
        CustomerEntity first = newCustomer("First", marker.toLowerCase() + "-first@test.example", CustomerStatusEn.ACTIVE);
        // A real millisecond gap, since user.createdAt is a database-assigned timestamp with
        // no test hook to stamp — two rows persisted in the same instant would leave the
        // ordering this test exists to prove genuinely undefined, not merely unasserted.
        Thread.sleep(5);
        CustomerEntity second = newCustomer("Second", marker.toLowerCase() + "-second@test.example", CustomerStatusEn.ACTIVE);

        List<AdminCustomerListItemDto> newestFirst = customerAdminService.allCustomers(
                new PageRequest(), markerSearch(sortBy("user.createdAt", SortDirection.DESC)));

        assertEquals(List.of(second.getId().toString(), first.getId().toString()),
                newestFirst.stream().map(AdminCustomerListItemDto::getId).toList());
    }

    @Test
    @TestTransaction
    @DisplayName("sorts by email through the linked user account")
    void allCustomers_sortByEmail_ordersThroughUserJoin()
    {
        marker = "sort-" + UUID.randomUUID();
        newCustomer("X", marker.toLowerCase() + "-zzz@test.example", CustomerStatusEn.ACTIVE);
        newCustomer("Y", marker.toLowerCase() + "-aaa@test.example", CustomerStatusEn.ACTIVE);

        List<AdminCustomerListItemDto> ascending = customerAdminService.allCustomers(
                new PageRequest(), markerSearch(sortBy("user.email", SortDirection.ASC)));

        assertEquals(List.of(marker.toLowerCase() + "-aaa@test.example", marker.toLowerCase() + "-zzz@test.example"),
                ascending.stream().map(AdminCustomerListItemDto::getEmail).toList());
    }

    @Test
    @TestTransaction
    @DisplayName("still applies the status filter and the search expansion when a sort is also given")
    void allCustomers_sortWithOtherFilters_stillFilters()
    {
        marker = "sort-" + UUID.randomUUID();
        newCustomer("Active", marker.toLowerCase() + "-active@test.example", CustomerStatusEn.ACTIVE);
        newCustomer("Pending", marker.toLowerCase() + "-pending@test.example", CustomerStatusEn.PENDING);

        FilterRequest request = markerSearch(sortBy("firstName", SortDirection.ASC));
        request.setFilters(List.of(
                new Filter("search", FilterOperator.LIKE, marker),
                new Filter("status", FilterOperator.EQUALS, "ACTIVE")
        ));

        List<AdminCustomerListItemDto> results = customerAdminService.allCustomers(new PageRequest(), request);

        assertEquals(1, results.size());
        assertEquals(marker + "-Active", results.get(0).getFirstName());
    }

    @Test
    @TestTransaction
    @DisplayName("count is unaffected by sort — the same rows match with or without one")
    void customerCount_withSort_countsTheSameRows()
    {
        marker = "sort-" + UUID.randomUUID();
        newCustomer("One", marker.toLowerCase() + "-one@test.example", CustomerStatusEn.ACTIVE);
        newCustomer("Two", marker.toLowerCase() + "-two@test.example", CustomerStatusEn.ACTIVE);

        long withSort = customerAdminService.customerCount(markerSearch(sortBy("firstName", SortDirection.ASC)));
        long withoutSort = customerAdminService.customerCount(markerSearch(null));

        assertEquals(2, withSort);
        assertEquals(2, withoutSort);
    }
}
