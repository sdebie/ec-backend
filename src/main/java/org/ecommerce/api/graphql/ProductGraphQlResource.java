package org.ecommerce.api.graphql;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.persistance.dto.ProductListItem;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.math.BigDecimal;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
            ORDER BY p.id ASC
        """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = Panache.getEntityManager()
                .createNativeQuery(sql)
                .getResultList();

        List<ProductListItem> list = new ArrayList<>();
        for (Object[] r : rows) {
            Long id = r[0] == null ? null : ((Number) r[0]).longValue();
            String name = (String) r[1];
            String description = (String) r[2];
            Double price = null;
            if (r[3] != null) {
                if (r[3] instanceof BigDecimal bd) price = bd.doubleValue();
                else if (r[3] instanceof Number n) price = n.doubleValue();
                else price = Double.valueOf(String.valueOf(r[3]));
            }
            String imageUrl = (String) r[4];

            // Map variant IDs from JDBC array / list
            List<Long> variantIds = new ArrayList<>();
            Object vCol = r.length > 5 ? r[5] : null;
            if (vCol != null) {
                try {
                    if (vCol instanceof Array arr) {
                        Object o = arr.getArray();
                        if (o instanceof Object[] oa) {
                            for (Object x : oa) {
                                if (x == null) continue;
                                if (x instanceof Number num) variantIds.add(num.longValue());
                                else variantIds.add(Long.valueOf(String.valueOf(x)));
                            }
                        }
                    } else if (vCol instanceof List<?> lst) {
                        for (Object x : lst) {
                            if (x == null) continue;
                            if (x instanceof Number num) variantIds.add(num.longValue());
                            else variantIds.add(Long.valueOf(String.valueOf(x)));
                        }
                    } else if (vCol instanceof Object[] oa) {
                        for (Object x : oa) {
                            if (x == null) continue;
                            if (x instanceof Number num) variantIds.add(num.longValue());
                            else variantIds.add(Long.valueOf(String.valueOf(x)));
                        }
                    } else {
                        // Fallback: comma separated or single value
                        String s = String.valueOf(vCol);
                        if (s != null && !s.isBlank()) {
                            Arrays.stream(s.replaceAll("[{}]", "").split(","))
                                    .map(String::trim)
                                    .filter(t -> !t.isEmpty())
                                    .forEach(t -> variantIds.add(Long.valueOf(t)));
                        }
                    }
                } catch (Exception ignore) {
                }
            }

            list.add(new ProductListItem(id, name, description, price, imageUrl, variantIds));
        }
        return list;
    }
}
