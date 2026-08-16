package org.ecommerce.backend.service;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders {@code password_reset_code.html} with real Qute. The mail send is
 * fire-and-log, so without this a broken expression would ship silently past a
 * green suite — exactly as {@link WholesaleSubmissionTemplateRenderTest} guards
 * the wholesale templates.
 */
@QuarkusTest
@DisplayName("PasswordResetCodeTemplateRenderTest — Qute renders the code and expiry")
class PasswordResetCodeTemplateRenderTest
{
    @Inject
    @Location("password_reset_code.html")
    Template template;

    @Test
    @DisplayName("renders the code and expiry minutes")
    void rendersCodeAndExpiry()
    {
        String html = template
                .data("code", "482913")
                .data("expiresInMinutes", 10)
                .render();

        assertTrue(html.contains("482913"));
        assertTrue(html.contains("10"));
    }
}
