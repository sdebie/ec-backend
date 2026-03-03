package org.ecommerce.persistance.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.persistance.entity.ProductImageEntity;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProductImageRepository implements PanacheRepository<ProductImageEntity> {

    /**
     * Find all images for a product, ordered by sort order
     */
    public List<ProductImageEntity> findByProductId(UUID productId) {
        return list("product.id = ?1 ORDER BY sortOrder ASC", productId);
    }

    /**
     * Find featured image for a product
     */
    public ProductImageEntity findFeaturedByProductId(UUID productId) {
        return find("product.id = ?1 AND isFeatured = true", productId).firstResult();
    }

    /**
     * Update the featured image for a product (set one image as featured, unfeature others)
     */
    public void setFeaturedImage(UUID productId, UUID imageId) {
        // Unfeature all images for this product
        update("isFeatured = false WHERE product.id = ?1", productId);

        // Feature the specific image
        update("isFeatured = true WHERE id = ?1 AND product.id = ?2", imageId, productId);
    }
}

