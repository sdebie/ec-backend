package org.ecommerce.backend.api.graphql;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.backend.service.WholesaleCustomerService;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test for rate limiting on the {@code createWholesaleApplication} GraphQL mutation.
 * <p>
 * Uses the REAL {@link org.ecommerce.backend.service.RateLimiterService} (not mocked) so the
 * fixed-window counter is exercised end-to-end. The {@code WholesaleCustomerService} is mocked
 * to verify it is never invoked on a denied request (no persistence, no event).
 * <p>
 * Test-profile config: {@code %test.ratelimit.wholesale-application.max=2},
 * {@code %test.ratelimit.wholesale-application.window-seconds=2}.
 * <p>
 */
@QuarkusTest
@DisplayName("Wholesale Application Rate Limiting — integration")
class WholesaleApplicationRateLimitIT
{
    private static final String RATE_LIMIT_IP = "10.99.0." + System.nanoTime() % 200;

    @InjectMock
    WholesaleCustomerService wholesaleCustomerService;

    @BeforeEach
    void setUp()
    {
        WholesaleCustomerDto resultDto = new WholesaleCustomerDto();
        resultDto.setId(UUID.randomUUID());
        resultDto.setFirstName("Test");
        resultDto.setApplicantEmail("test@example.com");

        when(wholesaleCustomerService.createWholesaleApplication(any(WholesaleCustomerDto.class)))
                .thenReturn(resultDto);
    }

    @Test
    @DisplayName("exceeding the wholesale-application limit yields GraphQL rate-limit error")
    void exceedLimit_returnsRateLimitError()
    {
        // Use a unique IP per test run to avoid cross-test interference
        String testIp = "10.99.1." + (System.nanoTime() % 250);

        // Send max (2) allowed requests
        for (int i = 0; i < 2; i++) {
            given()
                    .contentType("application/json")
                    .header("X-Forwarded-For", testIp)
                    .body(createApplicationMutation(i))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", nullValue());
        }

        // 3rd request — exceeds the limit
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", testIp)
                .body(createApplicationMutation(99))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", hasSize(1))
                .body("errors[0].message", equalTo("Too many requests \u2014 please try again later."));

        // Service was called exactly 2 times (the allowed requests), not 3
        verify(wholesaleCustomerService, times(2)).createWholesaleApplication(any(WholesaleCustomerDto.class));
    }

    @Test
    @DisplayName("denied request does not persist an application row or fire a submitted event")
    void deniedRequest_noPersistence_noEvent()
    {
        // Use a unique IP for this test
        String testIp = "10.99.2." + (System.nanoTime() % 250);

        // Exhaust the limit
        for (int i = 0; i < 2; i++) {
            given()
                    .contentType("application/json")
                    .header("X-Forwarded-For", testIp)
                    .body(createApplicationMutation(i))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", nullValue());
        }

        // Reset the mock invocation count to cleanly assert "zero further calls"
        reset(wholesaleCustomerService);
        WholesaleCustomerDto freshDto = new WholesaleCustomerDto();
        freshDto.setId(UUID.randomUUID());
        freshDto.setFirstName("Test");
        freshDto.setApplicantEmail("test@example.com");
        when(wholesaleCustomerService.createWholesaleApplication(any(WholesaleCustomerDto.class)))
                .thenReturn(freshDto);

        // This request is rate-limited — should not reach the service at all
        given()
                .contentType("application/json")
                .header("X-Forwarded-For", testIp)
                .body(createApplicationMutation(99))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors[0].message", equalTo("Too many requests \u2014 please try again later."));

        // Verify no service invocation (no row persisted, no event fired)
        verifyNoInteractions(wholesaleCustomerService);
    }

    /**
     * Build a minimal valid createWholesaleApplication mutation payload.
     * Each call uses a unique applicantEmail so the mutation won't fail on
     * duplicate-email checks within the allowed window.
     */
    private String createApplicationMutation(int index)
    {
        return """
                {
                    "query": "mutation CreateWholesaleApplication($customer: WholesaleCustomerDtoInput!) { createWholesaleApplication(customer: $customer) { firstName } }",
                    "variables": {
                        "customer": {
                            "applicantEmail": "rate-test-%d@example.com",
                            "firstName": "RateTest",
                            "companyName": "Test Corp %d"
                        }
                    }
                }
                """.formatted(index, index);
    }
}
