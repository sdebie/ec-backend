package org.ecommerce.backend.service;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.dto.LoginRequestDto;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.repository.StaffRepository;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

@ApplicationScoped
public class AdminAuthService
{
    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    @Inject
    StaffRepository staffRepository;

    public String authenticate(LoginRequestDto loginDto)
    {
        // 1. Fetch user from DB (assuming Panache)
        StaffUserEntity user = staffRepository.findByEmail(loginDto.email());

        // 2. Verify password against BCrypt hash
        if (user != null && user.isActive() && BcryptUtil.matches(loginDto.password(), user.getPasswordHash())) {
            return generateToken(user);
        }
        return null;
    }

    private String generateToken(StaffUserEntity user)
    {
        // 3. Map the Enum role to the JWT 'groups' claim for RBAC
        return Jwt.issuer(issuer)
                .subject(user.getEmail())
                .upn(user.getEmail())
                .groups(new HashSet<>(List.of(user.getRole().name())))
                .claim("full_name", user.getFullName())
                .expiresIn(Duration.ofHours(8))
                .sign();
    }
}

