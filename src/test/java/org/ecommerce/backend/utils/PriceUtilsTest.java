package org.ecommerce.backend.utils;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@QuarkusTest
class PriceUtilsTest {

    @BeforeEach
    void setUp() {
        PanacheMock.mock(VariantPricesEntity.class);
    }

    @Test
    void getMinimumPrice_shouldReturnZeroWhenVariantIdOrPriceTypeIsMissing() {
        assertEquals(BigDecimal.ZERO, PriceUtils.getMinimumPrice(null, PriceTypeEn.RETAIL_PRICE));
        assertEquals(BigDecimal.ZERO, PriceUtils.getMinimumPrice(UUID.randomUUID(), null));
    }

    @Test
    void getMinimumPrice_shouldReturnLatestActivePriceWithinDateWindow() {
        UUID variantId = UUID.randomUUID();
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

        when(VariantPricesEntity.findByVariantId(variantId)).thenReturn(List.of(
                expiredPrice,
                futurePrice,
                wholesalePrice,
                olderActivePrice,
                latestActivePrice));

        assertEquals(new BigDecimal("24.99"), PriceUtils.getMinimumPrice(variantId, PriceTypeEn.RETAIL_PRICE));
    }

    @Test
    void getMinimumPrice_shouldUseRecencyTieBreakersWhenStartDatesMatch() {
        UUID variantId = UUID.randomUUID();
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

        when(VariantPricesEntity.findByVariantId(variantId)).thenReturn(List.of(firstPrice, laterUpdatedPrice));

        assertEquals(new BigDecimal("27.50"), PriceUtils.getMinimumPrice(variantId, PriceTypeEn.RETAIL_PRICE));
    }

    private VariantPricesEntity price(
            PriceTypeEn priceType,
            String amount,
            LocalDateTime startDate,
            LocalDateTime endDate,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        VariantPricesEntity entity = new VariantPricesEntity();
        entity.id = UUID.randomUUID();
        entity.priceType = priceType;
        entity.price = new BigDecimal(amount);
        entity.priceStartDate = startDate;
        entity.priceEndDate = endDate;
        entity.createdAt = createdAt;
        entity.updatedAt = updatedAt;
        return entity;
    }
}

