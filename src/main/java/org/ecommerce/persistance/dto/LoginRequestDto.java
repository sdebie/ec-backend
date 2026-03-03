package org.ecommerce.persistance.dto;

import jakarta.validation.constraints.NotBlank;

// The data coming IN from React-Vite
public record LoginRequestDto(
        @NotBlank String username,
        @NotBlank String password
) {}
