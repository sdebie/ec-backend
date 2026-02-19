package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "customers")
public class CustomerEntity extends PanacheEntity {

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


    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Helper to find a customer by email (useful for logins/lookups)
     */
    public static CustomerEntity findByEmail(String email) {
        return find("email", email).firstResult();
    }
}