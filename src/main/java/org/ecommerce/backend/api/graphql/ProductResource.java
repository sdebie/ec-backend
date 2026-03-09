package org.ecommerce.backend.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.common.dto.ProductListItemDto;
import org.ecommerce.common.dto.ProductListDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.backend.service.ProductService;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
@GraphQLApi
public class ProductResource
{
    @Inject
    ProductService productService;

    @Query("products")
    @Description("Returns a simple list of products with price and sales price for the selected category")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> products(@Name("categoryName") String categoryName,
                                             @DefaultValue("RETAIL") @Name("priceCategory") String priceCategory) {
        return productService.getAllProducts(categoryName, priceCategory);
    }

    @Query("variantsByIds")
    @Description("Fetch product variants by a list of ids, including product relation and prices for the selected category")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantEntity> variantsByIds(@Name("ids") List<String> ids,
                                                    @DefaultValue("RETAIL") @Name("priceCategory") String priceCategory) {
        return productService.getVariantsByIds(ids, priceCategory);
    }

    @Query("getProductWithVariants")
    @Description("Fetch a product with all variants for a given product id, including product images and prices for the selected category")
    @Transactional(value = TxType.SUPPORTS)
    public ProductListDto getProductWithVariants(@Name("productId") String productId,
                                                 @DefaultValue("RETAIL") @Name("priceCategory") String priceCategory) {
        return productService.getProductWithVariantsDto(productId, priceCategory);
    }


}
