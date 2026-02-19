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
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@GraphQLApi
public class ProductGraphQlResource {

    @Query("products")
    @Description("Returns a simple list of products with min price and featured image")
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
                   ) AS image_url
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
            list.add(new ProductListItem(id, name, description, price, imageUrl));
        }
        return list;
    }
}
