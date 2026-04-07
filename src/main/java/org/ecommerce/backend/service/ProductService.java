package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductListItemDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductRepository;
import org.ecommerce.common.repository.ProductVariantRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class ProductService
{
    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    ProductImageRepository productImageRepository;

    @Inject
    ProductMapper productMapper;

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getAllProducts(PageRequest pageRequest, FilterRequest filterRequest)
    {
        List<ProductListItemDto> products = productRepository.findAllProductListItems(pageRequest, filterRequest);

        return products.stream().map(product -> {
            if (product.id == null) {
                product.variantIds = List.of();
                product.productImages = List.of();
                product.retailPrice = BigDecimal.ZERO;
                product.retailSalesPrice = BigDecimal.ZERO;
                product.wholesalePrice = BigDecimal.ZERO;
                product.wholesaleSalesPrice = BigDecimal.ZERO;
                return product;
            }

            UUID productId = UUID.fromString(product.id);

            product.variantIds = productVariantRepository.findByVariantsForProductId(productId)
                    .stream()
                    .map(v -> v.id.toString())
                    .collect(Collectors.toList());

            product.productImages = productMapper.mapImageEntitiesToDtos(
                    productImageRepository.findByProductId(productId));

            product.retailPrice = productVariantRepository.getMinimumPrice(productId, PriceTypeEn.RETAIL_PRICE);
            product.retailSalesPrice = productVariantRepository.getMinimumPrice(productId, PriceTypeEn.RETAIL_SALE_PRICE);
            product.wholesalePrice = productVariantRepository.getMinimumPrice(productId, PriceTypeEn.WHOLESALE_PRICE);
            product.wholesaleSalesPrice = productVariantRepository.getMinimumPrice(productId, PriceTypeEn.WHOLESALE_SALE_PRICE);

            return product;
        }).collect(Collectors.toList());
    }

    @Transactional(value = TxType.SUPPORTS)
    public long productCount(FilterRequest filterRequest)
    {
        return productRepository.count(filterRequest);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantDto> getVariantsByIds(List<String> ids)
    {
        List<UUID> uuidIds = ids.stream()
                .map(UUID::fromString)
                .collect(Collectors.toList());
        return productMapper.mapVariantEntitiesToDtos(
                productVariantRepository.findByIdsWithProduct(uuidIds));
    }

    @Transactional(value = TxType.SUPPORTS)
    public ProductInformationDto getProductInformationDto(String productId)
    {
        UUID pid = UUID.fromString(productId);
        ProductEntity product = productRepository.findByIdWithCategoryAndBrand(pid);
        if (product == null) {
            return null;
        }

        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findByVariantsForProductId(pid),
                productImageRepository.findByProductId(pid));
    }
}
