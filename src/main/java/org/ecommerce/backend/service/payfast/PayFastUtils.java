package org.ecommerce.backend.service.payfast;

import org.apache.commons.codec.digest.DigestUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

public class PayFastUtils
{
    public static String generateSecuritySignature(String joinedNameValuePair)
    {
        return DigestUtils.md5Hex(joinedNameValuePair.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Extracts the {@code signature} value from a raw ITN body, scanning every
     * {@code &}-separated pair (last occurrence wins, matching how a duplicate
     * form field would resolve).
     */
    public static String extractSignature(String rawBody)
    {
        if (rawBody == null) {
            return null;
        }
        String signature = null;
        for (String pair : rawBody.split("&")) {
            if (pair.startsWith("signature=")) {
                signature = pair.substring("signature=".length());
            }
        }
        return signature;
    }

    /**
     * Rebuilds the raw ITN body with the {@code signature} pair removed, preserving
     * posted order and encoding. This is PayFast's own {@code pfParamString}: the same
     * string is the base for the signature check (passphrase appended) and the exact
     * body PayFast expects back for server-side confirmation (passphrase never appended).
     */
    public static String buildParamString(String rawBody)
    {
        StringJoiner baseString = new StringJoiner("&");
        for (String pair : rawBody.split("&")) {
            if (!pair.startsWith("signature=") && !pair.isBlank()) {
                baseString.add(pair);
            }
        }
        return baseString.toString();
    }

    /**
     * Checks whether an IPv4 address falls inside a CIDR block (e.g. {@code 197.97.145.144/28})
     * or matches a bare IPv4 address (treated as a /32). Fails closed (returns false) on any
     * malformed input, including non-IPv4 addresses.
     */
    public static boolean ipMatchesCidr(String ip, String cidr)
    {
        try {
            String[] parts = cidr.split("/", 2);
            int prefixLength = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 32;
            if (prefixLength == 0) {
                return true;
            }
            int targetInt = ipv4ToInt(ip);
            int networkInt = ipv4ToInt(parts[0].trim());
            int mask = 0xFFFFFFFF << (32 - prefixLength);
            return (targetInt & mask) == (networkInt & mask);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int ipv4ToInt(String ip)
    {
        String[] octets = ip.trim().split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("Not an IPv4 address: " + ip);
        }
        int result = 0;
        for (String octet : octets) {
            int value = Integer.parseInt(octet);
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("Octet out of range: " + octet);
            }
            result = (result << 8) | value;
        }
        return result;
    }

    /**
     * Returns a new LinkedHashMap whose iteration order follows the predefined PayFast field order.
     * - Entries present in {@code input} that are listed in the order array will appear first in that exact order.
     * - Any remaining entries not listed in the order array will be appended afterward in the input's natural (TreeMap) order.
     */
    public static LinkedHashMap<String, String> sortByPredefinedOrder(TreeMap<String, String> input)
    {
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

    static String concatenateNonEmptyNameValuePairs(Map<String, String> sortedInput)
    {

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
