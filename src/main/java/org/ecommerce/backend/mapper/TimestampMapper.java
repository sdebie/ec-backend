package org.ecommerce.backend.mapper;

import org.mapstruct.Mapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;
import static org.mapstruct.ReportingPolicy.ERROR;

/**
 * The one timestamp → wire-string conversion, shared via {@code uses} by every mapper that
 * emits a timestamp as text.
 * <p>
 * Always emits seconds — {@code 2026-08-15T11:45:00}, never {@code 2026-08-15T11:45}.
 * {@code toString()} drops zero seconds, which made the same instant serialise to two
 * different strings depending on its value and left the API emitting two formats side by
 * side. A fixed-width form is what a parser, a sort, and a diff all expect.
 * <p>
 * Declaring these per-mapper instead makes any mapper that {@code uses} another one fail to
 * compile with "ambiguous mapping methods", so they live here once.
 * <p>
 * ⚠️ These carry no zone, because most of the entities behind them are {@code LocalDateTime}
 * over {@code TIMESTAMP} columns. That is a storage-layer problem, not a formatting one: a
 * point-in-time event needs an instant, not a wall-clock reading. Until those fields move to
 * {@code Instant}/{@code TIMESTAMPTZ}, appending a {@code Z} here would claim a precision the
 * stored value does not have.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface TimestampMapper
{
    default String map(LocalDateTime value)
    {
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    default String map(OffsetDateTime value)
    {
        return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
