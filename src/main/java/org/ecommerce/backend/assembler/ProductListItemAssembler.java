package org.ecommerce.backend.assembler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Read-model assembler for product list-item DTOs (admin + shopping).
 * <p>
 * These DTOs need per-row lookups (lowest active price, images, stock), so this
 * collaborator coordinates the relevant <em>repositories</em> — it does not open
 * queries itself, and it is not a pure mapper. Repositories own the queries;
 * services orchestrate repository → assembler. Pure field copying lives here.
 * <p>
 * Replaces the former query-bearing {@code ProductListItemMapper} and the DTO
 * assembly that briefly lived inside {@code ProductRepository}.
 */
@ApplicationScoped
public class ProductListItemAssembler {

    @Inject
    ProductVariantRepository variantRepository;

    @Inject
    ProductImageRepository imageRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    @Inject
    ProductMapper productMapper;

    public AdminProductListItemDto buildAdminListItem(ProductEntity product, LocalDateTime now) {
        AdminProductListItemDto dto = new AdminProductListItemDto();
        dto.id = product.id != null ? product.id.toString() : null;
        dto.name = product.name;
        dto.slug = product.slug;
        dto.status = product.status != null ? product.status.name() : null;

        CategoryEntity cat = product.getCategory();
        if (cat != null) {
            CategoryDto catDto = new CategoryDto();
            catDto.setId(cat.id);
            catDto.setName(cat.name);
            dto.category = catDto;
        }

        if (product.id == null) {
            return dto;
        }

        List<ProductVariantEntity> variants = variantRepository.findByVariantsForProductId(product.id);
        if (!variants.isEmpty()) {
            ProductVariantEntity primaryVariant = variants.get(0);
            dto.sku = primaryVariant.sku;
            ProductImageEntity thumb = imageRepository.findThumbnailForVariant(primaryVariant.id);
            if (thumb != null) {
                dto.thumbnailUrl = thumb.imageUrl;
            }
        }

        dto.stockCount = variantRepository.sumStock(product.id);
        dto.stockLevel = deriveStockLevel(dto.stockCount);

        VariantPricesEntity retail = variantPricesRepository.findLowestActiveRetailForAdmin(product.id, now);
        if (retail != null) {
            dto.retailPrice = retail.price.toPlainString();
        }

        return dto;
    }

    public ProductShoppingListItemDto buildShoppingListItem(ProductEntity product, LocalDateTime now, boolean ignoreStatus) {
        ProductShoppingListItemDto dto = new ProductShoppingListItemDto();
        dto.id = product.id == null ? null : product.id.toString();
        dto.name = product.name;
        dto.slug = product.slug;
        dto.shortDescription = product.shorDescription;
        dto.productType = product.productType == null ? null : product.productType.name();
        dto.status = product.status == null ? null : product.status.name();

        if (product.id == null) {
            dto.variantCount = 0;
            dto.images = List.of();
            return dto;
        }

        dto.variantCount = variantRepository.countForProduct(product.id, ignoreStatus);
        dto.variantId = product.productType != ProductTypeEn.SIMPLE
                ? null
                : variantRepository.findFirstVariantId(product.id, ignoreStatus);
        dto.images = imageRepository.findForListing(product.id).stream()
                .map(productMapper::mapImageEntityToDto)
                .toList();
        dto.retailPrice = toVariantPriceDto(variantPricesRepository.findLowestActive(product.id, PriceTypeEn.RETAIL_PRICE, now, ignoreStatus), now);
        dto.wholesalePrice = toVariantPriceDto(variantPricesRepository.findLowestActive(product.id, PriceTypeEn.WHOLESALE_PRICE, now, ignoreStatus), now);
        dto.retailSalePrice = toVariantPriceDto(variantPricesRepository.findLowestActive(product.id, PriceTypeEn.RETAIL_SALE_PRICE, now, ignoreStatus), now);
        dto.wholesaleSalePrice = toVariantPriceDto(variantPricesRepository.findLowestActive(product.id, PriceTypeEn.WHOLESALE_SALE_PRICE, now, ignoreStatus), now);
        return dto;
    }

    // ─── Pure field mapping (no queries) ────────────────────────────────────

    private String deriveStockLevel(int stockCount) {
        if (stockCount <= 0) {
            return "OUT_OF_STOCK";
        } else if (stockCount <= 10) {
            return "LOW_STOCK";
        } else {
            return "IN_STOCK";
        }
    }

    private VariantPriceDto toVariantPriceDto(VariantPricesEntity price, LocalDateTime now) {
        if (price == null) {
            return null;
        }
        VariantPriceDto dto = new VariantPriceDto();
        dto.id = price.id == null ? null : price.id.toString();
        dto.priceType = price.priceType == null ? null : price.priceType.name();
        dto.price = price.price;
        dto.priceStartDate = price.priceStartDate;
        dto.priceEndDate = price.priceEndDate;
        dto.isActive = Boolean.TRUE;
        dto.saleDaysRemaining = calculateSaleDaysRemaining(price.priceType, price.priceEndDate, now);
        return dto;
    }

    private Long calculateSaleDaysRemaining(PriceTypeEn priceType, LocalDateTime endDate, LocalDateTime now) {
        if (priceType == null || endDate == null) {
            return null;
        }
        if (priceType != PriceTypeEn.RETAIL_SALE_PRICE && priceType != PriceTypeEn.WHOLESALE_SALE_PRICE) {
            return null;
        }
        long daysRemaining = ChronoUnit.DAYS.between(now.toLocalDate(), endDate.toLocalDate());
        return Math.max(daysRemaining, 0L);
    }
}
