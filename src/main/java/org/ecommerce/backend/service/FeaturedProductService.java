package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.dto.FeaturedProductResultDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.repository.CategoryRepository;
import org.ecommerce.common.repository.ProductRepository;
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
                .map(product -> toAdminProductListItemDto(product, now))
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
                .map(product -> toShoppingListItemDto(product, now))
                .collect(Collectors.toList());
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return 8;
        }
        return Math.min(limit, FEATURED_CAP);
    }

    private AdminProductListItemDto toAdminProductListItemDto(ProductEntity product, LocalDateTime now) {
        AdminProductListItemDto dto = new AdminProductListItemDto();
        dto.id = product.id != null ? product.id.toString() : null;
        dto.name = product.name;
        dto.slug = product.slug;
        dto.status = product.status != null ? product.status.name() : null;

        CategoryEntity cat = product.getCategory();
        if (cat != null) {
            CategoryDto catDto = new CategoryDto();
            catDto.setId(cat.id);
            catDto.setName(cat.name);
            dto.category = catDto;
        }

        if (product.id == null) {
            return dto;
        }

        UUID productId = product.id;
        var em = productRepository.getEntityManager();

        // Primary variant — resolve SKU and thumbnail
        var variants = em.createQuery(
                        "SELECT v FROM ProductVariantEntity v WHERE v.product.id = :productId ORDER BY v.id ASC",
                        org.ecommerce.common.entity.ProductVariantEntity.class)
                .setParameter("productId", productId)
                .getResultList();

        if (!variants.isEmpty()) {
            var primaryVariant = variants.get(0);
            dto.sku = primaryVariant.sku;

            var images = em.createQuery(
                            "SELECT pi FROM ProductImageEntity pi WHERE pi.productVariant.id = :variantId " +
                                    "ORDER BY CASE WHEN pi.isFeatured = true THEN 0 ELSE 1 END ASC, pi.sortOrder ASC, pi.id ASC",
                            org.ecommerce.common.entity.ProductImageEntity.class)
                    .setParameter("variantId", primaryVariant.id)
                    .setMaxResults(1)
                    .getResultList();
            if (!images.isEmpty()) {
                dto.thumbnailUrl = images.get(0).imageUrl;
            }
        }

        // Aggregated stock count
        Long totalStock = em.createQuery(
                        "SELECT COALESCE(SUM(v.stockQuantity), 0) FROM ProductVariantEntity v WHERE v.product.id = :productId",
                        Long.class)
                .setParameter("productId", productId)
                .getSingleResult();
        dto.stockCount = totalStock != null ? totalStock.intValue() : 0;
        dto.stockLevel = deriveStockLevel(dto.stockCount);

        // Lowest active retail price
        var retailPrices = em.createQuery(
                        "SELECT vp FROM VariantPricesEntity vp JOIN vp.variant v " +
                                "WHERE v.product.id = :productId " +
                                "AND v.status = :activeStatus " +
                                "AND vp.priceType = :priceType " +
                                "AND (vp.priceStartDate IS NULL OR vp.priceStartDate <= :now) " +
                                "AND (vp.priceEndDate IS NULL OR vp.priceEndDate >= :now) " +
                                "ORDER BY vp.price ASC",
                        org.ecommerce.common.entity.VariantPricesEntity.class)
                .setParameter("productId", productId)
                .setParameter("activeStatus", ProductStatusEn.ACTIVE)
                .setParameter("priceType", org.ecommerce.common.enums.PriceTypeEn.RETAIL_PRICE)
                .setParameter("now", now)
                .setMaxResults(1)
                .getResultList();

        if (!retailPrices.isEmpty()) {
            dto.retailPrice = retailPrices.get(0).price.toPlainString();
        }

        return dto;
    }

    private String deriveStockLevel(int stockCount) {
        if (stockCount <= 0) {
            return "OUT_OF_STOCK";
        } else if (stockCount <= 10) {
            return "LOW_STOCK";
        } else {
            return "IN_STOCK";
        }
    }

    private ProductShoppingListItemDto toShoppingListItemDto(ProductEntity product, LocalDateTime now) {
        ProductShoppingListItemDto dto = new ProductShoppingListItemDto();
        dto.id = product.id == null ? null : product.id.toString();
        dto.name = product.name;
        dto.slug = product.slug;
        dto.shortDescription = product.shorDescription;
        dto.productType = product.productType == null ? null : product.productType.name();
        dto.status = product.status == null ? null : product.status.name();

        if (product.id != null) {
            boolean ignoreStatus = false;
            dto.variantCount = countVariants(product.id, ignoreStatus);
            dto.variantId = product.productType == org.ecommerce.common.enums.ProductTypeEn.SIMPLE
                    ? findFirstVariantId(product.id, ignoreStatus)
                    : null;
            dto.images = findProductImages(product.id);
            dto.retailPrice = findLowestActivePrice(product.id, org.ecommerce.common.enums.PriceTypeEn.RETAIL_PRICE, now, ignoreStatus);
            dto.wholesalePrice = findLowestActivePrice(product.id, org.ecommerce.common.enums.PriceTypeEn.WHOLESALE_PRICE, now, ignoreStatus);
            dto.retailSalePrice = findLowestActivePrice(product.id, org.ecommerce.common.enums.PriceTypeEn.RETAIL_SALE_PRICE, now, ignoreStatus);
            dto.wholesaleSalePrice = findLowestActivePrice(product.id, org.ecommerce.common.enums.PriceTypeEn.WHOLESALE_SALE_PRICE, now, ignoreStatus);
        } else {
            dto.variantCount = 0;
            dto.images = List.of();
        }

        return dto;
    }

    private int countVariants(UUID productId, boolean ignoreStatus) {
        var em = productRepository.getEntityManager();
        String query = ignoreStatus
                ? "SELECT COUNT(v) FROM ProductVariantEntity v WHERE v.product.id = :productId"
                : "SELECT COUNT(v) FROM ProductVariantEntity v WHERE v.product.id = :productId AND v.status = :activeStatus";

        var q = em.createQuery(query, Long.class)
                .setParameter("productId", productId);
        if (!ignoreStatus) {
            q.setParameter("activeStatus", ProductStatusEn.ACTIVE);
        }
        Long count = q.getSingleResult();
        return count != null ? count.intValue() : 0;
    }

    private String findFirstVariantId(UUID productId, boolean ignoreStatus) {
        var em = productRepository.getEntityManager();
        String query = ignoreStatus
                ? "SELECT v.id FROM ProductVariantEntity v WHERE v.product.id = :productId ORDER BY v.id ASC"
                : "SELECT v.id FROM ProductVariantEntity v WHERE v.product.id = :productId AND v.status = :activeStatus ORDER BY v.id ASC";

        var q = em.createQuery(query, UUID.class)
                .setParameter("productId", productId)
                .setMaxResults(1);
        if (!ignoreStatus) {
            q.setParameter("activeStatus", ProductStatusEn.ACTIVE);
        }
        var results = q.getResultList();
        return results.isEmpty() ? null : results.get(0).toString();
    }

    private List<org.ecommerce.common.dto.ProductImageDto> findProductImages(UUID productId) {
        var em = productRepository.getEntityManager();
        var images = em.createQuery(
                        "SELECT pi FROM ProductImageEntity pi " +
                                "JOIN pi.productVariant v " +
                                "WHERE v.product.id = :productId " +
                                "ORDER BY CASE WHEN pi.isFeatured = true THEN 0 ELSE 1 END ASC, pi.sortOrder ASC, pi.id ASC",
                        org.ecommerce.common.entity.ProductImageEntity.class)
                .setParameter("productId", productId)
                .getResultList();

        return images.stream().map(img -> {
            org.ecommerce.common.dto.ProductImageDto dto = new org.ecommerce.common.dto.ProductImageDto();
            dto.id = img.id == null ? null : img.id.toString();
            dto.imageUrl = img.imageUrl;
            dto.isFeatured = img.isFeatured != null && img.isFeatured;
            dto.sortOrder = img.sortOrder;
            return dto;
        }).collect(Collectors.toList());
    }

    private org.ecommerce.common.dto.VariantPriceDto findLowestActivePrice(
            UUID productId,
            org.ecommerce.common.enums.PriceTypeEn priceType,
            LocalDateTime now,
            boolean ignoreStatus) {

        var em = productRepository.getEntityManager();
        String query = "SELECT vp FROM VariantPricesEntity vp JOIN vp.variant v " +
                "WHERE v.product.id = :productId " +
                (ignoreStatus ? "" : "AND v.status = :activeStatus ") +
                "AND vp.priceType = :priceType " +
                "AND (vp.priceStartDate IS NULL OR vp.priceStartDate <= :now) " +
                "AND (vp.priceEndDate IS NULL OR vp.priceEndDate >= :now) " +
                "ORDER BY vp.price ASC";

        var q = em.createQuery(query, org.ecommerce.common.entity.VariantPricesEntity.class)
                .setParameter("productId", productId)
                .setParameter("priceType", priceType)
                .setParameter("now", now)
                .setMaxResults(1);

        if (!ignoreStatus) {
            q.setParameter("activeStatus", ProductStatusEn.ACTIVE);
        }

        var results = q.getResultList();
        if (results.isEmpty()) {
            return null;
        }

        org.ecommerce.common.entity.VariantPricesEntity vp = results.get(0);
        org.ecommerce.common.dto.VariantPriceDto dto = new org.ecommerce.common.dto.VariantPriceDto();
        dto.id = vp.id == null ? null : vp.id.toString();
        dto.price = vp.price;
        dto.priceType = vp.priceType == null ? null : vp.priceType.name();
        dto.priceStartDate = vp.priceStartDate;
        dto.priceEndDate = vp.priceEndDate;
        return dto;
    }
}
