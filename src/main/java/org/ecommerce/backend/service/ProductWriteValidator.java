package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;

import java.math.BigDecimal;
import java.util.*;

/**
 * Validates the complete product-write aggregate before any persistence.
 * Called by both addProductInformation and updateProductInformation to reject
 * malformed or hostile input atomically — no partial writes.
 */
@Slf4j
@ApplicationScoped
public class ProductWriteValidator
{
    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    @Inject
    ProductImageRepository productImageRepository;

    /**
     * Validates a create payload. Checks:
     * - Non-null product data
     * - At least one variant
     * - Non-blank, request-unique SKUs
     * - Exactly one strictly-positive RETAIL_PRICE per variant
     * - SKUs not owned by another product
     */
    public void validateForCreate(ProductInformationDto input)
    {
        validateCommon(input);
        validateSkusNotOwnedByAnotherProduct(input.getVariants(), null);
    }

    /**
     * Validates an update payload. In addition to the create checks:
     * - Every supplied variant id belongs to the target product
     * - Every supplied price id belongs to a variant of the target product
     * - Every supplied image id belongs to a variant of the target product
     * - SKUs not owned by another product (excluding this product's own variants)
     */
    public void validateForUpdate(UUID productId, ProductInformationDto input)
    {
        validateCommon(input);
        validateOwnership(productId, input.getVariants());
        validateSkusNotOwnedByAnotherProduct(input.getVariants(), productId);
    }

    private void validateCommon(ProductInformationDto input)
    {
        if (input == null) {
            throw new IllegalArgumentException("Product information cannot be null");
        }
        if (input.getProduct() == null) {
            throw new IllegalArgumentException("Product data is required");
        }
        if (input.getVariants() == null || input.getVariants().isEmpty()) {
            throw new IllegalArgumentException("At least one variant is required");
        }

        Set<String> seenSkus = new HashSet<>();
        for (int i = 0; i < input.getVariants().size(); i++) {
            ProductVariantDto variant = input.getVariants().get(i);
            validateVariant(variant, i, seenSkus);
        }
    }

