package org.ecommerce.backend.service;

// Feature: wholesale-application-enhancements, Property 3: DTO-to-entity field mapping preserves values
// Feature: wholesale-application-enhancements, Property 5: Partial unique constraint permits multiple null account_email values
// Feature: wholesale-application-enhancements, Property 6: applicantEmail and accountEmail are independently stored
// Feature: wholesale-application-enhancements, Property 7: Created applications default to PENDING status

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.AddressDto;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.entity.WholesaleProfileEntity;
import org.ecommerce.common.enums.WholesaleCustomerStatusEn;
import org.ecommerce.common.repository.WholesaleApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for the wholesale application create path.
 * <p>
 * Tests the WholesaleCustomerService.createWholesaleApplication method to verify:
 * - Property 3: DTO-to-entity field mapping preserves values
 * - Property 5: Partial unique constraint permits multiple null account_email values
 * - Property 6: applicantEmail and accountEmail are independently stored
 * - Property 7: Created applications default to PENDING status
 * <p>
 * Uses @QuarkusTest with PanacheMock to properly mock Panache entity methods.
 * Property-based testing is achieved by running 100 iterations with random inputs.
 * <p>
 */
@QuarkusTest
@Tag("Feature: wholesale-application-enhancements")
public class WholesaleApplicationCreatePropertyTest
{
    private static final int TRIES = 100;
    private final Random random = new Random(42); // Fixed seed for reproducibility

    @Inject
    WholesaleCustomerService service;

