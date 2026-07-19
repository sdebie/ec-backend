package org.ecommerce.backend.service.payfast;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayFastServiceItnSignatureTest {

    private static final String PASSPHRASE = "testpayfast1";

    private PayFastService service;

    @BeforeEach
    void setUp() {
        service = new PayFastService();
        service.passphrase = PASSPHRASE;
    }

    private static String sign(String paramString) {
        return DigestUtils.md5Hex((paramString + "&passphrase=" + PASSPHRASE).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsBodyWithValidSignature() {
        String params = "m_payment_id=abc-123&pf_payment_id=999&payment_status=COMPLETE&amount_gross=100.00";
        String body = params + "&signature=" + sign(params);

        assertTrue(service.verifyItnSignature(body));
    }

    @Test
    void acceptsSignaturePairInPostedPositionMidBody() {
        String params = "m_payment_id=abc-123&payment_status=COMPLETE";
        String body = "m_payment_id=abc-123&signature=" + sign(params) + "&payment_status=COMPLETE";

        assertTrue(service.verifyItnSignature(body));
    }

    @Test
    void rejectsTamperedBody() {
        String params = "m_payment_id=abc-123&payment_status=PENDING&amount_gross=100.00";
        String tampered = "m_payment_id=abc-123&payment_status=COMPLETE&amount_gross=100.00";

        assertFalse(service.verifyItnSignature(tampered + "&signature=" + sign(params)));
    }

    @Test
    void rejectsMissingSignature() {
        assertFalse(service.verifyItnSignature("m_payment_id=abc-123&payment_status=COMPLETE"));
    }

    @Test
    void rejectsForgedSignature() {
        String params = "m_payment_id=abc-123&payment_status=COMPLETE";
        assertFalse(service.verifyItnSignature(params + "&signature=deadbeefdeadbeefdeadbeefdeadbeef"));
    }

    @Test
    void rejectsBlankBody() {
        assertFalse(service.verifyItnSignature(null));
        assertFalse(service.verifyItnSignature(""));
    }

    @Test
    void signatureWithoutPassphraseWhenNoneConfigured() {
        service.passphrase = "";
        String params = "m_payment_id=abc-123&payment_status=COMPLETE";
        String signature = DigestUtils.md5Hex(params.getBytes(StandardCharsets.UTF_8));

        assertTrue(service.verifyItnSignature(params + "&signature=" + signature));
    }
}
