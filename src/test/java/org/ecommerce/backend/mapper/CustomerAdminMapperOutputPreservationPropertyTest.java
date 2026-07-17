package org.ecommerce.backend.mapper;

// Feature: service-layer-refactor, Property 3: Mapper output preservation (customer admin)
// Validates: Requirements 1.3, 2.4, 4.2, 4.4

import net.jqwik.api.*;
import org.ecommerce.common.dto.AdminCustomerDetailDto;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.dto.AdminOrderRefDto;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test verifying that the extracted {@link CustomerAdminMapper} pure methods
 * produce output identical to the old inline mapping logic that previously lived in
 * {@code CustomerAdminService}.
 *
 * Tests the pure (non-query-bearing) methods:
 * <ul>
 *   <li>{@code toListItemDto(CustomerEntity, WholesaleApplicationEntity)} — pure field copy</li>
 *   <li>{@code toDetailDto(CustomerEntity, WholesaleApplicationEntity, List<OrderEntity>)} — pure</li>
 *   <li>{@code toOrderRefDto(OrderEntity)} — pure</li>
 * </ul>
 *
 * The reference implementation below is a direct transcription of the deleted inline
 * methods from CustomerAdminService — a field-by-field copy with null-safe navigation.
 *
 * Validates: Requirements 1.3, 2.4, 4.2, 4.4
 */
public class CustomerAdminMapperOutputPreservationPropertyTest {

    // Instantiate the mapper with a real WholesaleMapperImpl for delegation
    private final CustomerAdminMapper mapper;

