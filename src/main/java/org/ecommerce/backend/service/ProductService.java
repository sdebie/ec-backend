package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.ProductListDto;
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
        List<ProductEntity> products = productRepository.findAll(pageRequest, filterRequest);

        return products.stream().map(product -> {
            UUID productId = product.id;

            List<String> variantIds = productVariantRepository.findByProductIdWithProduct(productId)
                    .stream()
                    .map(v -> v.id.toString())
                    .collect(Collectors.toList());

            var productImages = productMapper.mapImageEntitiesToDtos(
                    productImageRepository.findByProductId(productId));

            BigDecimal retailPrice        = productVariantRepository.getMinimumPrice(productId, PriceTypeEn.RETAIL_PRICE);
            BigDecimal retailSalePrice    = productVariantRepository.getMinimumPrice(productId, PriceTypeEn.RETAIL_SALE_PRICE);
            BigDecimal wholesalePrice     = productVariantRepository.getMinimumPrice(productId, PriceTypeEn.WHOLESALE_PRICE);
            BigDecimal wholesaleSalePrice = productVariantRepository.getMinimumPrice(productId, PriceTypeEn.WHOLESALE_SALE_PRICE);

            String categoryName = product.category != null ? product.category.name : null;

            return new ProductListItemDto(
                    productId.toString(), product.name, product.description,
                    retailPrice, retailSalePrice, wholesalePrice, wholesaleSalePrice,
                    productImages, variantIds, categoryName);
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
    public ProductListDto getProductWithVariantsDto(String productId)
    {
        UUID pid = UUID.fromString(productId);
        return productMapper.mapToProductListDto(
                productId,
                productVariantRepository.findByProductIdWithProduct(pid),
                productImageRepository.findByProductId(pid));
    }
}
