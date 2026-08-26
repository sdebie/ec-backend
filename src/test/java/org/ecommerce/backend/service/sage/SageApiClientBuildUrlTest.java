package org.ecommerce.backend.service.sage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SageApiClientBuildUrlTest
{
    private static final String BASE_URL = "https://resellers.accounting.sageone.co.za/api/2.0.0/";
    private static final String API_KEY = "785D6210-1FE3-4727-8B3B-CEF9D44C3DB3";
    private static final String COMPANY_ID = "17424";

    @Test
    void buildsItemGetUrlWithFilter()
    {
        Map<String, String> extraParams = new LinkedHashMap<>();
        extraParams.put("$filter", "Modified ge datetime'2026-08-04'");

        String url = SageApiClient.buildUrl(BASE_URL, "Item/Get", API_KEY, COMPANY_ID, extraParams);

        assertEquals(
                "https://resellers.accounting.sageone.co.za/api/2.0.0/Item/Get"
                        + "?apikey=785D6210-1FE3-4727-8B3B-CEF9D44C3DB3&CompanyId=17424"
                        + "&$filter=Modified+ge+datetime%272026-08-04%27",
                url
        );
    }

    @Test
    void buildsPriceListingReportUrlWithoutExtraParams()
    {
        String url = SageApiClient.buildUrl(BASE_URL, "PriceListingReport/Get", API_KEY, COMPANY_ID, Map.of());

        assertEquals(
                "https://resellers.accounting.sageone.co.za/api/2.0.0/PriceListingReport/Get"
                        + "?apikey=785D6210-1FE3-4727-8B3B-CEF9D44C3DB3&CompanyId=17424",
                url
        );
    }

    @Test
    void normalizesBaseUrlMissingTrailingSlash()
    {
        String url = SageApiClient.buildUrl(
                "https://resellers.accounting.sageone.co.za/api/2.0.0", "Item/Get", API_KEY, COMPANY_ID, null);

        assertEquals(
                "https://resellers.accounting.sageone.co.za/api/2.0.0/Item/Get"
                        + "?apikey=785D6210-1FE3-4727-8B3B-CEF9D44C3DB3&CompanyId=17424",
                url
        );
    }

    @Test
    void buildsBasicAuthHeaderFromUsernameAndPassword()
    {
        String header = SageApiClient.buildBasicAuthHeader("resellerUser", "s3cret");

        assertEquals("Basic " + Base64.getEncoder().encodeToString("resellerUser:s3cret".getBytes(StandardCharsets.UTF_8)), header);
        assertEquals("Basic cmVzZWxsZXJVc2VyOnMzY3JldA==", header);
    }
}
