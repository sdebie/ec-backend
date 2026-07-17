package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.backend.exception.FeaturedCapExceededException;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.FeaturedProductResultDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.repository.CategoryRepository;
import org.ecommerce.common.repository.ProductRepository;
import org.ecommerce.common.entity.CategoryEntity;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class FeaturedProductService {

    private static final Logger LOG = Logger.getLogger(FeaturedProductService.class);

    public static final int FEATURED_CAP = 50;

    @Inject
    ProductRepository productRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    org.ecommerce.backend.assembler.ProductListItemAssembler productListItemAssembler;

    /**
     * Toggle the featured flag on a product.
     *
     * @throws NotFoundException             if productId does not exist
     * @throws FeaturedCapExceededException  if featuring would exceed 50
     */
    @Transactional(value = TxType.REQUIRED)
    public FeaturedProductResultDto setFeatured(UUID productId, boolean featured) {
        ProductEntity product = productRepository.findById(productId);
        if (product == null) {
            throw new NotFoundException("Product not found with id: " + productId);
        }

        if (featured) {
            long currentCount = ProductEntity.count("isFeatured", true);
            if (currentCount >= FEATURED_CAP) {
                throw new FeaturedCapExceededException();
            }
            product.isFeatured = true;
        } else {
            product.isFeatured = false;
        }

        product.persist();

        FeaturedProductResultDto result = new FeaturedProductResultDto();
        result.productId = productId.toString();
        result.featured = product.isFeatured;
        return result;
    }

    /**
     * Returns all featured products (any status) ordered by name ascending.
     * Used by the admin featured list.
     */
    @Transactional(value = TxType.SUPPORTS)
    public List<AdminProductListItemDto> getFeaturedProductsForAdmin() {
        List<ProductEntity> featuredProducts = ProductEntity.find(
                "isFeatured = true order by name asc"
        ).list();

        LocalDateTime now = LocalDateTime.now();
        return featuredProducts.stream()
                .map(product -> productListItemAssembler.buildAdminListItem(product, now))
                .collect(Collectors.toList());
    }

    /**
     * Returns featured + ACTIVE products as shopping DTOs.
     * Supports optional limit (default 8, max 50) and category slug filter.
     */
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getFeaturedShoppingProducts(Integer limit, String categorySlug) {
        int effectiveLimit = resolveLimit(limit);

        List<ProductEntity> products;

        if (categorySlug != null && !categorySlug.isBlank()) {
            CategoryEntity category = categoryRepository.findBySlugIgnoreCase(categorySlug);
            if (category == null) {
                return List.of();
            }

            products = ProductEntity.find(
                    "isFeatured = true AND status = ?1 AND ?2 MEMBER OF categories ORDER BY name ASC",
                    ProductStatusEn.ACTIVE, category
            ).page(0, effectiveLimit).list();
        } else {
            products = ProductEntity.find(
                    "isFeatured = true AND status = ?1 ORDER BY name ASC",
                    ProductStatusEn.ACTIVE
            ).page(0, effectiveLimit).list();
        }

        LocalDateTime now = LocalDateTime.now();
        return products.stream()
                .map(product -> productListItemAssembler.buildShoppingListItem(product, now, false))
                .collect(Collectors.toList());
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return 8;
        }
        return Math.min(limit, FEATURED_CAP);
    }
}
