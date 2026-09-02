package org.ecommerce.backend.service;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders {@code contact_enquiry.html} with real Qute. {@link ContactEnquiryMailerTest}
 * mocks the MailTemplate entirely, so without this a broken expression would ship
 * silently past a green suite — exactly as {@link WholesaleStatusTemplateRenderTest}
 * guards the wholesale templates.
 */
@QuarkusTest
@DisplayName("ContactEnquiryTemplateRenderTest — Qute renders enquiry details")
class ContactEnquiryTemplateRenderTest
{
    @Inject
    @Location("contact_enquiry.html")
    Template template;

    @Test
    @DisplayName("renders name, email, phone, company and message")
    void rendersAllFields()
    {
        String html = template
                .data("name", "Jane Doe")
                .data("email", "jane@visitor.com")
                .data("phone", "0821234567")
                .data("company", "ACME Corp")
                .data("message", "I'd like to enquire about your services.")
                .render();

        assertTrue(html.contains("Jane Doe"));
        assertTrue(html.contains("jane@visitor.com"));
        assertTrue(html.contains("0821234567"));
        assertTrue(html.contains("ACME Corp"));
        assertTrue(html.contains("enquire about your services"));
    }

    @Test
    @DisplayName("omits the company row when no company is given")
    void omitsCompanyRowWhenAbsent()
    {
        String html = template
                .data("name", "Jane Doe")
                .data("email", "jane@visitor.com")
                .data("phone", "0821234567")
                .data("company", null)
                .data("message", "No company here.")
                .render();

        assertFalse(html.contains("<th>Company</th>"));
    }
}