    private void validateVariant(ProductVariantDto variant, int index, Set<String> seenSkus)
    {
        if (variant == null) {
            throw new IllegalArgumentException("Variant at index " + index + " cannot be null");
        }

        // SKU must be non-blank
        if (variant.getSku() == null || variant.getSku().isBlank()) {
            throw new IllegalArgumentException("Variant at index " + index + " must have a non-blank SKU");
        }

        // SKU must be unique within the request
        String normalizedSku = variant.getSku().trim();
        if (!seenSkus.add(normalizedSku.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Duplicate SKU in request: " + normalizedSku);
        }

        // Exactly one RETAIL_PRICE that is strictly positive
        validateRetailPrice(variant, index);
    }

    private void validateRetailPrice(ProductVariantDto variant, int index)
    {
        if (variant.getPrices() == null || variant.getPrices().isEmpty()) {
            throw new IllegalArgumentException("Variant at index " + index + " (SKU: " + variant.getSku() + ") must have exactly one RETAIL_PRICE");
        }

        long retailCount = variant.getPrices()
                .stream()
                .filter(p -> p != null && PriceTypeEn.RETAIL_PRICE.name().equals(p.getPriceType()))
                .count();

        if (retailCount == 0) {
            throw new IllegalArgumentException("Variant at index " + index + " (SKU: " + variant.getSku() + ") must have exactly one RETAIL_PRICE");
        }
        if (retailCount > 1) {
            throw new IllegalArgumentException("Variant at index " + index + " (SKU: " + variant.getSku() + ") has multiple RETAIL_PRICE entries; exactly one is required");
        }

        // The single RETAIL_PRICE must be strictly positive (> 0)
        VariantPriceDto retailPrice = variant.getPrices()
                .stream()
                .filter(p -> p != null && PriceTypeEn.RETAIL_PRICE.name().equals(p.getPriceType()))
                .findFirst()
                .orElseThrow();

        if (retailPrice.getPrice() == null) {
            throw new IllegalArgumentException("Variant at index " + index + " (SKU: " + variant.getSku() + ") RETAIL_PRICE amount is required");
        }

        // Use compareTo for BigDecimal scale-insensitive comparison
        if (retailPrice.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Variant at index " + index + " (SKU: " + variant.getSku() + ") RETAIL_PRICE must be strictly positive (> 0)");
        }
    }

    /**
     * On update, verify that every variant id, price id, and image id in the payload
     * belongs to the target product. Foreign or unknown ids fail the whole request.
     */
    private void validateOwnership(UUID productId, List<ProductVariantDto> variants)
    {
        // Load existing variant ids for this product
        List<ProductVariantEntity> existingVariants = productVariantRepository.findByVariantsForProductId(productId);
        Set<UUID> ownedVariantIds = new HashSet<>();
        for (ProductVariantEntity v : existingVariants) {
            ownedVariantIds.add(v.getId());
        }

        // Collect owned price ids (all prices across all owned variants)
        Map<UUID, UUID> priceOwnerVariantIds = new HashMap<>();
        for (ProductVariantEntity v : existingVariants) {
            List<VariantPricesEntity> prices = VariantPricesEntity.findByVariantId(v.getId());
            for (VariantPricesEntity p : prices) {
                priceOwnerVariantIds.put(p.getId(), v.getId());
            }
        }

        // Collect owned image ids (all images across all owned variants)
        Set<UUID> ownedImageIds = new HashSet<>();
        List<ProductImageEntity> existingImages = productImageRepository.findByProductId(productId);
        for (ProductImageEntity img : existingImages) {
            ownedImageIds.add(img.getId());
        }

        for (ProductVariantDto variant : variants) {
            UUID suppliedVariantId = null;
            // Check variant id ownership
            if (variant.getId() != null && !variant.getId().isBlank()) {
                suppliedVariantId = parseUuidOrFail(variant.getId(), "variant");
                if (!ownedVariantIds.contains(suppliedVariantId)) {
                    throw new IllegalArgumentException("Variant id " + variant.getId() + " does not belong to the target product");
                }
            }

            // Check price id ownership
            if (variant.getPrices() != null) {
                for (VariantPriceDto price : variant.getPrices()) {
                    if (price != null && price.getId() != null && !price.getId().isBlank()) {
                        UUID priceId = parseUuidOrFail(price.getId(), "price");
                        UUID ownerVariantId = priceOwnerVariantIds.get(priceId);
                        if (ownerVariantId == null || suppliedVariantId == null || !ownerVariantId.equals(suppliedVariantId)) {
                            throw new IllegalArgumentException("Price id " + price.getId() + " does not belong to the supplied variant");
                        }
                    }
                }
            }

            // Check image id ownership
            if (variant.getImages() != null) {
                for (var image : variant.getImages()) {
                    if (image != null && image.getId() != null && !image.getId().isBlank()) {
                        UUID imageId = parseUuidOrFail(image.getId(), "image");
                        if (!ownedImageIds.contains(imageId)) {
                            throw new IllegalArgumentException("Image id " + image.getId() + " does not belong to the target product");
                        }
                    }
                }
            }
        }
    }

    /**
     * Validate that no SKU in the request is owned by another product.
     * On create, productId is null (all existing SKU owners are foreign).
     * On update, productId identifies the current product (its own SKUs are allowed).
     */
    private void validateSkusNotOwnedByAnotherProduct(List<ProductVariantDto> variants, UUID productId)
    {
        for (ProductVariantDto variant : variants) {
            if (variant.getSku() == null || variant.getSku().isBlank()) {
                continue; // already caught by validateCommon
            }

            String sku = variant.getSku().trim();
            ProductVariantEntity existing = productVariantRepository.findBySkuWithProduct(sku);
            if (existing != null) {
                // If we're updating and this SKU belongs to our own product, that's fine
                if (productId != null && existing.getProduct() != null && productId.equals(existing.getProduct().getId())) {
                    continue;
                }
                // If the variant id matches what's in the payload, it's the same variant being updated
                if (variant.getId() != null && !variant.getId().isBlank()) {
                    try {
                        UUID payloadVariantId = UUID.fromString(variant.getId());
                        if (payloadVariantId.equals(existing.getId())) {
                            continue;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Invalid UUID will be caught elsewhere
                    }
                }
                throw new IllegalArgumentException("SKU '" + sku + "' is already owned by another product");
            }
        }
    }

    private UUID parseUuidOrFail(String value, String label)
    {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + label + " id format: " + value);
        }
    }
}
