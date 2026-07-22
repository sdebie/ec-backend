package org.ecommerce.backend.service;

// Feature: wholesale-application-review-workflow, Property 4: No hardcoded sender or client identity
// Validates: Requirements 4.3, 4.5, 4.6, 5.1, 5.3

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level regression guards: ensure the service layer and email template contain
 * NO hardcoded personal addresses, client-specific strings, or placeholder junk.
 * <p>
 * These are plain file-content checks — they read source files and assert with
 * string contains. They are NOT integration tests; they guard against drift in the
 * committed source.
 * <p>
 * Feature: wholesale-application-review-workflow
 * Property 4: No hardcoded sender or client identity
 * Validates: Requirements 4.3, 4.5, 4.6, 5.1, 5.3
 */
@DisplayName("WholesaleMailSourceGuardTest — no hardcoded sender/identity in source")
class WholesaleMailSourceGuardTest
{
    /**
     * Resolve project root by walking up from the test class's compiled location.
     * The test runs from ec-backend/ so we resolve relative to that module root.
     */
    private static Path resolveModuleRoot()
    {
        // When running via Maven Surefire, the working directory is the module root (ec-backend/)
        Path cwd = Path.of(System.getProperty("user.dir"));
        // If we're already in ec-backend, use it; otherwise try to find it
        if (cwd.getFileName().toString().equals("ec-backend")) {
            return cwd;
        }
        // Fallback: look for ec-backend as a subdirectory
        Path ecBackend = cwd.resolve("ec-backend");
        if (Files.isDirectory(ecBackend)) {
            return ecBackend;
        }
        // Last resort: use cwd and hope for the best
        return cwd;
    }

    private static final Path MODULE_ROOT = resolveModuleRoot();

    // ─── Service layer: no hardcoded personal address / .from( literal ─────

    @Nested
    @DisplayName("WholesaleCustomerService.java — no hardcoded sender patterns")
    class ServiceLayerGuards
    {

        private final Path serviceFile = MODULE_ROOT.resolve(
                "src/main/java/org/ecommerce/backend/service/WholesaleCustomerService.java");

        @Test
        @DisplayName("service file exists and is readable")
        void serviceFileExists()
        {
            assertTrue(Files.isReadable(serviceFile),
                    "WholesaleCustomerService.java must exist at: " + serviceFile);
        }

        @Test
        @DisplayName("no 'shawn.debie' (or any personal address fragment) in service layer")
        void noPersonalAddressInService() throws IOException
        {
            String content = Files.readString(serviceFile);
            assertFalse(content.toLowerCase().contains("shawn.debie"),
                    "WholesaleCustomerService must not contain 'shawn.debie' (hardcoded personal address)");
        }

        @Test
        @DisplayName("no hardcoded .from( literal in service layer")
        void noHardcodedFromLiteralInService() throws IOException
        {
            String content = Files.readString(serviceFile);
            // Check for .from("...") pattern — a hardcoded from-address literal
            // We look for .from( followed by a quote (indicating a hardcoded string)
            assertFalse(content.matches("(?s).*\\.from\\(\\s*\"[^\"]+\"\\s*\\).*"),
                    "WholesaleCustomerService must not contain .from(\"...\") — " +
                            "from-address must come from injected config, not a literal");
        }
    }

    // ─── Template: no placeholder strings ───────────────────────────────────

    @Nested
    @DisplayName("wholesale_status.html — no placeholder/client-specific strings")
    class TemplateGuards
    {

        private final Path templateFile = MODULE_ROOT.resolve(
                "src/main/resources/templates/wholesale_status.html");

        @Test
        @DisplayName("template file exists and is readable")
        void templateFileExists()
        {
            assertTrue(Files.isReadable(templateFile),
                    "wholesale_status.html must exist at: " + templateFile);
        }

        @Test
        @DisplayName("no 'yourstore.com' in template")
        void noYourstoreDotCom() throws IOException
        {
            String content = Files.readString(templateFile);
            assertFalse(content.toLowerCase().contains("yourstore.com"),
                    "wholesale_status.html must not contain 'yourstore.com' (placeholder string)");
        }

        @Test
        @DisplayName("no 'Your Store Name' in template")
        void noYourStoreName() throws IOException
        {
            String content = Files.readString(templateFile);
            assertFalse(content.contains("Your Store Name"),
                    "wholesale_status.html must not contain 'Your Store Name' (placeholder string)");
        }

        @Test
        @DisplayName("no '123 Fashion Ave' in template")
        void noFashionAve() throws IOException
        {
            String content = Files.readString(templateFile);
            assertFalse(content.contains("123 Fashion Ave"),
                    "wholesale_status.html must not contain '123 Fashion Ave' (placeholder address)");
        }
    }
}
