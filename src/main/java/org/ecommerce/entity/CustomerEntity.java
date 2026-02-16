package org.ecommerce.entity;

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
    public String addressLine1;
    public String addressLine2;
    public String city;
    public String province;
    public String postal_code;

    @OneToMany(mappedBy = "customerEntity", cascade = CascadeType.ALL)
    public List<QuotationEntity> quotationEntities;

    public LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Helper to find a customer by email (useful for logins/lookups)
     */
    public static CustomerEntity findByEmail(String email) {
        return find("email", email).firstResult();
    }
}