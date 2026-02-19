package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItemEntity extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", referencedColumnName = "id", nullable = false)
    public OrderEntity orderEntity;

    // Link to product variant via JPA relation
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "variant_id", referencedColumnName = "id", nullable = true)
    public ProductVariantEntity variant;

    // Backward-compatible transient field for serialization input/output
    @Transient
    public Long variantId;

    @Column(nullable = false)
    public Integer quantity;

    @Column(name = "unit_price", nullable = false)
    public BigDecimal unitPrice;

    // --- Optional Helpers ---

    /**
     * Calculates the subtotal for this specific line item.
     */
    public BigDecimal getSubtotal() {
        return unitPrice.multiply(new BigDecimal(quantity));
    }
}