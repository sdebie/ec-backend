package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "orders")
public class OrderEntity extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    public CustomerEntity customerEntity;

    @Column(name = "total_amount", nullable = false)
    public BigDecimal totalAmount;

    @Column(name = "session_id", nullable = false)
    public UUID sessionId;

    @Column(length = 50)
    public String status = "PENDING"; // PENDING, PAID, CANCELLED

    // Delivery Details (not yet persisted in DB schema)
    @Transient
    public String shippingPhone;
    @Transient
    public String shippingAddressLine1;
    @Transient
    public String shippingAddressLine2;
    @Transient
    public String shippingCity;
    @Transient
    public String shippingProvince;
    @Transient
    public String shippingPostalCode;

    @OneToMany(mappedBy = "orderEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<OrderItemEntity> items;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    public LocalDateTime createdAt;


}