package org.ecommerce.backend.service.import_engine;

/**
 * Sage-specific extension of ImportStrategy for item imports.
 * Focuses on syncing Sage item data (SKU, names, descriptions, etc).
 */
public interface SageItemImportStrategy extends ImportStrategy {
    /**
     * Additional method for handling Sage pagination.
     * Sage API may return data in pages; this handles fetching all pages.
     */
    default boolean supportsPagedFetch() {
        return true;
    }
}
