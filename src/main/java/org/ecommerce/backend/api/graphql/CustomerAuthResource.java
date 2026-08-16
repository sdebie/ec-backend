package org.ecommerce.backend.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.ecommerce.backend.exception.RateLimitExceededException;
import org.ecommerce.backend.service.CustomerPasswordResetService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.utils.CurrentRequestClientIp;

@ApplicationScoped
@GraphQLApi
public class CustomerAuthResource {

    @Inject
    CustomerPasswordResetService customerPasswordResetService;

    @Inject
    RateLimiterService rateLimiterService;

    @Inject
    CurrentRequestClientIp currentRequestClientIp;

    @Mutation("initiateCustomerPasswordReset")
    @Description("Initiates a customer password reset. Always returns a generic message.")
    public String initiateCustomerPasswordReset(@Name("email") String email) {
        String clientIp = currentRequestClientIp.resolve();

        // Chained-check: IP limiter first; if denied, email counter is NOT incremented.
        // Silent denial — return the identical generic response to prevent enumeration.
        RateLimitDecision ipDecision = rateLimiterService.check("password-reset-request", clientIp, 5, 3600);
        if (!ipDecision.allowed()) {
            return "If the account exists, a reset link has been sent.";
        }

        // Email limiter second (IP passed) — shared counters with REST entry point
        String emailKey = (email != null) ? email.toLowerCase().trim() : "unknown";
        RateLimitDecision emailDecision = rateLimiterService.check("password-reset-request-email", emailKey, 3, 3600);
        if (!emailDecision.allowed()) {
            return "If the account exists, a reset link has been sent.";
        }

        customerPasswordResetService.initiatePasswordReset(email);
        // Generic response prevents email enumeration.
        return "If the account exists, a reset link has been sent.";
    }

    @Mutation("completeCustomerPasswordReset")
    @Description("Completes a customer password reset using a valid token.")
    public String completeCustomerPasswordReset(@Name("token") String token,
                                                @Name("newPassword") String newPassword) {
        String clientIp = currentRequestClientIp.resolve();

        // Rate limit completion attempts to prevent token brute force
        RateLimitDecision decision = rateLimiterService.check("password-reset-complete", clientIp, 10, 3600);
        if (!decision.allowed()) {
            throw new RateLimitExceededException();
        }

        customerPasswordResetService.completePasswordReset(token, newPassword);
        return "Password updated successfully.";
    }
}

