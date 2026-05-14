package org.ecommerce.backend.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility to convert additionalInfo objects/strings to JSON strings.
 * Automatically stringifies objects if they arrive as Maps/Objects from GraphQL input.
 */
public class JsonConverter {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Convert input (can be string or object) to a JSON string.
     * Returns "{}" if input is null.
     */
    public static String toJsonString(Object input) {
        if (input == null) {
            return "{}";
        }
        if (input instanceof String) {
            return (String) input;
        }
        // If it's an object (e.g., HashMap from GraphQL), serialize to JSON
        try {
            return mapper.writeValueAsString(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize additionalInfo to JSON: " + e.getMessage(), e);
        }
    }
}

