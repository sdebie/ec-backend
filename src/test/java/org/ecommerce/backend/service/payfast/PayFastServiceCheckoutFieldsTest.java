package org.ecommerce.backend.service.payfast;

import org.ecommerce.common.entity.OrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayFastServiceCheckoutFieldsTest
{
    private static final String PASSPHRASE = "test-passphrase";

    private PayFastService service;

    @BeforeEach
    void setUp()
    {
        service = new PayFastService();
        service.merchantId = "10000100";
        service.merchantKey = "merchant-key";
        service.passphrase = PASSPHRASE;
        service.notifyUrl = "https://example.test/api/payments/itn";
        service.returnUrl = "https://example.test/payment-success";
        service.cancelUrl = "https://example.test/payment-cancelled";
    }

    private static OrderEntity order()
    {
        OrderEntity order = new OrderEntity();
        order.setId(UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("1000.00"));
        return order;
    }

    @Test
    void checkoutFieldsNeverIncludeThePassphrase()
    {
        List<HtmlFormField> fields = service.generateHiddenHTMLForm(order(), "guest@example.com");

        assertTrue(fields.stream().noneMatch(f -> "passphrase".equals(f.name())),
                "the merchant passphrase must never be returned to the client");
    }

    @Test
    void checkoutFieldsStillCarryTheOtherPayfastParameters()
    {
        List<HtmlFormField> fields = service.generateHiddenHTMLForm(order(), "guest@example.com");
        List<String> names = fields.stream().map(HtmlFormField::name).toList();

        assertTrue(names.containsAll(List.of(
                "merchant_id", "merchant_key", "return_url", "cancel_url", "notify_url",
                "amount", "m_payment_id", "item_name", "email_address", "payment_method", "signature"
        )));
    }

    @Test
    void signatureStillFoldsInThePassphraseEvenThoughTheFieldIsStripped()
    {
        OrderEntity order = order();

        String signatureWithPassphrase = signatureOf(service.generateHiddenHTMLForm(order, "guest@example.com"));

        service.passphrase = "";
        String signatureWithoutPassphrase = signatureOf(service.generateHiddenHTMLForm(order, "guest@example.com"));

        assertNotEquals(signatureWithPassphrase, signatureWithoutPassphrase,
                "stripping the passphrase field must not also drop it from the signed string");
    }

    private static String signatureOf(List<HtmlFormField> fields)
    {
        return fields.stream()
                .filter(f -> "signature".equals(f.name()))
                .map(f -> String.valueOf(f.value()))
                .findFirst()
                .orElseThrow();
    }
}
