package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.utils.ClientIpUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Public REST endpoint proxying REST Countries lookups. No auth annotation — public by
 * design, matching {@link ContactEnquiryResource}. Unlike the other public endpoints, a
 * successful call here spends a live, metered, paid upstream API key, so the rate-limit
 * check must run before the key is ever read.
 */
@Path("/api/countries")
@Produces(MediaType.APPLICATION_JSON)
public class CountriesResource {

    @ConfigProperty(name = "restcountries.api.base-url", defaultValue = "https://api.restcountries.com/countries/v5")
    String baseUrl;

    @ConfigProperty(name = "restcountries.api.key")
    Optional<String> apiKey;

    @Inject
    RateLimiterService rateLimiterService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @GET
    public Response getCountries(
            @QueryParam("q") String q,
            @QueryParam("limit") Integer limit,
            @QueryParam("pretty") String pretty,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    ) {
        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);
        RateLimitDecision decision = rateLimiterService.check("countries", clientIp, 20, 3600);
        if (!decision.allowed()) {
            return Response.status(429).header("Retry-After", decision.retryAfterSeconds()).build();
        }

        String resolvedApiKey = apiKey.map(String::trim).orElse("");
        if (resolvedApiKey.isBlank()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"errors\":[{\"message\":\"Rest Countries API key is not configured on the server\"}]}")
                    .build();
        }

        String url = buildUrl(q, limit, pretty != null);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + resolvedApiKey)
                .GET()
                .build();

        try {
            HttpResponse<String> upstream = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return Response.status(upstream.statusCode())
                    .type(MediaType.APPLICATION_JSON)
                    .entity(upstream.body())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"errors\":[{\"message\":\"Countries service request was interrupted\"}]}")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"errors\":[{\"message\":\"Countries service unavailable\"}]}")
                    .build();
        }
    }

    private String buildUrl(String q, Integer limit, boolean includePretty) {
        List<String> params = new ArrayList<>();

        if (q != null && !q.isBlank()) {
            params.add("q=" + encode(q));
        }
        if (limit != null && limit > 0) {
            params.add("limit=" + limit);
        }
        if (includePretty) {
            params.add("pretty=");
        }

        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

