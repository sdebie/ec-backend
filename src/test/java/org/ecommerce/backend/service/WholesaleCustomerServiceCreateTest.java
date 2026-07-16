package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.entity.WholesaleProfileEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.enums.WholesaleCustomerStatusEn;
import org.ecommerce.common.repository.WholesaleApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the WholesaleCustomerService create path.
 *
 * Requirements: 4.4, 4.5, 5.2, 7.1
 */
@QuarkusTest
class WholesaleCustomerServiceCreateTest {

    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @InjectMock
    WholesaleApplicationRepository wholesaleApplicationRepository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        PanacheMock.mock(WholesaleApplicationEntity.class);
        PanacheMock.mock(UserEntity.class);
        PanacheMock.mock(CustomerEntity.class);
        PanacheMock.mock(WholesaleProfileEntity.class);

        // Default: no existing application found (duplicate check passes)
        PanacheQuery<PanacheEntityBase> mockQuery = mock(PanacheQuery.class);
        when(mockQuery.firstResult()).thenReturn(null);
        when(WholesaleApplicationEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
                .thenReturn(mockQuery);
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private WholesaleCustomerDto buildValidDto() {
        WholesaleCustomerDto dto = new WholesaleCustomerDto();
        dto.setApplicantEmail("applicant@example.com");
        dto.setEmail("account@example.com");
        dto.setFirstName("John");
        dto.setLastName("Doe");
        dto.setPhone("0821234567");
        dto.setCompanyName("ACME Corp");
        dto.setTradingName("ACME Trading");
        dto.setCompanyPhone("0111234567");
        dto.setCompanyEmail("info@acme.com");
        dto.setVatNumber("VAT123");
        dto.setRegNumber("REG456");
        dto.setFinanceContactName("Jane Finance");
        dto.setFinanceContactEmail("finance@acme.com");
        dto.setFinanceContactPhone("0119876543");
        dto.setPurchaseOrderRequired(true);
        dto.setNotes("Test application");
        dto.setPhysicalAddressLine1("123 Main St");
        dto.setPhysicalCity("Johannesburg");
        dto.setPhysicalProvince("Gauteng");
        dto.setPhysicalPostalCode("2000");
        return dto;
    }

    // ── Test: Rejects null applicantEmail (Requirement 4.4) ──────────────────

    @Test
    void createWholesaleApplication_shouldRejectNullApplicantEmail() {
        WholesaleCustomerDto dto = buildValidDto();
        dto.setApplicantEmail(null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> wholesaleCustomerService.createWholesaleApplication(dto)
        );

        assertEquals("applicantEmail is required", ex.getMessage());
    }

    @Test
    void createWholesaleApplication_shouldRejectBlankApplicantEmail() {
        WholesaleCustomerDto dto = buildValidDto();
        dto.setApplicantEmail("   ");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> wholesaleCustomerService.createWholesaleApplication(dto)
        );

        assertEquals("applicantEmail is required", ex.getMessage());
    }

    // ── Test: Rejects duplicate non-null account_email (Requirement 4.5) ─────

    @SuppressWarnings("unchecked")
    @Test
    void createWholesaleApplication_shouldRejectDuplicateNonNullAccountEmail() {
        WholesaleCustomerDto dto = buildValidDto();
        dto.setEmail("existing@example.com");

        // Mock: an existing application is found with this email
        WholesaleApplicationEntity existing = new WholesaleApplicationEntity();
        existing.id = UUID.randomUUID();
        existing.accountEmail = "existing@example.com";

        PanacheQuery<PanacheEntityBase> mockQuery = mock(PanacheQuery.class);
        when(mockQuery.firstResult()).thenReturn(existing);
        when(WholesaleApplicationEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
                .thenReturn(mockQuery);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> wholesaleCustomerService.createWholesaleApplication(dto)
        );

        assertTrue(ex.getMessage().contains("wholesale application already exists with email:"));
        assertTrue(ex.getMessage().contains("existing@example.com"));
    }

    // ── Test: Permits null account_email — no duplicate check (Requirement 4.5) ─

