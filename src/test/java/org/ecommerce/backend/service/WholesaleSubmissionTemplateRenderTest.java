package org.ecommerce.backend.service;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renders the wholesale submission mail templates with real Qute. The mail sends are
 * fire-and-log (a template error is logged, never propagated), so without these tests a
 * broken expression would ship silently past a green suite.
 */
@QuarkusTest
@DisplayName("WholesaleSubmissionTemplateRenderTest — Qute templates render without errors")
class WholesaleSubmissionTemplateRenderTest {

    @Inject
    @Location("wholesale_application_received.html")
    Template adminTemplate;

    @Inject
    @Location("wholesale_registration.html")
    Template applicantTemplate;

    private static final UUID APPLICATION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private WholesaleCustomerDto fullDto() {
        WholesaleCustomerDto dto = new WholesaleCustomerDto();
        dto.setApplicantEmail("applicant@test.com");
        dto.setEmail("account@test.com");
        dto.setFirstName("Jane");
        dto.setLastName("Doe");
        dto.setPhone("0821234567");
        dto.setCompanyName("ACME Corp");
        dto.setTradingName("ACME Trading");
        dto.setRegNumber("REG-42");
        dto.setVatNumber("VAT123");
        dto.setCompanyEmail("info@acme.com");
        dto.setCompanyPhone("0111234567");
        dto.setFinanceContactName("Fin Person");
        dto.setFinanceContactEmail("fin@acme.com");
        dto.setFinanceContactPhone("0837654321");
        dto.setPurchaseOrderRequired(true);
        dto.setPhysicalAddressLine1("1 Main Rd");
        dto.setPhysicalAddressLine2("Unit 2");
        dto.setPhysicalSuburb("Gardens");
        dto.setPhysicalCity("Cape Town");
        dto.setPhysicalProvince("Western Cape");
        dto.setPhysicalPostalCode("8001");
        dto.setPostalAddressLine1("PO Box 99");
        dto.setPostalCity("Cape Town");
        dto.setPostalProvince("Western Cape");
        dto.setPostalPostalCode("8000");
        dto.setNotes("Please call before delivery");
        return dto;
    }

    /** Only the fields the create path guarantees; every optional field left null. */
    private WholesaleCustomerDto minimalDto() {
        WholesaleCustomerDto dto = new WholesaleCustomerDto();
        dto.setApplicantEmail("applicant@test.com");
        dto.setFirstName("Jane");
        dto.setCompanyName("ACME Corp");
        dto.setPurchaseOrderRequired(false);
        return dto;
    }

    @Test
    @DisplayName("admin template renders every field of a full application")
    void adminTemplateRendersFullApplication() {
        String html = adminTemplate
                .data("applicationId", APPLICATION_ID)
                .data("app", fullDto())
                .render();

        assertTrue(html.contains("ACME Corp"));
        assertTrue(html.contains("ACME Trading"));
        assertTrue(html.contains("Jane Doe"));
        assertTrue(html.contains("applicant@test.com"));
        assertTrue(html.contains("VAT123"));
        assertTrue(html.contains("REG-42"));
        assertTrue(html.contains("Fin Person"));
        assertTrue(html.contains("1 Main Rd"));
        assertTrue(html.contains("PO Box 99"));
        assertTrue(html.contains("Please call before delivery"));
        assertTrue(html.contains(APPLICATION_ID.toString()));
        assertTrue(html.contains("Yes")); // PO required
    }

    @Test
    @DisplayName("admin template renders a minimal application (all optionals null)")
    void adminTemplateRendersMinimalApplication() {
        String html = adminTemplate
                .data("applicationId", APPLICATION_ID)
                .data("app", minimalDto())
                .render();

        assertTrue(html.contains("ACME Corp"));
        assertTrue(html.contains("applicant@test.com"));
        assertFalse(html.contains("Trading name"));
        assertFalse(html.contains("Finance contact"));
        assertFalse(html.contains("Physical address"));
    }

    @Test
    @DisplayName("applicant template renders every field with store name")
    void applicantTemplateRendersFullApplication() {
        String html = applicantTemplate
                .data("applicationId", APPLICATION_ID)
                .data("storeName", "My Store")
                .data("app", fullDto())
                .render();

        assertTrue(html.contains("Hi Jane,"));
        assertTrue(html.contains("My Store"));
        assertTrue(html.contains("ACME Corp"));
        assertTrue(html.contains("1 Main Rd"));
        assertTrue(html.contains(APPLICATION_ID.toString()));
        // No leftover placeholder identity from the legacy template
        assertFalse(html.contains("yourstore.com"));
        assertFalse(html.contains("Your Store Name"));
    }

    @Test
    @DisplayName("applicant template renders a minimal application (all optionals null)")
    void applicantTemplateRendersMinimalApplication() {
        String html = applicantTemplate
                .data("applicationId", APPLICATION_ID)
                .data("storeName", "My Store")
                .data("app", minimalDto())
                .render();

        assertTrue(html.contains("Hi Jane,"));
        assertTrue(html.contains("ACME Corp"));
        assertFalse(html.contains("Trading name"));
        assertFalse(html.contains("Postal address"));
        assertTrue(html.contains("No")); // PO required = false
    }
}
