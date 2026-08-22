package org.ecommerce.backend.service;

/**
 * What {@link ProductService#deleteProduct} actually did. Order history is the
 * only bar to physical deletion — a product whose variants were never ordered is
 * hard-deleted; one with order references is archived instead, so orders keep
 * their variant rows. The caller cannot tell the two apart from a void return.
 */
public enum ProductDeletionOutcome
{
    /** Physically removed — the product and all its variants are gone. */
    DELETED,

    /** Kept, but disabled: the product and its ACTIVE variants moved to DISABLED. */
    ARCHIVED
}
