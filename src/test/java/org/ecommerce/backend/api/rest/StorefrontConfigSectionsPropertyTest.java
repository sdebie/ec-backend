package org.ecommerce.backend.api.rest;

// Feature: about-page, Property 1: Enabled filtering invariant
// Feature: about-page, Property 2: Malformed input fallback

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.jqwik.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for the real StorefrontConfigResource.applySections()
 * assembly seam.
 *
 */
class StorefrontConfigSectionsPropertyTest
{

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StorefrontConfigResource resource()
    {
        StorefrontConfigResource resource = new StorefrontConfigResource();
        resource.objectMapper = objectMapper;
        return resource;
    }

    private JsonNode parseJson(String raw)
    {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Feature: about-page, Property 1: Enabled filtering invariant
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * For any JSON array of section objects with arbitrary `enabled` values
     * (true, false, or absent), applySections SHALL produce an output array
     * containing only those sections where `enabled` is not explicitly `false`,
     * and no output section SHALL contain the `enabled` field.
     *
     */
    @Property(tries = 150)
    void enabledFilteringInvariant(
            @ForAll("sectionArrays") ArrayNode inputSections
    )
    {
        // Arrange: place the input array in a settings map
        Map<String, JsonNode> settings = new HashMap<>();
        settings.put("storefront.about_sections", inputSections);

        ObjectNode config = objectMapper.createObjectNode();

        // Act
        resource().applySections(config, settings, "storefront.about_sections", "aboutSections");

        // Assert
        JsonNode output = config.get("aboutSections");
        assertNotNull(output);
        assertTrue(output.isArray(), "Output must be an array");

        int expectedCount = 0;
        for (JsonNode s : inputSections) {
            boolean isDisabled = s.has("enabled") && !s.get("enabled").asBoolean(true);
            if (!isDisabled) {
                expectedCount++;
            }
        }

        assertEquals(expectedCount, output.size(),
                "Output size must equal count of non-disabled sections");

        // Verify each output section:
        // 1. Does not contain the "enabled" field
        // 2. Corresponds to a non-disabled input section (in order)
        int outputIdx = 0;
        for (int i = 0; i < inputSections.size(); i++) {
            JsonNode inputSection = inputSections.get(i);
            boolean isDisabled = inputSection.has("enabled") && !inputSection.get("enabled").asBoolean(true);
            if (!isDisabled) {
                JsonNode outputSection = output.get(outputIdx);
                assertFalse(outputSection.has("enabled"),
                        "Output section must not contain 'enabled' field");

                // Verify the section identity is preserved (id field if present)
                if (inputSection.has("id")) {
                    assertEquals(inputSection.get("id").asText(), outputSection.get("id").asText(),
                            "Section id must be preserved in output");
                }
                outputIdx++;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Feature: about-page, Property 2: Malformed input fallback
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * For any setting value that is not a valid JSON array (including absent row,
     * null, empty string, non-array JSON, or malformed JSON), applySections SHALL
     * produce an empty array and all other fields in the config response SHALL
     * remain unchanged.
     *
     */
    @Property(tries = 150)
    void malformedInputFallback(
            @ForAll("nonArrayValues") String malformedValue
    )
    {
        // Arrange: put a pre-existing field on config to verify it's preserved
        ObjectNode config = objectMapper.createObjectNode();
        config.put("clientId", "test-client");
        config.put("clientName", "Test Store");

        // Parse the malformed value the same way StorefrontConfigResource does
        Map<String, JsonNode> settings = new HashMap<>();
        JsonNode parsed = parseJson(malformedValue);
        settings.put("storefront.about_sections", parsed);

        // Act
        resource().applySections(config, settings, "storefront.about_sections", "aboutSections");

        // Assert: aboutSections is an empty array
        JsonNode output = config.get("aboutSections");
        assertNotNull(output, "aboutSections must be present");
        assertTrue(output.isArray(), "aboutSections must be an array");
        assertEquals(0, output.size(), "aboutSections must be empty for non-array input");

        // Assert: other config fields are unchanged
        assertEquals("test-client", config.get("clientId").asText());
        assertEquals("Test Store", config.get("clientName").asText());
    }

    /**
     * Additional case: when the setting key is entirely absent from the map,
     * applySections SHALL produce an empty array.
     *
     */
    @Property(tries = 100)
    void absentKeyProducesEmptyArray(
            @ForAll("randomFieldNames") String outputField
    )
    {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("existingField", "preserved");

        // Empty settings map — key is absent
        Map<String, JsonNode> settings = new HashMap<>();

        // Act
        resource().applySections(config, settings, "storefront.about_sections", outputField);

        // Assert
        JsonNode output = config.get(outputField);
        assertNotNull(output);
        assertTrue(output.isArray());
        assertEquals(0, output.size());

        // Other fields preserved
        assertEquals("preserved", config.get("existingField").asText());
    }

    // ── Generators ────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<ArrayNode> sectionArrays()
    {
        // Generate arrays of 0–10 section objects with mixed enabled states
        Arbitrary<ObjectNode> sectionArb = Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10),  // id
                Arbitraries.of("hero", "stats", "cta", "benefits", "content-split", "brands"),  // type
                Arbitraries.of("true", "false", "absent")  // enabled state
        ).as((id, type, enabledState) -> {
            ObjectNode section = objectMapper.createObjectNode();
            section.put("id", id);
            section.put("type", type);
            switch (enabledState) {
                case "true" -> section.put("enabled", true);
                case "false" -> section.put("enabled", false);
                // "absent" → no enabled field
            }
            ObjectNode props = objectMapper.createObjectNode();
            props.put("title", "Section " + id);
            section.set("props", props);
            return section;
        });

        return sectionArb.list().ofMinSize(0).ofMaxSize(10)
                .map(sections -> {
                    ArrayNode arr = objectMapper.createArrayNode();
                    sections.forEach(arr::add);
                    return arr;
                });
    }

    @Provide
    Arbitrary<String> nonArrayValues()
    {
        return Arbitraries.oneOf(
                // Invalid JSON strings
                Arbitraries.of(
                        "NOT JSON",
                        "{not: valid}",
                        "{{{{",
                        "[[[invalid",
                        "",
                        "null",
                        "undefined",
                        "12345",
                        "true",
                        "false"
                ),
                // Valid JSON but not arrays (objects, primitives)
                Arbitraries.of(
                        "{\"key\":\"value\"}",
                        "{\"sections\":[]}",
                        "\"a string\"",
                        "42",
                        "3.14",
                        "{}",
                        "{\"enabled\":true}"
                ),
                // Random garbage strings
                Arbitraries.strings().ofMinLength(1).ofMaxLength(50)
                        .filter(s -> {
                            // Filter out anything that IS a valid JSON array
                            try {
                                JsonNode node = objectMapper.readTree(s);
                                return !node.isArray();
                            } catch (Exception e) {
                                return true; // invalid JSON is fine
                            }
                        })
        );
    }

    @Provide
    Arbitrary<String> randomFieldNames()
    {
        return Arbitraries.of("aboutSections", "sections", "customField", "output");
    }
}
