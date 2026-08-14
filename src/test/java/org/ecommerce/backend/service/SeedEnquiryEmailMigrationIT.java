package org.ecommerce.backend.service;

// Feature: contact-enquiry-form, Task 19: Migration test

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-backed integration test for V2.9.6__seed_storefront_contact_enquiry_email.sql.
 * <p>
 * Verifies the migration SQL logic against a real PostgreSQL database:
 * <ol>
 *   <li>After migration: {@code enquiryEmail} is present with the seeded value.</li>
 *   <li>Idempotent: re-running the migration is a no-op.</li>
 *   <li>Operator preservation: a pre-existing {@code enquiryEmail} is never overwritten.</li>
 * </ol>
 * <p>
 * Each test sets up its own precondition via native SQL inside a {@link TestTransaction}
 * (rolled back afterward), then executes the exact migration SQL and asserts outcomes.
 */
@QuarkusTest
@DisplayName("V2.9.6 — Seed storefront.contact.enquiryEmail")
class SeedEnquiryEmailMigrationIT
{
    @Inject
    EntityManager em;

    /**
     * The exact logic from V2.9.6__seed_storefront_contact_enquiry_email.sql.
     * Uses jsonb_exists() instead of the {@code ?} operator because Hibernate interprets
     * {@code ?} as a positional parameter placeholder.
     */
    private static final String MIGRATION_SQL = """
            UPDATE store_settings
            SET setting_value = (setting_value::jsonb || '{"enquiryEmail":"info@uvhholdings.co.za"}')::text
            WHERE setting_key = 'storefront.contact'
              AND NOT jsonb_exists(setting_value::jsonb, 'enquiryEmail')
            """;

    /**
     * Helper: upsert the storefront.contact row with the given JSON value.
     * Uses ON CONFLICT to handle both fresh and pre-existing rows.
     */
    private void upsertContact(String jsonValue)
    {
        em.createNativeQuery("""
                        INSERT INTO store_settings (setting_key, setting_value, description)
                        VALUES ('storefront.contact', ?1, 'test')
                        ON CONFLICT (setting_key) DO UPDATE SET setting_value = ?2
                        """)
                .setParameter(1, jsonValue)
                .setParameter(2, jsonValue)
                .executeUpdate();
        em.flush();
    }

    /**
     * Helper: read the current setting_value for storefront.contact.
     */
    private String readContactValue()
    {
        return (String) em.createNativeQuery(
                        "SELECT setting_value FROM store_settings WHERE setting_key = 'storefront.contact'")
                .getSingleResult();
    }

    // ── Test 1: enquiryEmail is present after migration ─────────────────────

    @Test
    @TestTransaction
    @DisplayName("enquiryEmail is seeded when absent")
    void enquiryEmailPresentAfterMigration()
    {
        // Precondition: storefront.contact exists without enquiryEmail
        String baseJson = "{\"emails\":[\"info@uvhholdings.co.za\"],\"phones\":[\"+27 76 819 5245\"]}";
        upsertContact(baseJson);

        // Act: run migration
        em.createNativeQuery(MIGRATION_SQL).executeUpdate();
        em.flush();

        // Assert: enquiryEmail is now present with the expected value
        String result = readContactValue();
        assertTrue(result.contains("\"enquiryEmail\""), "enquiryEmail key must be present after migration");
        assertTrue(result.contains("\"info@uvhholdings.co.za\""), "enquiryEmail must have the seeded value");

        // Verify it's valid JSON with the expected field via jsonb extraction
        String extracted = (String) em.createNativeQuery(
                        "SELECT setting_value::jsonb->>'enquiryEmail' FROM store_settings WHERE setting_key = 'storefront.contact'")
                .getSingleResult();
        assertEquals("info@uvhholdings.co.za", extracted);
    }

    // ── Test 2: re-run is a no-op (idempotent) ──────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("re-run is a no-op (idempotent)")
    void reRunIsNoOp()
    {
        // Precondition: storefront.contact exists without enquiryEmail
        String baseJson = "{\"emails\":[\"info@uvhholdings.co.za\"],\"phones\":[\"+27 76 819 5245\"]}";
        upsertContact(baseJson);

        // Act: run migration twice
        em.createNativeQuery(MIGRATION_SQL).executeUpdate();
        em.flush();
        String afterFirst = readContactValue();

        em.createNativeQuery(MIGRATION_SQL).executeUpdate();
        em.flush();
        String afterSecond = readContactValue();

        // Assert: no change on second run
        assertEquals(afterFirst, afterSecond, "Second run must not alter the value (idempotent)");
    }

    // ── Test 3: operator-edited value is preserved ──────────────────────────

    @Test
    @TestTransaction
    @DisplayName("pre-existing operator enquiryEmail is preserved")
    void operatorEditedValuePreserved()
    {
        // Precondition: storefront.contact already has an operator-set enquiryEmail
        String operatorJson = "{\"emails\":[\"info@uvhholdings.co.za\"],\"enquiryEmail\":\"custom@operator.co.za\"}";
        upsertContact(operatorJson);

        // Act: run migration
        em.createNativeQuery(MIGRATION_SQL).executeUpdate();
        em.flush();

        // Assert: operator value is preserved, NOT overwritten
        String extracted = (String) em.createNativeQuery(
                        "SELECT setting_value::jsonb->>'enquiryEmail' FROM store_settings WHERE setting_key = 'storefront.contact'")
                .getSingleResult();
        assertEquals("custom@operator.co.za", extracted,
                "Migration must NOT overwrite an operator-configured enquiryEmail");
    }
}
