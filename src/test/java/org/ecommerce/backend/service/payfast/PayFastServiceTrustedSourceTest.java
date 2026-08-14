package org.ecommerce.backend.service.payfast;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayFastServiceTrustedSourceTest
{
    private PayFastService service;

    @BeforeEach
    void setUp()
    {
        service = new PayFastService();
        service.allowedIpRanges = "197.97.145.144/28,41.74.179.192/27,144.126.193.139";
    }

    @Test
    void acceptsIpInsideAConfiguredRange()
    {
        assertTrue(service.isTrustedSource("197.97.145.150"));
        assertTrue(service.isTrustedSource("41.74.179.200"));
    }

    @Test
    void acceptsExactMatchOnABareConfiguredIp()
    {
        assertTrue(service.isTrustedSource("144.126.193.139"));
    }

    @Test
    void rejectsIpOutsideEveryConfiguredRange()
    {
        assertFalse(service.isTrustedSource("8.8.8.8"));
        assertFalse(service.isTrustedSource("197.97.145.200"));
    }

    @Test
    void rejectsNullBlankOrMalformedIp()
    {
        assertFalse(service.isTrustedSource(null));
        assertFalse(service.isTrustedSource(""));
        assertFalse(service.isTrustedSource("not-an-ip"));
    }

    @Test
    void rejectsEverythingWhenNoRangesConfigured()
    {
        service.allowedIpRanges = "";
        assertFalse(service.isTrustedSource("197.97.145.150"));
    }
}
