package org.ecommerce.backend.mapper;

// Feature: service-layer-refactor, Property 3: Mapper output preservation (customer admin)

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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test verifying that the extracted {@link CustomerAdminMapper} pure methods
 * produce output identical to the old inline mapping logic that previously lived in
 * {@code CustomerAdminService}.
 * <p>
 * Tests the pure (non-query-bearing) methods:
 * <ul>
 *   <li>{@code toListItemDto(CustomerEntity, WholesaleApplicationEntity)} — pure field copy</li>
 *   <li>{@code toDetailDto(CustomerEntity, WholesaleApplicationEntity, List<OrderEntity>)} — pure</li>
 *   <li>{@code toOrderRefDto(OrderEntity)} — pure</li>
 * </ul>
 * <p>
 * The reference implementation below is a direct transcription of the deleted inline
 * methods from CustomerAdminService — a field-by-field copy with null-safe navigation.
 * <p>
 */
public class CustomerAdminMapperOutputPreservationPropertyTest
{
    // Instantiate the mapper with a real WholesaleMapperImpl for delegation
    private final CustomerAdminMapper mapper;

    public CustomerAdminMapperOutputPreservationPropertyTest()
    {
        CustomerAdminMapper m = new CustomerAdminMapperImpl();
        // Inject the real WholesaleMapper (MapStruct-generated) via reflection
        try {
            var field = CustomerAdminMapperImpl.class.getDeclaredField("wholesaleMapper");
            field.setAccessible(true);
            field.set(m, new WholesaleMapperImpl());
            var tsField = CustomerAdminMapperImpl.class.getDeclaredField("timestampMapper");
            tsField.setAccessible(true);
            tsField.set(m, new TimestampMapperImpl());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to inject WholesaleMapper into CustomerAdminMapper", e);
        }
        this.mapper = m;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Reference implementations (old inline logic from CustomerAdminService)
    // ══════════════════════════════════════════════════════════════════════════

    private AdminCustomerListItemDto referenceToListItemDto(CustomerEntity c, WholesaleApplicationEntity app)
    {
        AdminCustomerListItemDto dto = new AdminCustomerListItemDto();
        dto.setId(c.getId().toString());
        dto.setFirstName(c.getFirstName());
        dto.setLastName(c.getLastName());
        dto.setEmail(c.getUser() != null ? c.getUser().getEmail() : null);
        dto.setStatus(c.getStatus() != null ? c.getStatus().name() : null);
        dto.setShopperType(c.getShopperType() != null ? c.getShopperType().name() : null);
        dto.setRegisteredAt(c.getUser() != null && c.getUser().getCreatedAt() != null ? c.getUser().getCreatedAt().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null);
        dto.setWholesaleApplicationStatus(app != null && app.getStatus() != null ? app.getStatus().name() : null);
        return dto;
    }

    private AdminOrderRefDto referenceToOrderRefDto(OrderEntity o)
    {
        AdminOrderRefDto dto = new AdminOrderRefDto();
        dto.setId(o.getId().toString());
        dto.setReference("ORD-" + o.getId().toString().substring(0, 8).toUpperCase());
        dto.setPlacedAt(o.getCreatedAt() != null ? o.getCreatedAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        dto.setTotal(o.getTotalAmount() != null ? o.getTotalAmount().doubleValue() : 0.0);
        dto.setStatus(o.getStatus() != null ? o.getStatus().name() : null);
        return dto;
    }

    private AdminCustomerDetailDto referenceToDetailDto(CustomerEntity c,
                                                        WholesaleApplicationEntity app,
                                                        List<OrderEntity> orders)
    {
        AdminCustomerDetailDto dto = new AdminCustomerDetailDto();
        dto.setId(c.getId().toString());
        dto.setFirstName(c.getFirstName());
        dto.setLastName(c.getLastName());
        dto.setEmail(c.getUser() != null ? c.getUser().getEmail() : null);
        dto.setPhone(c.getPhone());
        dto.setStatus(c.getStatus() != null ? c.getStatus().name() : null);
        dto.setShopperType(c.getShopperType() != null ? c.getShopperType().name() : null);
        dto.setRegisteredAt(c.getUser() != null && c.getUser().getCreatedAt() != null ? c.getUser().getCreatedAt().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null);

        // Wholesale application delegates to WholesaleMapper — use same impl
        WholesaleMapper wholesaleMapper = new WholesaleMapperImpl();
        dto.setWholesaleApplication(app != null ? wholesaleMapper.toDetailsDto(app) : null);

        dto.setRecentOrders(orders.stream()
                .map(this::referenceToOrderRefDto)
                .toList());

        return dto;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Property: toListItemDto(c, app) output preservation
    // ══════════════════════════════════════════════════════════════════════════

    @Property(tries = 200)
    void toListItemDto_pureOverload_matchesOldInlineLogic(@ForAll("customerEntities") CustomerEntity customer, @ForAll("nullableWholesaleApps") WholesaleApplicationEntity app)
    {
        AdminCustomerListItemDto mapperResult = mapper.toListItemDto(customer, app);
        AdminCustomerListItemDto referenceResult = referenceToListItemDto(customer, app);

        assertEquals(referenceResult.getId(), mapperResult.getId(), "id mismatch");
        assertEquals(referenceResult.getFirstName(), mapperResult.getFirstName(), "firstName mismatch");
        assertEquals(referenceResult.getLastName(), mapperResult.getLastName(), "lastName mismatch");
        assertEquals(referenceResult.getEmail(), mapperResult.getEmail(), "email mismatch");
        assertEquals(referenceResult.getStatus(), mapperResult.getStatus(), "status mismatch");
        assertEquals(referenceResult.getShopperType(), mapperResult.getShopperType(), "shopperType mismatch");
        assertEquals(referenceResult.getRegisteredAt(), mapperResult.getRegisteredAt(), "registeredAt mismatch");
        assertEquals(referenceResult.getWholesaleApplicationStatus(), mapperResult.getWholesaleApplicationStatus(), "wholesaleApplicationStatus mismatch");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Property: toOrderRefDto(o) output preservation
    // ══════════════════════════════════════════════════════════════════════════

    @Property(tries = 200)
    void toOrderRefDto_matchesOldInlineLogic(
            @ForAll("orderEntities") OrderEntity order
    )
    {
        AdminOrderRefDto mapperResult = mapper.toOrderRefDto(order);
        AdminOrderRefDto referenceResult = referenceToOrderRefDto(order);

        assertEquals(referenceResult.getId(), mapperResult.getId(), "id mismatch");
        assertEquals(referenceResult.getReference(), mapperResult.getReference(), "reference mismatch");
        assertEquals(referenceResult.getPlacedAt(), mapperResult.getPlacedAt(), "placedAt mismatch");
        assertEquals(referenceResult.getTotal(), mapperResult.getTotal(), 0.001, "total mismatch");
        assertEquals(referenceResult.getStatus(), mapperResult.getStatus(), "status mismatch");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Property: toDetailDto(c, app, orders) output preservation
    // ══════════════════════════════════════════════════════════════════════════

    @Property(tries = 200)
    void toDetailDto_matchesOldInlineLogic(@ForAll("customerEntities") CustomerEntity customer, @ForAll("nullableWholesaleApps") WholesaleApplicationEntity app, @ForAll("orderLists") List<OrderEntity> orders)
    {
        AdminCustomerDetailDto mapperResult = mapper.toDetailDto(customer, app, orders);
        AdminCustomerDetailDto referenceResult = referenceToDetailDto(customer, app, orders);

        // Customer fields
        assertEquals(referenceResult.getId(), mapperResult.getId(), "id mismatch");
        assertEquals(referenceResult.getFirstName(), mapperResult.getFirstName(), "firstName mismatch");
        assertEquals(referenceResult.getLastName(), mapperResult.getLastName(), "lastName mismatch");
        assertEquals(referenceResult.getEmail(), mapperResult.getEmail(), "email mismatch");
        assertEquals(referenceResult.getPhone(), mapperResult.getPhone(), "phone mismatch");
        assertEquals(referenceResult.getStatus(), mapperResult.getStatus(), "status mismatch");
        assertEquals(referenceResult.getShopperType(), mapperResult.getShopperType(), "shopperType mismatch");
        assertEquals(referenceResult.getRegisteredAt(), mapperResult.getRegisteredAt(), "registeredAt mismatch");

        // Wholesale application
        assertWholesaleApplicationEquals(referenceResult.getWholesaleApplication(), mapperResult.getWholesaleApplication());

        // Recent orders
        assertNotNull(mapperResult.getRecentOrders());
        assertEquals(referenceResult.getRecentOrders().size(), mapperResult.getRecentOrders().size(), "recentOrders size mismatch");
        for (int i = 0; i < referenceResult.getRecentOrders().size(); i++) {
            AdminOrderRefDto refOrder = referenceResult.getRecentOrders().get(i);
            AdminOrderRefDto mapOrder = mapperResult.getRecentOrders().get(i);
            assertEquals(refOrder.getId(), mapOrder.getId(), "order[" + i + "].id mismatch");
            assertEquals(refOrder.getReference(), mapOrder.getReference(), "order[" + i + "].reference mismatch");
            assertEquals(refOrder.getPlacedAt(), mapOrder.getPlacedAt(), "order[" + i + "].placedAt mismatch");
            assertEquals(refOrder.getTotal(), mapOrder.getTotal(), 0.001, "order[" + i + "].total mismatch");
            assertEquals(refOrder.getStatus(), mapOrder.getStatus(), "order[" + i + "].status mismatch");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void assertWholesaleApplicationEquals(WholesaleApplicationDetailsDto expected, WholesaleApplicationDetailsDto actual)
    {
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
    Arbitrary<CustomerEntity> customerEntities()
    {
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
                    c.setId(id);
                    c.setFirstName(firstName);
                    c.setLastName(lastName);
                    c.setPhone(phone);
                    c.setStatus(status);
                    c.setShopperType(shopperType);
                    c.setUser(user);
                    return c;
                });
    }

    private Arbitrary<UserEntity> buildUserEntities()
    {
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
                    u.setId(UUID.randomUUID());
                    u.setEmail(email);
                    u.setCreatedAt(createdAt);
                    return u;
                });
    }

    @Provide
    Arbitrary<WholesaleApplicationEntity> nullableWholesaleApps()
    {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                buildWholesaleApps()
        );
    }

    private Arbitrary<WholesaleApplicationEntity> buildWholesaleApps()
    {
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
                    c.setId(id);
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
                    e.setId(id);
                    e.setStatus(status);
                    e.setFirstName(firstName);
                    e.setCompanyName(companyName);
                    e.setAccountEmail(accountEmail);
                    e.setCustomer(customer);
                    e.setCreatedAt(createdAt);
                    return e;
                });
    }

    @Provide
    Arbitrary<OrderEntity> orderEntities()
    {
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
                    o.setId(id);
                    o.setTotalAmount(totalAmount);
                    o.setStatus(status);
                    o.setCreatedAt(createdAt);
                    return o;
                });
    }

    @Provide
    Arbitrary<List<OrderEntity>> orderLists()
    {
        return orderEntities().list().ofMinSize(0).ofMaxSize(5);
    }
}
