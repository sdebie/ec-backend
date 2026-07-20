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
 *
 * Pins the current output of BOTH:
 * - WholesaleCustomerService.toDetailsDto (via getWholesaleApplicationById)
 * - CustomerAdminService.toApplicationDetailsDto (via adminCustomer)
 *
 * These two services hand-map the same entity to the same DTO type.
 * This test captures their current behaviour as a baseline before
 * consolidation into a single WholesaleMapper.
 *
 * Requirements: 4.2, 4.4
 */
@QuarkusTest
class WholesaleApplicationMappingCharacterizationTest {

    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Inject
    CustomerAdminService customerAdminService;

    @InjectMock
    WholesaleApplicationRepository wholesaleApplicationRepository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        PanacheMock.mock(CustomerEntity.class);
        PanacheMock.mock(WholesaleApplicationEntity.class);
        PanacheMock.mock(OrderEntity.class);
    }

    // ── Test: Fully populated entity ────────────────────────────────────────

    @Test
    void toDetailsDto_fullyPopulated_pinsAllFields() {
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();

        when(wholesaleApplicationRepository.findById(app.id)).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.id);

        assertFullyPopulatedBaseline(result, app);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toApplicationDetailsDto_fullyPopulated_pinsAllFields() {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();

        CustomerEntity customer = buildCustomerEntity(customerId);
        app.customer = customer;

        stubPanacheForAdminCustomer(customerId, customer, app);

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNotNull(detail.wholesaleApplication, "wholesaleApplication should be populated");
        assertFullyPopulatedBaseline(detail.wholesaleApplication, app);
    }

    @Test
    void bothServices_fullyPopulated_produceIdenticalOutput() {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();

        CustomerEntity customer = buildCustomerEntity(customerId);
        app.customer = customer;

        // WholesaleCustomerService path
        when(wholesaleApplicationRepository.findById(app.id)).thenReturn(app);
        WholesaleApplicationDetailsDto fromWholesaleService = wholesaleCustomerService.getWholesaleApplicationById(app.id);

        // CustomerAdminService path
        stubPanacheForAdminCustomer(customerId, customer, app);
        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);
        WholesaleApplicationDetailsDto fromAdminService = detail.wholesaleApplication;

        assertDtosEqual(fromWholesaleService, fromAdminService);
    }

    // ── Test: Null optional fields ──────────────────────────────────────────

    @Test
    void toDetailsDto_nullOptionalFields_pinsNullsCorrectly() {
        WholesaleApplicationEntity app = buildMinimalApplication();

        when(wholesaleApplicationRepository.findById(app.id)).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.id);

        assertMinimalBaseline(result, app);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toApplicationDetailsDto_nullOptionalFields_pinsNullsCorrectly() {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildMinimalApplication();

        CustomerEntity customer = buildCustomerEntity(customerId);
        app.customer = customer;

        stubPanacheForAdminCustomer(customerId, customer, app);

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNotNull(detail.wholesaleApplication);
        // In the admin path, the application was found via customer.id query,
        // so app.customer is set — customerId on DTO reflects the linked customer
        WholesaleApplicationDetailsDto dto = detail.wholesaleApplication;
        assertEquals(app.id, dto.getId());
        assertEquals(app.applicantEmail, dto.getApplicantEmail());
        assertEquals(app.firstName, dto.getFirstName());
        assertEquals(app.companyName, dto.getCompanyName());
        assertEquals(app.status, dto.getStatus());
        assertEquals(app.createdAt, dto.getCreatedAt());
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
        assertNull(dto.getPhysicalAddressLine1());
        assertNull(dto.getPhysicalAddressLine2());
        assertNull(dto.getPhysicalSuburb());
        assertNull(dto.getPhysicalCity());
        assertNull(dto.getPhysicalProvince());
        assertNull(dto.getPhysicalPostalCode());
        assertNull(dto.getPostalAddressLine1());
        assertNull(dto.getPostalAddressLine2());
        assertNull(dto.getPostalSuburb());
        assertNull(dto.getPostalCity());
        assertNull(dto.getPostalProvince());
        assertNull(dto.getPostalPostalCode());
    }

    @Test
    void bothServices_nullOptionalFields_produceIdenticalOutput() {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildMinimalApplication();

        CustomerEntity customer = buildCustomerEntity(customerId);
        app.customer = customer;

        when(wholesaleApplicationRepository.findById(app.id)).thenReturn(app);
        WholesaleApplicationDetailsDto fromWholesaleService = wholesaleCustomerService.getWholesaleApplicationById(app.id);

        stubPanacheForAdminCustomer(customerId, customer, app);
        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);
        WholesaleApplicationDetailsDto fromAdminService = detail.wholesaleApplication;

        assertDtosEqual(fromWholesaleService, fromAdminService);
    }

    // ── Test: Various statuses ──────────────────────────────────────────────

    @Test
    void toDetailsDto_pendingStatus_mapsCorrectly() {
        assertStatusMappedCorrectly(WholesaleApplicationStatusEn.PENDING);
    }

    @Test
    void toDetailsDto_approvedStatus_mapsCorrectly() {
        assertStatusMappedCorrectly(WholesaleApplicationStatusEn.APPROVED);
    }

    @Test
    void toDetailsDto_rejectedStatus_mapsCorrectly() {
        assertStatusMappedCorrectly(WholesaleApplicationStatusEn.REJECTED);
    }

    @Test
    void toDetailsDto_convertedStatus_mapsCorrectly() {
        assertStatusMappedCorrectly(WholesaleApplicationStatusEn.CONVERTED);
    }

    // ── Test: Null customer (no linked customer yet) ────────────────────────

    @Test
    void toDetailsDto_nullCustomer_customerId_isNull() {
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();
        app.customer = null;

        when(wholesaleApplicationRepository.findById(app.id)).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.id);

        assertNull(result.getCustomerId(), "customerId should be null when entity.customer is null");
        // All other fields should still be populated
        assertEquals(app.id, result.getId());
        assertEquals(app.accountEmail, result.getEmail());
        assertEquals(app.companyName, result.getCompanyName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toApplicationDetailsDto_nullCustomerOnApp_customerId_isNull() {
        UUID customerId = UUID.randomUUID();
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();
        // The wholesale application entity's customer link is null (not yet converted)
        app.customer = null;

        CustomerEntity customer = buildCustomerEntity(customerId);

        stubPanacheForAdminCustomer(customerId, customer, app);

        AdminCustomerDetailDto detail = customerAdminService.adminCustomer(customerId);

        assertNotNull(detail.wholesaleApplication);
        assertNull(detail.wholesaleApplication.getCustomerId(),
                "customerId on DTO should be null when app.customer is null");
    }

    // ── Test: ProcessedAt populated (approved/rejected) ─────────────────────

    @Test
    void toDetailsDto_withProcessedAt_mapsDateCorrectly() {
        WholesaleApplicationEntity app = buildFullyPopulatedApplication();
        app.status = WholesaleApplicationStatusEn.APPROVED;
        app.processedAt = OffsetDateTime.parse("2026-07-01T14:30:00Z");

        when(wholesaleApplicationRepository.findById(app.id)).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.id);

        assertEquals(WholesaleApplicationStatusEn.APPROVED, result.getStatus());
        assertEquals(OffsetDateTime.parse("2026-07-01T14:30:00Z"), result.getProcessedAt());
    }

    // ── Builders ────────────────────────────────────────────────────────────

    private WholesaleApplicationEntity buildFullyPopulatedApplication() {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.id = UUID.randomUUID();
        app.applicantEmail = "applicant@wholesale.co.za";
        app.accountEmail = "account@wholesale.co.za";
        app.firstName = "Johan";
        app.lastName = "van der Merwe";
        app.phone = "0821234567";
        app.companyName = "Wholesale Traders (Pty) Ltd";
        app.tradingName = "WT Trading";
        app.companyPhone = "0111234567";
        app.companyEmail = "info@wholesaletraders.co.za";
        app.vatNumber = "4123456789";
        app.regNumber = "2020/123456/07";
        app.financeContactName = "Anelize Botha";
        app.financeContactEmail = "finance@wholesaletraders.co.za";
        app.financeContactPhone = "0119876543";
        app.purchaseOrderRequired = true;
        app.notes = "Bulk orders monthly, minimum R50k per order";
        app.status = WholesaleApplicationStatusEn.PENDING;
        app.createdAt = OffsetDateTime.parse("2026-06-15T08:30:00Z");
        app.processedAt = null;

        app.physicalAddressLine1 = "42 Industry Road";
        app.physicalAddressLine2 = "Unit 7, Warehouse Park";
        app.physicalSuburb = "Midrand";
        app.physicalCity = "Johannesburg";
        app.physicalProvince = "Gauteng";
        app.physicalPostalCode = "1685";

        app.postalAddressLine1 = "PO Box 5500";
        app.postalAddressLine2 = null;
        app.postalSuburb = "Halfway House";
        app.postalCity = "Midrand";
        app.postalProvince = "Gauteng";
        app.postalPostalCode = "1685";

        // Customer will be set by tests that need it
        app.customer = null;
        return app;
    }

    private WholesaleApplicationEntity buildMinimalApplication() {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.id = UUID.randomUUID();
        app.applicantEmail = "minimal@example.com";
        app.accountEmail = null;
        app.firstName = "Min";
        app.lastName = null;
        app.phone = null;
        app.companyName = "Min Corp";
        app.tradingName = null;
        app.companyPhone = null;
        app.companyEmail = null;
        app.vatNumber = null;
        app.regNumber = null;
        app.financeContactName = null;
        app.financeContactEmail = null;
        app.financeContactPhone = null;
        app.purchaseOrderRequired = null;
        app.notes = null;
        app.status = WholesaleApplicationStatusEn.PENDING;
        app.createdAt = OffsetDateTime.parse("2026-07-10T12:00:00Z");
        app.processedAt = null;

        app.physicalAddressLine1 = null;
        app.physicalAddressLine2 = null;
        app.physicalSuburb = null;
        app.physicalCity = null;
        app.physicalProvince = null;
        app.physicalPostalCode = null;

        app.postalAddressLine1 = null;
        app.postalAddressLine2 = null;
        app.postalSuburb = null;
        app.postalCity = null;
        app.postalProvince = null;
        app.postalPostalCode = null;

        app.customer = null;
        return app;
    }

    private CustomerEntity buildCustomerEntity(UUID customerId) {
        CustomerEntity customer = new CustomerEntity();
        customer.id = customerId;
        customer.firstName = "Test";
        customer.lastName = "Customer";
        customer.status = CustomerStatusEn.ACTIVE;
        customer.shopperType = CustomerTypeEn.WHOLESALER;

        UserEntity user = new UserEntity();
        user.email = "test@example.com";
        user.createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        customer.user = user;

        return customer;
    }

    @SuppressWarnings("unchecked")
    private void stubPanacheForAdminCustomer(UUID customerId, CustomerEntity customer, WholesaleApplicationEntity app) {
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

    private void assertFullyPopulatedBaseline(WholesaleApplicationDetailsDto dto, WholesaleApplicationEntity app) {
        assertEquals(app.id, dto.getId());
        assertEquals(app.accountEmail, dto.getEmail());
        assertEquals(app.applicantEmail, dto.getApplicantEmail());
        assertEquals(app.firstName, dto.getFirstName());
        assertEquals(app.lastName, dto.getLastName());
        assertEquals(app.phone, dto.getPhone());

        // Company fields
        assertEquals(app.companyName, dto.getCompanyName());
        assertEquals(app.tradingName, dto.getTradingName());
        assertEquals(app.companyPhone, dto.getCompanyPhone());
        assertEquals(app.companyEmail, dto.getCompanyEmail());
        assertEquals(app.vatNumber, dto.getVatNumber());
        assertEquals(app.regNumber, dto.getRegNumber());

        // Finance fields
        assertEquals(app.financeContactName, dto.getFinanceContactName());
        assertEquals(app.financeContactEmail, dto.getFinanceContactEmail());
        assertEquals(app.financeContactPhone, dto.getFinanceContactPhone());
        assertEquals(app.purchaseOrderRequired, dto.getPurchaseOrderRequired());

        // Metadata
        assertEquals(app.notes, dto.getNotes());
        assertEquals(app.status, dto.getStatus());
        assertEquals(app.createdAt, dto.getCreatedAt());
        assertEquals(app.processedAt, dto.getProcessedAt());
        assertEquals(app.customer != null ? app.customer.id : null, dto.getCustomerId());

        // Physical address
        assertEquals(app.physicalAddressLine1, dto.getPhysicalAddressLine1());
        assertEquals(app.physicalAddressLine2, dto.getPhysicalAddressLine2());
        assertEquals(app.physicalSuburb, dto.getPhysicalSuburb());
        assertEquals(app.physicalCity, dto.getPhysicalCity());
        assertEquals(app.physicalProvince, dto.getPhysicalProvince());
        assertEquals(app.physicalPostalCode, dto.getPhysicalPostalCode());

        // Postal address
        assertEquals(app.postalAddressLine1, dto.getPostalAddressLine1());
        assertEquals(app.postalAddressLine2, dto.getPostalAddressLine2());
        assertEquals(app.postalSuburb, dto.getPostalSuburb());
        assertEquals(app.postalCity, dto.getPostalCity());
        assertEquals(app.postalProvince, dto.getPostalProvince());
        assertEquals(app.postalPostalCode, dto.getPostalPostalCode());
    }

    private void assertMinimalBaseline(WholesaleApplicationDetailsDto dto, WholesaleApplicationEntity app) {
        assertEquals(app.id, dto.getId());
        assertEquals(app.applicantEmail, dto.getApplicantEmail());
        assertEquals(app.firstName, dto.getFirstName());
        assertEquals(app.companyName, dto.getCompanyName());
        assertEquals(app.status, dto.getStatus());
        assertEquals(app.createdAt, dto.getCreatedAt());

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
        assertNull(dto.getPhysicalAddressLine1());
        assertNull(dto.getPhysicalAddressLine2());
        assertNull(dto.getPhysicalSuburb());
        assertNull(dto.getPhysicalCity());
        assertNull(dto.getPhysicalProvince());
        assertNull(dto.getPhysicalPostalCode());
        assertNull(dto.getPostalAddressLine1());
        assertNull(dto.getPostalAddressLine2());
        assertNull(dto.getPostalSuburb());
        assertNull(dto.getPostalCity());
        assertNull(dto.getPostalProvince());
        assertNull(dto.getPostalPostalCode());
    }

    private void assertDtosEqual(WholesaleApplicationDetailsDto a, WholesaleApplicationDetailsDto b) {
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

        assertEquals(a.getPhysicalAddressLine1(), b.getPhysicalAddressLine1(), "physicalAddressLine1");
        assertEquals(a.getPhysicalAddressLine2(), b.getPhysicalAddressLine2(), "physicalAddressLine2");
        assertEquals(a.getPhysicalSuburb(), b.getPhysicalSuburb(), "physicalSuburb");
        assertEquals(a.getPhysicalCity(), b.getPhysicalCity(), "physicalCity");
        assertEquals(a.getPhysicalProvince(), b.getPhysicalProvince(), "physicalProvince");
        assertEquals(a.getPhysicalPostalCode(), b.getPhysicalPostalCode(), "physicalPostalCode");

        assertEquals(a.getPostalAddressLine1(), b.getPostalAddressLine1(), "postalAddressLine1");
        assertEquals(a.getPostalAddressLine2(), b.getPostalAddressLine2(), "postalAddressLine2");
        assertEquals(a.getPostalSuburb(), b.getPostalSuburb(), "postalSuburb");
        assertEquals(a.getPostalCity(), b.getPostalCity(), "postalCity");
        assertEquals(a.getPostalProvince(), b.getPostalProvince(), "postalProvince");
        assertEquals(a.getPostalPostalCode(), b.getPostalPostalCode(), "postalPostalCode");
    }

    private void assertStatusMappedCorrectly(WholesaleApplicationStatusEn status) {
        WholesaleApplicationEntity app = buildMinimalApplication();
        app.status = status;
        if (status == WholesaleApplicationStatusEn.APPROVED || status == WholesaleApplicationStatusEn.REJECTED) {
            app.processedAt = OffsetDateTime.parse("2026-07-15T10:00:00Z");
        }

        when(wholesaleApplicationRepository.findById(app.id)).thenReturn(app);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(app.id);

        assertEquals(status, result.getStatus());
        if (status == WholesaleApplicationStatusEn.APPROVED || status == WholesaleApplicationStatusEn.REJECTED) {
            assertEquals(OffsetDateTime.parse("2026-07-15T10:00:00Z"), result.getProcessedAt());
        }
    }
}
