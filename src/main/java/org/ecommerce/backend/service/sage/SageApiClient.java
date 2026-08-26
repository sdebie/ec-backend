package org.ecommerce.backend.service.sage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import org.ecommerce.common.entity.SageSettingsEntity;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Calls the Sage Accounting reseller API (base + type + apikey/CompanyId +
 * extra query params), e.g. {@code Item/Get} or {@code PriceListingReport/Get}.
 * Authenticated with HTTP Basic Auth (sage_settings.username/password) in
 * addition to the apikey/CompanyId query params. Credentials come from the
 * single {@link SageSettingsEntity} row.
 */
@ApplicationScoped
public class SageApiClient
{
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String call(String type, Map<String, String> extraParams)
    {
        SageSettingsEntity settings = SageSettingsEntity.findAll().firstResult();
        if (settings == null || isBlank(settings.getKey()) || isBlank(settings.getCompanyId()) || isBlank(settings.getApiUrl())
                || isBlank(settings.getUsername()) || isBlank(settings.getPassword())) {
            throw new IllegalStateException("Sage API is not configured (sage_settings.key / company_id / api_url / username / password missing)");
        }

        String url = buildUrl(settings.getApiUrl(), type, settings.getKey(), settings.getCompanyId(), extraParams);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", MediaType.APPLICATION_JSON)
                .header("Authorization", buildBasicAuthHeader(settings.getUsername(), settings.getPassword()))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SageApiException("Sage API returned status " + response.statusCode(), response.statusCode(), response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SageApiException("Sage API request was interrupted", e);
        } catch (java.io.IOException e) {
            throw new SageApiException("Sage API request failed", e);
        }
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }

    static String buildBasicAuthHeader(String username, String password)
    {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    static String buildUrl(String baseUrl, String type, String apiKey, String companyId, Map<String, String> extraParams)
    {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        StringBuilder url = new StringBuilder(normalizedBase)
                .append(type)
                .append("?apikey=").append(apiKey)
                .append("&CompanyId=").append(companyId);

        if (extraParams != null) {
            for (Map.Entry<String, String> entry : extraParams.entrySet()) {
                url.append('&')
                        .append(entry.getKey())
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return url.toString();
    }
}
