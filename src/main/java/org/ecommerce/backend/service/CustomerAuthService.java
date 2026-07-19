package org.ecommerce.backend.service;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.CustomerEntity;

import java.time.Duration;
import java.util.Set;

@ApplicationScoped
public class CustomerAuthService {

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    public String generateToken(CustomerEntity ce) {
        String shopperType = ce.shopperType != null ? ce.shopperType.name() : "GUEST";
        return Jwt.issuer(issuer)
                .subject(ce.user.email)
                .upn(ce.user.email)
                .groups(Set.of("customer"))
                .claim("shopperType", shopperType)
                .expiresIn(Duration.ofHours(24))
                .sign();
    }
}
