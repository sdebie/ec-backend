package org.ecommerce.backend.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.SettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class StorefrontConfigResourceTest
{
    @InjectMock
    SettingsRepository settingsRepository;

    @Test
    void getConfig_shouldReturnDefaultHeaderAnnouncementWhenNoRowExists()
    {
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
    void getConfig_shouldReturnStoredHeaderAnnouncementWhenRowExists()
    {
        StoreSettingsEntity headerSetting = new StoreSettingsEntity();
        headerSetting.setKey("storefront.header");
        headerSetting.setValue("{\"announcement\":{\"enabled\":true,\"text\":\"Free shipping over R500!\",\"backgroundColor\":\"#ff0000\",\"textColor\":\"#000000\"}}");

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
    void getConfig_shouldReturnDefaultLoginStylePageWhenAuthSettingAbsent()
    {
        when(settingsRepository.getAllStoreSettings()).thenReturn(Collections.emptyList());

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("auth.loginStyle", equalTo("page"));
    }

    @Test
    void getConfig_shouldReturnLoginStyleModalWhenAuthSettingIsModal()
    {
        StoreSettingsEntity authSetting = new StoreSettingsEntity();
        authSetting.setKey("storefront.auth.login_style");
        authSetting.setValue("modal");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(authSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("auth.loginStyle", equalTo("modal"));
    }

    @Test
    void getConfig_shouldAssembleNavigationWithoutRemovedItems()
    {
        StoreSettingsEntity navigationSetting = new StoreSettingsEntity();
        navigationSetting.setKey("storefront.navigation");
        navigationSetting.setValue("{\"items\":[{\"id\":\"home\",\"label\":\"Home\",\"path\":\"/\",\"external\":false,\"sortOrder\":0},{\"id\":\"products\",\"label\":\"Products\",\"path\":\"/products\",\"external\":false,\"sortOrder\":1},{\"id\":\"about\",\"label\":\"About Us\",\"path\":\"/about-us\",\"external\":false,\"sortOrder\":2},{\"id\":\"contact\",\"label\":\"Contact Us\",\"path\":\"/contact-us\",\"external\":false,\"sortOrder\":3}]}");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(navigationSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("nav", hasSize(4))
                .body("nav[2].id", equalTo("about"))
                .body("nav[2].sortOrder", equalTo(2))
                .body("nav[3].id", equalTo("contact"))
                .body("nav[3].sortOrder", equalTo(3));
    }

    // ── Contact assembly tests ────────────────────────────────────────────────

    @Test
    void getConfig_shouldIncludeContactObjectWhenStorefrontContactSettingExists()
    {
        StoreSettingsEntity contactSetting = new StoreSettingsEntity();
        contactSetting.setKey("storefront.contact");
        contactSetting.setValue("{\"emails\":[\"info@store.co.za\",\"support@store.co.za\"],\"phones\":[\"+27123456789\"],\"landline\":\"+27219876543\",\"physicalAddress\":\"123 Main Street\\nCape Town\\n8001\",\"businessHours\":\"Mon-Fri 08:00-17:00\",\"responseSla\":\"We respond within 24 hours\",\"mapUrl\":\"https://www.google.com/maps/place/test\",\"mapEmbedUrl\":\"https://www.google.com/maps/embed?pb=abc\"}");

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
    void getConfig_shouldOmitContactKeyWhenStorefrontContactSettingAbsent()
    {
        // Only non-contact settings present — contact should not appear in response
        StoreSettingsEntity headerSetting = new StoreSettingsEntity();
        headerSetting.setKey("storefront.header");
        headerSetting.setValue("{\"announcement\":{\"enabled\":false,\"text\":\"\",\"backgroundColor\":\"#1a1f35\",\"textColor\":\"#ffffff\"}}");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(headerSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("$", not(hasKey("contact")))
                .body("header.announcement.enabled", equalTo(false));
    }

    @Test
    void getConfig_shouldPassThroughEnquiryEmailInContact()
    {
        StoreSettingsEntity contactSetting = new StoreSettingsEntity();
        contactSetting.setKey("storefront.contact");
        contactSetting.setValue("{\"emails\":[\"info@store.co.za\"],\"phones\":[\"+27123456789\"],\"enquiryEmail\":\"enquiries@store.co.za\"}");

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
    void getConfig_shouldOmitEnquiryEmailFromContactWhenNotInSetting()
    {
        StoreSettingsEntity contactSetting = new StoreSettingsEntity();
        contactSetting.setKey("storefront.contact");
        contactSetting.setValue("{\"emails\":[\"info@store.co.za\"],\"phones\":[\"+27123456789\"]}");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(contactSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("contact.emails", hasItems("info@store.co.za"))
                .body("contact", not(hasKey("enquiryEmail")));
    }

    // ── Branding assembly tests ───────────────────────────────────────────────

    @Test
    void getConfig_shouldAlwaysEmitBrandingFallingBackToClientNameWhenBrandingRowAbsent()
    {
        // storefront.branding is boot-critical (the header/footer dereference
        // config.branding.name). With no branding row but a config row present,
        // branding must still be emitted, named after clientName.
        StoreSettingsEntity configSetting = new StoreSettingsEntity();
        configSetting.setKey("storefront.config");
        configSetting.setValue("{\"clientId\":\"acme\",\"clientName\":\"Acme Store\"}");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(configSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("branding.name", equalTo("Acme Store"))
                .body("branding", not(hasKey("logo")));
    }

    @Test
    void getConfig_shouldEmitBrandingWithGenericNameWhenNoSettingsAtAll()
    {
        when(settingsRepository.getAllStoreSettings()).thenReturn(Collections.emptyList());

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("branding.name", equalTo("Storefront"));
    }

    @Test
    void getConfig_shouldAssembleBrandingWithNestedLogoWhenRowPresent()
    {
        StoreSettingsEntity brandingSetting = new StoreSettingsEntity();
        brandingSetting.setKey("storefront.branding");
        brandingSetting.setValue("{\"name\":\"UVH Holdings\",\"tagline\":\"Your partner\",\"logoSrc\":\"storefront/uvh-logo.png\",\"logoAlt\":\"UVH Holdings logo\",\"logoWidth\":180,\"logoHeight\":48}");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(brandingSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("branding.name", equalTo("UVH Holdings"))
                .body("branding.tagline", equalTo("Your partner"))
                .body("branding.logo.src", equalTo("storefront/uvh-logo.png"))
                .body("branding.logo.alt", equalTo("UVH Holdings logo"))
                .body("branding.logo.width", equalTo(180))
                .body("branding.logo.height", equalTo(48));
    }

    // ── aboutSections tests ───────────────────────────────────────────────────

    @Test
    void getConfig_shouldReturnAboutSectionsWhenRowPresent()
    {
        StoreSettingsEntity aboutSetting = new StoreSettingsEntity();
        aboutSetting.setKey("storefront.about_sections");
        aboutSetting.setValue("[{\"id\":\"s1\",\"type\":\"hero\",\"enabled\":true,\"props\":{\"title\":\"Hello\"}},{\"id\":\"s2\",\"type\":\"stats\",\"enabled\":true,\"props\":{\"items\":[]}}]");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(aboutSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("aboutSections", hasSize(2))
                .body("aboutSections[0].id", equalTo("s1"))
                .body("aboutSections[0].type", equalTo("hero"))
                .body("aboutSections[0].props.title", equalTo("Hello"))
                .body("aboutSections[1].id", equalTo("s2"))
                .body("aboutSections[1].type", equalTo("stats"));
    }

    @Test
    void getConfig_shouldFilterDisabledAboutSectionsAndStripEnabledField()
    {
        StoreSettingsEntity aboutSetting = new StoreSettingsEntity();
        aboutSetting.setKey("storefront.about_sections");
        aboutSetting.setValue("[{\"id\":\"s1\",\"type\":\"hero\",\"enabled\":true,\"props\":{\"title\":\"Keep\"}},{\"id\":\"s2\",\"type\":\"stats\",\"enabled\":false,\"props\":{\"items\":[]}},{\"id\":\"s3\",\"type\":\"cta\",\"props\":{\"text\":\"No enabled field\"}}]");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(aboutSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                // s2 (enabled:false) excluded → only s1 and s3 remain
                .body("aboutSections", hasSize(2))
                .body("aboutSections[0].id", equalTo("s1"))
                .body("aboutSections[0].props.title", equalTo("Keep"))
                .body("aboutSections[0]", not(hasKey("enabled")))
                .body("aboutSections[1].id", equalTo("s3"))
                .body("aboutSections[1].props.text", equalTo("No enabled field"))
                .body("aboutSections[1]", not(hasKey("enabled")));
    }

    @Test
    void getConfig_shouldReturnEmptyAboutSectionsWhenRowMissing()
    {
        // No storefront.about_sections row at all
        when(settingsRepository.getAllStoreSettings()).thenReturn(Collections.emptyList());

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .body("aboutSections", hasSize(0));
    }

    @Test
    void getConfig_shouldReturnEmptyAboutSectionsWhenMalformedJsonWithRestOfConfigIntact()
    {
        StoreSettingsEntity aboutSetting = new StoreSettingsEntity();
        aboutSetting.setKey("storefront.about_sections");
        aboutSetting.setValue("NOT VALID JSON [[[");

        StoreSettingsEntity configSetting = new StoreSettingsEntity();
        configSetting.setKey("storefront.config");
        configSetting.setValue("{\"clientId\":\"test-client\",\"clientName\":\"Test Store\"}");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(aboutSetting, configSetting));

        given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                // Malformed about_sections falls back to empty array
                .body("aboutSections", hasSize(0))
                // Rest of config is intact
                .body("clientId", equalTo("test-client"))
                .body("clientName", equalTo("Test Store"));
    }

    @Test
    void getConfig_shouldReturnHomeSectionsUnchanged_regressionPin() throws Exception
    {
        // This is a regression pin: the existing `sections` (home) output must be
        // semantically identical to its pre-refactor behaviour.
        StoreSettingsEntity homeSetting = new StoreSettingsEntity();
        homeSetting.setKey("storefront.home_sections");
        homeSetting.setValue("[{\"id\":\"hero-1\",\"type\":\"hero\",\"enabled\":true,\"props\":{\"title\":\"Welcome\",\"subtitle\":\"Shop now\"}},{\"id\":\"feat-1\",\"type\":\"featured-products\",\"enabled\":false,\"props\":{}},{\"id\":\"cta-1\",\"type\":\"cta\",\"props\":{\"text\":\"Get started\",\"to\":\"/products\"}}]");

        when(settingsRepository.getAllStoreSettings()).thenReturn(List.of(homeSetting));

        String responseBody = given()
                .when().get("/api/storefront/config")
                .then()
                .statusCode(200)
                .extract().body().asString();

        // Parse and verify field-by-field (JsonNode equality, not string comparison)
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(responseBody);
        JsonNode sections = root.get("sections");

        // enabled:false (feat-1) was filtered out, so only hero-1 and cta-1 remain
        assertTrue(sections.isArray());
        assertEquals(2, sections.size());

        // hero-1: verify all fields present and correct
        JsonNode hero = sections.get(0);
        assertEquals("hero-1", hero.get("id").asText());
        assertEquals("hero", hero.get("type").asText());
        assertEquals("Welcome", hero.get("props").get("title").asText());
        assertEquals("Shop now", hero.get("props").get("subtitle").asText());
        assertFalse(hero.has("enabled"), "enabled field must be stripped");

        // cta-1: verify fields (no enabled field in input either — still should not appear)
        JsonNode cta = sections.get(1);
        assertEquals("cta-1", cta.get("id").asText());
        assertEquals("cta", cta.get("type").asText());
        assertEquals("Get started", cta.get("props").get("text").asText());
        assertEquals("/products", cta.get("props").get("to").asText());
        assertFalse(cta.has("enabled"), "enabled field must be stripped");
    }
}
