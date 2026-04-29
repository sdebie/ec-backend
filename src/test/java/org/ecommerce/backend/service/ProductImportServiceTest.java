package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Disabled
@QuarkusTest
class ProductImportServiceTest {

    @Inject
    ProductImportService productImportService;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(ProductUploadBatchEntity.class);
        PanacheMock.mock(ProductUploadStagedEntity.class);
        PanacheMock.mock(ProductEntity.class);
        PanacheMock.mock(ProductVariantEntity.class);
        PanacheMock.mock(CategoryEntity.class);
        PanacheMock.mock(BrandEntity.class);
        PanacheMock.mock(VariantPricesEntity.class);
        PanacheMock.mock(ProductImageEntity.class);

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> variantQuery = (PanacheQuery<PanacheEntityBase>) org.mockito.Mockito.mock(PanacheQuery.class);
        when(ProductVariantEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(variantQuery);
        when(variantQuery.firstResult()).thenReturn(null);
        when(variantQuery.firstResultOptional()).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> categoryQuery = (PanacheQuery<PanacheEntityBase>) org.mockito.Mockito.mock(PanacheQuery.class);
        when(CategoryEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(categoryQuery);
        when(categoryQuery.firstResult()).thenReturn(null);
        when(categoryQuery.firstResultOptional()).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> brandQuery = (PanacheQuery<PanacheEntityBase>) org.mockito.Mockito.mock(PanacheQuery.class);
        when(BrandEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(brandQuery);
        when(brandQuery.firstResult()).thenReturn(null);
        when(brandQuery.firstResultOptional()).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> productQuery = (PanacheQuery<PanacheEntityBase>) org.mockito.Mockito.mock(PanacheQuery.class);
        when(ProductEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(productQuery);
        when(productQuery.firstResult()).thenReturn(null);
        when(productQuery.firstResultOptional()).thenReturn(Optional.empty());

        when(ProductImageEntity.list(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(new ArrayList<>());
    }


    @Test
    void validateAndDiff_shouldAddValidationErrorsWhenRequiredFieldsAreMissing() throws Exception {
        ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
        staged.sku = "TSHIRT-ERR-1";
        staged.name = null;
        staged.categorySlug = null;

        ArrayList<String> validationErrors = new ArrayList<>();

        invokeValidateAndDiff(staged, validationErrors, 10, null, "", "");

        assertTrue(validationErrors.contains("category_slug is required"));
        assertTrue(validationErrors.contains("brand_slug is required"));
        assertTrue(validationErrors.contains("name is required"));
    }

    @Test
    void validateAndDiff_shouldAddValidationErrorsWhenCategoryOrBrandDoNotExist() throws Exception {
        ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
        staged.sku = "TSHIRT-NEW-1";
        staged.name = "Blue Cotton Tee";
        staged.categorySlug = "apparel";
        staged.brandSlug = "nike";

        ArrayList<String> validationErrors = new ArrayList<>();

        invokeValidateAndDiff(staged, validationErrors, 10, "nike", "TSHIRT-NEW-1.jpg", "{}");

        assertTrue(validationErrors.contains("Unknown category_slug: apparel"));
        assertTrue(validationErrors.contains("Unknown brand_slug: nike"));
        assertTrue(staged.isNewProduct);
    }

    @Test
    void validateAndDiff_shouldAddValidationErrorWhenSkuBelongsToAnotherProduct() throws Exception {
        ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
        staged.sku = "TSHIRT-CONFLICT";
        staged.name = "Blue Cotton Tee";
        staged.categorySlug = "apparel";

        CategoryEntity category = new CategoryEntity();
        category.slug = "apparel";
        BrandEntity brand = new BrandEntity();
        brand.slug = "nike";

        ProductEntity matchedProduct = new ProductEntity();
        matchedProduct.id = UUID.randomUUID();
        matchedProduct.name = "Blue Cotton Tee";
        matchedProduct.category = category;
        matchedProduct.brand = brand;

        ProductVariantEntity existingVariant = new ProductVariantEntity();
        ProductEntity existingProduct = new ProductEntity();
        existingProduct.id = UUID.randomUUID();
        existingProduct.name = "Other Tee";
        existingVariant.product = existingProduct;

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> categoryQuery = (PanacheQuery<PanacheEntityBase>) mock(PanacheQuery.class);
        when(CategoryEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(categoryQuery);
        when(categoryQuery.firstResult()).thenReturn(category);

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> brandQuery = (PanacheQuery<PanacheEntityBase>) mock(PanacheQuery.class);
        when(BrandEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(brandQuery);
        when(brandQuery.firstResult()).thenReturn(brand);

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> productQuery = (PanacheQuery<PanacheEntityBase>) mock(PanacheQuery.class);
        when(ProductEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(productQuery);
        when(productQuery.firstResult()).thenReturn(matchedProduct);

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> variantQuery = (PanacheQuery<PanacheEntityBase>) mock(PanacheQuery.class);
        when(ProductVariantEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(variantQuery);
        when(variantQuery.firstResult()).thenReturn(existingVariant);

        ArrayList<String> validationErrors = new ArrayList<>();

        invokeValidateAndDiff(staged, validationErrors, 10, "nike", "TSHIRT-CONFLICT.jpg", "{}");

        assertTrue(validationErrors.stream().anyMatch(err -> err.contains("already belongs to another product")));
    }

    @Test
    void validateAndDiff_shouldNotMutateExistingVariantDuringValidation() throws Exception {
        ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
        staged.sku = "TSHIRT-EXISTING";
        staged.name = "Blue Cotton Tee";
        staged.categorySlug = "apparel";
        staged.brandSlug = "nike";

        CategoryEntity category = new CategoryEntity();
        category.slug = "apparel";
        BrandEntity brand = new BrandEntity();
        brand.slug = "nike";

        ProductEntity product = new ProductEntity();
        product.id = UUID.randomUUID();
        product.name = "Blue Cotton Tee";
        product.category = category;
        product.brand = brand;

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.id = UUID.randomUUID();
        variant.product = product;
        variant.stockQuantity = 5;
        variant.attributesJson = "{\"color\":\"Blue\"}";

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> categoryQuery = (PanacheQuery<PanacheEntityBase>) mock(PanacheQuery.class);
        when(CategoryEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(categoryQuery);
        when(categoryQuery.firstResult()).thenReturn(category);

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> brandQuery = (PanacheQuery<PanacheEntityBase>) mock(PanacheQuery.class);
        when(BrandEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(brandQuery);
        when(brandQuery.firstResult()).thenReturn(brand);

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> productQuery = (PanacheQuery<PanacheEntityBase>) mock(PanacheQuery.class);
        when(ProductEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(productQuery);
        when(productQuery.firstResult()).thenReturn(product);

        @SuppressWarnings("unchecked")
        PanacheQuery<PanacheEntityBase> variantQuery = (PanacheQuery<PanacheEntityBase>) mock(PanacheQuery.class);
        when(ProductVariantEntity.find(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(variantQuery);
        when(variantQuery.firstResult()).thenReturn(variant);

        ArrayList<String> validationErrors = new ArrayList<>();

        invokeValidateAndDiff(staged, validationErrors, 99, "nike", "TSHIRT-EXISTING.jpg", "{\"color\":\"Blue\",\"size\":\"L\"}");

        assertEquals(Integer.valueOf(5), variant.stockQuantity);
        assertEquals("{\"color\":\"Blue\"}", variant.attributesJson);
        assertTrue(validationErrors.isEmpty());
    }

    @Test
    void handleCsvUploadForBatch_shouldLoadExistingBatchByIdAndUpdateItsStatus() throws Exception {
        String csv = """
                sku,name,category_slug,brand_slug,retail_price,wholesale_price,stock,images,attributes
                TSHIRT-BLU-L,"Blue Cotton Tee",apparel,nike,299.00,150.00,100,,"{""color"": ""Blue"", ""size"": ""L""}"
                """;

        UUID batchId = UUID.randomUUID();
        ProductUploadBatchEntity batch = new ProductUploadBatchEntity();
        batch.id = batchId;
        batch.productUploadStatusEn = ProductUploadStatusEn.IMPORTING;

        when(ProductUploadBatchEntity.findById(batchId)).thenReturn(batch);

        InputStream inputStream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

        productImportService.handleCsvUploadForBatch(inputStream, batchId);

        assertEquals(1, batch.totalRows);
        assertEquals(Integer.valueOf(2), batch.validationErrorCount);
        assertEquals(ProductUploadStatusEn.PENDING, batch.productUploadStatusEn);
    }

    @Test
    void applyValidationResults_shouldStoreValidationStatusAndMessage() throws Exception {
        ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
        ArrayList<String> validationErrors = new ArrayList<>();
        validationErrors.add("Unknown category: apparel");
        validationErrors.add("Unknown brand: nike");

        Method applyValidationResults = ProductImportService.class.getDeclaredMethod(
                "applyValidationResults",
                ProductUploadStagedEntity.class,
                java.util.List.class
        );
        applyValidationResults.setAccessible(true);
        applyValidationResults.invoke(productImportService, staged, validationErrors);

        assertEquals(ProductImportValidationStatusEn.INVALID, staged.validationStatus);
        assertEquals("Unknown category: apparel; Unknown brand: nike", staged.validationErrors);
    }

    private void invokeValidateAndDiff(
            ProductUploadStagedEntity staged,
            ArrayList<String> validationErrors,
            Integer stock,
            String brandSlug,
            String imagesValue,
            String attributesJson
    ) throws Exception {
        Method validateAndDiff = ProductImportService.class.getDeclaredMethod(
                "validateAndDiff",
                ProductUploadStagedEntity.class,
                java.util.List.class,
                Integer.class,
                String.class,
                String.class,
                String.class
        );
        validateAndDiff.setAccessible(true);
        validateAndDiff.invoke(productImportService, staged, validationErrors, stock, brandSlug, imagesValue, attributesJson);
    }
}
