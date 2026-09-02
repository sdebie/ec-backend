package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.CustomerRepository;
import org.ecommerce.common.repository.OrderRepository;
import org.ecommerce.common.repository.QuoteRequestRepository;
import org.ecommerce.common.repository.StaffRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DB-backed proof that the field-level filter/sort allowlist actually closes the reachability
 * gap it was built for — not just that the unit-level {@code PanacheQueryBuilder} mechanism
 * works in isolation (see {@code PanacheQueryBuilderAllowlistTest} in ec-common), but that the
 * real, previously-live exploit path is rejected end to end through the actual repositories a
 * GraphQL resolver calls.
 * <p>
 * {@code customerCount_rejectsFilteringByPasswordResetCodeHash} reproduces the exact documented
 * vulnerability verbatim: a VIEWER-role-reachable {@code customerCount} call filtering on
 * {@code user.passwordResetCodeHash} with ILIKE, which previously let a caller binary-search a
 * customer's password-reset-code hash one character at a time via the returned count. It must
 * now throw rather than execute.
 */
@QuarkusTest
class FieldFilterAllowlistSecurityTest
{
    @Inject
    CustomerRepository customerRepository;

    @Inject
    StaffRepository staffRepository;

    @Inject
    OrderRepository orderRepository;

    @Inject
    QuoteRequestRepository quoteRequestRepository;

    private FilterRequest filter(String key, FilterOperator operator, String value)
    {
        FilterRequest request = new FilterRequest();
        request.setFilters(List.of(new Filter(key, operator, value)));
        return request;
    }

    @Test
    @TestTransaction
    @DisplayName("the documented exploit: customerCount can no longer filter by user.passwordResetCodeHash")
    void customerCount_rejectsFilteringByPasswordResetCodeHash()
    {
        FilterRequest exploit = filter("user.passwordResetCodeHash", FilterOperator.ILIKE, "a");

        assertThrows(IllegalArgumentException.class, () -> customerRepository.countForAdmin(exploit));
        assertThrows(IllegalArgumentException.class, () -> customerRepository.findForAdmin(exploit, new PageRequest()));
    }

    @Test
    @TestTransaction
    @DisplayName("the same oracle shape against the customer's own password hash, not just the reset code")
    void customerCount_rejectsFilteringByPasswordHash()
    {
        FilterRequest exploit = filter("user.passwordHash", FilterOperator.EQUALS, "irrelevant");
        assertThrows(IllegalArgumentException.class, () -> customerRepository.countForAdmin(exploit));
    }

    @Test
    @TestTransaction
    @DisplayName("legitimate customer filtering still works after the allowlist was added")
    void customerCount_stillPermitsFilteringByStatus()
    {
        String marker = "ZZALLOWLIST-" + UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setEmail(marker.toLowerCase() + "@test.example");
        user.setPasswordHash("irrelevant-test-hash");
        user.persist();

        CustomerEntity customer = new CustomerEntity();
        customer.setUser(user);
        customer.setFirstName(marker);
        customer.setLastName("Customer");
        customer.setShopperType(CustomerTypeEn.RETAILER);
        customer.setStatus(CustomerStatusEn.ACTIVE);
        customer.persist();

        FilterRequest legitimate = new FilterRequest();
        legitimate.setFilters(List.of(
                new Filter("firstName", FilterOperator.EQUALS, marker),
                new Filter("status", FilterOperator.EQUALS, "ACTIVE")));

        long count = assertDoesNotThrow(() -> customerRepository.countForAdmin(legitimate));
        assertEquals(1, count, "the allowlist must not reject legitimate, real-world filter keys");
    }

    @Test
    @TestTransaction
    @DisplayName("staffCount cannot turn a filter into a password-hash oracle")
    void staffCount_rejectsFilteringByPasswordHash()
    {
        FilterRequest exploit = filter("passwordHash", FilterOperator.ILIKE, "a");
        assertThrows(IllegalArgumentException.class, () -> staffRepository.count(exploit));
    }

    @Test
    @TestTransaction
    @DisplayName("staffCount cannot probe the password-reset lockout counter either")
    void staffCount_rejectsFilteringByResetCodeAttempts()
    {
        FilterRequest exploit = filter("passwordResetCodeAttempts", FilterOperator.GREATER_THAN, "0");
        assertThrows(IllegalArgumentException.class, () -> staffRepository.count(exploit));
    }

    @Test
    @TestTransaction
    @DisplayName("the same oracle shape reached one hop further, through Order -> customer -> user")
    void orderFindAll_rejectsFilteringByCustomersPasswordHash()
    {
        FilterRequest exploit = filter("customerEntity.user.passwordHash", FilterOperator.EQUALS, "irrelevant");
        assertThrows(IllegalArgumentException.class, () -> orderRepository.findAll(new PageRequest(), exploit));
    }

    @Test
    @TestTransaction
    @DisplayName("the same oracle shape reached through QuoteRequest -> quotedBy (StaffUserEntity)")
    void quoteRequestCount_rejectsFilteringByQuotedByPasswordHash()
    {
        FilterRequest exploit = filter("quotedBy.passwordHash", FilterOperator.ILIKE, "a");
        assertThrows(IllegalArgumentException.class, () -> quoteRequestRepository.count(exploit));
    }
}
