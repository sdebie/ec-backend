package org.ecommerce.backend.service;

// Feature: admin-product-write, Task 2.3: Backend test — edit persists every change + Deletion Policy
// Validates: Requirements 3.2, 3.3, 3.4, 4.2, 9.1, 9.2, 9.3, 9.6, 8.1

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed integration test for the product-write aggregate (edit path + deletion policy).
 *
 * Uses the REAL {@link ProductService#updateProductInformation} and
 * {@link ProductService#deleteProduct} — no mocks.
 * {@link TestTransaction} ensures each test rolls back so the shared dev DB is never mutated.
 *
 * Covers:
 *   - Edit persists every change: add/remove variant, change price, scalar changes (Req 3.2, 3.3, 3.4, 4.2)
 *   - Active-only editor/storefront reads exclude disabled variants (Req 9.6)
 *   - Draft unreferenced hard delete (Req 9.1)
 *   - Draft order-referenced soft delete (Req 9.3)
 *   - Non-draft soft delete (Req 9.2)
 *   - Whole-product deletion (Req 9.1, 9.2)
 *   - Order/history preservation (Req 9.3)
 *   - Real service coverage (Req 8.1)
 */
@QuarkusTest
class ProductEditIntegrationTest {

    @Inject
    ProductService productService;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    EntityManager em;

    // ─── Helpers ────────────────────────────────────────────────────────────

    private ProductInformationDto createProduct(String marker, String status, int variantCount) {
        ProductDto product = new ProductDto();
        product.name = marker + "Product";
        product.slug = (marker + "slug-" + UUID.randomUUID()).toLowerCase();
        product.shortDescription = "Short " + marker;
        product.description = "Description " + marker;
        product.status = status;

        List<ProductVariantDto> variants = new ArrayList<>();
        for (int i = 0; i < variantCount; i++) {
            ProductVariantDto variant = new ProductVariantDto();
            variant.sku = marker + "SKU-" + (char) ('A' + i) + "-" + UUID.randomUUID().toString().substring(0, 8);
            variant.stockQuantity = 10 + i * 5;
            VariantPriceDto price = new VariantPriceDto();
            price.priceType = "RETAIL_PRICE";
            price.price = new BigDecimal("100.00").add(new BigDecimal(i * 50));
            variant.prices = List.of(price);
            variant.images = (i == 0)
                    ? List.of(new ProductImageDto(null, "/images/" + marker + "img.jpg", 0, true))
                    : List.of();
            variants.add(variant);
        }

        ProductInformationDto input = new ProductInformationDto();
        input.product = product;
        input.variants = variants;
        return productService.addProductInformation(input);
    }

    /**
     * Inserts a minimal order + order_item referencing the given variant directly via EntityManager.
     */
    private void insertOrderReferencing(UUID variantId) {
        OrderEntity order = new OrderEntity();
        order.totalAmount = new BigDecimal("100.00");
        order.status = OrderStatusEn.PENDING;
        order.contactEmail = "test@example.com";
        order.contactFirstName = "Test";
        order.contactLastName = "User";
        order.sessionId = UUID.randomUUID();
        em.persist(order);
        em.flush();

        OrderItemEntity item = new OrderItemEntity();
        item.orderEntity = order;
        item.variant = em.find(ProductVariantEntity.class, variantId);
        item.quantity = 1;
        item.unitPrice = new BigDecimal("100.00");
        em.persist(item);
        em.flush();
    }

    // ─── Edit persists every change ─────────────────────────────────────────

    @Test
    @TestTransaction
    void editPersistsEveryChange_addRemoveVariantAndChangePrice() {
        String marker = "ZZED-CHNG-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a product with 2 variants (ACTIVE so removal triggers soft-delete)
        ProductInformationDto created = createProduct(marker, "ACTIVE", 2);
        String productId = created.product.id;
        em.flush();
        em.clear();

        // Read it back to get the full state
        ProductInformationDto original = productService.getProductInformationDto(productId);
        assertThat(original.variants, hasSize(2));

        String variantToKeepId = original.variants.get(0).id;
        String variantToKeepSku = original.variants.get(0).sku;
        String variantToRemoveSku = original.variants.get(1).sku;

        // Build the update payload:
        // - Keep variant 0 with a CHANGED price
        // - Remove variant 1 (not in payload)
        // - Add a brand new variant 2
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.product = new ProductDto();
        updateInput.product.id = productId;
        updateInput.product.name = marker + "Updated Name";
        updateInput.product.description = "Updated description";
        updateInput.product.status = "ACTIVE";

        List<ProductVariantDto> updateVariants = new ArrayList<>();

        // Kept variant with changed price
        ProductVariantDto keptVariant = new ProductVariantDto();
        keptVariant.id = variantToKeepId;
        keptVariant.sku = variantToKeepSku;
        keptVariant.stockQuantity = 99;
        VariantPriceDto changedPrice = new VariantPriceDto();
        changedPrice.priceType = "RETAIL_PRICE";
        changedPrice.price = new BigDecimal("777.77");
        // Use existing price id so it's an upsert
        if (original.variants.get(0).prices != null && !original.variants.get(0).prices.isEmpty()) {
            changedPrice.id = original.variants.get(0).prices.get(0).id;
        }
        keptVariant.prices = List.of(changedPrice);
        keptVariant.images = List.of(new ProductImageDto(null, "/images/updated.jpg", 0, true));
        updateVariants.add(keptVariant);

        // New variant
        ProductVariantDto newVariant = new ProductVariantDto();
        newVariant.sku = marker + "SKU-NEW-" + UUID.randomUUID().toString().substring(0, 8);
        newVariant.stockQuantity = 42;
        VariantPriceDto newPrice = new VariantPriceDto();
        newPrice.priceType = "RETAIL_PRICE";
        newPrice.price = new BigDecimal("555.55");
        newVariant.prices = List.of(newPrice);
        newVariant.images = List.of();
        updateVariants.add(newVariant);

        updateInput.variants = updateVariants;

        // Perform the update
        ProductInformationDto updated = productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // Read it back via the admin-edit read path
        ProductInformationDto read = productService.getProductInformationDto(productId);
        assertNotNull(read);

        // Scalar fields persisted
        assertThat(read.product.name, equalTo(marker + "Updated Name"));
        assertThat(read.product.description, equalTo("Updated description"));

        // Active-only read: should have 2 variants (kept + new), removed variant absent
        assertThat(read.variants, hasSize(2));

        List<String> readSkus = read.variants.stream().map(v -> v.sku).toList();
        assertThat(readSkus, hasItem(variantToKeepSku));
        assertThat(readSkus, hasItem(newVariant.sku));
        assertThat(readSkus, not(hasItem(variantToRemoveSku)));

        // Price updated in place (not duplicated)
        ProductVariantDto readKeptVariant = read.variants.stream()
                .filter(v -> v.sku.equals(variantToKeepSku)).findFirst().orElseThrow();
        List<VariantPriceDto> retailPrices = readKeptVariant.prices.stream()
                .filter(p -> "RETAIL_PRICE".equals(p.priceType)).toList();
        assertThat("Price updated, not duplicated", retailPrices, hasSize(1));
        assertThat(retailPrices.get(0).price.compareTo(new BigDecimal("777.77")), equalTo(0));

        // Stock updated
        assertThat(readKeptVariant.stockQuantity, equalTo(99));

        // New variant present with correct price
        ProductVariantDto readNewVariant = read.variants.stream()
                .filter(v -> v.sku.equals(newVariant.sku)).findFirst().orElseThrow();
        assertThat(readNewVariant.stockQuantity, equalTo(42));
        VariantPriceDto newRetail = readNewVariant.prices.stream()
                .filter(p -> "RETAIL_PRICE".equals(p.priceType)).findFirst().orElseThrow();
        assertThat(newRetail.price.compareTo(new BigDecimal("555.55")), equalTo(0));
    }

    @Test
    @TestTransaction
    void editPersistsEveryChange_storefrontReadExcludesDisabledVariant() {
        String marker = "ZZED-SFRD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create an ACTIVE product with 2 variants
        ProductInformationDto created = createProduct(marker, "ACTIVE", 2);
        String productId = created.product.id;
        String slug = created.product.slug;
        em.flush();
        em.clear();

        ProductInformationDto original = productService.getProductInformationDto(productId);
        String variantToKeepSku = original.variants.get(0).sku;
        String variantToRemoveSku = original.variants.get(1).sku;

        // Update: keep only variant 0, remove variant 1
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.product = new ProductDto();
        updateInput.product.id = productId;
        updateInput.product.status = "ACTIVE";

        ProductVariantDto keptVariant = new ProductVariantDto();
        keptVariant.id = original.variants.get(0).id;
        keptVariant.sku = variantToKeepSku;
        keptVariant.stockQuantity = original.variants.get(0).stockQuantity;
        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = original.variants.get(0).prices.get(0).price;
        price.id = original.variants.get(0).prices.get(0).id;
        keptVariant.prices = List.of(price);
        keptVariant.images = List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true));
        updateInput.variants = List.of(keptVariant);

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // Storefront detail read (by slug) must exclude the disabled variant
        ProductInformationDto storefrontRead = productService.getProductInformationBySlug(slug);
        assertNotNull(storefrontRead);
        assertThat(storefrontRead.variants, hasSize(1));
        assertThat(storefrontRead.variants.get(0).sku, equalTo(variantToKeepSku));
    }

    // ─── Deletion Policy: Variant removal ───────────────────────────────────

    @Test
    @TestTransaction
    void deletionPolicy_draftUnreferenced_hardDeletesVariant() {
        String marker = "ZZED-DHRD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a PENDING (draft) product with 2 variants
        ProductInformationDto created = createProduct(marker, "PENDING", 2);
        String productId = created.product.id;
        em.flush();
        em.clear();

        ProductInformationDto original = productService.getProductInformationDto(productId);
        String variantToKeepId = original.variants.get(0).id;
        String variantToKeepSku = original.variants.get(0).sku;
        String variantToRemoveId = original.variants.get(1).id;

        // Update: keep only variant 0, remove variant 1
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.product = new ProductDto();
        updateInput.product.id = productId;
        updateInput.product.status = "PENDING";

        ProductVariantDto kept = new ProductVariantDto();
        kept.id = variantToKeepId;
        kept.sku = variantToKeepSku;
        kept.stockQuantity = 10;
        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = original.variants.get(0).prices.get(0).price;
        price.id = original.variants.get(0).prices.get(0).id;
        kept.prices = List.of(price);
        kept.images = List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true));
        updateInput.variants = List.of(kept);

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // The removed variant must be PHYSICALLY GONE (hard deleted)
        ProductVariantEntity deletedVariant = em.find(ProductVariantEntity.class, UUID.fromString(variantToRemoveId));
        assertNull(deletedVariant, "Draft unreferenced variant must be hard-deleted (physically removed)");
    }

    @Test
    @TestTransaction
    void deletionPolicy_draftOrderReferenced_softDeletesVariant() {
        String marker = "ZZED-DREF-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a PENDING (draft) product with 2 variants
        ProductInformationDto created = createProduct(marker, "PENDING", 2);
        String productId = created.product.id;
        em.flush();
        em.clear();

        ProductInformationDto original = productService.getProductInformationDto(productId);
        String variantToKeepId = original.variants.get(0).id;
        String variantToKeepSku = original.variants.get(0).sku;
        String variantToRemoveId = original.variants.get(1).id;

        // Simulate an order referencing variant 1
        insertOrderReferencing(UUID.fromString(variantToRemoveId));
        em.flush();
        em.clear();

        // Update: keep only variant 0, remove variant 1
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.product = new ProductDto();
        updateInput.product.id = productId;
        updateInput.product.status = "PENDING";

        ProductVariantDto kept = new ProductVariantDto();
        kept.id = variantToKeepId;
        kept.sku = variantToKeepSku;
        kept.stockQuantity = 10;
        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = original.variants.get(0).prices.get(0).price;
        price.id = original.variants.get(0).prices.get(0).id;
        kept.prices = List.of(price);
        kept.images = List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true));
        updateInput.variants = List.of(kept);

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // The removed variant must still exist with DISABLED status (soft-deleted)
        ProductVariantEntity removedVariant = em.find(ProductVariantEntity.class, UUID.fromString(variantToRemoveId));
        assertNotNull(removedVariant, "Order-referenced variant must NOT be hard-deleted");
        assertThat(removedVariant.status, equalTo(ProductStatusEn.DISABLED));
    }

    @Test
    @TestTransaction
    void deletionPolicy_nonDraft_softDeletesVariant() {
        String marker = "ZZED-NDSD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create an ACTIVE (non-draft) product with 2 variants
        ProductInformationDto created = createProduct(marker, "ACTIVE", 2);
        String productId = created.product.id;
        em.flush();
        em.clear();

        ProductInformationDto original = productService.getProductInformationDto(productId);
        String variantToKeepId = original.variants.get(0).id;
        String variantToKeepSku = original.variants.get(0).sku;
        String variantToRemoveId = original.variants.get(1).id;

        // Update: keep only variant 0, remove variant 1 — no order reference
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.product = new ProductDto();
        updateInput.product.id = productId;
        updateInput.product.status = "ACTIVE";

        ProductVariantDto kept = new ProductVariantDto();
        kept.id = variantToKeepId;
        kept.sku = variantToKeepSku;
        kept.stockQuantity = 10;
        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = original.variants.get(0).prices.get(0).price;
        price.id = original.variants.get(0).prices.get(0).id;
        kept.prices = List.of(price);
        kept.images = List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true));
        updateInput.variants = List.of(kept);

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // The removed variant must still exist with DISABLED status (soft-deleted, not hard)
        ProductVariantEntity removedVariant = em.find(ProductVariantEntity.class, UUID.fromString(variantToRemoveId));
        assertNotNull(removedVariant, "Non-draft variant must NOT be hard-deleted");
        assertThat(removedVariant.status, equalTo(ProductStatusEn.DISABLED));

        // Active-only read must NOT include the disabled variant
        ProductInformationDto read = productService.getProductInformationDto(productId);
        assertThat(read.variants, hasSize(1));
        assertThat(read.variants.get(0).sku, equalTo(variantToKeepSku));
    }

    // ─── Deletion Policy: Whole-product deletion ────────────────────────────

    @Test
    @TestTransaction
    void deletionPolicy_wholeProductDelete_pendingNoReferences_hardDeletes() {
        String marker = "ZZED-WPHD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a PENDING product with no order references
        ProductInformationDto created = createProduct(marker, "PENDING", 2);
        String productId = created.product.id;
        em.flush();
        em.clear();

        // Delete the product
        productService.deleteProduct(productId);
        em.flush();
        em.clear();

        // Product must be physically gone
        ProductEntity deletedProduct = em.find(ProductEntity.class, UUID.fromString(productId));
        assertNull(deletedProduct, "PENDING product with no order references must be hard-deleted");

        // Variants must also be physically gone (cascade)
        for (ProductVariantDto v : created.variants) {
            ProductVariantEntity variant = em.find(ProductVariantEntity.class, UUID.fromString(v.id));
            assertNull(variant, "Variants of hard-deleted product must also be physically removed");
        }
    }

    @Test
    @TestTransaction
    void deletionPolicy_wholeProductDelete_activeProduct_softDeletes() {
        String marker = "ZZED-WPSD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create an ACTIVE product
        ProductInformationDto created = createProduct(marker, "ACTIVE", 2);
        String productId = created.product.id;
        em.flush();
        em.clear();

        // Delete the product
        productService.deleteProduct(productId);
        em.flush();
        em.clear();

        // Product must still exist with DISABLED status
        ProductEntity product = em.find(ProductEntity.class, UUID.fromString(productId));
        assertNotNull(product, "ACTIVE product must NOT be hard-deleted");
        assertThat(product.status, equalTo(ProductStatusEn.DISABLED));

        // All child variants that were ACTIVE must now be DISABLED
        for (ProductVariantDto v : created.variants) {
            ProductVariantEntity variant = em.find(ProductVariantEntity.class, UUID.fromString(v.id));
            assertNotNull(variant, "Variants of soft-deleted product must still exist");
            assertThat(variant.status, equalTo(ProductStatusEn.DISABLED));
        }
    }

    @Test
    @TestTransaction
    void deletionPolicy_wholeProductDelete_pendingWithOrderRef_softDeletes() {
        String marker = "ZZED-WPOR-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a PENDING product
        ProductInformationDto created = createProduct(marker, "PENDING", 2);
        String productId = created.product.id;
        em.flush();

        // Insert an order referencing one of the variants
        UUID referencedVariantId = UUID.fromString(created.variants.get(0).id);
        insertOrderReferencing(referencedVariantId);
        em.flush();
        em.clear();

        // Delete the product
        productService.deleteProduct(productId);
        em.flush();
        em.clear();

        // Product must still exist with DISABLED status (order-referenced prevents hard delete)
        ProductEntity product = em.find(ProductEntity.class, UUID.fromString(productId));
        assertNotNull(product, "PENDING product with order-referenced variant must NOT be hard-deleted");
        assertThat(product.status, equalTo(ProductStatusEn.DISABLED));

        // Active child variants must be DISABLED
        for (ProductVariantDto v : created.variants) {
            ProductVariantEntity variant = em.find(ProductVariantEntity.class, UUID.fromString(v.id));
            assertNotNull(variant, "Variant rows must be preserved for order history");
            assertThat(variant.status, equalTo(ProductStatusEn.DISABLED));
        }
    }

    // ─── Order/history preservation ─────────────────────────────────────────

    @Test
    @TestTransaction
    void deletionPolicy_orderHistoryPreserved_afterSoftDelete() {
        String marker = "ZZED-OHST-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create an ACTIVE product with 2 variants
        ProductInformationDto created = createProduct(marker, "ACTIVE", 2);
        String productId = created.product.id;
        em.flush();

        // Insert an order referencing variant 1
        UUID referencedVariantId = UUID.fromString(created.variants.get(1).id);
        insertOrderReferencing(referencedVariantId);
        em.flush();
        em.clear();

        // Remove variant 1 via update (non-draft → soft-delete)
        ProductInformationDto original = productService.getProductInformationDto(productId);

        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.product = new ProductDto();
        updateInput.product.id = productId;
        updateInput.product.status = "ACTIVE";

        // Keep only variant 0
        ProductVariantDto kept = new ProductVariantDto();
        kept.id = original.variants.get(0).id;
        kept.sku = original.variants.get(0).sku;
        kept.stockQuantity = original.variants.get(0).stockQuantity;
        VariantPriceDto price = new VariantPriceDto();
        price.priceType = "RETAIL_PRICE";
        price.price = original.variants.get(0).prices.get(0).price;
        price.id = original.variants.get(0).prices.get(0).id;
        kept.prices = List.of(price);
        kept.images = List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true));
        updateInput.variants = List.of(kept);

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // The order_items row still references a valid variant (row exists with DISABLED status)
        Long orderItemCount = em.createQuery(
                        "SELECT COUNT(oi) FROM OrderItemEntity oi WHERE oi.variant.id = :variantId", Long.class)
                .setParameter("variantId", referencedVariantId)
                .getSingleResult();
        assertThat("Order item reference must still be valid", orderItemCount, equalTo(1L));

        // The variant row exists with DISABLED status
        ProductVariantEntity softDeleted = em.find(ProductVariantEntity.class, referencedVariantId);
        assertNotNull(softDeleted, "Soft-deleted variant must still exist for order history");
        assertThat(softDeleted.status, equalTo(ProductStatusEn.DISABLED));
    }
}
