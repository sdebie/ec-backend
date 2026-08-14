package org.ecommerce.backend.service.payfast;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.OrderEntity;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@ApplicationScoped
public class PayFastService
{
    private static final Logger LOG = Logger.getLogger(PayFastService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

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

    @ConfigProperty(name = "payfast.validate-url")
    String validateUrl;
    @ConfigProperty(name = "payfast.itn-allowed-ips")
    String allowedIpRanges;

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

        String receivedSignature = PayFastUtils.extractSignature(rawBody);
        if (receivedSignature == null || receivedSignature.isBlank()) {
            return false;
        }

        String baseString = PayFastUtils.buildParamString(rawBody);
        if (passphrase != null && !passphrase.isBlank()) {
            baseString += "&passphrase=" + URLEncoder.encode(passphrase.trim(), StandardCharsets.UTF_8);
        }

        String calculatedSignature = DigestUtils.md5Hex(baseString.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(
                calculatedSignature.getBytes(StandardCharsets.UTF_8),
                receivedSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Checks the ITN's resolved client IP against PayFast's published source ranges
     * ({@code payfast.itn-allowed-ips}, comma-separated CIDR blocks or bare IPs).
     * Callers must pass the trusted-proxy-resolved IP (see {@code ClientIpUtils}) —
     * never a client-supplied header directly, which would make this check spoofable.
     */
    public boolean isTrustedSource(String remoteIp)
    {
        if (remoteIp == null || remoteIp.isBlank() || allowedIpRanges == null) {
            return false;
        }
        for (String range : allowedIpRanges.split(",")) {
            String cidr = range.trim();
            if (!cidr.isEmpty() && PayFastUtils.ipMatchesCidr(remoteIp.trim(), cidr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * PayFast's fourth required ITN check: post the received data back to PayFast
     * and confirm they recognise it. This is the only one of the four checks that
     * can't be spoofed offline from a leaked passphrase — it requires PayFast's own
     * server to agree the transaction happened.
     */
    public boolean confirmWithPayFast(String rawBody)
    {
        String paramString = PayFastUtils.buildParamString(rawBody);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(validateUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(paramString, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && "VALID".equals(response.body() == null ? null : response.body().trim());
        } catch (Exception e) {
            LOG.error("PayFast server confirmation request failed: " + e.getMessage());
            return false;
        }
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
        // The passphrase only salts the signature — PayFast never lists it as a
        // submitted field, so it must be dropped before the map becomes response
        // fields, or the merchant secret ships to the browser in the checkout JSON
        // and the auto-submitted hidden form.
        sortedData.remove("passphrase");
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
