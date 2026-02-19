package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "product_variants")
public class ProductVariantEntity extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    public ProductEntity product;

    @Column(nullable = false, unique = true)
    public String sku;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal price;

    @Column(name = "stock_quantity")
    public Integer stockQuantity;

    // Store JSONB as String to keep mapping minimal; can be mapped to JSON later if needed
    @Column(name = "attributes")
    public String attributesJson;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    public BigDecimal weightKg;

    // Helper method to fetch a variant together with its Product entity
    public static ProductVariantEntity findByIdWithProduct(Long id) {
        if (id == null) return null;
        return find("select v from ProductVariantEntity v left join fetch v.product where v.id = ?1", id).firstResult();
    }
}
