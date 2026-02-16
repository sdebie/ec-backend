package org.ecommerce.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "quotations")
public class QuotationEntity extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    public CustomerEntity customerEntity;

    @Column(name = "total_amount", nullable = false)
    public BigDecimal totalAmount;

    @Column(length = 50)
    public String status = "PENDING"; // PENDING, PAID, CANCELLED

    // Delivery Details
    public String shippingPhone;
    public String shippingAddressLine1;
    public String shippingAddressLine2;
    public String shippingCity;
    public String shippingProvince;
    public String shippingPostalCode;

    @OneToMany(mappedBy = "quotationEntity", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<QuotationItemEntity> items;

    public LocalDateTime createdAt = LocalDateTime.now();
}