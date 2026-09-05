package org.ecommerce.backend.mapper;

import org.mapstruct.Mapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;
import static org.mapstruct.ReportingPolicy.ERROR;

/**
 * Instant → RFC 3339 UTC string, shared via {@code uses} by every mapper that emits a
 * timestamp as text. Always {@code 2026-08-15T11:45:00Z} — {@code T}, seconds, trailing
 * {@code Z}. Declaring this per-mapper instead makes any mapper that {@code uses} another
 * one fail to compile with "ambiguous mapping methods".
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface TimestampMapper
{
    default String map(Instant value)
    {
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value);
    }
}
