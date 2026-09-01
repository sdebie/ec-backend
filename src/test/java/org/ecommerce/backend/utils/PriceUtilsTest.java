package org.ecommerce.backend.utils;

import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class PriceUtilsTest
{

    @Test
    void currentPrice_shouldReturnZeroWhenVariantIdOrPriceTypeIsMissing()
    {
        assertEquals(BigDecimal.ZERO, PriceUtils.currentPrice(null, PriceTypeEn.RETAIL_PRICE));
        assertEquals(BigDecimal.ZERO, PriceUtils.currentPrice(List.of(), null));
    }

    @Test
    void currentPrice_shouldReturnLatestActivePriceWithinDateWindow()
    {
        LocalDateTime now = LocalDateTime.now();

        VariantPricesEntity expiredPrice = price(
                PriceTypeEn.RETAIL_PRICE,
                "14.99",
                now.minusDays(10),
                now.minusDays(1),
                now.minusDays(10),
                now.minusDays(10));

        VariantPricesEntity futurePrice = price(
                PriceTypeEn.RETAIL_PRICE,
                "39.99",
                now.plusHours(2),
                now.plusDays(2),
                now.minusHours(1),
                now.minusHours(1));

        VariantPricesEntity wholesalePrice = price(
                PriceTypeEn.WHOLESALE_PRICE,
                "18.99",
                now.minusDays(2),
                now.plusDays(2),
                now.minusDays(2),
                now.minusDays(2));

        VariantPricesEntity olderActivePrice = price(
                PriceTypeEn.RETAIL_PRICE,
                "19.99",
                now.minusDays(5),
                now.plusDays(5),
                now.minusDays(5),
                now.minusDays(5));

        VariantPricesEntity latestActivePrice = price(
                PriceTypeEn.RETAIL_PRICE,
                "24.99",
                now.minusHours(1),
                now.plusDays(1),
                now.minusHours(1),
                now.minusMinutes(30));

        List<VariantPricesEntity> prices = List.of(
                expiredPrice,
                futurePrice,
                wholesalePrice,
                olderActivePrice,
                latestActivePrice);

        assertEquals(new BigDecimal("24.99"), PriceUtils.currentPrice(prices, PriceTypeEn.RETAIL_PRICE));
    }

    @Test
    void currentPrice_shouldUseRecencyTieBreakersWhenStartDatesMatch()
    {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startDate = now.minusDays(1);
        LocalDateTime endDate = now.plusDays(1);

        VariantPricesEntity firstPrice = price(
                PriceTypeEn.RETAIL_PRICE,
                "25.00",
                startDate,
                endDate,
                now.minusDays(2),
                now.minusHours(2));

        VariantPricesEntity laterUpdatedPrice = price(
                PriceTypeEn.RETAIL_PRICE,
                "27.50",
                startDate,
                endDate,
                now.minusDays(2),
                now.minusMinutes(10));

        List<VariantPricesEntity> prices = List.of(firstPrice, laterUpdatedPrice);

        assertEquals(new BigDecimal("27.50"), PriceUtils.currentPrice(prices, PriceTypeEn.RETAIL_PRICE));
    }

    private VariantPricesEntity price(
            PriceTypeEn priceType,
            String amount,
            LocalDateTime startDate,
            LocalDateTime endDate,
            LocalDateTime createdAt,
            LocalDateTime updatedAt)
    {
        VariantPricesEntity entity = new VariantPricesEntity();
        entity.setId(UUID.randomUUID());
        entity.setPriceType(priceType);
        entity.setPrice(new BigDecimal(amount));
        entity.setPriceStartDate(startDate);
        entity.setPriceEndDate(endDate);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);
        return entity;
    }
}

