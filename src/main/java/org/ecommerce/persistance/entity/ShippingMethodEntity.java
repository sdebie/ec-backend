package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

// Structured Shipping Methods
@Entity
@Table(name = "shipping_methods")
public class ShippingMethodEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    public String name;

    @Column(name = "is_active")
    public boolean isActive = true;

    @Column(name = "base_fee")
    public BigDecimal baseFee;

    @Column(name = "estimated_days")
    public String estimatedDays;
}
