package org.ecommerce.persistance.dto;

public record TokenResponseDto(
        String token,
        String username,
        String role
) {}
