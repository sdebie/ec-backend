package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.entity.BrandEntity;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.PageContentEntity;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed integration tests for ImageService.cleanupUnassociatedFile's cross-table
 * reference check. A shared image library file must be refused for deletion while ANY
 * table that can hold an image path still references it — not just ProductImageEntity,
 * which is all the pre-existing check covered.
 */
@QuarkusTest
class ImageCleanupIntegrationTest
{
    @Inject
    ImageService imageService;

    private String uniquePath(String prefix)
    {
        return prefix + "-" + UUID.randomUUID() + ".jpg";
    }

    @Test
    @TestTransaction
    void refusesToDeleteFileReferencedByBrandLogo()
    {
        String path = uniquePath("brand-logo");
        BrandEntity brand = new BrandEntity();
        brand.setName("Cleanup Test Brand " + UUID.randomUUID());
        brand.setSlug("cleanup-test-brand-" + UUID.randomUUID());
        brand.setLogoUrl(path);
        brand.persist();

        ImageService.CleanupOutcome outcome = imageService.cleanupUnassociatedFile(path);

        assertFalse(outcome.deleted(), "Must refuse to delete an image still referenced by a brand logo");
        assertNotNull(outcome.reason());
        assertTrue(outcome.reason().toLowerCase().contains("brand"), "Reason should name the brand reference: " + outcome.reason());
    }

    @Test
    @TestTransaction
    void refusesToDeleteFileReferencedByCategoryImage()
    {
        String path = uniquePath("category-image");
        CategoryEntity category = new CategoryEntity();
        category.setName("Cleanup Test Category " + UUID.randomUUID());
        category.setSlug("cleanup-test-category-" + UUID.randomUUID());
        category.setImageUrl(path);
        category.persist();

        ImageService.CleanupOutcome outcome = imageService.cleanupUnassociatedFile(path);

        assertFalse(outcome.deleted(), "Must refuse to delete an image still referenced by a category image");
        assertNotNull(outcome.reason());
        assertTrue(outcome.reason().toLowerCase().contains("categor"), "Reason should name the category reference: " + outcome.reason());
    }

    @Test
    @TestTransaction
    void refusesToDeleteFileEmbeddedInStoreSettingsValue()
    {
        // Storefront hero/branding/accreditor images live inside a JSON blob in
        // store_settings.setting_value, not a dedicated column — the path is a
        // substring of the value, not the whole value.
        String path = uniquePath("hero-banner");
        StoreSettingsEntity setting = new StoreSettingsEntity();
        setting.setKey("cleanup.test." + UUID.randomUUID());
        setting.setValue("{\"backgroundImageUrl\":\"" + path + "\"}");
        setting.persist();

        ImageService.CleanupOutcome outcome = imageService.cleanupUnassociatedFile(path);

        assertFalse(outcome.deleted(), "Must refuse to delete an image path embedded in a store settings value");
        assertNotNull(outcome.reason());
    }

    @Test
    @TestTransaction
    void refusesToDeleteFileEmbeddedInPageContent()
    {
        String path = uniquePath("legal-page-image");
        PageContentEntity page = new PageContentEntity();
        page.setSlug("cleanup-test-page-" + UUID.randomUUID());
        page.setTitle("Cleanup Test Page");
        page.setCategory("legal");
        page.setDraftContent("<p>See <img src=\"" + path + "\"/></p>");
        page.persist();

        ImageService.CleanupOutcome outcome = imageService.cleanupUnassociatedFile(path);

        assertFalse(outcome.deleted(), "Must refuse to delete an image path embedded in page content");
        assertNotNull(outcome.reason());
    }

    @Test
    @TestTransaction
    void succeedsWhenPathIsTrulyUnreferenced()
    {
        // Nothing references this path anywhere, and it was never physically written to
        // test storage either — this must still be treated as a successful, unblocked
        // delete rather than an ambiguous "false" that reads the same as "still in use".
        String path = uniquePath("orphan");

        ImageService.CleanupOutcome outcome = imageService.cleanupUnassociatedFile(path);

        assertTrue(outcome.deleted(), "An unreferenced path must succeed even if no physical file existed to remove: " + outcome.reason());
        assertNull(outcome.reason());
    }
}
