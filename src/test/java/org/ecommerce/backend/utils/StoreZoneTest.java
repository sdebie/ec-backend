package org.ecommerce.backend.utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoreZoneTest
{
    @Test
    void blankIsNoBound()
    {
        assertNull(StoreZone.startOfDay(null, "fromDate"));
        assertNull(StoreZone.startOfDay("  ", "fromDate"));
        assertNull(StoreZone.exclusiveEndOfDay(null, "toDate"));
    }

    @Test
    void johannesburgMidnightIsTwoHoursBehindUtc()
    {
        // 2026-01-01 00:00 SAST = 2025-12-31 22:00 UTC
        assertEquals(Instant.parse("2025-12-31T22:00:00Z"), StoreZone.startOfDay("2026-01-01", "fromDate"));
        assertEquals(Instant.parse("2026-01-01T22:00:00Z"), StoreZone.exclusiveEndOfDay("2026-01-01", "toDate"));
    }

    @Test
    void rejectsAValueThatIsNotACalendarDay()
    {
        assertThrows(IllegalArgumentException.class,
                () -> StoreZone.startOfDay("2026-01-01T00:00:00Z", "fromDate"));
    }
}
