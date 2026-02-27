package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Table(name = "customers")
public class CustomerEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "shopper_type")
    public CustomerTypeEn shopperType;

    @Column(unique = true, nullable = false)
    public String email;

    @Column(name = "first_name")
    public String firstName;

    @Column(name = "last_name")
    public String lastName;

    public String phone;

    // --- Default Shipping / Billing Address ---
    @Column(name = "address_line_1")
    public String addressLine1;

    @Column(name = "address_line_2")
    public String addressLine2;

    public String city;
    public String province;

    @Column(name = "postal_code")
    public String postalCode;

    @OneToMany(mappedBy = "customerEntity", cascade = CascadeType.ALL)
    public List<OrderEntity> orderEntities;

    @Column(name = "password_hash")
    public String passwordHash;

    @Column(name = "last_login")
    public LocalDateTime passwordUpdatedAt;

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Helper to find a customer by email (useful for logins/lookups)
     */
    public static CustomerEntity findByEmail(String email) {
        return find("email", email).firstResult();
    }

}
