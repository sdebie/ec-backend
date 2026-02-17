package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "quotation_items")
public class QuotationItemEntity extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", nullable = false)
    public QuotationEntity quotationEntity;

    @Column(name = "variant_id", nullable = false)
    public Long variantId; // Links to your product_variants (size, color, etc.)

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