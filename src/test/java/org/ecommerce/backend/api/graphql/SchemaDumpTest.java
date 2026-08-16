package org.ecommerce.backend.api.graphql;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TEMPORARY — delete after refreshing the frontend SDL snapshot.
 * Dumps the live schema from the test lifecycle so `schema.graphql` can be refreshed
 * without a dev server holding port 8080.
 */
@QuarkusTest
class SchemaDumpTest
{
    @Test
    void dumpSchema() throws Exception
    {
        String sdl = RestAssured.given()
                .when().get("/api/graphql/schema.graphql")
                .then().statusCode(200)
                .extract().asString();

        Files.writeString(Path.of("target/dumped-schema.graphql"), sdl);
    }
}