    @InjectMock
    WholesaleApplicationRepository wholesaleApplicationRepository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup()
    {
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

    // ══════════════════════════════════════════════════════════════════════════════
    // Property 3: DTO-to-entity field mapping preserves values
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * For any valid WholesaleCustomerDto input, the returned DTO from
     * createWholesaleApplication SHALL have each mapped field equal to the
     * normalized (trimmed, null-if-blank) input value.
     * <p>
     */
    @Test
    @Tag("Property 3: DTO-to-entity field mapping preserves values")
    void dtoToEntityFieldMappingPreservesValues()
    {
        for (int i = 0; i < TRIES; i++) {
            WholesaleCustomerDto dto = generateValidDto();

            // Act
            WholesaleCustomerDto result = service.createWholesaleApplication(dto);

            // Assert: the returned DTO reflects the normalized field values
            assertEquals(normalize(dto.getEmail()), result.getEmail(), "Iteration " + i + ": accountEmail mismatch");
            assertEquals(normalize(dto.getFirstName()), result.getFirstName(), "Iteration " + i + ": firstName mismatch");
            assertEquals(normalize(dto.getLastName()), result.getLastName(), "Iteration " + i + ": lastName mismatch");
            assertEquals(normalize(dto.getPhone()), result.getPhone(), "Iteration " + i + ": phone mismatch");
            assertEquals(normalize(dto.getVatNumber()), result.getVatNumber(), "Iteration " + i + ": vatNumber mismatch");
            assertEquals(normalize(dto.getRegNumber()), result.getRegNumber(), "Iteration " + i + ": regNumber mismatch");
            assertEquals(normalize(dto.getNotes()), result.getNotes(), "Iteration " + i + ": notes mismatch");

            // Company name uses firstNonBlank(companyName, firstName)
            String expectedCompanyName = normalize(dto.getCompanyName());
            if (expectedCompanyName == null) {
                expectedCompanyName = normalize(dto.getFirstName());
            }
            assertEquals(expectedCompanyName, result.getCompanyName(), "Iteration " + i + ": companyName mismatch");

            // Address fields — physical
            assertEquals(normalize(dto.getPhysicalAddress().getLine1()), result.getPhysicalAddress().getLine1(), "Iteration " + i + ": physicalAddress.line1 mismatch");
            assertEquals(normalize(dto.getPhysicalAddress().getLine2()), result.getPhysicalAddress().getLine2(), "Iteration " + i + ": physicalAddress.line2 mismatch");
            assertEquals(normalize(dto.getPhysicalAddress().getSuburb()), result.getPhysicalAddress().getSuburb(), "Iteration " + i + ": physicalAddress.suburb mismatch");
            assertEquals(normalize(dto.getPhysicalAddress().getCity()), result.getPhysicalAddress().getCity(), "Iteration " + i + ": physicalAddress.city mismatch");
            assertEquals(normalize(dto.getPhysicalAddress().getProvince()), result.getPhysicalAddress().getProvince(), "Iteration " + i + ": physicalAddress.province mismatch");
            assertEquals(normalize(dto.getPhysicalAddress().getPostalCode()), result.getPhysicalAddress().getPostalCode(), "Iteration " + i + ": physicalAddress.postalCode mismatch");

            // Address fields — postal
            assertEquals(normalize(dto.getPostalAddress().getLine1()), result.getPostalAddress().getLine1(), "Iteration " + i + ": postalAddress.line1 mismatch");
            assertEquals(normalize(dto.getPostalAddress().getLine2()), result.getPostalAddress().getLine2(), "Iteration " + i + ": postalAddress.line2 mismatch");
            assertEquals(normalize(dto.getPostalAddress().getSuburb()), result.getPostalAddress().getSuburb(), "Iteration " + i + ": postalAddress.suburb mismatch");
            assertEquals(normalize(dto.getPostalAddress().getCity()), result.getPostalAddress().getCity(), "Iteration " + i + ": postalAddress.city mismatch");
            assertEquals(normalize(dto.getPostalAddress().getProvince()), result.getPostalAddress().getProvince(), "Iteration " + i + ": postalAddress.province mismatch");
            assertEquals(normalize(dto.getPostalAddress().getPostalCode()), result.getPostalAddress().getPostalCode(), "Iteration " + i + ": postalAddress.postalCode mismatch");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Property 5: Partial unique constraint permits multiple null account_email
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * For any two valid application inputs where account_email is null (empty/omitted),
     * both applications SHALL persist successfully without unique constraint violation.
     * The service skips the duplicate check entirely when account_email is null.
     * <p>
     */
    @Test
    @Tag("Property 5: Partial unique constraint permits multiple null account_email values")
    void multipleNullAccountEmailsPermitted()
    {
        for (int i = 0; i < TRIES; i++) {
            WholesaleCustomerDto dto1 = generateValidDto();
            WholesaleCustomerDto dto2 = generateValidDto();
            dto1.setEmail(null);
            dto2.setEmail(null);

            // Both should succeed without any duplicate-check exception
            WholesaleCustomerDto result1 = service.createWholesaleApplication(dto1);
            WholesaleCustomerDto result2 = service.createWholesaleApplication(dto2);

            assertNull(result1.getEmail(), "Iteration " + i + ": first application's account_email should be null");
            assertNull(result2.getEmail(), "Iteration " + i + ": second application's account_email should be null");
        }
    }

    /**
     * For any two inputs where account_email is the same non-null value, the second
     * SHALL be rejected with an IllegalArgumentException.
     * <p>
     */
    @Test
    @Tag("Property 5: Partial unique constraint permits multiple null account_email values")
    @SuppressWarnings("unchecked")
    void duplicateNonNullAccountEmailIsRejected()
    {
        for (int i = 0; i < TRIES; i++) {
            WholesaleCustomerDto dto1 = generateValidDto();
            WholesaleCustomerDto dto2 = generateValidDto();
            String sharedEmail = generateEmail();

            dto1.setEmail(sharedEmail);
            dto2.setEmail(sharedEmail);

            // Reset mock: no duplicate found (for first call)
            PanacheQuery<PanacheEntityBase> freshQuery = mock(PanacheQuery.class);
            when(freshQuery.firstResult()).thenReturn(null);
            when(WholesaleApplicationEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(freshQuery);

            // First call succeeds
            service.createWholesaleApplication(dto1);

            // Simulate existing entity found on duplicate check
            WholesaleApplicationEntity existing = new WholesaleApplicationEntity();
            existing.setAccountEmail(sharedEmail.trim());
            PanacheQuery<PanacheEntityBase> mockQuery = mock(PanacheQuery.class);
            when(mockQuery.firstResult()).thenReturn(existing);
            when(WholesaleApplicationEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(mockQuery);

            // Second call with same email should throw
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createWholesaleApplication(dto2), "Iteration " + i + ": should reject duplicate non-null account_email");
            assertTrue(ex.getMessage().contains("wholesale application already exists with email"), "Iteration " + i + ": error should indicate duplicate email, got: " + ex.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Property 6: applicantEmail and accountEmail are independently stored
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * When applicantEmail and accountEmail are both provided with distinct values,
     * each is stored in its own column without cross-contamination.
     * We verify via the returned DTO (toDto maps entity.accountEmail → getEmail()).
     * <p>
     */
    @Test
    @Tag("Property 6: applicantEmail and accountEmail independently stored")
    void applicantEmailAndAccountEmailAreIndependentlyStored()
    {
        int verified = 0;
        for (int i = 0; verified < TRIES && i < TRIES * 2; i++) {
            String applicantEmail = generateEmail();
            String accountEmail = generateEmail();

            // Ensure they're distinct
            if (applicantEmail.trim().equalsIgnoreCase(accountEmail.trim())) {
                continue;
            }

            WholesaleCustomerDto dto = buildMinimalValidDto();
            dto.setApplicantEmail(applicantEmail);
            dto.setEmail(accountEmail);

            // Act
            WholesaleCustomerDto result = service.createWholesaleApplication(dto);

            // Assert: accountEmail is stored independently (toDto maps it to getEmail())
            assertEquals(accountEmail.trim(), result.getEmail(), "Iteration " + verified + ": accountEmail should be stored as provided (trimmed)");
            // The returned DTO's getEmail() (accountEmail) should NOT equal the applicantEmail
            assertNotEquals(applicantEmail.trim(), result.getEmail(), "Iteration " + verified + ": accountEmail must not be contaminated by applicantEmail");
            verified++;
        }
        assertTrue(verified >= TRIES, "Should have verified at least " + TRIES + " cases");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Property 7: Created applications default to PENDING status
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * For any valid createWholesaleApplication invocation — including inputs that
     * explicitly supply status = APPROVED/REJECTED/CONVERTED — the resulting entity
     * SHALL have status = PENDING.
     * <p>
     */
    @Test
    @Tag("Property 7: Created applications default to PENDING status")
    void createdApplicationsAlwaysHavePendingStatus()
    {
        WholesaleCustomerStatusEn[] statuses = WholesaleCustomerStatusEn.values();

        for (int i = 0; i < TRIES; i++) {
            WholesaleCustomerDto dto = generateValidDto();
            // Pick a random status including APPROVED, REJECTED, CONVERTED
            WholesaleCustomerStatusEn suppliedStatus = statuses[random.nextInt(statuses.length)];
            dto.setStatus(suppliedStatus);

            // Act
            WholesaleCustomerDto result = service.createWholesaleApplication(dto);

            // Assert: returned status is always PENDING regardless of supplied status
            assertEquals(WholesaleCustomerStatusEn.PENDING, result.getStatus(), "Iteration " + i + ": created application must always have PENDING status, " + "regardless of client-supplied status: " + suppliedStatus);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Random Data Generators
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * Generates a valid WholesaleCustomerDto with random field values.
     * Required fields (applicantEmail, firstName, companyName) are always non-blank.
     * Optional fields are randomly null, blank, or populated.
     */
    private WholesaleCustomerDto generateValidDto()
    {
        WholesaleCustomerDto dto = new WholesaleCustomerDto();
        dto.setApplicantEmail(generateEmail());
        dto.setEmail(randomNullableEmail());
        dto.setFirstName(generateNonBlankString());
        dto.setLastName(randomNullableString());
        dto.setPhone(randomNullableString());
        dto.setCompanyName(generateNonBlankString());
        dto.setTradingName(randomNullableString());
        dto.setCompanyPhone(randomNullableString());
        dto.setCompanyEmail(randomNullableEmail());
        dto.setVatNumber(randomNullableString());
        dto.setRegNumber(randomNullableString());
        dto.setFinanceContactName(randomNullableString());
        dto.setFinanceContactEmail(randomNullableEmail());
        dto.setFinanceContactPhone(randomNullableString());
        dto.setPurchaseOrderRequired(randomNullableBoolean());
        dto.setNotes(randomNullableString());
        AddressDto physical = new AddressDto();
        physical.setLine1(randomNullableString());
        physical.setLine2(randomNullableString());
        physical.setSuburb(randomNullableString());
        physical.setCity(randomNullableString());
        physical.setProvince(randomNullableString());
        physical.setPostalCode(randomNullableString());
        dto.setPhysicalAddress(physical);
        AddressDto postal = new AddressDto();
        postal.setLine1(randomNullableString());
        postal.setLine2(randomNullableString());
        postal.setSuburb(randomNullableString());
        postal.setCity(randomNullableString());
        postal.setProvince(randomNullableString());
        postal.setPostalCode(randomNullableString());
        dto.setPostalAddress(postal);
        return dto;
    }

    private String generateEmail()
    {
        return generateAlphaString(3, 10).toLowerCase() + "@example.com";
    }

    private String generateNonBlankString()
    {
        return generateAlphaString(2, 20);
    }

    private String randomNullableString()
    {
        int choice = random.nextInt(4);
        return switch (choice) {
            case 0 -> null;
            case 1 -> "";
            case 2 -> "   ";
            default -> generateAlphaString(1, 20);
        };
    }

    private String randomNullableEmail()
    {
        int choice = random.nextInt(3);
        return switch (choice) {
            case 0 -> null;
            case 1 -> "";
            default -> generateEmail();
        };
    }

    private Boolean randomNullableBoolean()
    {
        int choice = random.nextInt(3);
        return switch (choice) {
            case 0 -> null;
            case 1 -> true;
            default -> false;
        };
    }

    private String generateAlphaString(int minLen, int maxLen)
    {
        int length = minLen + random.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = (char) ('a' + random.nextInt(26));
            sb.append(c);
        }
        return sb.toString();
    }

    // ── Helper Methods ───────────────────────────────────────────────────────────

    /**
     * Replicates the normalize logic from WholesaleCustomerService:
     * trim, then return null if blank.
     */
    private String normalize(String value)
    {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Creates a minimal valid DTO with required fields populated.
     */
    private WholesaleCustomerDto buildMinimalValidDto()
    {
        WholesaleCustomerDto dto = new WholesaleCustomerDto();
        dto.setApplicantEmail("applicant@example.com");
        dto.setFirstName("John");
        dto.setCompanyName("TestCo");
        return dto;
    }
}
