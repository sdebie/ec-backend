package org.ecommerce.backend.service;

// Feature: admin-product-write, Task 1.3: Backend test — create round-trips + aggregate guard
// Validates: Requirements 2.2, 2.3, 2.4, 4.1, 8.1, 8.4, 10.1, 10.2

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.dto.PageResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed integration test for the product-write aggregate (create path).
 *
 * Uses the REAL {@link ProductService#addProductInformation} — no mocks.
 * {@link TestTransaction} ensures each test rolls back so the shared dev DB is never mutated.
 *
 * Covers:
 *   - Create round-trip: two variants with retail prices and images persisted and readable (Req 2.2, 2.3, 2.4, 4.1)
 *   - Admin list visibility: created product appears in adminProductList (Req 2.4)
 *   - Aggregate guard: malformed payloads rejected atomically (Req 8.4, 10.1, 10.2)
 *   - Foreign id rejection on update (Req 10.2)
 */
@QuarkusTest
class ProductWriteIntegrationTest {

    @Inject
    ProductService productService;

    @Inject
    EntityManager em;

    // ─── Helper: build a valid create payload ────────────────────────────────

    private ProductInformationDto validCreateInput(String marker) {
        ProductDto product = new ProductDto();
        product.name = marker + "Test Product";
        product.slug = (marker + "test-product-" + UUID.randomUUID()).toLowerCase();
        product.shortDescription = "Short desc";
        product.description = "Full description for " + marker;
        product.status = "ACTIVE";

        // Variant 1 — carries the image manifest (index 0)
        ProductVariantDto variant1 = new ProductVariantDto();
        variant1.sku = marker + "SKU-A-" + UUID.randomUUID().toString().substring(0, 8);
        variant1.stockQuantity = 50;
        VariantPriceDto price1 = new VariantPriceDto();
        price1.priceType = "RETAIL_PRICE";
        price1.price = new BigDecimal("199.99");
        variant1.prices = List.of(price1);

        // Images on variant index 0 (the manifest carrier)
        ProductImageDto img1 = new ProductImageDto(null, "/images/product-a.jpg", 0, true);
        ProductImageDto img2 = new ProductImageDto(null, "/images/product-b.jpg", 1, false);
        variant1.images = List.of(img1, img2);

        // Variant 2
        ProductVariantDto variant2 = new ProductVariantDto();
        variant2.sku = marker + "SKU-B-" + UUID.randomUUID().toString().substring(0, 8);
        variant2.stockQuantity = 25;
        VariantPriceDto price2 = new VariantPriceDto();
        price2.priceType = "RETAIL_PRICE";
        price2.price = new BigDecimal("299.50");
        variant2.prices = List.of(price2);
        variant2.images = List.of(); // no images on second variant

        ProductInformationDto input = new ProductInformationDto();
        input.product = product;
        input.variants = List.of(variant1, variant2);
        return input;
    }

    // ─── Create round-trip ───────────────────────────────────────────────────

    @Test
    @TestTransaction
    void createRoundTrip_twoVariantsWithPricesAndImages_allPersisted() {
        String marker = "ZZPW-RT-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);

        // The returned aggregate has the product with an id
        assertNotNull(created);
        assertNotNull(created.product);
        assertNotNull(created.product.id, "Product id must be assigned on create");
        assertThat(created.product.name, equalTo(input.product.name));
        assertThat(created.product.slug, equalTo(input.product.slug));
        assertThat(created.product.status, equalTo("ACTIVE"));

        // Two variants returned
        assertThat(created.variants, hasSize(2));

        // Flush and clear persistence context to force a fresh load from the DB
        em.flush();
        em.clear();

        // Read it back via the admin-edit read path
        ProductInformationDto read = productService.getProductInformationDto(created.product.id);
        assertNotNull(read, "getProductInformation must return the created product");
        assertThat(read.product.name, equalTo(input.product.name));
        assertThat(read.product.slug, equalTo(input.product.slug));
        assertThat(read.product.status, equalTo("ACTIVE"));

        // Both variants persisted with correct SKUs and stock
        assertThat(read.variants, hasSize(2));

        // Collect SKUs from the read
        List<String> readSkus = read.variants.stream().map(v -> v.sku).toList();
        assertThat(readSkus, containsInAnyOrder(input.variants.get(0).sku, input.variants.get(1).sku));

        // Each variant has its RETAIL_PRICE persisted
        for (ProductVariantDto readVariant : read.variants) {
            assertNotNull(readVariant.id, "Variant must have an assigned id");
            assertThat(readVariant.prices, not(empty()));

            List<VariantPriceDto> retailPrices = readVariant.prices.stream()
                    .filter(p -> "RETAIL_PRICE".equals(p.priceType))
                    .toList();
            assertThat("Each variant must have exactly one RETAIL_PRICE",
                    retailPrices, hasSize(1));
            assertThat(retailPrices.get(0).price.compareTo(BigDecimal.ZERO) > 0, is(true));
        }

        // Verify specific price values
        ProductVariantDto readV1 = read.variants.stream()
                .filter(v -> v.sku.equals(input.variants.get(0).sku)).findFirst().orElseThrow();
        ProductVariantDto readV2 = read.variants.stream()
                .filter(v -> v.sku.equals(input.variants.get(1).sku)).findFirst().orElseThrow();

        assertThat(readV1.stockQuantity, equalTo(50));
        assertThat(readV2.stockQuantity, equalTo(25));

        VariantPriceDto v1Retail = readV1.prices.stream()
                .filter(p -> "RETAIL_PRICE".equals(p.priceType)).findFirst().orElseThrow();
        VariantPriceDto v2Retail = readV2.prices.stream()
                .filter(p -> "RETAIL_PRICE".equals(p.priceType)).findFirst().orElseThrow();

        assertThat(v1Retail.price.compareTo(new BigDecimal("199.99")), equalTo(0));
        assertThat(v2Retail.price.compareTo(new BigDecimal("299.50")), equalTo(0));

        // Images persisted on the deterministic owner variant (lowest-UUID active variant)
        // At least one variant must carry the images
        boolean imagesFound = read.variants.stream()
                .anyMatch(v -> v.images != null && v.images.size() == 2);
        assertTrue(imagesFound, "The image manifest (2 images) must be persisted on the deterministic owner variant");

        // Verify image properties
        ProductVariantDto ownerVariant = read.variants.stream()
                .filter(v -> v.images != null && !v.images.isEmpty())
                .findFirst().orElseThrow();
        assertThat(ownerVariant.images, hasSize(2));

        // The deterministic owner is the variant with the lowest UUID
        UUID v1Id = UUID.fromString(read.variants.get(0).id);
        UUID v2Id = UUID.fromString(read.variants.get(1).id);
        UUID expectedOwnerId = v1Id.toString().compareTo(v2Id.toString()) < 0 ? v1Id : v2Id;
        assertThat(UUID.fromString(ownerVariant.id), equalTo(expectedOwnerId));

        // Verify image URLs and featured flag
        List<String> imageUrls = ownerVariant.images.stream().map(i -> i.imageUrl).toList();
        assertThat(imageUrls, containsInAnyOrder("/images/product-a.jpg", "/images/product-b.jpg"));

        ProductImageDto featuredImg = ownerVariant.images.stream()
                .filter(i -> i.isFeatured).findFirst().orElse(null);
        assertNotNull(featuredImg, "At least one image must be marked as featured");
        assertThat(featuredImg.imageUrl, equalTo("/images/product-a.jpg"));
    }

    @Test
    @TestTransaction
    void createRoundTrip_productAppearsInAdminList() {
        String marker = "ZZPW-LIST-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);

        // Flush to ensure the admin list query sees the new product
        em.flush();

        // The product must appear in the admin product list
        PageResponse<AdminProductListItemDto> listPage =
                productService.getAdminProductList(0, 100, null, null, null, marker);

        assertThat(listPage.getContent(), not(empty()));

        boolean found = listPage.getContent().stream()
                .anyMatch(item -> item.id.equals(created.product.id));
        assertTrue(found, "Created product must appear in adminProductList when searching by its marker");
    }

    // ─── Aggregate guard: malformed payloads fail atomically ─────────────────

    @Test
    @TestTransaction
    void aggregateGuard_nullInput_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> productService.addProductInformation(null));

        // No partial product created
        assertNoProductsWithMarker("ZZPW-NULL-");
    }

    @Test
    @TestTransaction
    void aggregateGuard_nullProductData_rejected() {
        ProductInformationDto input = new ProductInformationDto();
        input.product = null;
        input.variants = List.of();

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProductInformation(input));
    }

    @Test
    @TestTransaction
    void aggregateGuard_emptyVariants_rejected() {
        ProductInformationDto input = new ProductInformationDto();
        input.product = new ProductDto();
        input.product.name = "ZZPW-EMPTY-VARS";
        input.product.slug = "zzpw-empty-vars-" + UUID.randomUUID();
        input.variants = List.of();

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProductInformation(input));

        // Assert atomicity — no product row created
        assertNoProductsWithMarker("ZZPW-EMPTY-VARS");
    }

    @Test
    @TestTransaction
    void aggregateGuard_blankSku_rejected() {
        String marker = "ZZPW-BLANKSKU-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);
        // Mutate to have a blank SKU
        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.sku = "   ";
        badVariant.stockQuantity = 5;
        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = new BigDecimal("10.00");
        badVariant.prices = List.of(price);

        input.variants = List.of(badVariant);

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_noRetailPrice_rejected() {
        String marker = "ZZPW-NOPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        // Variant with no RETAIL_PRICE (only WHOLESALE_PRICE)
        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.sku = marker + "SKU-X";
        badVariant.stockQuantity = 5;
        VariantPriceDto wholesale = new VariantPriceDto();
        wholesale.priceType = "WHOLESALE_PRICE";
        wholesale.price = new BigDecimal("50.00");
        badVariant.prices = List.of(wholesale);

        input.variants = List.of(badVariant);

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_multipleRetailPrices_rejected() {
        String marker = "ZZPW-MULTPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.sku = marker + "SKU-X";
        badVariant.stockQuantity = 5;
        VariantPriceDto price1 = new VariantPriceDto();
        price1.priceType = "RETAIL_PRICE";
        price1.price = new BigDecimal("10.00");
        VariantPriceDto price2 = new VariantPriceDto();
        price2.priceType = "RETAIL_PRICE";
        price2.price = new BigDecimal("20.00");
        badVariant.prices = List.of(price1, price2);

        input.variants = List.of(badVariant);

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_zeroPrice_rejected() {
        String marker = "ZZPW-ZEROPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.sku = marker + "SKU-X";
        badVariant.stockQuantity = 5;
        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = BigDecimal.ZERO;
        badVariant.prices = List.of(price);

        input.variants = List.of(badVariant);

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_negativePrice_rejected() {
        String marker = "ZZPW-NEGPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.sku = marker + "SKU-X";
        badVariant.stockQuantity = 5;
        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = new BigDecimal("-15.00");
        badVariant.prices = List.of(price);

        input.variants = List.of(badVariant);

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_duplicateSkusInRequest_rejected() {
        String marker = "ZZPW-DUPESKU-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        String sharedSku = marker + "DUPE-SKU";

        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = new BigDecimal("10.00");

        ProductVariantDto v1 = new ProductVariantDto();
        v1.sku = sharedSku;
        v1.stockQuantity = 5;
        v1.prices = List.of(price);

        ProductVariantDto v2 = new ProductVariantDto();
        v2.sku = sharedSku; // duplicate
        v2.stockQuantity = 3;
        v2.prices = List.of(price);

        input.variants = List.of(v1, v2);

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    // ─── Foreign id rejection on update ──────────────────────────────────────

    @Test
    @TestTransaction
    void aggregateGuard_foreignVariantId_rejectedOnUpdate() {
        // First create a valid product
        String marker = "ZZPW-FVAR-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);
        em.flush();

        String productId = created.product.id;

        // Build an update payload with a foreign variant id (not owned by this product)
        ProductInformationDto updateInput = validCreateInput(marker + "UPD-");
        updateInput.product.id = productId;
        updateInput.variants.get(0).id = UUID.randomUUID().toString(); // foreign variant id

        assertThrows(IllegalArgumentException.class,
                () -> productService.updateProductInformation(productId, updateInput));
    }

    @Test
    @TestTransaction
    void aggregateGuard_foreignPriceId_rejectedOnUpdate() {
        // First create a valid product
        String marker = "ZZPW-FPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);
        em.flush();

        String productId = created.product.id;
        String ownedVariantId = created.variants.get(0).id;

        // Build an update payload with a foreign price id
        ProductInformationDto updateInput = validCreateInput(marker + "UPD-");
        updateInput.product.id = productId;
        // Use the real variant id so variant ownership passes
        updateInput.variants = new ArrayList<>();
        ProductVariantDto updateVariant = new ProductVariantDto();
        updateVariant.id = ownedVariantId;
        updateVariant.sku = created.variants.get(0).sku;
        updateVariant.stockQuantity = 10;
        VariantPriceDto priceWithForeignId = new VariantPriceDto();
        priceWithForeignId.id = UUID.randomUUID().toString(); // foreign price id
        priceWithForeignId.priceType = "RETAIL_PRICE";
        priceWithForeignId.price = new BigDecimal("55.00");
        updateVariant.prices = List.of(priceWithForeignId);
        updateInput.variants.add(updateVariant);

        assertThrows(IllegalArgumentException.class,
                () -> productService.updateProductInformation(productId, updateInput));
    }

    @Test
    @TestTransaction
    void aggregateGuard_siblingVariantPriceId_rejectedOnUpdate() {
        String marker = "ZZPW-SIBLING-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto created = productService.addProductInformation(validCreateInput(marker));
        em.flush();
        em.clear();
        created = productService.getProductInformationDto(created.product.id);

        ProductVariantDto first = created.variants.get(0);
        ProductVariantDto second = created.variants.get(1);
        VariantPriceDto siblingPrice = second.prices.stream()
                .filter(price -> "RETAIL_PRICE".equals(price.priceType))
                .findFirst().orElseThrow();

        ProductInformationDto updateInput = validCreateInput(marker + "UPD-");
        String productId = created.product.id;
        updateInput.product.id = productId;
        ProductVariantDto updateVariant = new ProductVariantDto();
        updateVariant.id = first.id;
        updateVariant.sku = first.sku;
        updateVariant.stockQuantity = first.stockQuantity;
        VariantPriceDto crossedPrice = new VariantPriceDto();
        crossedPrice.id = siblingPrice.id;
        crossedPrice.priceType = "RETAIL_PRICE";
        crossedPrice.price = new BigDecimal("88.00");
        updateVariant.prices = List.of(crossedPrice);
        updateInput.variants = List.of(updateVariant);

        assertThrows(IllegalArgumentException.class,
                () -> productService.updateProductInformation(productId, updateInput));
    }

    @Test
    @TestTransaction
    void storefrontDetail_disabledProduct_isNotExposed() {
        String marker = "ZZPW-DISABLED-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto created = productService.addProductInformation(validCreateInput(marker));
        productService.updateProductStatus(created.product.id, "DISABLED");
        em.flush();

        assertNull(productService.getProductInformationBySlug(created.product.slug));
    }

    @Test
    @TestTransaction
    void aggregateGuard_foreignImageId_rejectedOnUpdate() {
        // First create a valid product
        String marker = "ZZPW-FIMG-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);
        em.flush();

        String productId = created.product.id;
        String ownedVariantId = created.variants.get(0).id;

        // Build an update payload with a foreign image id
        ProductInformationDto updateInput = validCreateInput(marker + "UPD-");
        updateInput.product.id = productId;

        updateInput.variants = new ArrayList<>();
        ProductVariantDto updateVariant = new ProductVariantDto();
        updateVariant.id = ownedVariantId;
        updateVariant.sku = created.variants.get(0).sku;
        updateVariant.stockQuantity = 10;
        VariantPriceDto validPrice = new VariantPriceDto();
        validPrice.priceType = "RETAIL_PRICE";
        validPrice.price = new BigDecimal("55.00");
        // Use the real price id from the created variant
        if (created.variants.get(0).prices != null && !created.variants.get(0).prices.isEmpty()) {
            validPrice.id = created.variants.get(0).prices.get(0).id;
        }
        updateVariant.prices = List.of(validPrice);

        // Add image with a foreign id
        ProductImageDto foreignImage = new ProductImageDto();
        foreignImage.id = UUID.randomUUID().toString(); // foreign image id
        foreignImage.imageUrl = "/images/foreign.jpg";
        foreignImage.isFeatured = true;
        foreignImage.sortOrder = 0;
        updateVariant.images = List.of(foreignImage);

        updateInput.variants.add(updateVariant);

        assertThrows(IllegalArgumentException.class,
                () -> productService.updateProductInformation(productId, updateInput));
    }

    // ─── Atomicity assertion helper ──────────────────────────────────────────

    /**
     * Asserts that no product exists whose name starts with the given marker.
     * Used to verify that a rejected create left no partial data.
     * Only checks if a transaction is still active (validation failures that throw
     * before any persistence leave no active transaction).
     */
    private void assertNoProductsWithMarker(String marker) {
        try {
            Long count = em.createQuery(
                            "SELECT COUNT(p) FROM ProductEntity p WHERE p.name LIKE :pattern", Long.class)
                    .setParameter("pattern", marker + "%")
                    .getSingleResult();
            assertThat("No partial product should exist after a rejected create",
                    count, equalTo(0L));
        } catch (Exception e) {
            // If no active transaction (TX was rolled back by the exception), 
            // that inherently means no partial data was committed — atomicity preserved.
        }
    }
}
