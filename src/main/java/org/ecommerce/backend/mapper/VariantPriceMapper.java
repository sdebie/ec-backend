package org.ecommerce.backend.mapper;

import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.backend.utils.PriceUtils;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.VariantPricesEntity;

import java.time.LocalDateTime;

/**
 * Single owner of the entity → {@link VariantPriceDto} mapping, including the
 * sale-countdown rule.
 * <p>
 * Every surface that renders a price tier maps through here — catalogue list items and
 * wishlist hydration alike — so a change to the countdown rule cannot reach one surface
 * and miss the other.
 * <p>
 * Hand-written rather than MapStruct: the sale countdown is derived from the caller's
 * clock, which MapStruct can express only as an expression string that loses
 * compile-time checking and is harder to test directly. The countdown rule itself lives
 * in {@link PriceUtils}, shared with {@link ProductMapper}.
 */
@ApplicationScoped
public class VariantPriceMapper
{
    /**
     * Maps a price row to its wire shape.
     *
     * @param price the price row, may be {@code null} when the tier is unset
     * @param now   the clock the sale countdown is measured against
     * @return the mapped DTO, or {@code null} when {@code price} is {@code null}
     */
    public VariantPriceDto toDto(VariantPricesEntity price, LocalDateTime now)
    {
        if (price == null) {
            return null;
        }
        VariantPriceDto dto = new VariantPriceDto();
        dto.setId(price.getId() == null ? null : price.getId().toString());
        dto.setPriceType(price.getPriceType() == null ? null : price.getPriceType().name());
        dto.setPrice(price.getPrice());
        dto.setPriceStartDate(price.getPriceStartDate());
        dto.setPriceEndDate(price.getPriceEndDate());
        dto.setIsActive(Boolean.TRUE);
        dto.setSaleDaysRemaining(PriceUtils.saleDaysRemaining(price.getPriceType(), price.getPriceEndDate(), now));
        return dto;
    }

}
