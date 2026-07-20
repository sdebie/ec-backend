package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.ProductDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class ProductWriteValidatorTest {

    @Inject
    ProductWriteValidator validator;

    @InjectMock
    ProductVariantRepository productVariantRepository;

    @InjectMock
    ProductImageRepository productImageRepository;

    @InjectMock
    VariantPricesRepository variantPricesRepository;

    // --- Helper methods ---

    private ProductInformationDto validCreateInput() {
        ProductDto product = new ProductDto();
        product.name = "Test Product";
        product.slug = "test-product";

        VariantPriceDto retailPrice = new VariantPriceDto();
        retailPrice.priceType = "RETAIL_PRICE";
        retailPrice.price = new BigDecimal("99.99");

        ProductVariantDto variant = new ProductVariantDto();
        variant.sku = "SKU-001";
        variant.stockQuantity = 10;
        variant.prices = List.of(retailPrice);

        ProductInformationDto input = new ProductInformationDto();
        input.product = product;
        input.variants = List.of(variant);
        return input;
    }

    // --- Create validation tests ---

    @Test
    void validateForCreate_validInput_shouldPass() {
        when(productVariantRepository.findBySkuWithProduct("SKU-001")).thenReturn(null);

        assertDoesNotThrow(() -> validator.validateForCreate(validCreateInput()));
    }

    @Test
    void validateForCreate_nullInput_shouldReject() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(null));
        assertTrue(ex.getMessage().contains("cannot be null"));
    }

    @Test
    void validateForCreate_nullProduct_shouldReject() {
        ProductInformationDto input = new ProductInformationDto();
        input.product = null;
        input.variants = List.of();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertEquals("Product data is required", ex.getMessage());
    }

    @Test
    void validateForCreate_emptyVariants_shouldReject() {
        ProductInformationDto input = new ProductInformationDto();
        input.product = new ProductDto();
        input.variants = List.of();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertEquals("At least one variant is required", ex.getMessage());
    }

    @Test
    void validateForCreate_nullVariants_shouldReject() {
        ProductInformationDto input = new ProductInformationDto();
        input.product = new ProductDto();
        input.variants = null;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertEquals("At least one variant is required", ex.getMessage());
    }

    @Test
    void validateForCreate_blankSku_shouldReject() {
        ProductInformationDto input = validCreateInput();
        input.variants.get(0).sku = "   ";

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("non-blank SKU"));
    }

    @Test
    void validateForCreate_nullSku_shouldReject() {
        ProductInformationDto input = validCreateInput();
        input.variants.get(0).sku = null;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("non-blank SKU"));
    }

    @Test
    void validateForCreate_duplicateSkus_shouldReject() {
        ProductDto product = new ProductDto();
        product.name = "Test";

        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = new BigDecimal("10.00");

        ProductVariantDto variant1 = new ProductVariantDto();
        variant1.sku = "DUPE-SKU";
        variant1.prices = List.of(price);

        ProductVariantDto variant2 = new ProductVariantDto();
        variant2.sku = "dupe-sku"; // case-insensitive duplicate
        variant2.prices = List.of(price);

        ProductInformationDto input = new ProductInformationDto();
        input.product = product;
        input.variants = List.of(variant1, variant2);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("Duplicate SKU"));
    }

    @Test
    void validateForCreate_noRetailPrice_shouldReject() {
        ProductInformationDto input = validCreateInput();
        // Set prices to a non-retail type
        VariantPriceDto wholesalePrice = new VariantPriceDto();
        wholesalePrice.priceType = "WHOLESALE_PRICE";
        wholesalePrice.price = new BigDecimal("50.00");
        input.variants.get(0).prices = List.of(wholesalePrice);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("RETAIL_PRICE"));
    }

    @Test
    void validateForCreate_multipleRetailPrices_shouldReject() {
        ProductInformationDto input = validCreateInput();
        VariantPriceDto price1 = new VariantPriceDto();
        price1.priceType = "RETAIL_PRICE";
        price1.price = new BigDecimal("10.00");
        VariantPriceDto price2 = new VariantPriceDto();
        price2.priceType = "RETAIL_PRICE";
        price2.price = new BigDecimal("20.00");
        input.variants.get(0).prices = List.of(price1, price2);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("multiple RETAIL_PRICE"));
    }

    @Test
    void validateForCreate_zeroPriceAmount_shouldReject() {
        ProductInformationDto input = validCreateInput();
        input.variants.get(0).prices.get(0).price = BigDecimal.ZERO;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("strictly positive"));
    }

    @Test
    void validateForCreate_negativePriceAmount_shouldReject() {
        ProductInformationDto input = validCreateInput();
        input.variants.get(0).prices.get(0).price = new BigDecimal("-5.00");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("strictly positive"));
    }

    @Test
    void validateForCreate_nullPriceAmount_shouldReject() {
        ProductInformationDto input = validCreateInput();
        input.variants.get(0).prices.get(0).price = null;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("amount is required"));
    }

    @Test
    void validateForCreate_emptyPricesList_shouldReject() {
        ProductInformationDto input = validCreateInput();
        input.variants.get(0).prices = List.of();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("RETAIL_PRICE"));
    }

    @Test
    void validateForCreate_skuOwnedByAnotherProduct_shouldReject() {
        ProductInformationDto input = validCreateInput();

        ProductVariantEntity existingVariant = new ProductVariantEntity();
        existingVariant.id = UUID.randomUUID();
        existingVariant.product = new ProductEntity();
        existingVariant.product.id = UUID.randomUUID(); // different product
        existingVariant.sku = "SKU-001";

        when(productVariantRepository.findBySkuWithProduct("SKU-001")).thenReturn(existingVariant);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForCreate(input));
        assertTrue(ex.getMessage().contains("already owned by another product"));
    }

    @Test
    void validateForCreate_priceAtScaleTwo_usesCompareToNotEquals() {
        // BigDecimal("99.990") is > 0 using compareTo but not equals(99.99)
        ProductInformationDto input = validCreateInput();
        input.variants.get(0).prices.get(0).price = new BigDecimal("99.990");

        when(productVariantRepository.findBySkuWithProduct("SKU-001")).thenReturn(null);

        assertDoesNotThrow(() -> validator.validateForCreate(input));
    }

    // --- Update validation tests ---

    @Test
    void validateForUpdate_validInput_shouldPass() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        ProductInformationDto input = validCreateInput();
        input.variants.get(0).id = variantId.toString();

        ProductVariantEntity ownedVariant = new ProductVariantEntity();
        ownedVariant.id = variantId;
        ownedVariant.product = new ProductEntity();
        ownedVariant.product.id = productId;
        ownedVariant.sku = "SKU-001";

        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of(ownedVariant));
        when(productImageRepository.findByProductId(productId)).thenReturn(List.of());
        when(productVariantRepository.findBySkuWithProduct("SKU-001")).thenReturn(ownedVariant);

        assertDoesNotThrow(() -> validator.validateForUpdate(productId, input));
    }

    @Test
    void validateForUpdate_foreignVariantId_shouldReject() {
        UUID productId = UUID.randomUUID();
        UUID foreignVariantId = UUID.randomUUID();

        ProductInformationDto input = validCreateInput();
        input.variants.get(0).id = foreignVariantId.toString();

        // Product has no variants (the foreign id is not among them)
        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of());
        when(productImageRepository.findByProductId(productId)).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForUpdate(productId, input));
        assertTrue(ex.getMessage().contains("does not belong to the target product"));
    }

    @Test
    void validateForUpdate_foreignPriceId_shouldReject() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID foreignPriceId = UUID.randomUUID();

        ProductInformationDto input = validCreateInput();
        input.variants.get(0).id = variantId.toString();
        input.variants.get(0).prices.get(0).id = foreignPriceId.toString();

        ProductVariantEntity ownedVariant = new ProductVariantEntity();
        ownedVariant.id = variantId;

        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of(ownedVariant));
        when(productImageRepository.findByProductId(productId)).thenReturn(List.of());
        // No prices owned

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForUpdate(productId, input));
        assertTrue(ex.getMessage().contains("Price id") && ex.getMessage().contains("does not belong"));
    }

    @Test
    void validateForUpdate_foreignImageId_shouldReject() {
        UUID productId = UUID.randomUUID();
        UUID foreignImageId = UUID.randomUUID();

        ProductInformationDto input = validCreateInput();
        ProductImageDto imageDto = new ProductImageDto();
        imageDto.id = foreignImageId.toString();
        imageDto.imageUrl = "/images/test.jpg";
        imageDto.isFeatured = true;
        input.variants.get(0).images = List.of(imageDto);

        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of());
        when(productImageRepository.findByProductId(productId)).thenReturn(List.of());

        // Will fail on variant id first if we add one, but images are on a new variant (no id)
        // Let's set up so variant passes but image fails
        UUID variantId = UUID.randomUUID();
        input.variants.get(0).id = variantId.toString();

        ProductVariantEntity ownedVariant = new ProductVariantEntity();
        ownedVariant.id = variantId;

        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of(ownedVariant));
        // No images owned by this product
        when(productImageRepository.findByProductId(productId)).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForUpdate(productId, input));
        assertTrue(ex.getMessage().contains("Image id") && ex.getMessage().contains("does not belong"));
    }

    @Test
    void validateForUpdate_skuOwnedBySameProduct_shouldPass() {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        ProductInformationDto input = validCreateInput();
        input.variants.get(0).id = variantId.toString();

        ProductVariantEntity ownedVariant = new ProductVariantEntity();
        ownedVariant.id = variantId;
        ownedVariant.product = new ProductEntity();
        ownedVariant.product.id = productId;
        ownedVariant.sku = "SKU-001";

        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of(ownedVariant));
        when(productImageRepository.findByProductId(productId)).thenReturn(List.of());
        when(productVariantRepository.findBySkuWithProduct("SKU-001")).thenReturn(ownedVariant);

        assertDoesNotThrow(() -> validator.validateForUpdate(productId, input));
    }

    @Test
    void validateForUpdate_skuOwnedByDifferentProduct_shouldReject() {
        UUID productId = UUID.randomUUID();
        UUID otherProductId = UUID.randomUUID();

        ProductInformationDto input = validCreateInput();
        // New variant (no id) with a SKU that belongs to another product

        ProductVariantEntity foreignVariant = new ProductVariantEntity();
        foreignVariant.id = UUID.randomUUID();
        foreignVariant.product = new ProductEntity();
        foreignVariant.product.id = otherProductId;
        foreignVariant.sku = "SKU-001";

        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of());
        when(productImageRepository.findByProductId(productId)).thenReturn(List.of());
        when(productVariantRepository.findBySkuWithProduct("SKU-001")).thenReturn(foreignVariant);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateForUpdate(productId, input));
        assertTrue(ex.getMessage().contains("already owned by another product"));
    }
}
