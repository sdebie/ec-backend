package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;

import java.math.BigDecimal;
import java.sql.Array;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductService
{
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getAllProducts(String categoryName, String priceCategory)
    {
        boolean filterByCategory = categoryName != null && !categoryName.isBlank() && !"ALL".equalsIgnoreCase(categoryName);

        String sql = """
                SELECT p.id,
                       p.name,
                       p.description,
                       (SELECT array_agg(v2.id ORDER BY v2.id) FROM product_variants v2 WHERE v2.product_id = p.id) AS variant_ids,
                       c.name AS category_name
                FROM products p
                LEFT JOIN categories c ON p.category_id = c.id
                """ + (filterByCategory ? "WHERE c.name = :categoryName" : "") + """
                    ORDER BY p.created_at ASC
                """;

        @SuppressWarnings("unchecked")
        var query = Panache.getEntityManager()
                .createNativeQuery(sql);
        if (filterByCategory) {
            query.setParameter("categoryName", categoryName);
        }
        List<Object[]> rows = query.getResultList();

        List<ProductListItemDto> list = new ArrayList<>();
        for (Object[] r : rows) {
            String id = r[0] == null ? null : String.valueOf(r[0]);
            String name = (String) r[1];
            String description = (String) r[2];

            // Map variant IDs from JDBC array / list
            List<String> variantIds = new ArrayList<>();
            Object vCol = r.length > 3 ? r[3] : null;
            if (vCol != null) {
                try {
                    switch (vCol) {
                        case Array arr -> {
                            Object o = arr.getArray();
                            if (o instanceof Object[] oa) {
                                for (Object x : oa) {
                                    if (x == null) continue;
                                    variantIds.add(String.valueOf(x));
                                }
                            }
                        }
                        case List<?> lst -> {
                            for (Object x : lst) {
                                if (x == null) continue;
                                variantIds.add(String.valueOf(x));
                            }
                        }
                        case Object[] oa -> {
                            for (Object x : oa) {
                                if (x == null) continue;
                                variantIds.add(String.valueOf(x));
                            }
                        }
                        default -> {
                            // Fallback: comma separated or single value
                            String s = String.valueOf(vCol);
                            if (s != null && !s.isBlank()) {
                                Arrays.stream(s.replaceAll("[{}]", "").split(","))
                                        .map(String::trim)
                                        .filter(t -> !t.isEmpty())
                                        .forEach(variantIds::add);
                            }
                        }
                    }
                } catch (Exception ignore) {
                }
            }

            // Fetch all product images for this product, ordered by sortOrder
            UUID productId = id != null ? UUID.fromString(id) : null;
            List<ProductImageDto> productImages = new ArrayList<>();
            if (productId != null) {
                List<ProductImageEntity> images = ProductImageEntity.list("product.id = ?1 order by sortOrder asc", productId);
                productImages = images.stream()
                        .map(img -> new ProductImageDto(img.id == null ? null : img.id.toString(), img.imageUrl, img.sortOrder, img.isFeatured != null && img.isFeatured))
                        .collect(Collectors.toList());
            }

            // Fetch the minimum prices for retail and wholesale
            BigDecimal retailPrice = getMinimumPrice(productId, PriceTypeEn.RETAIL_PRICE);
            BigDecimal retailSalePrice = getMinimumPrice(productId, PriceTypeEn.RETAIL_SALE_PRICE);
            BigDecimal wholesalePrice = getMinimumPrice(productId, PriceTypeEn.WHOLESALE_PRICE);
            BigDecimal wholesaleSalePrice = getMinimumPrice(productId, PriceTypeEn.WHOLESALE_SALE_PRICE);

            BigDecimal price, salesPrice;
            if ("WHOLESALE".equalsIgnoreCase(priceCategory)) {
                price = wholesalePrice;
                salesPrice = wholesaleSalePrice;
            } else {
                price = retailPrice;
                salesPrice = retailSalePrice;
            }

            String categoryNameResult = (String) (r.length > 4 ? r[4] : null);
            list.add(new ProductListItemDto(id, name, description, price, salesPrice, productImages, variantIds, categoryNameResult));
        }
        return list;
    }

    /**
     * Get the minimum price for a product across all variants for a specific price type.
     */
    private BigDecimal getMinimumPrice(UUID productId, PriceTypeEn priceType) {
        if (productId == null) return null;

        List<ProductVariantEntity> variants = ProductVariantEntity.listByProductIdWithProduct(productId);
        if (variants.isEmpty()) return null;

        return variants.stream()
                .flatMap(v -> v.variantPrices.stream())
                .filter(p -> p.priceType.equals(priceType) &&
                           p.isActive())
                .map(p -> p.price)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> getVariantsByIds(List<String> ids, String priceCategory) {
        List<UUID> uuidIds = ids.stream()
                .map(UUID::fromString)
                .collect(Collectors.toList());
        List<ProductVariantEntity> variants = ProductVariantEntity.listByIdsWithProduct(uuidIds);

        // Filter prices based on priceCategory
        for (ProductVariantEntity variant : variants) {
            if (variant.variantPrices != null) {
                List<VariantPricesEntity> filteredPrices = variant.variantPrices.stream()
                        .filter(p -> {
                            if ("WHOLESALE".equalsIgnoreCase(priceCategory)) {
                                return p.priceType == PriceTypeEn.WHOLESALE_PRICE || p.priceType == PriceTypeEn.WHOLESALE_SALE_PRICE;
                            } else {
                                return p.priceType == PriceTypeEn.RETAIL_PRICE || p.priceType == PriceTypeEn.RETAIL_SALE_PRICE;
                            }
                        })
                        .collect(Collectors.toList());
                variant.variantPrices = filteredPrices;
            }
        }

        return variants;
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> getProductWithVariants(String productId)
    {
        UUID pid = UUID.fromString(productId);
        return ProductVariantEntity.listByProductIdWithProduct(pid);
    }

    @Transactional(value = TxType.SUPPORTS)
    public ProductListDto getProductWithVariantsDto(String productId, String priceCategory) {
        UUID pid = UUID.fromString(productId);
        List<ProductVariantEntity> variants = ProductVariantEntity.listByProductIdWithProduct(pid);

        // Filter prices based on priceCategory
        for (ProductVariantEntity variant : variants) {
            if (variant.variantPrices != null) {
                List<VariantPricesEntity> filteredPrices = variant.variantPrices.stream()
                        .filter(p -> {
                            if ("WHOLESALE".equalsIgnoreCase(priceCategory)) {
                                return p.priceType == PriceTypeEn.WHOLESALE_PRICE || p.priceType == PriceTypeEn.WHOLESALE_SALE_PRICE;
                            } else {
                                return p.priceType == PriceTypeEn.RETAIL_PRICE || p.priceType == PriceTypeEn.RETAIL_SALE_PRICE;
                            }
                        })
                        .collect(Collectors.toList());
                variant.variantPrices = filteredPrices;
            }
        }

        // Fetch all images for this product
        List<ProductImageEntity> images = ProductImageEntity.list("product.id = ?1 order by sortOrder asc", pid);
        List<ProductImageDto> imageDtos = images.stream()
                .map(img -> new ProductImageDto(img.id == null ? null : img.id.toString(), img.imageUrl, img.sortOrder, img.isFeatured != null && img.isFeatured))
                .collect(Collectors.toList());

        // Convert variants to variant-only DTOs with prices
        List<ProductVariantDto> variantDtos = variants.stream()
                .map(variant -> {
                    List<VariantPriceDto> priceDtos = variant.variantPrices.stream()
                            .map(vp -> new VariantPriceDto(
                                    vp.id.toString(),
                                    vp.priceType.name(),
                                    vp.price,
                                    vp.priceStartDate,
                                    vp.priceEndDate,
                                    vp.isActive()
                            ))
                            .collect(Collectors.toList());

                    return new ProductVariantDto(
                            variant.id.toString(),
                            variant.sku,
                            priceDtos,
                            variant.stockQuantity,
                            variant.attributesJson,
                            variant.weightKg
                    );
                })
                .collect(Collectors.toList());

        // Derive product-level info from first variant if available
        String name = null;
        String description = null;
        if (!variants.isEmpty() && variants.get(0).product != null) {
            name = variants.get(0).product.name;
            description = variants.get(0).product.description;
        }

        return new ProductListDto(productId, name, description, imageDtos, variantDtos);
    }
}
