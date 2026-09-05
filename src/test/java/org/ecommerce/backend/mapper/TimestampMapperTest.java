package org.ecommerce.backend.mapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimestampMapperTest
{
    private final TimestampMapper mapper = new TimestampMapper() {};

    @Test
    void nullMapsToNull()
    {
        assertNull(mapper.map((Instant) null));
    }

    @Test
    void wholeSecondsAreRfc3339WithZ()
    {
        assertEquals("2026-08-15T11:45:00Z", mapper.map(Instant.parse("2026-08-15T11:45:00Z")));
    }

    @Test
    void trailingZNeverANumericOffset()
    {
        String result = mapper.map(Instant.parse("2026-08-15T11:45:00Z"));
        assertTrue(result.endsWith("Z"), "expected a trailing Z, got: " + result);
    }
}
