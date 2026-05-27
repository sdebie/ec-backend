package org.ecommerce.backend.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.ecommerce.backend.service.CustomerPasswordResetService;

@ApplicationScoped
@GraphQLApi
public class CustomerAuthResource {

    @Inject
    CustomerPasswordResetService customerPasswordResetService;

    @Mutation("initiateCustomerPasswordReset")
    @Description("Initiates a customer password reset. Always returns a generic message.")
    public String initiateCustomerPasswordReset(@Name("email") String email) {
        customerPasswordResetService.initiatePasswordReset(email);
        // Generic response prevents email enumeration.
        return "If the account exists, a reset link has been sent.";
    }

    @Mutation("completeCustomerPasswordReset")
    @Description("Completes a customer password reset using a valid token.")
    public String completeCustomerPasswordReset(@Name("token") String token,
                                                @Name("newPassword") String newPassword) {
        customerPasswordResetService.completePasswordReset(token, newPassword);
        return "Password updated successfully.";
    }
}

