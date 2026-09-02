package org.ecommerce.backend.service;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.repository.CustomerRepository;
import org.ecommerce.common.repository.UserRepository;

import java.time.Duration;
import java.util.Set;

@ApplicationScoped
public class CustomerAuthService
{
    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    @Inject
    UserRepository userRepository;

    @Inject
    CustomerRepository customerRepository;

    public UserEntity findUserByEmail(String email)
    {
        return userRepository.findByEmail(email);
    }

    public CustomerEntity findCustomerByEmail(String email)
    {
        return customerRepository.findByEmail(email);
    }

    public void persistUser(UserEntity user)
    {
        userRepository.persist(user);
    }

    public void persistCustomer(CustomerEntity customer)
    {
        customerRepository.persist(customer);
    }

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
