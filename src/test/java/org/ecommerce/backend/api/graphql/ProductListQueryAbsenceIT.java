package org.ecommerce.backend.api.graphql;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Permanent regression guard: the three legacy product-list queries must stay deleted.
 * <p>
 * {@code productList}, {@code productListByCategory} and {@code productListByBrand}
 * lived on {@link ProductResource} and returned the legacy {@code ProductListItemDto}
 * shape. Three separate callsite sweeps — auth-hardening task 6 (2026-07-19), the
 * catalogue-ordering spec review (2026-07-28) and the deletion audit itself — each
 * found <b>zero</b> {@code ec-frontend} consumers. The storefront uses
 * {@code shoppingProductList}/{@code saleProductList}; the admin table uses
 * {@code adminProductList}.
 * <p>
 * They were not a security hole — auth-hardening had already guarded all three with
 * {@code @RolesAllowed({SUPER_ADMIN, CATALOG_MANAGER, ORDER_MANAGER, VIEWER})}, so
 * they were never anonymously reachable. What made them worth removing rather than
 * repairing is that they were dead API sitting on a genuinely expensive path: both
 * backing repository methods join-fetched {@code p.categories} and then called
 * {@code .page(...)}, which Hibernate cannot push down to LIMIT/OFFSET, so every
 * call materialised the whole filtered result set (~2,443 products) before paging it
 * in memory — and {@code ProductService.enrichProductListItems} then ran two further
 * queries per returned row on top of that.
 * <p>
 * Reintroducing any of them would restore that cost for no consumer, so this test
 * exists to make the removal stick. If a future admin surface genuinely needs a
 * product list, build it on {@code adminProductList} (law 4: reuse, don't duplicate)
 * rather than reviving these.
 *
 * @see OrderMutationAbsenceIT the same guard pattern, applied to order creation
 */
@QuarkusTest
class ProductListQueryAbsenceIT
{
    private static final String SCHEMA_PATH = "/api/graphql/schema.graphql";

    @Test
    @DisplayName("the published SDL declares none of the three legacy product-list queries")
    void schemaHasNoLegacyProductListQueries()
    {
        given()
                .when().get(SCHEMA_PATH)
                .then()
                .statusCode(200)
                // Anchored on the leading space of the SDL field indentation so these
                // do not match the surviving `shoppingProductList`/`saleProductList`/
                // `adminProductList` fields, which all end in the same characters.
                .body(not(containsString(" productList(")))
                .body(not(containsString(" productListByCategory(")))
                .body(not(containsString(" productListByBrand(")));
    }

    @Test
    @DisplayName("the published SDL declares no ProductListItemDto type")
    void schemaHasNoProductListItemDtoType()
    {
        given()
                .when().get(SCHEMA_PATH)
                .then()
                .statusCode(200)
                // The three deleted queries were its only producers, so the type left
                // the contract with them. `AdminProductListItemDto` is a different type
                // and must survive — hence the word-boundary-ish leading characters.
                .body(not(containsString("type ProductListItemDto")))
                .body(not(containsString(": ProductListItemDto")))
                .body(not(containsString("[ProductListItemDto]")));
    }

    @Test
    @DisplayName("calling productList is rejected as an unknown field, not executed")
    void productListQueryIsNotExecutable()
    {
        assertQueryIsUnknownField("productList", "{\"query\":\"{ productList { id name } }\"}");
    }

    @Test
    @DisplayName("calling productListByCategory is rejected as an unknown field, not executed")
    void productListByCategoryQueryIsNotExecutable()
    {
        assertQueryIsUnknownField(
                "productListByCategory",
                "{\"query\":\"{ productListByCategory(categoryId: \\\"11111111-1111-1111-1111-111111111111\\\") { id name } }\"}");
    }

    @Test
    @DisplayName("calling productListByBrand is rejected as an unknown field, not executed")
    void productListByBrandQueryIsNotExecutable()
    {
        assertQueryIsUnknownField(
                "productListByBrand",
                "{\"query\":\"{ productListByBrand(brandId: \\\"11111111-1111-1111-1111-111111111111\\\") { id name } }\"}");
    }

    /**
     * Asserts the field is rejected during schema validation rather than executed.
     * <p>
     * These queries used to sit behind {@code @RolesAllowed}, so an anonymous call
     * would have been rejected either way — but with an <i>authorisation</i> error,
     * not a validation one. Asserting that the error names the field proves the
     * schema no longer knows it, which a 401/403 would not.
     */
    private void assertQueryIsUnknownField(String fieldName, String body)
    {
        given()
                .contentType("application/json")
                .body(body)
                .when().post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", containsString(fieldName))
                .body("data", nullValue());
    }
}
