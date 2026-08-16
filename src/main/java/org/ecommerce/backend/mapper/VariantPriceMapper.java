package org.ecommerce.backend.mapper;

import org.ecommerce.backend.utils.PriceUtils;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Single owner of the entity → {@link VariantPriceDto} mapping.
 * <p>
 * Every surface that renders a price tier maps through here — catalogue list items and
 * wishlist hydration alike — so a change cannot reach one surface and miss the other.
 * The sale-countdown rule itself lives in {@link PriceUtils}, shared with
 * {@link ProductMapper}, because that mapper derives it from its own clock.
 * <p>
 * {@code now} is passed rather than read from an internal {@code LocalDateTime.now()} so a
 * page of prices is measured against one instant, and so tests can pin the clock.
 * <p>
 * It must stay {@code @Context}, not a second source parameter. With two source parameters
 * MapStruct generates {@code if (price == null && now == null) return null} — an {@code &&}
 * that lets a null price through to be dereferenced. A missing price tier is the normal case
 * here, so that guard would NPE on any variant without all four tiers.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, imports = PriceUtils.class,
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface VariantPriceMapper
{
    @Mapping(target = "isActive", constant = "true")
    @Mapping(target = "saleDaysRemaining", expression = "java(PriceUtils.saleDaysRemaining(price.getPriceType(), price.getPriceEndDate(), now))")
    VariantPriceDto toDto(VariantPricesEntity price, @Context LocalDateTime now);
}
