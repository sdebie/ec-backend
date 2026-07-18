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
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
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

    // ── Contact assembly tests ────────────────────────────────────────────────

    @Test
    void getConfig_shouldIncludeContactObjectWhenStorefrontContactSettingExists() {
        StoreSettingsEntity contactSetting = new StoreSettingsEntity();
        contactSetting.key = "storefront.contact";
        contactSetting.value = "{\"emails\":[\"info@store.co.za\",\"support@store.co.za\"],\"phones\":[\"+27123456789\"],\"landline\":\"+27219876543\",\"physicalAddress\":\"123 Main Street\\nCape Town\\n8001\",\"businessHours\":\"Mon-Fri 08:00-17:00\",\"responseSla\":\"We respond within 24 hours\",\"mapUrl\":\"https://www.google.com/maps/place/test\",\"mapEmbedUrl\":\"https://www.google.com/maps/embed?pb=abc\"}";

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(contactSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("contact.emails", hasItems("info@store.co.za", "support@store.co.za"))
                .body("contact.phones", hasItems("+27123456789"))
                .body("contact.landline", equalTo("+27219876543"))
                .body("contact.physicalAddress", equalTo("123 Main Street\nCape Town\n8001"))
                .body("contact.businessHours", equalTo("Mon-Fri 08:00-17:00"))
                .body("contact.responseSla", equalTo("We respond within 24 hours"))
                .body("contact.mapUrl", equalTo("https://www.google.com/maps/place/test"))
                .body("contact.mapEmbedUrl", equalTo("https://www.google.com/maps/embed?pb=abc"));
    }

    @Test
    void getConfig_shouldOmitContactKeyWhenStorefrontContactSettingAbsent() {
        // Only non-contact settings present — contact should not appear in response
        StoreSettingsEntity headerSetting = new StoreSettingsEntity();
        headerSetting.key = "storefront.header";
        headerSetting.value = "{\"announcement\":{\"enabled\":false,\"text\":\"\",\"backgroundColor\":\"#1a1f35\",\"textColor\":\"#ffffff\"}}";

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(headerSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("$", not(hasKey("contact")))
                .body("header.announcement.enabled", equalTo(false));
    }

    @Test
    void getConfig_shouldPassThroughEnquiryEmailInContact() {
        StoreSettingsEntity contactSetting = new StoreSettingsEntity();
        contactSetting.key = "storefront.contact";
        contactSetting.value = "{\"emails\":[\"info@store.co.za\"],\"phones\":[\"+27123456789\"],\"enquiryEmail\":\"enquiries@store.co.za\"}";

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(contactSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("contact.enquiryEmail", equalTo("enquiries@store.co.za"))
                .body("contact.emails", hasItems("info@store.co.za"))
                .body("contact.phones", hasItems("+27123456789"));
    }

    @Test
    void getConfig_shouldOmitEnquiryEmailFromContactWhenNotInSetting() {
        StoreSettingsEntity contactSetting = new StoreSettingsEntity();
        contactSetting.key = "storefront.contact";
        contactSetting.value = "{\"emails\":[\"info@store.co.za\"],\"phones\":[\"+27123456789\"]}";

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(contactSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("contact.emails", hasItems("info@store.co.za"))
                .body("contact", not(hasKey("enquiryEmail")));
    }
}
