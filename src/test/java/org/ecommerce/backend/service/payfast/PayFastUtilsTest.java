package org.ecommerce.backend.service.payfast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayFastUtilsTest
{
    @Test
    void extractSignature_findsSignatureRegardlessOfPosition()
    {
        assertEquals("abc123", PayFastUtils.extractSignature("m_payment_id=1&signature=abc123&payment_status=COMPLETE"));
        assertEquals("abc123", PayFastUtils.extractSignature("signature=abc123&m_payment_id=1"));
    }

    @Test
    void extractSignature_lastOccurrenceWinsOnDuplicateField()
    {
        assertEquals("second", PayFastUtils.extractSignature("signature=first&signature=second"));
    }

    @Test
    void extractSignature_returnsNullWhenAbsentOrBodyNull()
    {
        assertNull(PayFastUtils.extractSignature("m_payment_id=1&payment_status=COMPLETE"));
        assertNull(PayFastUtils.extractSignature(null));
    }

    @Test
    void buildParamString_stripsSignatureButKeepsPostedOrderAndEncoding()
    {
        String rawBody = "m_payment_id=1&amount_gross=100.00&signature=abc123&payment_status=COMPLETE";
        assertEquals("m_payment_id=1&amount_gross=100.00&payment_status=COMPLETE",
                PayFastUtils.buildParamString(rawBody));
    }

    @Test
    void buildParamString_dropsEveryDuplicateSignaturePair()
    {
        assertEquals("m_payment_id=1", PayFastUtils.buildParamString("signature=first&m_payment_id=1&signature=second"));
    }

    @Test
    void ipMatchesCidr_matchesWithinPublishedPayFastRange()
    {
        // 197.97.145.144/28 -> 197.97.145.144 - 197.97.145.159
        assertTrue(PayFastUtils.ipMatchesCidr("197.97.145.144", "197.97.145.144/28"), "network address itself");
        assertTrue(PayFastUtils.ipMatchesCidr("197.97.145.150", "197.97.145.144/28"), "mid-range address");
        assertTrue(PayFastUtils.ipMatchesCidr("197.97.145.159", "197.97.145.144/28"), "broadcast address");
    }

    @Test
    void ipMatchesCidr_rejectsJustOutsideThePublishedRange()
    {
        assertFalse(PayFastUtils.ipMatchesCidr("197.97.145.143", "197.97.145.144/28"));
        assertFalse(PayFastUtils.ipMatchesCidr("197.97.145.160", "197.97.145.144/28"));
    }

    @Test
    void ipMatchesCidr_bareIpIsTreatedAsSlash32()
    {
        assertTrue(PayFastUtils.ipMatchesCidr("144.126.193.139", "144.126.193.139"));
        assertFalse(PayFastUtils.ipMatchesCidr("144.126.193.140", "144.126.193.139"));
    }

    @Test
    void ipMatchesCidr_failsClosedOnMalformedInput()
    {
        assertFalse(PayFastUtils.ipMatchesCidr("not-an-ip", "197.97.145.144/28"));
        assertFalse(PayFastUtils.ipMatchesCidr("::1", "197.97.145.144/28"), "IPv6 is out of scope, must not match");
        assertFalse(PayFastUtils.ipMatchesCidr("", "197.97.145.144/28"));
        assertFalse(PayFastUtils.ipMatchesCidr(null, "197.97.145.144/28"));
        assertFalse(PayFastUtils.ipMatchesCidr("197.97.145.150", "not-a-cidr"));
    }
}
