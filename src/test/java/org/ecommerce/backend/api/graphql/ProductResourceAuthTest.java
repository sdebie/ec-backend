package org.ecommerce.backend.api.graphql;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.backend.service.ProductService;
import org.ecommerce.common.dto.ProductDto;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Role-matrix integration tests for product write mutations.
 * Asserts that SUPER_ADMIN and CATALOG_MANAGER can call addProductInformation
 * and updateProductInformation, while ORDER_MANAGER and VIEWER are rejected (403).
 * <p>
 */
@QuarkusTest
class ProductResourceAuthTest
{
    @InjectMock
    ProductService productService;

    @BeforeEach
    void setUp()
    {
        ProductDto product = new ProductDto();
        product.setId(UUID.randomUUID().toString());
        product.setName("Auth Test Product");
        product.setSlug("auth-test-product");

        ProductVariantDto variant = new ProductVariantDto();
        variant.setId(UUID.randomUUID().toString());
        variant.setSku("AUTH-SKU-001");
        variant.setStockQuantity(10);

        VariantPriceDto price = new VariantPriceDto();
        price.setId(UUID.randomUUID().toString());
        price.setPriceType("RETAIL_PRICE");
        price.setPrice(new BigDecimal("49.99"));

        variant.setPrices(List.of(price));
        variant.setImages(List.of());

        ProductInformationDto mockResult = new ProductInformationDto(product, List.of(variant));

        when(productService.addProductInformation(any())).thenReturn(mockResult);
        when(productService.updateProductInformation(anyString(), any())).thenReturn(mockResult);
    }

    // ─── Helper Methods ─────────────────────────────────────────────────────

    private String generateStaffJwt(String email, String role)
    {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups(role)
                .sign();
    }

    private static final String ADD_PRODUCT_MUTATION =
            "mutation($input: ProductInformationDtoInput) { addProductInformation(input: $input) { product { id name } variants { id sku } } }";

    private static final String UPDATE_PRODUCT_MUTATION =
            "mutation($productId: String!, $input: ProductInformationDtoInput) { updateProductInformation(productId: $productId, input: $input) { product { id name } variants { id sku } } }";

    private String addProductBody()
    {
        return "{\"query\":\"" + ADD_PRODUCT_MUTATION.replace("\"", "\\\"") + "\","
                + "\"variables\":{\"input\":{\"product\":{\"name\":\"Test\",\"slug\":\"test\",\"status\":\"PENDING\"},"
                + "\"variants\":[{\"sku\":\"SKU-1\",\"stockQuantity\":5,"
                + "\"prices\":[{\"priceType\":\"RETAIL_PRICE\",\"price\":19.99}],"
                + "\"images\":[]}]}}}";
    }

    private String updateProductBody(String productId)
    {
        return "{\"query\":\"" + UPDATE_PRODUCT_MUTATION.replace("\"", "\\\"") + "\","
                + "\"variables\":{\"productId\":\"" + productId + "\","
                + "\"input\":{\"product\":{\"id\":\"" + productId + "\",\"name\":\"Updated\",\"slug\":\"updated\",\"status\":\"ACTIVE\"},"
                + "\"variants\":[{\"sku\":\"SKU-1\",\"stockQuantity\":10,"
                + "\"prices\":[{\"priceType\":\"RETAIL_PRICE\",\"price\":29.99}],"
                + "\"images\":[]}]}}}";
    }

    // ─── addProductInformation role-matrix ───────────────────────────────────

    @Nested
    @DisplayName("addProductInformation role-matrix")
    class AddProductRoleMatrix
    {

        @Test
        @DisplayName("SUPER_ADMIN can create a product")
        void addProduct_superAdmin_succeeds()
        {
            String token = generateStaffJwt("superadmin@test.com", "SUPER_ADMIN");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(addProductBody())
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", nullValue())
                    .body("data.addProductInformation.product.id", notNullValue());
        }

        @Test
        @DisplayName("CATALOG_MANAGER can create a product")
        void addProduct_catalogManager_succeeds()
        {
            String token = generateStaffJwt("catalog@test.com", "CATALOG_MANAGER");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(addProductBody())
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", nullValue())
                    .body("data.addProductInformation.product.id", notNullValue());
        }

        @Test
        @DisplayName("ORDER_MANAGER cannot create a product (403)")
        void addProduct_orderManager_forbidden()
        {
            String token = generateStaffJwt("ordermanager@test.com", "ORDER_MANAGER");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(addProductBody())
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("VIEWER cannot create a product (403)")
        void addProduct_viewer_forbidden()
        {
            String token = generateStaffJwt("viewer@test.com", "VIEWER");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(addProductBody())
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }
    }

    // ─── updateProductInformation role-matrix ────────────────────────────────

    @Nested
    @DisplayName("updateProductInformation role-matrix")
    class UpdateProductRoleMatrix
    {

        private final String productId = UUID.randomUUID().toString();

        @Test
        @DisplayName("SUPER_ADMIN can update a product")
        void updateProduct_superAdmin_succeeds()
        {
            String token = generateStaffJwt("superadmin@test.com", "SUPER_ADMIN");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(updateProductBody(productId))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", nullValue())
                    .body("data.updateProductInformation.product.id", notNullValue());
        }

        @Test
        @DisplayName("CATALOG_MANAGER can update a product")
        void updateProduct_catalogManager_succeeds()
        {
            String token = generateStaffJwt("catalog@test.com", "CATALOG_MANAGER");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(updateProductBody(productId))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", nullValue())
                    .body("data.updateProductInformation.product.id", notNullValue());
        }

        @Test
        @DisplayName("ORDER_MANAGER cannot update a product (403)")
        void updateProduct_orderManager_forbidden()
        {
            String token = generateStaffJwt("ordermanager@test.com", "ORDER_MANAGER");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(updateProductBody(productId))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("VIEWER cannot update a product (403)")
        void updateProduct_viewer_forbidden()
        {
            String token = generateStaffJwt("viewer@test.com", "VIEWER");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(updateProductBody(productId))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }
    }
}
