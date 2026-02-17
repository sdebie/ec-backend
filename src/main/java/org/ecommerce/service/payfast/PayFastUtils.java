package org.ecommerce.service.payfast;

import org.apache.commons.codec.digest.DigestUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public class PayFastUtils {

    public static String generateSecuritySignature(String joinedNameValuePair) throws NoSuchAlgorithmException {
        String signature = DigestUtils.md5Hex(joinedNameValuePair.getBytes(StandardCharsets.UTF_8));
        System.out.println("DEBUG:: Generated Signature: " + signature + " for input + " + joinedNameValuePair + " using algorithm: MD5");
        return signature;
    }

    /**
     * Returns a new LinkedHashMap whose iteration order follows the predefined PayFast field order.
     * - Entries present in {@code input} that are listed in the order array will appear first in that exact order.
     * - Any remaining entries not listed in the order array will be appended afterward in the input's natural (TreeMap) order.
     */
    public static LinkedHashMap<String, String> sortByPredefinedOrder(TreeMap<String, String> input) {
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

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        // First, add keys that exist in input following the given order
        for (String key : order) {
            if (input.containsKey(key)) {
                String value = input.get(key);
                if (value == null)
                    continue;
                value = value.trim();
                if (value.isBlank())
                    continue;
                result.put(key, input.get(key));
            }
        }

        return result;
    }

    static String concatenateNonEmptyNameValuePairs(Map<String, String> sortedInput) {

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedInput.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            String key = entry.getKey();
            String value = entry.getValue();

            // URL-encode value ensuring percent-encodings use uppercase hex digits
            String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
            sb.append(key).append('=').append(encoded);
        }
        return sb.toString();
    }
}
