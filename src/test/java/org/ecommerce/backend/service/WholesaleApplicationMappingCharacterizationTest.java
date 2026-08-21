package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.AdminCustomerDetailDto;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.repository.WholesaleApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for wholesale application entity→DTO mapping.
 * <p>
 * Pins the current output of BOTH:
 * - WholesaleCustomerService.toDetailsDto (via getWholesaleApplicationById)
 * - CustomerAdminService.toApplicationDetailsDto (via adminCustomer)
 * <p>
 * These two services hand-map the same entity to the same DTO type.
 * This test captures their current behaviour as a baseline before
 * consolidation into a single WholesaleMapper.
 * <p>
 * Requirements: 4.2, 4.4
 */
@QuarkusTest
class WholesaleApplicationMappingCharacterizationTest
{
    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Inject
    CustomerAdminService customerAdminService;

    @InjectMock
    WholesaleApplicationRepository wholesaleApplicationRepository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(CustomerEntity.class);
        PanacheMock.mock(WholesaleApplicationEntity.class);
        PanacheMock.mock(OrderEntity.class);
    }

    // ── Test: Fully populated entity ────────────────────────────────────────

    @Test
    void toDetailsDto_fullyPopulated_pinsAllFields()
    {
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();

        when(wholesaleApplicationRepository.findById(app.getId())).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.getId());

        assertFullyPopulatedBaseline(result, app);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toApplicationDetailsDto_fullyPopulated_pinsAllFields()
    {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();

        CustomerEntity customer = buildCustomerEntity(customerId);
        app.setCustomer(customer);

        stubPanacheForAdminCustomer(customerId, customer, app);

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNotNull(detail.getWholesaleApplication(), "wholesaleApplication should be populated");
        assertFullyPopulatedBaseline(detail.getWholesaleApplication(), app);
    }

    @Test
    void bothServices_fullyPopulated_produceIdenticalOutput()
    {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();

        CustomerEntity customer = buildCustomerEntity(customerId);
        app.setCustomer(customer);

        // WholesaleCustomerService path
        when(wholesaleApplicationRepository.findById(app.getId())).thenReturn(app);
        WholesaleApplicationDetailsDto fromWholesaleService = wholesaleCustomerService.getWholesaleApplicationById(app.getId());

        // CustomerAdminService path
        stubPanacheForAdminCustomer(customerId, customer, app);
        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);
        WholesaleApplicationDetailsDto fromAdminService = detail.getWholesaleApplication();

        assertDtosEqual(fromWholesaleService, fromAdminService);
    }

    // ── Test: Null optional fields ──────────────────────────────────────────

    @Test
    void toDetailsDto_nullOptionalFields_pinsNullsCorrectly()
    {
        WholesaleApplicationEntity app = buildMinimalApplication();

        when(wholesaleApplicationRepository.findById(app.getId())).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.getId());

        assertMinimalBaseline(result, app);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toApplicationDetailsDto_nullOptionalFields_pinsNullsCorrectly()
    {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildMinimalApplication();

        CustomerEntity customer = buildCustomerEntity(customerId);
        app.setCustomer(customer);

        stubPanacheForAdminCustomer(customerId, customer, app);

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNotNull(detail.getWholesaleApplication());
        // In the admin path, the application was found via customer.id query,
        // so app.customer is set — customerId on DTO reflects the linked customer
        WholesaleApplicationDetailsDto dto = detail.getWholesaleApplication();
        assertEquals(app.getId(), dto.getId());
        assertEquals(app.getApplicantEmail(), dto.getApplicantEmail());
        assertEquals(app.getFirstName(), dto.getFirstName());
        assertEquals(app.getCompanyName(), dto.getCompanyName());
        assertEquals(app.getStatus(), dto.getStatus());
        assertEquals(app.getCreatedAt(), dto.getCreatedAt());
        assertEquals(customerId, dto.getCustomerId(), "customerId should match the linked customer");

        // All optional fields should be null
        assertNull(dto.getEmail(), "email (accountEmail) should be null");
        assertNull(dto.getLastName());
        assertNull(dto.getPhone());
        assertNull(dto.getTradingName());
        assertNull(dto.getCompanyPhone());
        assertNull(dto.getCompanyEmail());
        assertNull(dto.getVatNumber());
        assertNull(dto.getRegNumber());
        assertNull(dto.getFinanceContactName());
        assertNull(dto.getFinanceContactEmail());
        assertNull(dto.getFinanceContactPhone());
        assertNull(dto.getPurchaseOrderRequired());
        assertNull(dto.getNotes());
        assertNull(dto.getProcessedAt());

        // Address fields null
        assertNull(dto.getPhysicalAddress().getLine1());
        assertNull(dto.getPhysicalAddress().getLine2());
        assertNull(dto.getPhysicalAddress().getSuburb());
        assertNull(dto.getPhysicalAddress().getCity());
        assertNull(dto.getPhysicalAddress().getProvince());
        assertNull(dto.getPhysicalAddress().getPostalCode());
        assertNull(dto.getPostalAddress().getLine1());
        assertNull(dto.getPostalAddress().getLine2());
        assertNull(dto.getPostalAddress().getSuburb());
        assertNull(dto.getPostalAddress().getCity());
        assertNull(dto.getPostalAddress().getProvince());
        assertNull(dto.getPostalAddress().getPostalCode());
    }

    @Test
    void bothServices_nullOptionalFields_produceIdenticalOutput()
    {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildMinimalApplication();

        CustomerEntity customer = buildCustomerEntity(customerId);
        app.setCustomer(customer);

        when(wholesaleApplicationRepository.findById(app.getId())).thenReturn(app);
        WholesaleApplicationDetailsDto fromWholesaleService = wholesaleCustomerService.getWholesaleApplicationById(app.getId());

        stubPanacheForAdminCustomer(customerId, customer, app);
        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);
        WholesaleApplicationDetailsDto fromAdminService = detail.getWholesaleApplication();

        assertDtosEqual(fromWholesaleService, fromAdminService);
    }

    // ── Test: Various statuses ──────────────────────────────────────────────

    @Test
    void toDetailsDto_pendingStatus_mapsCorrectly()
    {
        assertStatusMappedCorrectly(WholesaleApplicationStatusEn.PENDING);
    }

    @Test
    void toDetailsDto_approvedStatus_mapsCorrectly()
    {
        assertStatusMappedCorrectly(WholesaleApplicationStatusEn.APPROVED);
    }

    @Test
    void toDetailsDto_rejectedStatus_mapsCorrectly()
    {
        assertStatusMappedCorrectly(WholesaleApplicationStatusEn.REJECTED);
    }

    @Test
    void toDetailsDto_convertedStatus_mapsCorrectly()
    {
        assertStatusMappedCorrectly(WholesaleApplicationStatusEn.CONVERTED);
    }

    // ── Test: Null customer (no linked customer yet) ────────────────────────

    @Test
    void toDetailsDto_nullCustomer_customerId_isNull()
    {
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();
        app.setCustomer(null);

        when(wholesaleApplicationRepository.findById(app.getId())).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.getId());

        assertNull(result.getCustomerId(), "customerId should be null when entity.customer is null");
        // All other fields should still be populated
        assertEquals(app.getId(), result.getId());
        assertEquals(app.getAccountEmail(), result.getEmail());
        assertEquals(app.getCompanyName(), result.getCompanyName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toApplicationDetailsDto_nullCustomerOnApp_customerId_isNull()
    {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();
        // The wholesale application entity's customer link is null (not yet converted)
        app.setCustomer(null);

        CustomerEntity customer = buildCustomerEntity(customerId);

        stubPanacheForAdminCustomer(customerId, customer, app);

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNotNull(detail.getWholesaleApplication());
        assertNull(detail.getWholesaleApplication().getCustomerId(), "customerId on DTO should be null when app.customer is null");
    }

    // ── Test: ProcessedAt populated (approved/rejected) ─────────────────────

    @Test
    void toDetailsDto_withProcessedAt_mapsDateCorrectly()
    {
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();
        app.setStatus(WholesaleApplicationStatusEn.APPROVED);
        app.setProcessedAt(OffsetDateTime.parse("2026-07-01T14:30:00Z"));

        when(wholesaleApplicationRepository.findById(app.getId())).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.getId());

        assertEquals(WholesaleApplicationStatusEn.APPROVED, result.getStatus());
        assertEquals(OffsetDateTime.parse("2026-07-01T14:30:00Z"), result.getProcessedAt());
    }

    // ── Builders ────────────────────────────────────────────────────────────

    private WholesaleApplicationEntity buildFullyPopulatedApplication()
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setId(UUID.randomUUID());
        app.setApplicantEmail("applicant@wholesale.co.za");
        app.setAccountEmail("account@wholesale.co.za");
        app.setFirstName("Johan");
        app.setLastName("van der Merwe");
        app.setPhone("0821234567");
        app.setCompanyName("Wholesale Traders (Pty) Ltd");
        app.setTradingName("WT Trading");
        app.setCompanyPhone("0111234567");
        app.setCompanyEmail("info@wholesaletraders.co.za");
        app.setVatNumber("4123456789");
        app.setRegNumber("2020/123456/07");
        app.setFinanceContactName("Anelize Botha");
        app.setFinanceContactEmail("finance@wholesaletraders.co.za");
        app.setFinanceContactPhone("0119876543");
        app.setPurchaseOrderRequired(true);
        app.setNotes("Bulk orders monthly, minimum R50k per order");
        app.setStatus(WholesaleApplicationStatusEn.PENDING);
        app.setCreatedAt(OffsetDateTime.parse("2026-06-15T08:30:00Z"));
        app.setProcessedAt(null);

        app.setPhysicalAddressLine1("42 Industry Road");
        app.setPhysicalAddressLine2("Unit 7, Warehouse Park");
        app.setPhysicalSuburb("Midrand");
        app.setPhysicalCity("Johannesburg");
        app.setPhysicalProvince("Gauteng");
        app.setPhysicalPostalCode("1685");

        app.setPostalAddressLine1("PO Box 5500");
        app.setPostalAddressLine2(null);
        app.setPostalSuburb("Halfway House");
        app.setPostalCity("Midrand");
        app.setPostalProvince("Gauteng");
        app.setPostalPostalCode("1685");

        // Customer will be set by tests that need it
        app.setCustomer(null);
        return app;
    }

    private WholesaleApplicationEntity buildMinimalApplication()
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setId(UUID.randomUUID());
        app.setApplicantEmail("minimal@example.com");
        app.setAccountEmail(null);
        app.setFirstName("Min");
        app.setLastName(null);
        app.setPhone(null);
        app.setCompanyName("Min Corp");
        app.setTradingName(null);
        app.setCompanyPhone(null);
        app.setCompanyEmail(null);
        app.setVatNumber(null);
        app.setRegNumber(null);
        app.setFinanceContactName(null);
        app.setFinanceContactEmail(null);
        app.setFinanceContactPhone(null);
        app.setPurchaseOrderRequired(null);
        app.setNotes(null);
        app.setStatus(WholesaleApplicationStatusEn.PENDING);
        app.setCreatedAt(OffsetDateTime.parse("2026-07-10T12:00:00Z"));
        app.setProcessedAt(null);

        app.setPhysicalAddressLine1(null);
        app.setPhysicalAddressLine2(null);
        app.setPhysicalSuburb(null);
        app.setPhysicalCity(null);
        app.setPhysicalProvince(null);
        app.setPhysicalPostalCode(null);

        app.setPostalAddressLine1(null);
        app.setPostalAddressLine2(null);
        app.setPostalSuburb(null);
        app.setPostalCity(null);
        app.setPostalProvince(null);
        app.setPostalPostalCode(null);

        app.setCustomer(null);
        return app;
    }

    private CustomerEntity buildCustomerEntity(UUID customerId)
    {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        customer.setFirstName("Test");
        customer.setLastName("Customer");
        customer.setStatus(CustomerStatusEn.ACTIVE);
        customer.setShopperType(CustomerTypeEn.WHOLESALER);

        UserEntity user = new UserEntity();
        user.setEmail("test@example.com");
        user.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        customer.setUser(user);

        return customer;
    }

    @SuppressWarnings("unchecked")
    private void stubPanacheForAdminCustomer(UUID customerId, CustomerEntity customer, WholesaleApplicationEntity app)
    {
        // CustomerEntity.findById(customerId) → customer
        when(CustomerEntity.findById(customerId)).thenReturn(customer);

        // WholesaleApplicationEntity.find("customer.id = ?1", customerId) → app
        PanacheQuery<PanacheEntityBase> appQuery = mock(PanacheQuery.class);
        when(appQuery.firstResult()).thenReturn(app);
        when(WholesaleApplicationEntity.find("customer.id = ?1", customerId)).thenReturn(appQuery);

        // OrderEntity.find("customerEntity.id = ?1 order by createdAt desc", customerId) → empty list
        PanacheQuery<PanacheEntityBase> orderQuery = mock(PanacheQuery.class);
        when(orderQuery.page(0, 10)).thenReturn(orderQuery);
        when(orderQuery.list()).thenReturn(Collections.emptyList());
        when(OrderEntity.find("customerEntity.id = ?1 order by createdAt desc", customerId)).thenReturn(orderQuery);
    }

    // ── Assertion helpers ───────────────────────────────────────────────────

    private void assertFullyPopulatedBaseline(WholesaleApplicationDetailsDto dto, WholesaleApplicationEntity app)
    {
        assertEquals(app.getId(), dto.getId());
        assertEquals(app.getAccountEmail(), dto.getEmail());
        assertEquals(app.getApplicantEmail(), dto.getApplicantEmail());
        assertEquals(app.getFirstName(), dto.getFirstName());
        assertEquals(app.getLastName(), dto.getLastName());
        assertEquals(app.getPhone(), dto.getPhone());

        // Company fields
        assertEquals(app.getCompanyName(), dto.getCompanyName());
        assertEquals(app.getTradingName(), dto.getTradingName());
        assertEquals(app.getCompanyPhone(), dto.getCompanyPhone());
        assertEquals(app.getCompanyEmail(), dto.getCompanyEmail());
        assertEquals(app.getVatNumber(), dto.getVatNumber());
        assertEquals(app.getRegNumber(), dto.getRegNumber());

        // Finance fields
        assertEquals(app.getFinanceContactName(), dto.getFinanceContactName());
        assertEquals(app.getFinanceContactEmail(), dto.getFinanceContactEmail());
        assertEquals(app.getFinanceContactPhone(), dto.getFinanceContactPhone());
        assertEquals(app.getPurchaseOrderRequired(), dto.getPurchaseOrderRequired());

        // Metadata
        assertEquals(app.getNotes(), dto.getNotes());
        assertEquals(app.getStatus(), dto.getStatus());
        assertEquals(app.getCreatedAt(), dto.getCreatedAt());
        assertEquals(app.getProcessedAt(), dto.getProcessedAt());
        assertEquals(app.getCustomer() != null ? app.getCustomer().getId() : null, dto.getCustomerId());

        // Physical address
        assertEquals(app.getPhysicalAddressLine1(), dto.getPhysicalAddress().getLine1());
        assertEquals(app.getPhysicalAddressLine2(), dto.getPhysicalAddress().getLine2());
        assertEquals(app.getPhysicalSuburb(), dto.getPhysicalAddress().getSuburb());
        assertEquals(app.getPhysicalCity(), dto.getPhysicalAddress().getCity());
        assertEquals(app.getPhysicalProvince(), dto.getPhysicalAddress().getProvince());
        assertEquals(app.getPhysicalPostalCode(), dto.getPhysicalAddress().getPostalCode());

        // Postal address
        assertEquals(app.getPostalAddressLine1(), dto.getPostalAddress().getLine1());
        assertEquals(app.getPostalAddressLine2(), dto.getPostalAddress().getLine2());
        assertEquals(app.getPostalSuburb(), dto.getPostalAddress().getSuburb());
        assertEquals(app.getPostalCity(), dto.getPostalAddress().getCity());
        assertEquals(app.getPostalProvince(), dto.getPostalAddress().getProvince());
        assertEquals(app.getPostalPostalCode(), dto.getPostalAddress().getPostalCode());
    }

    private void assertMinimalBaseline(WholesaleApplicationDetailsDto dto, WholesaleApplicationEntity app)
    {
        assertEquals(app.getId(), dto.getId());
        assertEquals(app.getApplicantEmail(), dto.getApplicantEmail());
        assertEquals(app.getFirstName(), dto.getFirstName());
        assertEquals(app.getCompanyName(), dto.getCompanyName());
        assertEquals(app.getStatus(), dto.getStatus());
        assertEquals(app.getCreatedAt(), dto.getCreatedAt());

        // All optional fields should be null
        assertNull(dto.getEmail(), "email (accountEmail) should be null");
        assertNull(dto.getLastName());
        assertNull(dto.getPhone());
        assertNull(dto.getTradingName());
        assertNull(dto.getCompanyPhone());
        assertNull(dto.getCompanyEmail());
        assertNull(dto.getVatNumber());
        assertNull(dto.getRegNumber());
        assertNull(dto.getFinanceContactName());
        assertNull(dto.getFinanceContactEmail());
        assertNull(dto.getFinanceContactPhone());
        assertNull(dto.getPurchaseOrderRequired());
        assertNull(dto.getNotes());
        assertNull(dto.getProcessedAt());
        assertNull(dto.getCustomerId());

        // Address fields null
        assertNull(dto.getPhysicalAddress().getLine1());
        assertNull(dto.getPhysicalAddress().getLine2());
        assertNull(dto.getPhysicalAddress().getSuburb());
        assertNull(dto.getPhysicalAddress().getCity());
        assertNull(dto.getPhysicalAddress().getProvince());
        assertNull(dto.getPhysicalAddress().getPostalCode());
        assertNull(dto.getPostalAddress().getLine1());
        assertNull(dto.getPostalAddress().getLine2());
        assertNull(dto.getPostalAddress().getSuburb());
        assertNull(dto.getPostalAddress().getCity());
        assertNull(dto.getPostalAddress().getProvince());
        assertNull(dto.getPostalAddress().getPostalCode());
    }

    private void assertDtosEqual(WholesaleApplicationDetailsDto a, WholesaleApplicationDetailsDto b)
    {
        assertEquals(a.getId(), b.getId(), "id");
        assertEquals(a.getEmail(), b.getEmail(), "email");
        assertEquals(a.getApplicantEmail(), b.getApplicantEmail(), "applicantEmail");
        assertEquals(a.getFirstName(), b.getFirstName(), "firstName");
        assertEquals(a.getLastName(), b.getLastName(), "lastName");
        assertEquals(a.getPhone(), b.getPhone(), "phone");

        assertEquals(a.getCompanyName(), b.getCompanyName(), "companyName");
        assertEquals(a.getTradingName(), b.getTradingName(), "tradingName");
        assertEquals(a.getCompanyPhone(), b.getCompanyPhone(), "companyPhone");
        assertEquals(a.getCompanyEmail(), b.getCompanyEmail(), "companyEmail");
        assertEquals(a.getVatNumber(), b.getVatNumber(), "vatNumber");
        assertEquals(a.getRegNumber(), b.getRegNumber(), "regNumber");

        assertEquals(a.getFinanceContactName(), b.getFinanceContactName(), "financeContactName");
        assertEquals(a.getFinanceContactEmail(), b.getFinanceContactEmail(), "financeContactEmail");
        assertEquals(a.getFinanceContactPhone(), b.getFinanceContactPhone(), "financeContactPhone");
        assertEquals(a.getPurchaseOrderRequired(), b.getPurchaseOrderRequired(), "purchaseOrderRequired");

        assertEquals(a.getNotes(), b.getNotes(), "notes");
        assertEquals(a.getStatus(), b.getStatus(), "status");
        assertEquals(a.getCreatedAt(), b.getCreatedAt(), "createdAt");
        assertEquals(a.getProcessedAt(), b.getProcessedAt(), "processedAt");
        assertEquals(a.getCustomerId(), b.getCustomerId(), "customerId");

        assertEquals(a.getPhysicalAddress().getLine1(), b.getPhysicalAddress().getLine1(), "physicalAddress.line1");
        assertEquals(a.getPhysicalAddress().getLine2(), b.getPhysicalAddress().getLine2(), "physicalAddress.line2");
        assertEquals(a.getPhysicalAddress().getSuburb(), b.getPhysicalAddress().getSuburb(), "physicalAddress.suburb");
        assertEquals(a.getPhysicalAddress().getCity(), b.getPhysicalAddress().getCity(), "physicalAddress.city");
        assertEquals(a.getPhysicalAddress().getProvince(), b.getPhysicalAddress().getProvince(), "physicalAddress.province");
        assertEquals(a.getPhysicalAddress().getPostalCode(), b.getPhysicalAddress().getPostalCode(), "physicalAddress.postalCode");

        assertEquals(a.getPostalAddress().getLine1(), b.getPostalAddress().getLine1(), "postalAddress.line1");
        assertEquals(a.getPostalAddress().getLine2(), b.getPostalAddress().getLine2(), "postalAddress.line2");
        assertEquals(a.getPostalAddress().getSuburb(), b.getPostalAddress().getSuburb(), "postalAddress.suburb");
        assertEquals(a.getPostalAddress().getCity(), b.getPostalAddress().getCity(), "postalAddress.city");
        assertEquals(a.getPostalAddress().getProvince(), b.getPostalAddress().getProvince(), "postalAddress.province");
        assertEquals(a.getPostalAddress().getPostalCode(), b.getPostalAddress().getPostalCode(), "postalAddress.postalCode");
    }

    private void assertStatusMappedCorrectly(WholesaleApplicationStatusEn status)
    {
        WholesaleApplicationEntity app = buildMinimalApplication();
        app.setStatus(status);
        if (status == WholesaleApplicationStatusEn.APPROVED || status == WholesaleApplicationStatusEn.REJECTED) {
            app.setProcessedAt(OffsetDateTime.parse("2026-07-15T10:00:00Z"));
        }

        when(wholesaleApplicationRepository.findById(app.getId())).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.getId());

        assertEquals(status, result.getStatus());
        if (status == WholesaleApplicationStatusEn.APPROVED || status == WholesaleApplicationStatusEn.REJECTED) {
            assertEquals(OffsetDateTime.parse("2026-07-15T10:00:00Z"), result.getProcessedAt());
        }
    }
}
