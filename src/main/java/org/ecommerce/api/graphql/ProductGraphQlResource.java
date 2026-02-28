package org.ecommerce.api.graphql;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.persistance.dto.ProductListItem;
import org.ecommerce.persistance.entity.ProductVariantEntity;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.math.BigDecimal;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
@GraphQLApi
public class ProductGraphQlResource {

    @Query("products")
    @Description("Returns a simple list of products with min price, featured image and variant ids")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItem> products() {
        String sql = """
            SELECT p.id,
                   p.name,
                   p.description,
                   (SELECT MIN(v.price) FROM product_variants v WHERE v.product_id = p.id) AS min_price,
                   COALESCE(
                       (SELECT i.image_url FROM product_images i WHERE i.product_id = p.id AND i.is_featured = TRUE ORDER BY i.sort_order ASC LIMIT 1),
                       (SELECT i2.image_url FROM product_images i2 WHERE i2.product_id = p.id ORDER BY i2.sort_order ASC LIMIT 1)
                   ) AS image_url,
                   (SELECT array_agg(v2.id ORDER BY v2.id) FROM product_variants v2 WHERE v2.product_id = p.id) AS variant_ids
            FROM products p
            ORDER BY p.created_at ASC
        """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = Panache.getEntityManager()
                .createNativeQuery(sql)
                .getResultList();

        List<ProductListItem> list = new ArrayList<>();
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
                    if (vCol instanceof Array arr) {
                        Object o = arr.getArray();
                        if (o instanceof Object[] oa) {
                            for (Object x : oa) {
                                if (x == null) continue;
                                variantIds.add(String.valueOf(x));
                            }
                        }
                    } else if (vCol instanceof List<?> lst) {
                        for (Object x : lst) {
                            if (x == null) continue;
                            variantIds.add(String.valueOf(x));
                        }
                    } else if (vCol instanceof Object[] oa) {
                        for (Object x : oa) {
                            if (x == null) continue;
                            variantIds.add(String.valueOf(x));
                        }
                    } else {
                        // Fallback: comma separated or single value
                        String s = String.valueOf(vCol);
                        if (s != null && !s.isBlank()) {
                            Arrays.stream(s.replaceAll("[{}]", "").split(","))
                                    .map(String::trim)
                                    .filter(t -> !t.isEmpty())
                                    .forEach(variantIds::add);
                        }
                    }
                } catch (Exception ignore) {
                }
            }

            list.add(new ProductListItem(id, name, description, price, imageUrl, variantIds));
        }
        return list;
    }

    @Query("variantsByIds")
    @Description("Fetch product variants by a list of ids, including product relation")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> variantsByIds(@Name("ids") List<String> ids) {
        List<UUID> uuidIds = ids.stream()
                .map(UUID::fromString)
                .collect(Collectors.toList());
        return ProductVariantEntity.listByIdsWithProduct(uuidIds);
    }

    @Query("getProductWithVariants")
    @Description("Fetch all variants for a given product id, including the product relation")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> getProductWithVariants(@Name("productId") String productId) {
        UUID pid = UUID.fromString(productId);
        return ProductVariantEntity.listByProductIdWithProduct(pid);
    }

}
