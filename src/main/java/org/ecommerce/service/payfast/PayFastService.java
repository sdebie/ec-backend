package org.ecommerce.service.payfast;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.HtmlFormField;
import org.ecommerce.persistance.entity.CustomerEntity;
import org.ecommerce.persistance.entity.QuotationEntity;

import java.math.BigDecimal;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@ApplicationScoped
public class PayFastService {

    @ConfigProperty(name = "payfast.merchant-id") String merchantId;
    @ConfigProperty(name = "payfast.merchant-key") String merchantKey;
    @ConfigProperty(name = "payfast.passphrase") String passphrase;

    @ConfigProperty(name = "payfast.notify-url") String notifyUrl;
    @ConfigProperty(name = "payfast.return-url") String returnUrl;
    @ConfigProperty(name = "payfast.cancel-url") String cancelUrl;

    /*
    private volatile String lastSignatureBaseString;

    public String getLastSignatureBaseString() {
        return lastSignatureBaseString;
    }

    public String generateSignature(Map<String, String> data) {
        // Inject merchant credentials from configuration (override any provided values)
        TreeMap<String, String> input = new TreeMap<>(data);
        input.put("merchant_id", merchantId);
        input.put("merchant_key", merchantKey);

        input.put("return_url", returnUrl);
        input.put("cancel_url", cancelUrl);
        input.put("notify_url", notifyUrl);

        if (passphrase != null && !passphrase.isBlank()) {
            input.put("passphrase", passphrase.trim());
        }
        //new fixed-order requirement.
        String[] order = new String[]{
                "merchant_id",
                "merchant_key",
                "return_url",
                "cancel_url",
                "notify_url",
                "fica_idnumber",
                "name_first",
                "name_last",
                "email_address",
                "cell_number",
                "m_payment_id",
                "amount",
                "item_name",
                "item_description",
                "email_confirmation",
                "confirmation_address",
                "payment_method",
                "passphrase"
        };

        StringBuilder sb = new StringBuilder();
        for (String key : order) {
            String value = input.get(key);
            if (value == null)
                continue;
            value = value.trim();
            if (value.isBlank())
                continue;
            if (sb.length() > 0)
                sb.append('&');
            // URL-encode value ensuring percent-encodings use uppercase hex digits (Java URLEncoder already does this)
            String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
            sb.append(key).append('=').append(encoded);
        }

        String finalString = sb.toString();

        // Save the exact ordered base string so it can be reused for the POST body construction
        this.lastSignatureBaseString = finalString;

        // Generate the MD5 Hash
        System.out.println("DEBUG: String used for signature: " + finalString);
        String signature = DigestUtils.md5Hex(finalString.getBytes(StandardCharsets.UTF_8));
        System.out.println("DEBUG: Signature: " + signature);

        return signature;
        return null;
    }
     */

    /**
     * Verifies an incoming ITN signature.
     *
    public boolean verifySignature(Map<String, String> params) {
        String receivedSignature = params.get("signature");

        // Remove signature to recalculate
        TreeMap<String, String> dataToVerify = new TreeMap<>(params);
        dataToVerify.remove("signature");

        String calculatedSignature = generateSignature(dataToVerify);
        return calculatedSignature.equals(receivedSignature);
    }
     */

    public List<HtmlFormField> generateHiddenHTMLForm(QuotationEntity quote) {

        Map<String, String> data = new HashMap<>();

        // 0. Try to enrich from DB (to fetch customer email/name)
        QuotationEntity persisted = null;
        if (quote != null && quote.id != null) {
            //persisted = QuotationEntity.findById(quote.id);
            quote = new QuotationEntity();
            quote.id = 1L;
            quote.totalAmount = new BigDecimal("100.00");
            CustomerEntity customer = new CustomerEntity();
            customer.email = "anything123456%40gmail.com";
            customer.firstName = "John";
            customer.lastName = "Doe";
            quote.customerEntity = customer;
            persisted = quote;
        }

        TreeMap<String, String> input = new TreeMap<>(data);
        input.put("merchant_id", merchantId);
        input.put("merchant_key", merchantKey);

        input.put("return_url", returnUrl);
        input.put("cancel_url", cancelUrl);
        input.put("notify_url", notifyUrl);

        if (passphrase != null && !passphrase.isBlank()) {
            input.put("passphrase", passphrase.trim());
        }

        try {
            Map<String, String> sortedData = PayFastUtils.sortByPredefinedOrder(input);
            String joinedNameValuePair = PayFastUtils.concatenateNonEmptyNameValuePairs(sortedData);
            String signature = PayFastUtils.generateSecuritySignature(joinedNameValuePair);
            return buildFormElements(sortedData, signature);
        } catch (NoSuchAlgorithmException e) {
            System.out.println("Failed to create security signature" + e);
        }

        return Collections.emptyList();
    }

    private List<HtmlFormField> buildFormElements(Map<String, String> sortedData, String signature) {
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