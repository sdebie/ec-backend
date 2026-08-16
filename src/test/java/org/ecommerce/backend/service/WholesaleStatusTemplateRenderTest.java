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
 * Renders {@code wholesale_status.html} with real Qute. The mail send is fire-and-log
 * (a template error is logged, never propagated), so without this a broken expression
 * — or a copy branch pointing at the wrong account state — would ship silently past a
 * green suite.
 */
@QuarkusTest
@DisplayName("WholesaleStatusTemplateRenderTest — Qute renders the correct copy per branch")
class WholesaleStatusTemplateRenderTest
{
    @Inject
    @Location("wholesale_status.html")
    Template template;

    private static final String FORGOT_PASSWORD_URL = "http://localhost:3000/account/forgot-password";

    @Test
    @DisplayName("approved + new account — links to Forgot Password, not \"Simply log in\"")
    void approvedNewAccount_rendersForgotPasswordLink()
    {
        String html = template
                .data("firstName", "Jane")
                .data("storeName", "My Store")
                .data("approved", true)
                .data("rejectionReason", null)
                .data("newAccountCreated", true)
                .data("forgotPasswordUrl", FORGOT_PASSWORD_URL)
                .render();

        assertTrue(html.contains(FORGOT_PASSWORD_URL), "must link to the forgot-password flow");
        assertTrue(html.contains("Forgot password"));
        assertFalse(html.contains("Simply log in"), "a brand-new account has no working password yet");
    }

    @Test
    @DisplayName("approved + upgraded existing account — \"Simply log in\", no forgot-password link")
    void approvedUpgradedAccount_rendersSimplyLogIn()
    {
        String html = template
                .data("firstName", "Jane")
                .data("storeName", "My Store")
                .data("approved", true)
                .data("rejectionReason", null)
                .data("newAccountCreated", false)
                .data("forgotPasswordUrl", FORGOT_PASSWORD_URL)
                .render();

        assertTrue(html.contains("Simply log in"), "the existing password still works");
        assertFalse(html.contains(FORGOT_PASSWORD_URL), "no need to send a link when the password already works");
    }

    @Test
    @DisplayName("rejected + reason present — reason section rendered")
    void rejected_withReason_rendersReasonSection()
    {
        String html = template
                .data("firstName", "Bob")
                .data("storeName", "My Store")
                .data("approved", false)
                .data("rejectionReason", "Insufficient documentation")
                .data("newAccountCreated", false)
                .data("forgotPasswordUrl", FORGOT_PASSWORD_URL)
                .render();

        assertTrue(html.contains("has not been approved"));
        assertTrue(html.contains("Insufficient documentation"));
        assertFalse(html.contains("Simply log in"));
        assertFalse(html.contains(FORGOT_PASSWORD_URL));
    }

    @Test
    @DisplayName("rejected + no reason — reason section absent")
    void rejected_withoutReason_omitsReasonSection()
    {
        String html = template
                .data("firstName", "Bob")
                .data("storeName", "My Store")
                .data("approved", false)
                .data("rejectionReason", null)
                .data("newAccountCreated", false)
                .data("forgotPasswordUrl", FORGOT_PASSWORD_URL)
                .render();

        assertTrue(html.contains("has not been approved"));
        assertFalse(html.contains("Reason"));
    }
}
