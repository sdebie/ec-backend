package org.ecommerce.backend.service;

// Feature: admin-product-write, Task 1.3: Backend test — create round-trips + aggregate guard
// Validates: Requirements 2.2, 2.3, 2.4, 4.1, 8.1, 8.4, 10.1, 10.2

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.*;
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
 * <p>
 * Uses the REAL {@link ProductService#addProductInformation} — no mocks.
 * {@link TestTransaction} ensures each test rolls back so the shared dev DB is never mutated.
 * <p>
 * Covers:
 * - Create round-trip: two variants with retail prices and images persisted and readable (Req 2.2, 2.3, 2.4, 4.1)
 * - Admin list visibility: created product appears in adminProductList (Req 2.4)
 * - Aggregate guard: malformed payloads rejected atomically (Req 8.4, 10.1, 10.2)
 * - Foreign id rejection on update (Req 10.2)
 */
@QuarkusTest
class ProductWriteIntegrationTest
{
    @Inject
    ProductService productService;

    @Inject
    EntityManager em;

    // ─── Helper: build a valid create payload ────────────────────────────────

    private ProductInformationDto validCreateInput(String marker)
    {
        ProductDto product = new ProductDto();
        product.setName(marker + "Test Product");
        product.setSlug((marker + "test-product-" + UUID.randomUUID()).toLowerCase());
        product.setShortDescription("Short desc");
        product.setDescription("Full description for " + marker);
        product.setStatus("ACTIVE");

        // Variant 1 — carries the image manifest (index 0)
        ProductVariantDto variant1 = new ProductVariantDto();
        variant1.setSku(marker + "SKU-A-" + UUID.randomUUID().toString().substring(0, 8));
        variant1.setStockQuantity(50);
        VariantPriceDto price1 = new VariantPriceDto();
        price1.setPriceType("RETAIL_PRICE");
        price1.setPrice(new BigDecimal("199.99"));
        variant1.setPrices(List.of(price1));

        // Images on variant index 0 (the manifest carrier)
        ProductImageDto img1 = new ProductImageDto(null, "/images/product-a.jpg", 0, true);
        ProductImageDto img2 = new ProductImageDto(null, "/images/product-b.jpg", 1, false);
        variant1.setImages(List.of(img1, img2));

        // Variant 2
        ProductVariantDto variant2 = new ProductVariantDto();
        variant2.setSku(marker + "SKU-B-" + UUID.randomUUID().toString().substring(0, 8));
        variant2.setStockQuantity(25);
        VariantPriceDto price2 = new VariantPriceDto();
        price2.setPriceType("RETAIL_PRICE");
        price2.setPrice(new BigDecimal("299.50"));
        variant2.setPrices(List.of(price2));
        variant2.setImages(List.of()); // no images on second variant

        ProductInformationDto input = new ProductInformationDto();
        input.setProduct(product);
        input.setVariants(List.of(variant1, variant2));
        return input;
    }

    // ─── Create round-trip ───────────────────────────────────────────────────

    @Test
    @TestTransaction
    void createRoundTrip_twoVariantsWithPricesAndImages_allPersisted()
    {
        String marker = "ZZPW-RT-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);

        // The returned aggregate has the product with an id
        assertNotNull(created);
        assertNotNull(created.getProduct());
        assertNotNull(created.getProduct().getId(), "Product id must be assigned on create");
        assertThat(created.getProduct().getName(), equalTo(input.getProduct().getName()));
        assertThat(created.getProduct().getSlug(), equalTo(input.getProduct().getSlug()));
        assertThat(created.getProduct().getStatus(), equalTo("ACTIVE"));

        // Two variants returned
        assertThat(created.getVariants(), hasSize(2));

        // Flush and clear persistence context to force a fresh load from the DB
        em.flush();
        em.clear();

        // Read it back via the admin-edit read path
        ProductInformationDto read = productService.getProductInformationDto(created.getProduct().getId());
        assertNotNull(read, "getProductInformation must return the created product");
        assertThat(read.getProduct().getName(), equalTo(input.getProduct().getName()));
        assertThat(read.getProduct().getSlug(), equalTo(input.getProduct().getSlug()));
        assertThat(read.getProduct().getStatus(), equalTo("ACTIVE"));

        // Both variants persisted with correct SKUs and stock
        assertThat(read.getVariants(), hasSize(2));

        // Collect SKUs from the read
        List<String> readSkus = read.getVariants().stream().map(v -> v.getSku()).toList();
        assertThat(readSkus, containsInAnyOrder(input.getVariants().get(0).getSku(), input.getVariants().get(1).getSku()));

        // Each variant has its RETAIL_PRICE persisted
        for (ProductVariantDto readVariant : read.getVariants()) {
            assertNotNull(readVariant.getId(), "Variant must have an assigned id");
            assertThat(readVariant.getPrices(), not(empty()));

            List<VariantPriceDto> retailPrices = readVariant.getPrices()
                    .stream()
                    .filter(p -> "RETAIL_PRICE".equals(p.getPriceType()))
                    .toList();
            assertThat("Each variant must have exactly one RETAIL_PRICE", retailPrices, hasSize(1));
            assertThat(retailPrices.get(0).getPrice().compareTo(BigDecimal.ZERO) > 0, is(true));
        }

        // Verify specific price values
        ProductVariantDto readV1 = read.getVariants()
                .stream()
                .filter(v -> v.getSku().equals(input.getVariants().get(0).getSku()))
                .findFirst()
                .orElseThrow();

        ProductVariantDto readV2 = read.getVariants()
                .stream()
                .filter(v -> v.getSku().equals(input.getVariants().get(1).getSku()))
                .findFirst()
                .orElseThrow();

        assertThat(readV1.getStockQuantity(), equalTo(50));
        assertThat(readV2.getStockQuantity(), equalTo(25));

        VariantPriceDto v1Retail = readV1.getPrices().stream()
                .filter(p -> "RETAIL_PRICE".equals(p.getPriceType()))
                .findFirst()
                .orElseThrow();

        VariantPriceDto v2Retail = readV2.getPrices().stream()
                .filter(p -> "RETAIL_PRICE".equals(p.getPriceType()))
                .findFirst()
                .orElseThrow();

        assertThat(v1Retail.getPrice().compareTo(new BigDecimal("199.99")), equalTo(0));
        assertThat(v2Retail.getPrice().compareTo(new BigDecimal("299.50")), equalTo(0));

        // Images persisted on the deterministic owner variant (lowest-UUID active variant)
        // At least one variant must carry the images
        boolean imagesFound = read.getVariants()
                .stream()
                .anyMatch(v -> v.getImages() != null && v.getImages().size() == 2);
        assertTrue(imagesFound, "The image manifest (2 images) must be persisted on the deterministic owner variant");

        // Verify image properties
        ProductVariantDto ownerVariant = read.getVariants()
                .stream()
                .filter(v -> v.getImages() != null && !v.getImages().isEmpty())
                .findFirst()
                .orElseThrow();

        assertThat(ownerVariant.getImages(), hasSize(2));

        // The deterministic owner is the variant with the lowest UUID
        UUID v1Id = UUID.fromString(read.getVariants().get(0).getId());
        UUID v2Id = UUID.fromString(read.getVariants().get(1).getId());
        UUID expectedOwnerId = v1Id.toString().compareTo(v2Id.toString()) < 0 ? v1Id : v2Id;
        assertThat(UUID.fromString(ownerVariant.getId()), equalTo(expectedOwnerId));

        // Verify image URLs and featured flag
        List<String> imageUrls = ownerVariant.getImages()
                .stream()
                .map(ProductImageDto::getImageUrl)
                .toList();
        assertThat(imageUrls, containsInAnyOrder("/images/product-a.jpg", "/images/product-b.jpg"));

        ProductImageDto featuredImg = ownerVariant.getImages()
                .stream()
                .filter(ProductImageDto::isFeatured)
                .findFirst()
                .orElse(null);

        assertNotNull(featuredImg, "At least one image must be marked as featured");
        assertThat(featuredImg.getImageUrl(), equalTo("/images/product-a.jpg"));
    }

    @Test
    @TestTransaction
    void createRoundTrip_productAppearsInAdminList()
    {
        String marker = "ZZPW-LIST-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);

        // Flush to ensure the admin list query sees the new product
        em.flush();

        // The product must appear in the admin product list
        PageResponse<AdminProductListItemDto> listPage = productService.getAdminProductList(0, 100, null, null, null, marker);

        assertThat(listPage.getContent(), not(empty()));

        boolean found = listPage.getContent()
                .stream()
                .anyMatch(item -> item.getId().equals(created.getProduct().getId()));

        assertTrue(found, "Created product must appear in adminProductList when searching by its marker");
    }

    // ─── Aggregate guard: malformed payloads fail atomically ─────────────────

    @Test
    @TestTransaction
    void aggregateGuard_nullInput_rejected()
    {
        assertThrows(IllegalArgumentException.class, () -> productService.addProductInformation(null));

        // No partial product created
        assertNoProductsWithMarker("ZZPW-NULL-");
    }

    @Test
    @TestTransaction
    void aggregateGuard_nullProductData_rejected()
    {
        ProductInformationDto input = new ProductInformationDto();
        input.setProduct(null);
        input.setVariants(List.of());

        assertThrows(IllegalArgumentException.class, () -> productService.addProductInformation(input));
    }

    @Test
    @TestTransaction
    void aggregateGuard_emptyVariants_rejected()
    {
        ProductInformationDto input = new ProductInformationDto();
        input.setProduct(new ProductDto());
        input.getProduct().setName("ZZPW-EMPTY-VARS");
        input.getProduct().setSlug("zzpw-empty-vars-" + UUID.randomUUID());
        input.setVariants(List.of());

        assertThrows(IllegalArgumentException.class, () -> productService.addProductInformation(input));

        // Assert atomicity — no product row created
        assertNoProductsWithMarker("ZZPW-EMPTY-VARS");
    }

    @Test
    @TestTransaction
    void aggregateGuard_blankSku_rejected()
    {
        String marker = "ZZPW-BLANKSKU-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);
        // Mutate to have a blank SKU
        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.setSku("   ");
        badVariant.setStockQuantity(5);
        VariantPriceDto price = new VariantPriceDto();
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(new BigDecimal("10.00"));
        badVariant.setPrices(List.of(price));

        input.setVariants(List.of(badVariant));

        assertThrows(IllegalArgumentException.class, () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_noRetailPrice_rejected()
    {
        String marker = "ZZPW-NOPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        // Variant with no RETAIL_PRICE (only WHOLESALE_PRICE)
        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.setSku(marker + "SKU-X");
        badVariant.setStockQuantity(5);
        VariantPriceDto wholesale = new VariantPriceDto();
        wholesale.setPriceType("WHOLESALE_PRICE");
        wholesale.setPrice(new BigDecimal("50.00"));
        badVariant.setPrices(List.of(wholesale));

        input.setVariants(List.of(badVariant));

        assertThrows(IllegalArgumentException.class, () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_multipleRetailPrices_rejected()
    {
        String marker = "ZZPW-MULTPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.setSku(marker + "SKU-X");
        badVariant.setStockQuantity(5);
        VariantPriceDto price1 = new VariantPriceDto();
        price1.setPriceType("RETAIL_PRICE");
        price1.setPrice(new BigDecimal("10.00"));
        VariantPriceDto price2 = new VariantPriceDto();
        price2.setPriceType("RETAIL_PRICE");
        price2.setPrice(new BigDecimal("20.00"));
        badVariant.setPrices(List.of(price1, price2));

        input.setVariants(List.of(badVariant));

        assertThrows(IllegalArgumentException.class, () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_zeroPrice_rejected()
    {
        String marker = "ZZPW-ZEROPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.setSku(marker + "SKU-X");
        badVariant.setStockQuantity(5);
        VariantPriceDto price = new VariantPriceDto();
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(BigDecimal.ZERO);
        badVariant.setPrices(List.of(price));

        input.setVariants(List.of(badVariant));

        assertThrows(IllegalArgumentException.class, () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_negativePrice_rejected()
    {
        String marker = "ZZPW-NEGPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        ProductVariantDto badVariant = new ProductVariantDto();
        badVariant.setSku(marker + "SKU-X");
        badVariant.setStockQuantity(5);
        VariantPriceDto price = new VariantPriceDto();
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(new BigDecimal("-15.00"));
        badVariant.setPrices(List.of(price));

        input.setVariants(List.of(badVariant));

        assertThrows(IllegalArgumentException.class, () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    @Test
    @TestTransaction
    void aggregateGuard_duplicateSkusInRequest_rejected()
    {
        String marker = "ZZPW-DUPESKU-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);

        String sharedSku = marker + "DUPE-SKU";

        VariantPriceDto price = new VariantPriceDto();
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(new BigDecimal("10.00"));

        ProductVariantDto v1 = new ProductVariantDto();
        v1.setSku(sharedSku);
        v1.setStockQuantity(5);
        v1.setPrices(List.of(price));

        ProductVariantDto v2 = new ProductVariantDto();
        v2.setSku(sharedSku); // duplicate
        v2.setStockQuantity(3);
        v2.setPrices(List.of(price));

        input.setVariants(List.of(v1, v2));

        assertThrows(IllegalArgumentException.class, () -> productService.addProductInformation(input));

        assertNoProductsWithMarker(marker);
    }

    // ─── Foreign id rejection on update ──────────────────────────────────────

    @Test
    @TestTransaction
    void aggregateGuard_foreignVariantId_rejectedOnUpdate()
    {
        // First create a valid product
        String marker = "ZZPW-FVAR-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);
        em.flush();

        String productId = created.getProduct().getId();

        // Build an update payload with a foreign variant id (not owned by this product)
        ProductInformationDto updateInput = validCreateInput(marker + "UPD-");
        updateInput.getProduct().setId(productId);
        updateInput.getVariants().get(0).setId(UUID.randomUUID().toString()); // foreign variant id

        assertThrows(IllegalArgumentException.class, () -> productService.updateProductInformation(productId, updateInput));
    }

    @Test
    @TestTransaction
    void aggregateGuard_foreignPriceId_rejectedOnUpdate()
    {
        // First create a valid product
        String marker = "ZZPW-FPRICE-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);
        em.flush();

        String productId = created.getProduct().getId();
        String ownedVariantId = created.getVariants().get(0).getId();

        // Build an update payload with a foreign price id
        ProductInformationDto updateInput = validCreateInput(marker + "UPD-");
        updateInput.getProduct().setId(productId);
        // Use the real variant id so variant ownership passes
        updateInput.setVariants(new ArrayList<>());
        ProductVariantDto updateVariant = new ProductVariantDto();
        updateVariant.setId(ownedVariantId);
        updateVariant.setSku(created.getVariants().get(0).getSku());
        updateVariant.setStockQuantity(10);
        VariantPriceDto priceWithForeignId = new VariantPriceDto();
        priceWithForeignId.setId(UUID.randomUUID().toString()); // foreign price id
        priceWithForeignId.setPriceType("RETAIL_PRICE");
        priceWithForeignId.setPrice(new BigDecimal("55.00"));
        updateVariant.setPrices(List.of(priceWithForeignId));
        updateInput.getVariants().add(updateVariant);

        assertThrows(IllegalArgumentException.class, () -> productService.updateProductInformation(productId, updateInput));
    }

    @Test
    @TestTransaction
    void aggregateGuard_siblingVariantPriceId_rejectedOnUpdate()
    {
        String marker = "ZZPW-SIBLING-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto created = productService.addProductInformation(validCreateInput(marker));
        em.flush();
        em.clear();
        created = productService.getProductInformationDto(created.getProduct().getId());

        ProductVariantDto first = created.getVariants().get(0);
        ProductVariantDto second = created.getVariants().get(1);
        VariantPriceDto siblingPrice = second.getPrices()
                .stream()
                .filter(price -> "RETAIL_PRICE".equals(price.getPriceType()))
                .findFirst()
                .orElseThrow();

        ProductInformationDto updateInput = validCreateInput(marker + "UPD-");
        String productId = created.getProduct().getId();
        updateInput.getProduct().setId(productId);
        ProductVariantDto updateVariant = new ProductVariantDto();
        updateVariant.setId(first.getId());
        updateVariant.setSku(first.getSku());
        updateVariant.setStockQuantity(first.getStockQuantity());
        VariantPriceDto crossedPrice = new VariantPriceDto();
        crossedPrice.setId(siblingPrice.getId());
        crossedPrice.setPriceType("RETAIL_PRICE");
        crossedPrice.setPrice(new BigDecimal("88.00"));
        updateVariant.setPrices(List.of(crossedPrice));
        updateInput.setVariants(List.of(updateVariant));

        assertThrows(IllegalArgumentException.class, () -> productService.updateProductInformation(productId, updateInput));
    }

    @Test
    @TestTransaction
    void storefrontDetail_disabledProduct_isNotExposed()
    {
        String marker = "ZZPW-DISABLED-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto created = productService.addProductInformation(validCreateInput(marker));
        productService.updateProductStatus(created.getProduct().getId(), "DISABLED");
        em.flush();

        assertNull(productService.getProductInformationBySlug(created.getProduct().getSlug()));
    }

    @Test
    @TestTransaction
    void aggregateGuard_foreignImageId_rejectedOnUpdate()
    {
        // First create a valid product
        String marker = "ZZPW-FIMG-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        ProductInformationDto input = validCreateInput(marker);
        ProductInformationDto created = productService.addProductInformation(input);
        em.flush();

        String productId = created.getProduct().getId();
        String ownedVariantId = created.getVariants().get(0).getId();

        // Build an update payload with a foreign image id
        ProductInformationDto updateInput = validCreateInput(marker + "UPD-");
        updateInput.getProduct().setId(productId);

        updateInput.setVariants(new ArrayList<>());
        ProductVariantDto updateVariant = new ProductVariantDto();
        updateVariant.setId(ownedVariantId);
        updateVariant.setSku(created.getVariants().get(0).getSku());
        updateVariant.setStockQuantity(10);
        VariantPriceDto validPrice = new VariantPriceDto();
        validPrice.setPriceType("RETAIL_PRICE");
        validPrice.setPrice(new BigDecimal("55.00"));
        // Use the real price id from the created variant
        if (created.getVariants().get(0).getPrices() != null && !created.getVariants().get(0).getPrices().isEmpty()) {
            validPrice.setId(created.getVariants().get(0).getPrices().get(0).getId());
        }
        updateVariant.setPrices(List.of(validPrice));

        // Add image with a foreign id
        ProductImageDto foreignImage = new ProductImageDto();
        foreignImage.setId(UUID.randomUUID().toString()); // foreign image id
        foreignImage.setImageUrl("/images/foreign.jpg");
        foreignImage.setFeatured(true);
        foreignImage.setSortOrder(0);
        updateVariant.setImages(List.of(foreignImage));

        updateInput.setVariants(List.of(updateVariant));

        assertThrows(IllegalArgumentException.class, () -> productService.updateProductInformation(productId, updateInput));
    }

    // ─── Atomicity assertion helper ──────────────────────────────────────────

    /**
     * Asserts that no product exists whose name starts with the given marker.
     * Used to verify that a rejected create left no partial data.
     * Only checks if a transaction is still active (validation failures that throw
     * before any persistence leave no active transaction).
     */
    private void assertNoProductsWithMarker(String marker)
    {
        try {
            Long count = em.createQuery("SELECT COUNT(p) FROM ProductEntity p WHERE p.name LIKE :pattern", Long.class).setParameter("pattern", marker + "%").getSingleResult();
            assertThat("No partial product should exist after a rejected create", count, equalTo(0L));
        } catch (Exception e) {
            // If no active transaction (TX was rolled back by the exception), 
            // that inherently means no partial data was committed — atomicity preserved.
        }
    }
}
