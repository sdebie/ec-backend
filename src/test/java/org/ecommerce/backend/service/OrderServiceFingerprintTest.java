package org.ecommerce.backend.service;

import org.ecommerce.common.dto.OrderCreationItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins OrderService.fingerprint(List<OrderCreationItemDto>) per design §5: aggregate quantity
 * per variant, sort by variant id, join as id:qty|id:qty, SHA-256, hex.
 */
class OrderServiceFingerprintTest
{
    private static final String V1 = "11111111-1111-1111-1111-111111111111";
    private static final String V2 = "22222222-2222-2222-2222-222222222222";

    private static OrderCreationItemDto item(String variantId, Integer quantity)
    {
        OrderCreationItemDto dto = new OrderCreationItemDto();
        dto.setVariantId(variantId);
        dto.setQuantity(quantity);
        return dto;
    }

    @Test
    void reorderedLines_produceSameFingerprint()
    {
        // Jackson erases JSON key order before this DTO ever exists, so the only ordering
        // that can survive to this method is list order — a retry rebuilt from re-hydrated
        // client state could list the same two lines in either sequence. A raw-body SHA-256
        // (the recipe design §5 warns against) would fail this exact case, since two
        // submissions of the same cart are not guaranteed byte-identical.
        String a = OrderService.fingerprint(List.of(item(V1, 2), item(V2, 1)));
        String b = OrderService.fingerprint(List.of(item(V2, 1), item(V1, 2)));
        assertEquals(a, b);
    }

    @Test
    void splitLinesForSameVariant_matchOneAggregatedLine()
    {
        // A cart carrying two lines for the same variant must hash the same as one line
        // carrying the combined quantity — the aggregation Requirement 3.2 requires.
        String split = OrderService.fingerprint(List.of(item(V1, 2), item(V1, 3)));
        String combined = OrderService.fingerprint(List.of(item(V1, 5)));
        assertEquals(split, combined);
    }

    @Test
    void changedQuantity_producesDifferentFingerprint()
    {
        String original = OrderService.fingerprint(List.of(item(V1, 2)));
        String changed = OrderService.fingerprint(List.of(item(V1, 3)));
        assertNotEquals(original, changed);
    }

    @Test
    void changedVariant_producesDifferentFingerprint()
    {
        String original = OrderService.fingerprint(List.of(item(V1, 2)));
        String changed = OrderService.fingerprint(List.of(item(V2, 2)));
        assertNotEquals(original, changed);
    }

    @Test
    void nullVariantId_doesNotThrow()
    {
        // Must be total: the fast-path lookup (§3.1) hashes before cart validation, so a
        // throwing fingerprint turns a malformed retry into a 500 instead of a replay.
        assertDoesNotThrow(() -> OrderService.fingerprint(List.of(item(null, 1))));
    }

    @Test
    void nullQuantity_doesNotThrow()
    {
        assertDoesNotThrow(() -> OrderService.fingerprint(List.of(item(V1, null))));
    }

    @Test
    void nullVariantIdAndNullQuantity_stillDiffersFromValidCart()
    {
        // Total (never throws) is necessary but not sufficient — a malformed line must still
        // hash differently from a cart that never had it, or a genuinely different cart could
        // collide with one carrying a bad line.
        String malformed = OrderService.fingerprint(List.of(item(null, null)));
        String valid = OrderService.fingerprint(List.of(item(V1, 1)));
        assertNotEquals(malformed, valid);
    }
}
