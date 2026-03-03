package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;

@Entity
@Table(name = "product_images")
public class ProductImageEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    public ProductEntity product;

    @Column(name = "image_url", nullable = false)
    public String imageUrl;

    @Column(name = "sort_order")
    public Integer sortOrder;

    @Column(name = "is_featured")
    public Boolean isFeatured;
}

