package org.ecommerce.backend;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;

/** Proves the actual HTTP behaviour of the {@code /static/images/*} route {@link Startup} registers. */
@QuarkusTest
class StaticImageRouteIT
{
    @Inject
    @ConfigProperty(name = "storage.path")
    String storagePath;

    private Path writtenFile;

    @AfterEach
    void cleanup() throws Exception
    {
        if (writtenFile != null) {
            Files.deleteIfExists(writtenFile);
        }
    }

    @Test
    void servedImage_carriesLongLivedImmutableCacheHeaders() throws Exception
    {
        String fileName = "static-route-test-" + UUID.randomUUID() + ".jpg";
        writtenFile = Path.of(storagePath, fileName);
        Files.createDirectories(writtenFile.getParent());
        Files.write(writtenFile, new byte[]{1, 2, 3, 4});

        RestAssured.given()
                .when().get("/static/images/" + fileName)
                .then()
                .statusCode(200)
                .header("Cache-Control", containsString("max-age="))
                .header("Cache-Control", containsString("immutable"));
    }

    @Test
    void missingImage_stays404AndUncached()
    {
        RestAssured.given()
                .when().get("/static/images/does-not-exist-" + UUID.randomUUID() + ".jpg")
                .then()
                .statusCode(404)
                .header("Cache-Control", nullValue());
    }
}
