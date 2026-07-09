package org.ecommerce.backend.api.rest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.SettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

@QuarkusTest
class StorefrontConfigResourceTest {

    @InjectMock
    SettingsRepository settingsRepository;

    @Test
    void getConfig_shouldReturnDefaultHeaderAnnouncementWhenNoRowExists() {
        // No storefront.header row in store_settings
        when(settingsRepository.getAllStoreSettings()).thenReturn(Collections.emptyList());

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("header.announcement.enabled", equalTo(false))
                .body("header.announcement.text", equalTo(""))
                .body("header.announcement.backgroundColor", equalTo("#1a1f35"))
                .body("header.announcement.textColor", equalTo("#ffffff"));
    }

    @Test
    void getConfig_shouldReturnStoredHeaderAnnouncementWhenRowExists() {
        StoreSettingsEntity headerSetting = new StoreSettingsEntity();
        headerSetting.key = "storefront.header";
        headerSetting.value = "{\"announcement\":{\"enabled\":true,\"text\":\"Free shipping over R500!\",\"backgroundColor\":\"#ff0000\",\"textColor\":\"#000000\"}}";

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(headerSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("header.announcement.enabled", equalTo(true))
                .body("header.announcement.text", equalTo("Free shipping over R500!"))
                .body("header.announcement.backgroundColor", equalTo("#ff0000"))
                .body("header.announcement.textColor", equalTo("#000000"));
    }

    @Test
    void getConfig_shouldReturnDefaultLoginStylePageWhenAuthSettingAbsent() {
        when(settingsRepository.getAllStoreSettings()).thenReturn(Collections.emptyList());

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("auth.loginStyle", equalTo("page"));
    }

    @Test
    void getConfig_shouldReturnLoginStyleModalWhenAuthSettingIsModal() {
        StoreSettingsEntity authSetting = new StoreSettingsEntity();
        authSetting.key = "storefront.auth.login_style";
        authSetting.value = "modal";

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(authSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("auth.loginStyle", equalTo("modal"));
    }
}