    @Test
    void createWholesaleApplication_shouldPermitNullAccountEmail() {
        WholesaleCustomerDto dto = buildValidDto();
        dto.setEmail(null);

        WholesaleCustomerDto result = wholesaleCustomerService.createWholesaleApplication(dto);

        assertNotNull(result);
        assertNull(result.getEmail());
    }

    @Test
    void createWholesaleApplication_shouldPermitBlankAccountEmail() {
        WholesaleCustomerDto dto = buildValidDto();
        dto.setEmail("   ");

        WholesaleCustomerDto result = wholesaleCustomerService.createWholesaleApplication(dto);

        assertNotNull(result);
        // blank normalizes to null
        assertNull(result.getEmail());
    }

    // ── Test: Submitting status = APPROVED still persists PENDING (Requirement 7.1) ─

    @Test
    void createWholesaleApplication_shouldPersistPendingStatus_whenInputIsApproved() {
        WholesaleCustomerDto dto = buildValidDto();
        dto.setStatus(WholesaleCustomerStatusEn.APPROVED);

        WholesaleCustomerDto result = wholesaleCustomerService.createWholesaleApplication(dto);

        assertNotNull(result);
        // status returned from toDto is mapped from the entity's status
        assertEquals(WholesaleCustomerStatusEn.PENDING, result.getStatus());
    }

    @Test
    void createWholesaleApplication_shouldPersistPendingStatus_whenInputIsRejected() {
        WholesaleCustomerDto dto = buildValidDto();
        dto.setStatus(WholesaleCustomerStatusEn.REJECTED);

        WholesaleCustomerDto result = wholesaleCustomerService.createWholesaleApplication(dto);

        assertNotNull(result);
        assertEquals(WholesaleCustomerStatusEn.PENDING, result.getStatus());
    }

    @Test
    void createWholesaleApplication_shouldPersistPendingStatus_whenInputStatusIsNull() {
        WholesaleCustomerDto dto = buildValidDto();
        dto.setStatus(null);

        WholesaleCustomerDto result = wholesaleCustomerService.createWholesaleApplication(dto);

        assertNotNull(result);
        assertEquals(WholesaleCustomerStatusEn.PENDING, result.getStatus());
    }

    // ── Test: Approval flow with new fields populated (Requirement 5.2) ──────

    @Test
    void createWholesaleCustomer_shouldCreateCustomerCorrectly_whenNewFieldsPopulated() {
        UUID applicationId = UUID.randomUUID();

        // Build an application entity with all new fields populated
        WholesaleApplicationEntity application = new WholesaleApplicationEntity();
        application.id = applicationId;
        application.applicantEmail = "applicant@example.com";
        application.accountEmail = "account@example.com";
        application.firstName = "John";
        application.lastName = "Doe";
        application.phone = "0821234567";
        application.companyName = "ACME Corp";
        application.tradingName = "ACME Trading";
        application.companyPhone = "0111234567";
        application.companyEmail = "info@acme.com";
        application.vatNumber = "VAT123";
        application.regNumber = "REG456";
        application.financeContactName = "Jane Finance";
        application.financeContactEmail = "finance@acme.com";
        application.financeContactPhone = "0119876543";
        application.purchaseOrderRequired = true;
        application.notes = "Test notes";
        application.physicalAddressLine1 = "123 Main St";
        application.physicalCity = "Johannesburg";
        application.physicalProvince = "Gauteng";
        application.physicalPostalCode = "2000";
        application.status = WholesaleApplicationStatusEn.PENDING;
        application.customer = null;

        // Mock findById to return the application
        when(WholesaleApplicationEntity.findById(applicationId))
                .thenReturn(application);

        // Mock UserEntity.findByEmail — no existing user
        when(UserEntity.findByEmail("account@example.com"))
                .thenReturn(null);

        // Act
        WholesaleCustomerDto result = wholesaleCustomerService.createWholesaleCustomer(applicationId);

        // Assert — application marked converted
        assertEquals(WholesaleApplicationStatusEn.CONVERTED, application.status);
        assertNotNull(application.processedAt);
        assertNotNull(application.customer);

        // Assert — result DTO returns the correct email (from account)
        assertNotNull(result);
        assertEquals("account@example.com", result.getEmail());
        assertEquals("John", result.getFirstName());
        assertEquals("Doe", result.getLastName());
    }
}
