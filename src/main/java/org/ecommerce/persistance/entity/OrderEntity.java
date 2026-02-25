package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import org.ecommerce.common.enums.OrderStatusEn;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

@Data
@Entity
@Table(name = "orders")
public class OrderEntity extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    public CustomerEntity customerEntity;

    @Column(name = "total_amount", nullable = false)
    public BigDecimal totalAmount;

    @Column(name = "session_id")
    public UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    public OrderStatusEn status = OrderStatusEn.PENDING;

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
    public List<OrderItemEntity> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    public LocalDateTime createdAt;

    // Finder methods to return fully-hydrated orders (customer + order_items)
    public static OrderEntity findOrderInfoById(Long id) {
        if (id == null)
            throw new IllegalArgumentException("sessionId must not be null");
        return find("select distinct o from OrderEntity o left join fetch o.customerEntity left join fetch o.items where o.id = ?1", id)
                .firstResult();
    }

    public static OrderEntity findLatestOrderInfoBySessionId(UUID sessionId) {
        if (sessionId == null)
            throw new IllegalArgumentException("sessionId must not be null");
        return find("select distinct o from OrderEntity o left join fetch o.customerEntity left join fetch o.items where o.sessionId = ?1 order by o.id desc", sessionId)
                .firstResult();
    }

}
