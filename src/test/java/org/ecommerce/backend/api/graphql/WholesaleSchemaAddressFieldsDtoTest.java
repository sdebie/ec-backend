package org.ecommerce.backend.api.graphql;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * WholesaleApplicationDetailsDto and WholesaleCustomerDto expose their addresses as a
 * nested AddressDto (physicalAddress/postalAddress), not 12 flat fields each — pins that
 * the live GraphQL schema reflects the Java shape (AddressDto, conceptual-duplication-scan
 * finding 1) exactly, both that the flat leaf names are gone from the parent types and that
 * AddressDto itself carries all 6 fields.
 */
@QuarkusTest
@DisplayName("Wholesale GraphQL schema — nested address shape")
class WholesaleSchemaAddressFieldsDtoTest
{
    private static final String[] EXPECTED_ADDRESS_LEAF_FIELDS = {
            "line1", "line2", "suburb", "city", "province", "postalCode"
    };

    private static final String[] OLD_FLAT_FIELD_NAMES = {
            "physicalAddressLine1", "physicalAddressLine2", "physicalSuburb",
            "physicalCity", "physicalProvince", "physicalPostalCode",
            "postalAddressLine1", "postalAddressLine2", "postalSuburb",
            "postalCity", "postalProvince", "postalPostalCode"
    };

    @Test
    @DisplayName("WholesaleApplicationDetailsDto nests physicalAddress/postalAddress, not flat fields")
    void detailsDtoExposesNestedAddressFields()
    {
        assertTypeHasNestedAddressFields("WholesaleApplicationDetailsDto");
    }

    @Test
    @DisplayName("WholesaleCustomerDto nests physicalAddress/postalAddress, not flat fields")
    void customerDtoExposesNestedAddressFields()
    {
        assertTypeHasNestedAddressFields("WholesaleCustomerDto");
    }

    @Test
    @DisplayName("AddressDto GraphQL type carries all 6 leaf fields")
    void addressDtoTypeExposesAllLeafFields()
    {
        given()
                .contentType("application/json")
                .body("{\"query\": " + toJsonString(typeFieldsQuery("AddressDto")) + "}")
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.__type.fields.name", hasItems(EXPECTED_ADDRESS_LEAF_FIELDS));
    }

    // Two separate introspection requests per type, not one query selecting __Type.fields
    // twice — SmallRye GraphQL's bad-faith-introspection guard rejects the latter as query
    // abuse, unrelated to what this test is actually checking.
    private static void assertTypeHasNestedAddressFields(String graphQlTypeName)
    {
        given()
                .contentType("application/json")
                .body("{\"query\": " + toJsonString(typeFieldsQuery(graphQlTypeName)) + "}")
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.__type.fields.name", hasItems("physicalAddress", "postalAddress"))
                .body("data.__type.fields.name", not(hasItems(OLD_FLAT_FIELD_NAMES)));
    }

    private static String typeFieldsQuery(String graphQlTypeName)
    {
        return "{ __type(name: \"" + graphQlTypeName + "\") { fields { name } } }";
    }

    private static String toJsonString(String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
