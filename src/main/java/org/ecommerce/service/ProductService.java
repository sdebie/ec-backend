package org.ecommerce.service;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.ecommerce.persistance.dto.ProductListItemDto;
import org.ecommerce.persistance.entity.ProductVariantEntity;

import java.math.BigDecimal;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductService {

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getAllProducts(String categoryName) {
        String sql = """
            SELECT p.id,
                   p.name,
                   p.description,
                   (SELECT MIN(v.price) FROM product_variants v WHERE v.product_id = p.id) AS min_price,
                   COALESCE(
                       (SELECT i.image_url FROM product_images i WHERE i.product_id = p.id AND i.is_featured = TRUE ORDER BY i.sort_order ASC LIMIT 1),
                       (SELECT i2.image_url FROM product_images i2 WHERE i2.product_id = p.id ORDER BY i2.sort_order ASC LIMIT 1)
                   ) AS image_url,
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
            String imageUrl = (String) r[4];

            // Map variant IDs from JDBC array / list (kept as Long for now)
            List<String> variantIds = new ArrayList<>();
            Object vCol = r.length > 5 ? r[5] : null;
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

            String categoryNameResult = (String) (r.length > 6 ? r[6] : null);
            list.add(new ProductListItemDto(id, name, description, price, imageUrl, variantIds, categoryNameResult));
        }
        return list;
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> getVariantsByIds(List<String> ids) {
        List<UUID> uuidIds = ids.stream()
                .map(UUID::fromString)
                .collect(Collectors.toList());
        return ProductVariantEntity.listByIdsWithProduct(uuidIds);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> getProductWithVariants(String productId) {
        UUID pid = UUID.fromString(productId);
        return ProductVariantEntity.listByProductIdWithProduct(pid);
    }
}
