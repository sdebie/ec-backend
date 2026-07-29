package org.ecommerce.backend.service;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.CustomerEntity;

import java.time.Duration;
import java.util.Set;

@ApplicationScoped
public class CustomerAuthService
{
    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    public String generateToken(CustomerEntity customerEntity)
    {
        String shopperType = customerEntity.getShopperType() != null ? customerEntity.getShopperType().name() : "RETAILER";
        return Jwt.issuer(issuer)
                .subject(customerEntity.getUser().getEmail())
                .upn(customerEntity.getUser().getEmail())
                .groups(Set.of("customer"))
                .claim("shopperType", shopperType)
                .expiresIn(Duration.ofHours(24))
                .sign();
    }
}
