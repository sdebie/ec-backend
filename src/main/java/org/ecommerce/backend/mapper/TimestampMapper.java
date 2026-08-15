package org.ecommerce.backend.mapper;

import org.mapstruct.Mapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * The one timestamp → wire-string conversion, shared via {@code uses} by every mapper that
 * emits a timestamp as text.
 * <p>
 * Pins {@code toString()} rather than MapStruct's built-in conversion. The built-in uses
 * {@code ISO_LOCAL_DATE_TIME.format()}, which always emits seconds (…T12:00:00), where
 * {@code toString()} omits them when they are zero (…T12:00) — a different string for the
 * same instant, and the clients already parse the shorter form.
 * <p>
 * Declaring this per-mapper instead makes any mapper that {@code uses} another one fail to
 * compile with "ambiguous mapping methods", so it lives here once.
 */
@Mapper(componentModel = "cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface TimestampMapper
{
    default String map(LocalDateTime value)
    {
        return value == null ? null : value.toString();
    }

    default String map(OffsetDateTime value)
    {
        return value == null ? null : value.toString();
    }
}
