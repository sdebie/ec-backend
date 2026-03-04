package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductListItemDto;
import org.ecommerce.common.dto.ProductListDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;

import java.math.BigDecimal;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductService
{
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getAllProducts(String categoryName)
    {
        String sql = """
                SELECT p.id,
                       p.name,
                       p.description,
                       (SELECT MIN(v.price) FROM product_variants v WHERE v.product_id = p.id) AS min_price,
                       (SELECT array_agg(v2.id ORDER BY v2.id) FROM product_variants v2 WHERE v2.product_id = p.id) AS variant_ids,
                       c.name AS category_name
                FROM products p
                LEFT JOIN categories c ON p.category_id = c.id
                """ + (categoryName != null && !categoryName.isBlank() ? "WHERE c.name = :categoryName" : "") + """
                    ORDER BY p.created_at ASC
                """;

        @SuppressWarnings("unchecked")
        var query = Panache.getEntityManager()
                .createNativeQuery(sql);
        if (categoryName != null && !categoryName.isBlank()) {
            query.setParameter("categoryName", categoryName);
        }
        List<Object[]> rows = query.getResultList();

        List<ProductListItemDto> list = new ArrayList<>();
        for (Object[] r : rows) {
            String id = r[0] == null ? null : String.valueOf(r[0]);
            String name = (String) r[1];
            String description = (String) r[2];
            Double price = null;
            if (r[3] != null) {
                if (r[3] instanceof BigDecimal bd) price = bd.doubleValue();
                else if (r[3] instanceof Number n) price = n.doubleValue();
                else price = Double.valueOf(String.valueOf(r[3]));
            }

            // Map variant IDs from JDBC array / list
            List<String> variantIds = new ArrayList<>();
            Object vCol = r.length > 4 ? r[4] : null;
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

            String categoryNameResult = (String) (r.length > 5 ? r[5] : null);
            list.add(new ProductListItemDto(id, name, description, price, productImages, variantIds, categoryNameResult));
        }
        return list;
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> getVariantsByIds(List<String> ids)
    {
        List<UUID> uuidIds = ids.stream()
                .map(UUID::fromString)
                .collect(Collectors.toList());
        return ProductVariantEntity.listByIdsWithProduct(uuidIds);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> getProductWithVariants(String productId)
    {
        UUID pid = UUID.fromString(productId);
        return ProductVariantEntity.listByProductIdWithProduct(pid);
    }

    @Transactional(value = TxType.SUPPORTS)
    public ProductListDto getProductWithVariantsDto(String productId)
    {
        UUID pid = UUID.fromString(productId);
        List<ProductVariantEntity> variants = ProductVariantEntity.listByProductIdWithProduct(pid);

        // Fetch all images for this product
        List<ProductImageEntity> images = ProductImageEntity.list("product.id = ?1 order by sortOrder asc", pid);
        List<ProductImageDto> imageDtos = images.stream()
                .map(img -> new ProductImageDto(img.id == null ? null : img.id.toString(), img.imageUrl, img.sortOrder, img.isFeatured != null && img.isFeatured))
                .collect(Collectors.toList());

        // Convert variants to variant-only DTOs
        List<ProductVariantDto> variantDtos = variants.stream()
                .map(variant -> new ProductVariantDto(
                        variant.id.toString(),
                        variant.sku,
                        variant.price,
                        variant.stockQuantity,
                        variant.attributesJson,
                        variant.weightKg
                ))
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
