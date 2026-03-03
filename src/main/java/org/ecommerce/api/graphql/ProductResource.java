package org.ecommerce.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.persistance.dto.ProductListItemDto;
import org.ecommerce.persistance.entity.ProductVariantEntity;
import org.ecommerce.service.ProductService;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.List;

@ApplicationScoped
@GraphQLApi
public class ProductResource {

    @Inject
    ProductService productService;

    @Query("products")
    @Description("Returns a simple list of products with min price, featured image and variant ids")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> products(@Name("categoryName") String categoryName) {
        return productService.getAllProducts(categoryName);
    }

    @Query("variantsByIds")
    @Description("Fetch product variants by a list of ids, including product relation")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> variantsByIds(@Name("ids") List<String> ids) {
        return productService.getVariantsByIds(ids);
    }

    @Query("getProductWithVariants")
    @Description("Fetch all variants for a given product id, including the product relation")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> getProductWithVariants(@Name("productId") String productId) {
        return productService.getProductWithVariants(productId);
    }

}
