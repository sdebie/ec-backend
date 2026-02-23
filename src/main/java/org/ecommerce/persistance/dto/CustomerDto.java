package org.ecommerce.persistance.dto;

/**
 * Simple DTO for customer information coming from the frontend.
 * For now it only contains the email address.
 */
public class CustomerDto {
    private String email;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
