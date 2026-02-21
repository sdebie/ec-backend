package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

// Structured Shipping Methods
@Entity
@Table(name = "shipping_methods")
public class ShippingMethodEntity extends PanacheEntity {
    public String name;

    @Column(name = "is_active")
    public boolean isActive = true;

    @Column(name = "base_fee")
    public BigDecimal baseFee;

    @Column(name = "estimated_days")
    public String estimatedDays;
}