    public CustomerAdminMapperOutputPreservationPropertyTest() {
        CustomerAdminMapper m = new CustomerAdminMapper();
        // Inject the real WholesaleMapper (MapStruct-generated) via reflection
        try {
            var field = CustomerAdminMapper.class.getDeclaredField("wholesaleMapper");
            field.setAccessible(true);
            field.set(m, new WholesaleMapperImpl());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to inject WholesaleMapper into CustomerAdminMapper", e);
        }
        this.mapper = m;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Reference implementations (old inline logic from CustomerAdminService)
    // ══════════════════════════════════════════════════════════════════════════

    private AdminCustomerListItemDto referenceToListItemDto(CustomerEntity c, WholesaleApplicationEntity app) {
        AdminCustomerListItemDto dto = new AdminCustomerListItemDto();
        dto.id = c.id.toString();
        dto.firstName = c.firstName;
        dto.lastName = c.lastName;
        dto.email = c.user != null ? c.user.email : null;
        dto.status = c.status != null ? c.status.name() : null;
        dto.shopperType = c.shopperType != null ? c.shopperType.name() : null;
        dto.registeredAt = c.user != null && c.user.createdAt != null
                ? c.user.createdAt.toString()
                : null;
        dto.wholesaleApplicationStatus = app != null && app.status != null
                ? app.status.name()
                : null;
        return dto;
    }

    private AdminOrderRefDto referenceToOrderRefDto(OrderEntity o) {
        AdminOrderRefDto dto = new AdminOrderRefDto();
        dto.id = o.id.toString();
        dto.reference = "ORD-" + o.id.toString().substring(0, 8).toUpperCase();
        dto.placedAt = o.createdAt != null ? o.createdAt.toString() : null;
        dto.total = o.totalAmount != null ? o.totalAmount.doubleValue() : 0.0;
        dto.status = o.status != null ? o.status.name() : null;
        return dto;
    }

    private AdminCustomerDetailDto referenceToDetailDto(CustomerEntity c,
                                                        WholesaleApplicationEntity app,
                                                        List<OrderEntity> orders) {
        AdminCustomerDetailDto dto = new AdminCustomerDetailDto();
        dto.id = c.id.toString();
        dto.firstName = c.firstName;
        dto.lastName = c.lastName;
        dto.email = c.user != null ? c.user.email : null;
        dto.phone = c.phone;
        dto.status = c.status != null ? c.status.name() : null;
        dto.shopperType = c.shopperType != null ? c.shopperType.name() : null;
        dto.registeredAt = c.user != null && c.user.createdAt != null
                ? c.user.createdAt.toString()
                : null;

        // Wholesale application delegates to WholesaleMapper — use same impl
        WholesaleMapper wholesaleMapper = new WholesaleMapperImpl();
        dto.wholesaleApplication = app != null ? wholesaleMapper.toDetailsDto(app) : null;

        dto.recentOrders = orders.stream()
                .map(this::referenceToOrderRefDto)
                .toList();

        return dto;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Property: toListItemDto(c, app) output preservation
    // ══════════════════════════════════════════════════════════════════════════

    @Property(tries = 200)
    void toListItemDto_pureOverload_matchesOldInlineLogic(
            @ForAll("customerEntities") CustomerEntity customer,
            @ForAll("nullableWholesaleApps") WholesaleApplicationEntity app
    ) {
        AdminCustomerListItemDto mapperResult = mapper.toListItemDto(customer, app);
        AdminCustomerListItemDto referenceResult = referenceToListItemDto(customer, app);

        assertEquals(referenceResult.id, mapperResult.id, "id mismatch");
        assertEquals(referenceResult.firstName, mapperResult.firstName, "firstName mismatch");
        assertEquals(referenceResult.lastName, mapperResult.lastName, "lastName mismatch");
        assertEquals(referenceResult.email, mapperResult.email, "email mismatch");
        assertEquals(referenceResult.status, mapperResult.status, "status mismatch");
        assertEquals(referenceResult.shopperType, mapperResult.shopperType, "shopperType mismatch");
        assertEquals(referenceResult.registeredAt, mapperResult.registeredAt, "registeredAt mismatch");
        assertEquals(referenceResult.wholesaleApplicationStatus, mapperResult.wholesaleApplicationStatus,
                "wholesaleApplicationStatus mismatch");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Property: toOrderRefDto(o) output preservation
    // ══════════════════════════════════════════════════════════════════════════

    @Property(tries = 200)
    void toOrderRefDto_matchesOldInlineLogic(
            @ForAll("orderEntities") OrderEntity order
    ) {
        AdminOrderRefDto mapperResult = mapper.toOrderRefDto(order);
        AdminOrderRefDto referenceResult = referenceToOrderRefDto(order);

        assertEquals(referenceResult.id, mapperResult.id, "id mismatch");
        assertEquals(referenceResult.reference, mapperResult.reference, "reference mismatch");
        assertEquals(referenceResult.placedAt, mapperResult.placedAt, "placedAt mismatch");
        assertEquals(referenceResult.total, mapperResult.total, 0.001, "total mismatch");
        assertEquals(referenceResult.status, mapperResult.status, "status mismatch");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Property: toDetailDto(c, app, orders) output preservation
    // ══════════════════════════════════════════════════════════════════════════

    @Property(tries = 200)
    void toDetailDto_matchesOldInlineLogic(
            @ForAll("customerEntities") CustomerEntity customer,
            @ForAll("nullableWholesaleApps") WholesaleApplicationEntity app,
            @ForAll("orderLists") List<OrderEntity> orders
    ) {
        AdminCustomerDetailDto mapperResult = mapper.toDetailDto(customer, app, orders);
        AdminCustomerDetailDto referenceResult = referenceToDetailDto(customer, app, orders);

        // Customer fields
        assertEquals(referenceResult.id, mapperResult.id, "id mismatch");
        assertEquals(referenceResult.firstName, mapperResult.firstName, "firstName mismatch");
        assertEquals(referenceResult.lastName, mapperResult.lastName, "lastName mismatch");
        assertEquals(referenceResult.email, mapperResult.email, "email mismatch");
        assertEquals(referenceResult.phone, mapperResult.phone, "phone mismatch");
        assertEquals(referenceResult.status, mapperResult.status, "status mismatch");
        assertEquals(referenceResult.shopperType, mapperResult.shopperType, "shopperType mismatch");
        assertEquals(referenceResult.registeredAt, mapperResult.registeredAt, "registeredAt mismatch");

        // Wholesale application
        assertWholesaleApplicationEquals(referenceResult.wholesaleApplication, mapperResult.wholesaleApplication);

        // Recent orders
        assertNotNull(mapperResult.recentOrders);
        assertEquals(referenceResult.recentOrders.size(), mapperResult.recentOrders.size(),
                "recentOrders size mismatch");
        for (int i = 0; i < referenceResult.recentOrders.size(); i++) {
            AdminOrderRefDto refOrder = referenceResult.recentOrders.get(i);
            AdminOrderRefDto mapOrder = mapperResult.recentOrders.get(i);
            assertEquals(refOrder.id, mapOrder.id, "order[" + i + "].id mismatch");
            assertEquals(refOrder.reference, mapOrder.reference, "order[" + i + "].reference mismatch");
            assertEquals(refOrder.placedAt, mapOrder.placedAt, "order[" + i + "].placedAt mismatch");
            assertEquals(refOrder.total, mapOrder.total, 0.001, "order[" + i + "].total mismatch");
            assertEquals(refOrder.status, mapOrder.status, "order[" + i + "].status mismatch");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void assertWholesaleApplicationEquals(WholesaleApplicationDetailsDto expected,
                                                  WholesaleApplicationDetailsDto actual) {
        if (expected == null) {
            assertNull(actual, "wholesaleApplication should be null");
            return;
        }
        assertNotNull(actual, "wholesaleApplication should not be null");
        assertEquals(expected.getId(), actual.getId(), "wholesaleApplication.id mismatch");
        assertEquals(expected.getEmail(), actual.getEmail(), "wholesaleApplication.email mismatch");
        assertEquals(expected.getFirstName(), actual.getFirstName(), "wholesaleApplication.firstName mismatch");
        assertEquals(expected.getLastName(), actual.getLastName(), "wholesaleApplication.lastName mismatch");
        assertEquals(expected.getCompanyName(), actual.getCompanyName(), "wholesaleApplication.companyName mismatch");
        assertEquals(expected.getStatus(), actual.getStatus(), "wholesaleApplication.status mismatch");
        assertEquals(expected.getCustomerId(), actual.getCustomerId(), "wholesaleApplication.customerId mismatch");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Providers
    // ══════════════════════════════════════════════════════════════════════════

    @Provide
    Arbitrary<CustomerEntity> customerEntities() {
        Arbitrary<UUID> ids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> nullableStrings = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
        );
        Arbitrary<CustomerStatusEn> nullableStatuses = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(CustomerStatusEn.values())
        );
        Arbitrary<CustomerTypeEn> nullableTypes = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(CustomerTypeEn.values())
        );
        Arbitrary<UserEntity> nullableUsers = Arbitraries.oneOf(
                Arbitraries.just(null),
                buildUserEntities()
        );

        return Combinators.combine(ids, nullableStrings, nullableStrings, nullableStrings,
                        nullableStatuses, nullableTypes, nullableUsers)
                .as((id, firstName, lastName, phone, status, shopperType, user) -> {
                    CustomerEntity c = new CustomerEntity();
                    c.id = id;
                    c.firstName = firstName;
                    c.lastName = lastName;
                    c.phone = phone;
                    c.status = status;
                    c.shopperType = shopperType;
                    c.user = user;
                    return c;
                });
    }

    private Arbitrary<UserEntity> buildUserEntities() {
        Arbitrary<String> emails = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(8)
                .map(s -> s.toLowerCase() + "@example.com");
        Arbitrary<OffsetDateTime> nullableCreatedAts = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.longs().between(0L, 2000000000L)
                        .map(epoch -> OffsetDateTime.ofInstant(
                                java.time.Instant.ofEpochSecond(epoch), ZoneOffset.UTC))
        );

        return Combinators.combine(emails, nullableCreatedAts)
                .as((email, createdAt) -> {
                    UserEntity u = new UserEntity();
                    u.id = UUID.randomUUID();
                    u.email = email;
                    u.createdAt = createdAt;
                    return u;
                });
    }

    @Provide
    Arbitrary<WholesaleApplicationEntity> nullableWholesaleApps() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                buildWholesaleApps()
        );
    }

    private Arbitrary<WholesaleApplicationEntity> buildWholesaleApps() {
        Arbitrary<UUID> ids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<WholesaleApplicationStatusEn> nullableStatuses = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(WholesaleApplicationStatusEn.values())
        );
        Arbitrary<String> nullableStrings = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
        );
        Arbitrary<CustomerEntity> nullableCustomers = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.create(UUID::randomUUID).map(id -> {
                    CustomerEntity c = new CustomerEntity();
                    c.id = id;
                    return c;
                })
        );
        Arbitrary<OffsetDateTime> nullableDates = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.longs().between(0L, 2000000000L)
                        .map(epoch -> OffsetDateTime.ofInstant(
                                java.time.Instant.ofEpochSecond(epoch), ZoneOffset.UTC))
        );

        return Combinators.combine(ids, nullableStatuses, nullableStrings, nullableStrings,
                        nullableStrings, nullableCustomers, nullableDates)
                .as((id, status, firstName, companyName, accountEmail, customer, createdAt) -> {
                    WholesaleApplicationEntity e = new WholesaleApplicationEntity();
                    e.id = id;
                    e.status = status;
                    e.firstName = firstName;
                    e.companyName = companyName;
                    e.accountEmail = accountEmail;
                    e.customer = customer;
                    e.createdAt = createdAt;
                    return e;
                });
    }

    @Provide
    Arbitrary<OrderEntity> orderEntities() {
        Arbitrary<UUID> ids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<BigDecimal> nullableTotals = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("100000.00"))
                        .ofScale(2)
        );
        Arbitrary<OrderStatusEn> nullableStatuses = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(OrderStatusEn.values())
        );
        Arbitrary<LocalDateTime> nullableCreatedAts = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.longs().between(0L, 2000000000L)
                        .map(epoch -> LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochSecond(epoch), ZoneOffset.UTC))
        );

        return Combinators.combine(ids, nullableTotals, nullableStatuses, nullableCreatedAts)
                .as((id, totalAmount, status, createdAt) -> {
                    OrderEntity o = new OrderEntity();
                    o.id = id;
                    o.totalAmount = totalAmount;
                    o.status = status;
                    o.createdAt = createdAt;
                    return o;
                });
    }

    @Provide
    Arbitrary<List<OrderEntity>> orderLists() {
        return orderEntities().list().ofMinSize(0).ofMaxSize(5);
    }
}
