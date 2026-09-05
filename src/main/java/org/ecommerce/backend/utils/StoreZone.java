package org.ecommerce.backend.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * This shop's wall clock ({@code Africa/Johannesburg}). Used only to turn an admin
 * calendar day ({@code yyyy-MM-dd}) into the {@link Instant} range a {@code TIMESTAMPTZ}
 * column is compared against — never to store a wall clock on an entity.
 */
public final class StoreZone
{
    public static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");

    private StoreZone()
    {
    }

    /** Inclusive lower bound: midnight at the start of {@code yyyyMmDd} in the store zone. */
    public static Instant startOfDay(String yyyyMmDd, String fieldName)
    {
        LocalDate day = parseDate(yyyyMmDd, fieldName);
        return day == null ? null : day.atStartOfDay(ZONE).toInstant();
    }

    /**
     * Exclusive upper bound: midnight at the start of the day after {@code yyyyMmDd}, so
     * every instant on that calendar day is {@code >= start} and {@code < end}.
     */
    public static Instant exclusiveEndOfDay(String yyyyMmDd, String fieldName)
    {
        LocalDate day = parseDate(yyyyMmDd, fieldName);
        return day == null ? null : day.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    private static LocalDate parseDate(String value, String fieldName)
    {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid " + fieldName + ": " + value + " (expected yyyy-MM-dd)");
        }
    }
}
