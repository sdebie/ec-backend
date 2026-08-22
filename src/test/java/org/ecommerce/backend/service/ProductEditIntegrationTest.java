package org.ecommerce.backend.service;

// Feature: admin-product-write, Task 2.3: Backend test — edit persists every change + Deletion Policy

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DB-backed integration test for the product-write aggregate (edit path + deletion policy).
 * <p>
 * Uses the REAL {@link ProductService#updateProductInformation} and
 * {@link ProductService#deleteProduct} — no mocks.
 * {@link TestTransaction} ensures each test rolls back so the shared dev DB is never mutated.
 * <p>
 * Covers:
 * - Edit persists every change: add/remove variant, change price, scalar changes (Req 3.2, 3.3, 3.4, 4.2)
 * - Active-only editor/storefront reads exclude disabled variants (Req 9.6)
 * - Unreferenced hard delete, any status (order history is the only bar to physical deletion)
 * - Order-referenced soft delete / archive (Req 9.3)
 * - Whole-product deletion
 * - Order/history preservation (Req 9.3)
 * - Real service coverage (Req 8.1)
 */
@QuarkusTest
class ProductEditIntegrationTest
{
    @Inject
    ProductService productService;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    EntityManager em;

    // ─── Helpers ────────────────────────────────────────────────────────────

    private ProductInformationDto createProduct(String marker, String status)
    {
        ProductDto product = new ProductDto();
        product.setName(marker + "Product");
        product.setSlug((marker + "slug-" + UUID.randomUUID()).toLowerCase());
        product.setShortDescription("Short " + marker);
        product.setDescription("Description " + marker);
        product.setStatus(status);

        List<ProductVariantDto> variants = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ProductVariantDto variant = new ProductVariantDto();
            variant.setSku(marker + "SKU-" + (char) ('A' + i) + "-" + UUID.randomUUID().toString().substring(0, 8));
            variant.setStockQuantity(10 + i * 5);
            VariantPriceDto price = new VariantPriceDto();
            price.setPriceType("RETAIL_PRICE");
            price.setPrice(new BigDecimal("100.00").add(new BigDecimal(i * 50)));
            variant.setPrices(List.of(price));
            variant.setImages((i == 0) ? List.of(new ProductImageDto(null, "/images/" + marker + "img.jpg", 0, true)) : List.of());
            variants.add(variant);
        }

        ProductInformationDto input = new ProductInformationDto();
        input.setProduct(product);
        input.setVariants(variants);
        return productService.addProductInformation(input);
    }

    /**
     * Inserts a minimal order + order_item referencing the given variant directly via EntityManager.
     */
    private void insertOrderReferencing(UUID variantId)
    {
        OrderEntity order = new OrderEntity();
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.PENDING);
        order.setContactEmail("test@example.com");
        order.setContactFirstName("Test");
        order.setContactLastName("User");
        order.setSessionId(UUID.randomUUID());
        em.persist(order);
        em.flush();

        OrderItemEntity item = new OrderItemEntity();
        item.setOrderEntity(order);
        item.setVariant(em.find(ProductVariantEntity.class, variantId));
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("100.00"));
        em.persist(item);
        em.flush();
    }

    // ─── Edit persists every change ─────────────────────────────────────────

    @Test
    @TestTransaction
    void editPersistsEveryChange_addRemoveVariantAndChangePrice()
    {
        String marker = "ZZED-CHNG-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a product with 2 variants (ACTIVE so removal triggers soft-delete)
        ProductInformationDto created = createProduct(marker, "ACTIVE");
        String productId = created.getProduct().getId();
        em.flush();
        em.clear();

        // Read it back to get the full state
        ProductInformationDto original = productService.getProductInformationDto(productId);
        assertThat(original.getVariants(), hasSize(2));

        String variantToKeepId = original.getVariants().get(0).getId();
        String variantToKeepSku = original.getVariants().get(0).getSku();
        String variantToRemoveSku = original.getVariants().get(1).getSku();

        // Build the update payload:
        // - Keep variant 0 with a CHANGED price
        // - Remove variant 1 (not in payload)
        // - Add a brand new variant 2
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.setProduct(new ProductDto());
        updateInput.getProduct().setId(productId);
        updateInput.getProduct().setName(marker + "Updated Name");
        updateInput.getProduct().setDescription("Updated description");
        updateInput.getProduct().setStatus("ACTIVE");

        List<ProductVariantDto> updateVariants = new ArrayList<>();

        // Kept variant with changed price
        ProductVariantDto keptVariant = new ProductVariantDto();
        keptVariant.setId(variantToKeepId);
        keptVariant.setSku(variantToKeepSku);
        keptVariant.setStockQuantity(99);
        VariantPriceDto changedPrice = new VariantPriceDto();
        changedPrice.setPriceType("RETAIL_PRICE");
        changedPrice.setPrice(new BigDecimal("777.77"));
        // Use existing price id so it's an upsert
        if (original.getVariants().getFirst().getPrices() != null && !original.getVariants().getFirst().getPrices().isEmpty()) {
            changedPrice.setId(original.getVariants().getFirst().getPrices().getFirst().getId());
        }
        keptVariant.setPrices(List.of(changedPrice));
        keptVariant.setImages(List.of(new ProductImageDto(null, "/images/updated.jpg", 0, true)));
        updateVariants.add(keptVariant);

        // New variant
        ProductVariantDto newVariant = new ProductVariantDto();
        newVariant.setSku(marker + "SKU-NEW-" + UUID.randomUUID().toString().substring(0, 8));
        newVariant.setStockQuantity(42);
        VariantPriceDto newPrice = new VariantPriceDto();
        newPrice.setPriceType("RETAIL_PRICE");
        newPrice.setPrice(new BigDecimal("555.55"));
        newVariant.setPrices(List.of(newPrice));
        newVariant.setImages(List.of());
        updateVariants.add(newVariant);

        updateInput.setVariants(updateVariants);

        // Perform the update
        ProductInformationDto updated = productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // Read it back via the admin-edit read path
        ProductInformationDto read = productService.getProductInformationDto(productId);
        assertNotNull(read);

        // Scalar fields persisted
        assertThat(read.getProduct().getName(), equalTo(marker + "Updated Name"));
        assertThat(read.getProduct().getDescription(), equalTo("Updated description"));

        // Active-only read: should have 2 variants (kept + new), removed variant absent
        assertThat(read.getVariants(), hasSize(2));

        List<String> readSkus = read.getVariants().stream().map(ProductVariantDto::getSku).toList();
        assertThat(readSkus, hasItem(variantToKeepSku));
        assertThat(readSkus, hasItem(newVariant.getSku()));
        assertThat(readSkus, not(hasItem(variantToRemoveSku)));

        // Price updated in place (not duplicated)
        ProductVariantDto readKeptVariant = read.getVariants().stream().filter(v -> v.getSku().equals(variantToKeepSku)).findFirst().orElseThrow();
        List<VariantPriceDto> retailPrices = readKeptVariant.getPrices().stream().filter(p -> "RETAIL_PRICE".equals(p.getPriceType())).toList();
        assertThat("Price updated, not duplicated", retailPrices, hasSize(1));
        assertThat(retailPrices.getFirst().getPrice().compareTo(new BigDecimal("777.77")), equalTo(0));

        // Stock updated
        assertThat(readKeptVariant.getStockQuantity(), equalTo(99));

        // New variant present with correct price
        ProductVariantDto readNewVariant = read.getVariants().stream().filter(v -> v.getSku().equals(newVariant.getSku())).findFirst().orElseThrow();
        assertThat(readNewVariant.getStockQuantity(), equalTo(42));
        VariantPriceDto newRetail = readNewVariant.getPrices().stream().filter(p -> "RETAIL_PRICE".equals(p.getPriceType())).findFirst().orElseThrow();
        assertThat(newRetail.getPrice().compareTo(new BigDecimal("555.55")), equalTo(0));
    }

    @Test
    @TestTransaction
    void editPersistsEveryChange_storefrontReadExcludesDisabledVariant()
    {
        String marker = "ZZED-SFRD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create an ACTIVE product with 2 variants
        ProductInformationDto created = createProduct(marker, "ACTIVE");
        String productId = created.getProduct().getId();
        String slug = created.getProduct().getSlug();
        em.flush();
        em.clear();

        ProductInformationDto original = productService.getProductInformationDto(productId);
        String variantToKeepSku = original.getVariants().get(0).getSku();
        String variantToRemoveSku = original.getVariants().get(1).getSku();

        // Update: keep only variant 0, remove variant 1
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.setProduct(new ProductDto());
        updateInput.getProduct().setId(productId);
        updateInput.getProduct().setStatus("ACTIVE");

        ProductVariantDto keptVariant = new ProductVariantDto();
        keptVariant.setId(original.getVariants().getFirst().getId());
        keptVariant.setSku(variantToKeepSku);
        keptVariant.setStockQuantity(original.getVariants().getFirst().getStockQuantity());
        VariantPriceDto price = new VariantPriceDto();
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(original.getVariants().getFirst().getPrices().getFirst().getPrice());
        price.setId(original.getVariants().getFirst().getPrices().getFirst().getId());
        keptVariant.setPrices(List.of(price));
        keptVariant.setImages(List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true)));
        updateInput.setVariants(List.of(keptVariant));

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // Storefront detail read (by slug) must exclude the disabled variant
        ProductInformationDto storefrontRead = productService.getProductInformationBySlug(slug);
        assertNotNull(storefrontRead);
        assertThat(storefrontRead.getVariants(), hasSize(1));
        assertThat(storefrontRead.getVariants().getFirst().getSku(), equalTo(variantToKeepSku));
    }

    // ─── Deletion Policy: Variant removal ───────────────────────────────────

    @Test
    @TestTransaction
    void deletionPolicy_draftUnreferenced_hardDeletesVariant()
    {
        String marker = "ZZED-DHRD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a PENDING (draft) product with 2 variants
        ProductInformationDto created = createProduct(marker, "PENDING");
        String productId = created.getProduct().getId();
        em.flush();
        em.clear();

        ProductInformationDto original = productService.getProductInformationDto(productId);
        String variantToKeepId = original.getVariants().get(0).getId();
        String variantToKeepSku = original.getVariants().get(0).getSku();
        String variantToRemoveId = original.getVariants().get(1).getId();

        // Update: keep only variant 0, remove variant 1
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.setProduct(new ProductDto());
        updateInput.getProduct().setId(productId);
        updateInput.getProduct().setStatus("PENDING");

        ProductVariantDto kept = new ProductVariantDto();
        kept.setId(variantToKeepId);
        kept.setSku(variantToKeepSku);
        kept.setStockQuantity(10);
        VariantPriceDto price = new VariantPriceDto();
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(original.getVariants().getFirst().getPrices().getFirst().getPrice());
        price.setId(original.getVariants().getFirst().getPrices().getFirst().getId());
        kept.setPrices(List.of(price));
        kept.setImages(List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true)));
        updateInput.setVariants(List.of(kept));

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // The removed variant must be PHYSICALLY GONE (hard deleted)
        ProductVariantEntity deletedVariant = em.find(ProductVariantEntity.class, UUID.fromString(variantToRemoveId));
        assertNull(deletedVariant, "Draft unreferenced variant must be hard-deleted (physically removed)");
    }

    @Test
    @TestTransaction
    void deletionPolicy_draftOrderReferenced_softDeletesVariant()
    {
        String marker = "ZZED-DREF-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a PENDING (draft) product with 2 variants
        ProductInformationDto created = createProduct(marker, "PENDING");
        String productId = created.getProduct().getId();
        em.flush();
        em.clear();

        ProductInformationDto original = productService.getProductInformationDto(productId);
        String variantToKeepId = original.getVariants().get(0).getId();
        String variantToKeepSku = original.getVariants().get(0).getSku();
        String variantToRemoveId = original.getVariants().get(1).getId();

        // Simulate an order referencing variant 1
        insertOrderReferencing(UUID.fromString(variantToRemoveId));
        em.flush();
        em.clear();

        // Update: keep only variant 0, remove variant 1
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.setProduct(new ProductDto());
        updateInput.getProduct().setId(productId);
        updateInput.getProduct().setStatus("PENDING");

        ProductVariantDto kept = new ProductVariantDto();
        kept.setId(variantToKeepId);
        kept.setSku(variantToKeepSku);
        kept.setStockQuantity(10);
        VariantPriceDto price = new VariantPriceDto();
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(original.getVariants().getFirst().getPrices().getFirst().getPrice());
        price.setId(original.getVariants().getFirst().getPrices().getFirst().getId());
        kept.setPrices(List.of(price));
        kept.setImages(List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true)));
        updateInput.setVariants(List.of(kept));

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // The removed variant must still exist with DISABLED status (soft-deleted)
        ProductVariantEntity removedVariant = em.find(ProductVariantEntity.class, UUID.fromString(variantToRemoveId));
        assertNotNull(removedVariant, "Order-referenced variant must NOT be hard-deleted");
        assertThat(removedVariant.getStatus(), equalTo(ProductStatusEn.DISABLED));
    }

    @Test
    @TestTransaction
    void deletionPolicy_nonDraft_softDeletesVariant()
    {
        String marker = "ZZED-NDSD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create an ACTIVE (non-draft) product with 2 variants
        ProductInformationDto created = createProduct(marker, "ACTIVE");
        String productId = created.getProduct().getId();
        em.flush();
        em.clear();

        ProductInformationDto original = productService.getProductInformationDto(productId);
        String variantToKeepId = original.getVariants().get(0).getId();
        String variantToKeepSku = original.getVariants().get(0).getSku();
        String variantToRemoveId = original.getVariants().get(1).getId();

        // Update: keep only variant 0, remove variant 1 — no order reference
        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.setProduct(new ProductDto());
        updateInput.getProduct().setId(productId);
        updateInput.getProduct().setStatus("ACTIVE");

        ProductVariantDto kept = new ProductVariantDto();
        kept.setId(variantToKeepId);
        kept.setSku(variantToKeepSku);
        kept.setStockQuantity(10);
        VariantPriceDto price = new VariantPriceDto();
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(original.getVariants().getFirst().getPrices().getFirst().getPrice());
        price.setId(original.getVariants().getFirst().getPrices().getFirst().getId());
        kept.setPrices(List.of(price));
        kept.setImages(List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true)));
        updateInput.setVariants(List.of(kept));

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // The removed variant must still exist with DISABLED status (soft-deleted, not hard)
        ProductVariantEntity removedVariant = em.find(ProductVariantEntity.class, UUID.fromString(variantToRemoveId));
        assertNotNull(removedVariant, "Non-draft variant must NOT be hard-deleted");
        assertThat(removedVariant.getStatus(), equalTo(ProductStatusEn.DISABLED));

        // Active-only read must NOT include the disabled variant
        ProductInformationDto read = productService.getProductInformationDto(productId);
        assertThat(read.getVariants(), hasSize(1));
        assertThat(read.getVariants().getFirst().getSku(), equalTo(variantToKeepSku));
    }

    // ─── Deletion Policy: Whole-product deletion ────────────────────────────

    @Test
    @TestTransaction
    void deletionPolicy_wholeProductDelete_pendingNoReferences_hardDeletes()
    {
        String marker = "ZZED-WPHD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a PENDING product with no order references
        ProductInformationDto created = createProduct(marker, "PENDING");
        String productId = created.getProduct().getId();
        em.flush();
        em.clear();

        // Delete the product
        ProductDeletionOutcome outcome = productService.deleteProduct(productId);
        em.flush();
        em.clear();

        assertThat(outcome, equalTo(ProductDeletionOutcome.DELETED));

        // Product must be physically gone
        ProductEntity deletedProduct = em.find(ProductEntity.class, UUID.fromString(productId));
        assertNull(deletedProduct, "PENDING product with no order references must be hard-deleted");

        // Variants must also be physically gone (cascade)
        for (ProductVariantDto v : created.getVariants()) {
            ProductVariantEntity variant = em.find(ProductVariantEntity.class, UUID.fromString(v.getId()));
            assertNull(variant, "Variants of hard-deleted product must also be physically removed");
        }
    }

    @Test
    @TestTransaction
    void deletionPolicy_wholeProductDelete_activeProductWithoutOrders_hardDeletes()
    {
        String marker = "ZZED-WPSD-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create an ACTIVE product with no order references
        ProductInformationDto created = createProduct(marker, "ACTIVE");
        String productId = created.getProduct().getId();
        em.flush();
        em.clear();

        // Delete the product
        ProductDeletionOutcome outcome = productService.deleteProduct(productId);
        em.flush();
        em.clear();

        assertThat(outcome, equalTo(ProductDeletionOutcome.DELETED));

        // Order history is the only bar to physical deletion: an ACTIVE product
        // with no ordered variants is hard-deleted like a draft.
        ProductEntity product = em.find(ProductEntity.class, UUID.fromString(productId));
        assertNull(product, "ACTIVE product with no order references must be hard-deleted");

        for (ProductVariantDto v : created.getVariants()) {
            ProductVariantEntity variant = em.find(ProductVariantEntity.class, UUID.fromString(v.getId()));
            assertNull(variant, "Variants of hard-deleted product must also be physically removed");
        }
    }

    @Test
    @TestTransaction
    void deletionPolicy_wholeProductDelete_pendingWithOrderRef_softDeletes()
    {
        String marker = "ZZED-WPOR-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create a PENDING product
        ProductInformationDto created = createProduct(marker, "PENDING");
        String productId = created.getProduct().getId();
        em.flush();

        // Insert an order referencing one of the variants
        UUID referencedVariantId = UUID.fromString(created.getVariants().getFirst().getId());
        insertOrderReferencing(referencedVariantId);
        em.flush();
        em.clear();

        // Delete the product
        ProductDeletionOutcome outcome = productService.deleteProduct(productId);
        em.flush();
        em.clear();

        assertThat(outcome, equalTo(ProductDeletionOutcome.ARCHIVED));

        // Product must still exist with DISABLED status (order-referenced prevents hard delete)
        ProductEntity product = em.find(ProductEntity.class, UUID.fromString(productId));
        assertNotNull(product, "PENDING product with order-referenced variant must NOT be hard-deleted");
        assertThat(product.getStatus(), equalTo(ProductStatusEn.DISABLED));

        // Active child variants must be DISABLED
        for (ProductVariantDto v : created.getVariants()) {
            ProductVariantEntity variant = em.find(ProductVariantEntity.class, UUID.fromString(v.getId()));
            assertNotNull(variant, "Variant rows must be preserved for order history");
            assertThat(variant.getStatus(), equalTo(ProductStatusEn.DISABLED));
        }
    }

    // ─── Order/history preservation ─────────────────────────────────────────

    @Test
    @TestTransaction
    void deletionPolicy_orderHistoryPreserved_afterSoftDelete()
    {
        String marker = "ZZED-OHST-" + UUID.randomUUID().toString().substring(0, 6) + "-";

        // Create an ACTIVE product with 2 variants
        ProductInformationDto created = createProduct(marker, "ACTIVE");
        String productId = created.getProduct().getId();
        em.flush();

        // Insert an order referencing variant 1
        UUID referencedVariantId = UUID.fromString(created.getVariants().get(1).getId());
        insertOrderReferencing(referencedVariantId);
        em.flush();
        em.clear();

        // Remove variant 1 via update (non-draft → soft-delete)
        ProductInformationDto original = productService.getProductInformationDto(productId);

        ProductInformationDto updateInput = new ProductInformationDto();
        updateInput.setProduct(new ProductDto());
        updateInput.getProduct().setId(productId);
        updateInput.getProduct().setStatus("ACTIVE");

        // Keep only variant 0
        ProductVariantDto kept = new ProductVariantDto();
        kept.setId(original.getVariants().getFirst().getId());
        kept.setSku(original.getVariants().getFirst().getSku());
        kept.setStockQuantity(original.getVariants().getFirst().getStockQuantity());
        VariantPriceDto price = new VariantPriceDto();
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(original.getVariants().getFirst().getPrices().getFirst().getPrice());
        price.setId(original.getVariants().getFirst().getPrices().getFirst().getId());
        kept.setPrices(List.of(price));
        kept.setImages(List.of(new ProductImageDto(null, "/images/kept.jpg", 0, true)));
        updateInput.setVariants(List.of(kept));

        productService.updateProductInformation(productId, updateInput);
        em.flush();
        em.clear();

        // The order_items row still references a valid variant (row exists with DISABLED status)
        Long orderItemCount = em.createQuery("SELECT COUNT(oi) FROM OrderItemEntity oi WHERE oi.variant.id = :variantId", Long.class).setParameter("variantId", referencedVariantId).getSingleResult();
        assertThat("Order item reference must still be valid", orderItemCount, equalTo(1L));

        // The variant row exists with DISABLED status
        ProductVariantEntity softDeleted = em.find(ProductVariantEntity.class, referencedVariantId);
        assertNotNull(softDeleted, "Soft-deleted variant must still exist for order history");
        assertThat(softDeleted.getStatus(), equalTo(ProductStatusEn.DISABLED));
    }
}
