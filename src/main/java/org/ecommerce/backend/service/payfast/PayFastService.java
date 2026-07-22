package org.ecommerce.backend.service.payfast;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.OrderEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@ApplicationScoped
public class PayFastService
{
    @ConfigProperty(name = "payfast.merchant-id")
    String merchantId;
    @ConfigProperty(name = "payfast.merchant-key")
    String merchantKey;
    @ConfigProperty(name = "payfast.passphrase")
    String passphrase;

    @ConfigProperty(name = "payfast.notify-url")
    String notifyUrl;
    @ConfigProperty(name = "payfast.return-url")
    String returnUrl;
    @ConfigProperty(name = "payfast.cancel-url")
    String cancelUrl;

    /**
     * Verifies the signature of an incoming ITN callback against the raw
     * form-urlencoded request body.
     * <p>
     * PayFast signs the parameters in the order they were posted, so the raw body
     * (not a re-sorted map) is the source of truth: the {@code signature} pair is
     * removed, the passphrase is appended, and the MD5 of the result must match
     * the received signature.
     */
    public boolean verifyItnSignature(String rawBody)
    {
        if (rawBody == null || rawBody.isBlank()) {
            return false;
        }

        String receivedSignature = null;
        StringJoiner baseString = new StringJoiner("&");
        for (String pair : rawBody.split("&")) {
            if (pair.startsWith("signature=")) {
                receivedSignature = pair.substring("signature=".length());
            } else if (!pair.isBlank()) {
                baseString.add(pair);
            }
        }
        if (receivedSignature == null || receivedSignature.isBlank()) {
            return false;
        }

        if (passphrase != null && !passphrase.isBlank()) {
            baseString.add("passphrase=" + URLEncoder.encode(passphrase.trim(), StandardCharsets.UTF_8));
        }

        String calculatedSignature = DigestUtils.md5Hex(baseString.toString().getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(
                calculatedSignature.getBytes(StandardCharsets.UTF_8),
                receivedSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    public List<HtmlFormField> generateHiddenHTMLForm(OrderEntity quote)
    {
        String email = (quote.getCustomerEntity() != null && quote.getCustomerEntity().getUser() != null)
                ? quote.getCustomerEntity().getUser().getEmail() : "";
        return generateHiddenHTMLForm(quote, email);
    }

    public List<HtmlFormField> generateHiddenHTMLForm(OrderEntity quote, String email)
    {
        TreeMap<String, String> stringTreeMap = getStringTreeMap(quote, email);
        Map<String, String> sortedData = PayFastUtils.sortByPredefinedOrder(stringTreeMap);
        String joinedNameValuePair = PayFastUtils.concatenateNonEmptyNameValuePairs(sortedData);
        String signature = PayFastUtils.generateSecuritySignature(joinedNameValuePair);
        return buildFormElements(sortedData, signature);
    }

    private TreeMap<String, String> getStringTreeMap(OrderEntity quote, String email)
    {

        TreeMap<String, String> input = new TreeMap<>();
        input.put("merchant_id", merchantId);
        input.put("merchant_key", merchantKey);

        input.put("return_url", returnUrl);
        input.put("cancel_url", cancelUrl);
        input.put("notify_url", notifyUrl);

        input.put("amount", quote.getTotalAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        input.put("m_payment_id", quote.getId().toString());
        input.put("item_name", quote.getId().toString());
        input.put("email_address", email != null ? email : "");

        input.put("payment_method", "dc");

        if (passphrase != null && !passphrase.isBlank()) {
            input.put("passphrase", passphrase.trim());
        }
        return input;
    }

    private List<HtmlFormField> buildFormElements(Map<String, String> sortedData, String signature)
    {
        sortedData.put("signature", signature);
        List<HtmlFormField> htmlFormElements = new ArrayList<>();
        for (Map.Entry<String, String> element : sortedData.entrySet()) {
            if (null != element.getValue()) {
                HtmlFormField formElement = new HtmlFormField(
                        element.getKey(),
                        "hidden",
                        element.getValue()
                );

                htmlFormElements.add(formElement);
            }
        }

        return htmlFormElements;
    }
}
