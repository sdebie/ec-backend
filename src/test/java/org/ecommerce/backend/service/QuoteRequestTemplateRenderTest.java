package org.ecommerce.backend.service;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.QuoteRequestItemDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders {@code quote_request.html} with real Qute. {@link QuoteRequestMailerTest}
 * mocks the MailTemplate entirely, so without this a broken expression would ship
 * silently past a green suite — exactly as {@link WholesaleStatusTemplateRenderTest}
 * guards the wholesale templates.
 */
@QuarkusTest
@DisplayName("QuoteRequestTemplateRenderTest — Qute renders request details and items")
class QuoteRequestTemplateRenderTest
{
    @Inject
    @Location("quote_request.html")
    Template template;

    private QuoteRequestItemDto item(String product, String sku, int quantity)
    {
        QuoteRequestItemDto item = new QuoteRequestItemDto();
        item.setProductNameSnapshot(product);
        item.setVariantSkuSnapshot(sku);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    @DisplayName("renders contact fields, message and requested items")
    void rendersAllFields()
    {
        String html = template
                .data("name", "John Smith")
                .data("email", "john@company.co.za")
                .data("phone", "0821234567")
                .data("company", "Smith Corp")
                .data("message", "Need bulk pricing please.")
                .data("items", List.of(item("Widget Pro", "WGT-001", 5)))
                .render();

        assertTrue(html.contains("John Smith"));
        assertTrue(html.contains("john@company.co.za"));
        assertTrue(html.contains("0821234567"));
        assertTrue(html.contains("Smith Corp"));
        assertTrue(html.contains("Need bulk pricing please."));
        assertTrue(html.contains("Widget Pro"));
        assertTrue(html.contains("WGT-001"));
    }

    @Test
    @DisplayName("omits optional rows and still renders items when phone/company/message are absent")
    void omitsOptionalRowsWhenAbsent()
    {
        String html = template
                .data("name", "John Smith")
                .data("email", "john@company.co.za")
                .data("phone", null)
                .data("company", null)
                .data("message", null)
                .data("items", List.of(item("Widget Pro", null, 1)))
                .render();

        assertTrue(html.contains("Widget Pro"));
        assertFalse(html.contains("<th>Phone</th>"));
        assertFalse(html.contains("<th>Company</th>"));
    }
}
