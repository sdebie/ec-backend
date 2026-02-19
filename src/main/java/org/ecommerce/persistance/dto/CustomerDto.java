package org.ecommerce.persistance.dto;

import lombok.Data;

/**
 * Simple DTO for customer information coming from the frontend.
 * For now it only contains the email address.
 */
@Data
public class CustomerDto {
    private String email;
}
