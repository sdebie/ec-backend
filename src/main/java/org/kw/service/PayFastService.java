package org.kw.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

@ApplicationScoped
public class PayFastService {

    @ConfigProperty(name = "payfast.merchant-id") String merchantId;
    @ConfigProperty(name = "payfast.merchant-key") String merchantKey;
    @ConfigProperty(name = "payfast.passphrase") String passphrase;

    // Holds the exact string used to compute the signature, preserving key order
    // Note: ApplicationScoped bean; concurrent requests may overwrite this value.
    // Callers should read it immediately after calling generateSignature.
    private volatile String lastSignatureBaseString;

    public String getLastSignatureBaseString() {
        return lastSignatureBaseString;
    }

    /**
     * Generates the MD5 signature required by PayFast.
     * IMPORTANT: Must follow the exact key order specified by business, not alphabetical.
     * Order:
     * merchant_id, merchant_key, return_url, cancel_url, notify_url, fica_idnumber,
     * name_first, name_last, email_address, cell_number, m_payment_id, amount,
     * item_name, item_description, email_confirmation, confirmation_address, payment_method
     * Notes:
     * - Do not URL-encode fully; only encode spaces as '+' for the signature base string.
     * - Skip null/blank values.
     * - Exclude any keys not in the list above.
     * - Do NOT include passphrase (not part of required order).
     * - Never include an existing "signature" field.
     */
    public String generateSignature(Map<String, String> data) {
        // Inject merchant credentials from configuration (override any provided values)
        TreeMap<String, String> input = new TreeMap<>(data);
        input.put("merchant_id", merchantId);
        input.put("merchant_key", merchantKey);
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
            if ("signature".equals(key)) continue; // defensive, though not in list
            String value = input.get(key);
            if (value == null) continue;
            value = value.trim();
            if (value.isBlank()) continue;
            if (sb.length() > 0) sb.append('&');
            // Encode spaces as '+' per requirement, leave other characters unchanged
            String valueWithPlus = value.replace(" ", "+");
            sb.append(key).append('=').append(valueWithPlus);
        }

        String finalString = sb.toString();
        // Save the exact ordered base string so it can be reused for the POST body construction
        this.lastSignatureBaseString = finalString;

        // Generate the MD5 Hash
        System.out.println("DEBUG: String used for signature: " + finalString);
        String signature = DigestUtils.md5Hex(finalString.getBytes(StandardCharsets.UTF_8));
        System.out.println("DEBUG: Signature: " + signature);

        return signature;
    }

    /**
     * Verifies an incoming ITN signature.
     */
    public boolean verifySignature(Map<String, String> params) {
        String receivedSignature = params.get("signature");

        // Remove signature to recalculate
        TreeMap<String, String> dataToVerify = new TreeMap<>(params);
        dataToVerify.remove("signature");

        String calculatedSignature = generateSignature(dataToVerify);
        return calculatedSignature.equals(receivedSignature);
    }
}