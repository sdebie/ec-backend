package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "products")
public class ProductEntity extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    public CategoryEntity category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    public BrandEntity brand;

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "product_type")
    public String productType; // SIMPLE or VARIABLE

    @Column(name = "created_at")
    public LocalDateTime createdAt = LocalDateTime.now();
}
