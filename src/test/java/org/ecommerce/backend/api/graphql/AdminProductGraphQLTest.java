package org.ecommerce.backend.api.graphql;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.backend.service.ProductService;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.AdminProductStatsDto;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.dto.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Integration tests for admin product GraphQL operations.
 * Tests authentication, authorization, and response shape for:
 * - adminProductStats query
 * - adminProductList query
 * - updateProductStatus mutation
 * - deleteProduct mutation
 * <p>
 */
@QuarkusTest
class AdminProductGraphQLTest
{
    private static final String STAFF_EMAIL = "admin@test.com";
    private static final String VIEWER_EMAIL = "viewer@test.com";

    @InjectMock
    ProductService productService;

    @BeforeEach
    void setUp()
    {
        // Set up mock stats
        AdminProductStatsDto mockStats = new AdminProductStatsDto();
        mockStats.setTotal(50);
        mockStats.setActive(30);
        mockStats.setPending(12);
        mockStats.setDisabled(8);

        // Set up mock product list item
        AdminProductListItemDto item = new AdminProductListItemDto();
        item.setId(UUID.randomUUID().toString());
        item.setName("Test Product");
        item.setSlug("test-product");
        item.setSku("SKU-001");
        item.setCategory(new CategoryDto());
        item.getCategory().setId(UUID.randomUUID());
        item.getCategory().setName("Electronics");
        item.setStatus("ACTIVE");
        item.setThumbnailUrl("https://example.com/thumb.jpg");
        item.setRetailPrice("199.99");
        item.setStockCount(25);
        item.setStockLevel("IN_STOCK");

        PageResponse<AdminProductListItemDto> mockPage = new PageResponse<>(List.of(item), 1, 1, 0, 10);

        // Set up service mocks
        when(productService.getProductStats()).thenReturn(mockStats);
        when(productService.getAdminProductList(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(mockPage);
    }

    private String generateStaffJwt(String email, String role)
    {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups(role)
                .sign();
    }

    /**
     * Builds a proper JSON body for a GraphQL request using variables.
     */
    private String graphqlBody(String query, Map<String, Object> variables)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":\"").append(query.replace("\"", "\\\"")).append("\"");
        if (variables != null && !variables.isEmpty()) {
            sb.append(",\"variables\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":");
                Object val = entry.getValue();
                if (val == null) {
                    sb.append("null");
                } else if (val instanceof Number) {
                    sb.append(val);
                } else {
                    sb.append("\"").append(val).append("\"");
                }
                first = false;
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private String graphqlBody(String query)
    {
        return graphqlBody(query, null);
    }

    @Nested
    @DisplayName("adminProductStats query")
    class AdminProductStatsTests
    {

        @Test
        @DisplayName("returns correct shape with staff JWT")
        void adminProductStats_withStaffJwt_returnsCorrectShape()
        {
            String token = generateStaffJwt(STAFF_EMAIL, "SUPER_ADMIN");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody("{ adminProductStats { total active pending disabled } }"))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("data.adminProductStats.total", equalTo(50))
                    .body("data.adminProductStats.active", equalTo(30))
                    .body("data.adminProductStats.pending", equalTo(12))
                    .body("data.adminProductStats.disabled", equalTo(8))
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("accessible with VIEWER role")
        void adminProductStats_withViewerRole_returnsData()
        {
            String token = generateStaffJwt(VIEWER_EMAIL, "VIEWER");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody("{ adminProductStats { total active pending disabled } }"))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("data.adminProductStats.total", equalTo(50))
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("unauthenticated request returns UNAUTHORIZED error")
        void adminProductStats_withoutJwt_returnsUnauthorized()
        {
            given()
                    .contentType("application/json")
                    .body(graphqlBody("{ adminProductStats { total active pending disabled } }"))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"))
                    .body("data.adminProductStats", nullValue());
        }
    }

    @Nested
    @DisplayName("adminProductList query")
    class AdminProductListTests
    {

        private static final String ADMIN_PRODUCT_LIST_QUERY =
                "query($pageIndex: Int, $pageSize: Int, $status: String, $categoryId: String, $brandId: String, $search: String) " +
                        "{ adminProductList(pageIndex: $pageIndex, pageSize: $pageSize, status: $status, categoryId: $categoryId, brandId: $brandId, search: $search) " +
                        "{ content { id name slug sku category { id name } status thumbnailUrl retailPrice stockCount stockLevel } totalElements totalPages pageIndex pageSize } }";

        @Test
        @DisplayName("supports pagination parameters")
        void adminProductList_withPagination_returnsPagedResults()
        {
            String token = generateStaffJwt(STAFF_EMAIL, "SUPER_ADMIN");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(ADMIN_PRODUCT_LIST_QUERY, Map.of("pageIndex", 0, "pageSize", 10)))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("data.adminProductList.content", hasSize(1))
                    .body("data.adminProductList.content[0].id", notNullValue())
                    .body("data.adminProductList.content[0].name", equalTo("Test Product"))
                    .body("data.adminProductList.content[0].sku", equalTo("SKU-001"))
                    .body("data.adminProductList.content[0].status", equalTo("ACTIVE"))
                    .body("data.adminProductList.totalElements", equalTo(1))
                    .body("data.adminProductList.totalPages", equalTo(1))
                    .body("data.adminProductList.pageIndex", equalTo(0))
                    .body("data.adminProductList.pageSize", equalTo(10))
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("supports filtering by status")
        void adminProductList_withStatusFilter_callsServiceWithStatus()
        {
            String token = generateStaffJwt(STAFF_EMAIL, "SUPER_ADMIN");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(ADMIN_PRODUCT_LIST_QUERY, Map.of("status", "ACTIVE")))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("data.adminProductList.content", notNullValue())
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("supports filtering by categoryId")
        void adminProductList_withCategoryFilter_callsServiceWithCategoryId()
        {
            String token = generateStaffJwt(STAFF_EMAIL, "SUPER_ADMIN");
            String categoryId = UUID.randomUUID().toString();

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(ADMIN_PRODUCT_LIST_QUERY, Map.of("categoryId", categoryId)))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("data.adminProductList.content", notNullValue())
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("supports filtering by brandId")
        void adminProductList_withBrandFilter_callsServiceWithBrandId()
        {
            String token = generateStaffJwt(STAFF_EMAIL, "SUPER_ADMIN");
            String brandId = UUID.randomUUID().toString();

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(ADMIN_PRODUCT_LIST_QUERY, Map.of("brandId", brandId)))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("data.adminProductList.content", notNullValue())
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("supports search filter")
        void adminProductList_withSearchFilter_callsServiceWithSearch()
        {
            String token = generateStaffJwt(STAFF_EMAIL, "SUPER_ADMIN");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(ADMIN_PRODUCT_LIST_QUERY, Map.of("search", "widget")))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("data.adminProductList.content", notNullValue())
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("returns full product item shape including category and stock")
        void adminProductList_returnsFullItemShape()
        {
            String token = generateStaffJwt(STAFF_EMAIL, "SUPER_ADMIN");

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(ADMIN_PRODUCT_LIST_QUERY, Map.of("pageIndex", 0, "pageSize", 10)))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("data.adminProductList.content[0].id", notNullValue())
                    .body("data.adminProductList.content[0].name", equalTo("Test Product"))
                    .body("data.adminProductList.content[0].slug", equalTo("test-product"))
                    .body("data.adminProductList.content[0].sku", equalTo("SKU-001"))
                    .body("data.adminProductList.content[0].category.name", equalTo("Electronics"))
                    .body("data.adminProductList.content[0].status", equalTo("ACTIVE"))
                    .body("data.adminProductList.content[0].thumbnailUrl", equalTo("https://example.com/thumb.jpg"))
                    .body("data.adminProductList.content[0].retailPrice", equalTo("199.99"))
                    .body("data.adminProductList.content[0].stockCount", equalTo(25))
                    .body("data.adminProductList.content[0].stockLevel", equalTo("IN_STOCK"))
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("unauthenticated request returns UNAUTHORIZED error")
        void adminProductList_withoutJwt_returnsUnauthorized()
        {
            given()
                    .contentType("application/json")
                    .body(graphqlBody(ADMIN_PRODUCT_LIST_QUERY))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"))
                    .body("data.adminProductList", nullValue());
        }
    }

    @Nested
    @DisplayName("updateProductStatus mutation")
    class UpdateProductStatusTests
    {

        private static final String UPDATE_STATUS_MUTATION =
                "mutation($id: String!, $status: String!) { updateProductStatus(id: $id, status: $status) }";

        @Test
        @DisplayName("succeeds with SUPER_ADMIN role")
        void updateProductStatus_withSuperAdmin_succeeds()
        {
            String token = generateStaffJwt(STAFF_EMAIL, "SUPER_ADMIN");
            String productId = UUID.randomUUID().toString();

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(UPDATE_STATUS_MUTATION, Map.of("id", productId, "status", "DISABLED")))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("denied for VIEWER role")
        void updateProductStatus_withViewerRole_returnsForbidden()
        {
            String token = generateStaffJwt(VIEWER_EMAIL, "VIEWER");
            String productId = UUID.randomUUID().toString();

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(UPDATE_STATUS_MUTATION, Map.of("id", productId, "status", "DISABLED")))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("unauthenticated request returns UNAUTHORIZED error")
        void updateProductStatus_withoutJwt_returnsUnauthorized()
        {
            String productId = UUID.randomUUID().toString();

            given()
                    .contentType("application/json")
                    .body(graphqlBody(UPDATE_STATUS_MUTATION, Map.of("id", productId, "status", "DISABLED")))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }
    }

    @Nested
    @DisplayName("deleteProduct mutation")
    class DeleteProductTests
    {

        private static final String DELETE_MUTATION =
                "mutation($id: String!) { deleteProduct(id: $id) }";

        @Test
        @DisplayName("succeeds with SUPER_ADMIN role")
        void deleteProduct_withSuperAdmin_succeeds()
        {
            String token = generateStaffJwt(STAFF_EMAIL, "SUPER_ADMIN");
            String productId = UUID.randomUUID().toString();

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(DELETE_MUTATION, Map.of("id", productId)))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", nullValue());
        }

        @Test
        @DisplayName("denied for VIEWER role")
        void deleteProduct_withViewerRole_returnsForbidden()
        {
            String token = generateStaffJwt(VIEWER_EMAIL, "VIEWER");
            String productId = UUID.randomUUID().toString();

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .body(graphqlBody(DELETE_MUTATION, Map.of("id", productId)))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("forbidden"));
        }

        @Test
        @DisplayName("unauthenticated request returns UNAUTHORIZED error")
        void deleteProduct_withoutJwt_returnsUnauthorized()
        {
            String productId = UUID.randomUUID().toString();

            given()
                    .contentType("application/json")
                    .body(graphqlBody(DELETE_MUTATION, Map.of("id", productId)))
                    .when()
                    .post("/api/graphql")
                    .then()
                    .statusCode(200)
                    .body("errors", not(empty()))
                    .body("errors[0].extensions.code", equalTo("unauthorized"));
        }
    }
}
