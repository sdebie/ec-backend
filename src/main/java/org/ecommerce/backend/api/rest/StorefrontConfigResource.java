package org.ecommerce.backend.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.SettingsRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Public endpoint that assembles StorefrontClientConfig from store_settings rows.
 * Called by the frontend on each page load to resolve branding, theme, and navigation
 * without requiring a code deploy when config changes.
 */
@Path("/api/storefront/config")
@Produces(MediaType.APPLICATION_JSON)
public class StorefrontConfigResource {

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    ObjectMapper objectMapper;

    @GET
    public Response getConfig() {
        List<StoreSettingsEntity> rows = settingsRepository.getAllStoreSettings();

        Map<String, String> rawSettings = rows.stream()
                .filter(r -> r.key.startsWith("storefront."))
                .collect(Collectors.toMap(
                        r -> r.key,
                        r -> r.value
                ));

        Map<String, JsonNode> sections = rawSettings.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> parseJson(e.getValue())
                ));

        ObjectNode config = objectMapper.createObjectNode();

        applyConfigSection(config, sections.get("storefront.config"));
        applyBranding(config, sections.get("storefront.branding"));
        applyTheme(config, sections.get("storefront.theme"));
        applyNavigation(config, sections.get("storefront.navigation"));
        applyFooter(config, sections.get("storefront.footer"));
        applyContact(config, sections.get("storefront.contact"));
        applyHeader(config, sections.get("storefront.header"));
        applyHomeSections(config, sections.get("storefront.home_sections"));
        applyAuth(config, rawSettings.get("storefront.auth.login_style"));

        return Response.ok(config).build();
    }

    // ── Section assemblers ────────────────────────────────────────────────────

    private void applyConfigSection(ObjectNode out, JsonNode section) {
        if (section == null) return;
        if (section.has("clientId"))    out.put("clientId", section.get("clientId").asText());
        if (section.has("clientName"))  out.put("clientName", section.get("clientName").asText());
        if (section.has("currency"))    out.put("currency", section.get("currency").asText());
        else                            out.put("currency", "ZAR");
        if (section.has("locale"))      out.put("locale", section.get("locale").asText());
        if (section.has("stickyHeader"))out.put("stickyHeader", section.get("stickyHeader").asBoolean());
    }

    private void applyBranding(ObjectNode out, JsonNode section) {
        if (section == null) return;
        ObjectNode branding = objectMapper.createObjectNode();
        if (section.has("name"))    branding.put("name", section.get("name").asText());
        if (section.has("tagline")) branding.put("tagline", section.get("tagline").asText());

        // Restructure flat logo fields into nested logo object
        if (section.has("logoSrc")) {
            ObjectNode logo = objectMapper.createObjectNode();
            logo.put("src", section.get("logoSrc").asText());
            if (section.has("logoAlt"))    logo.put("alt", section.get("logoAlt").asText());
            if (section.has("logoWidth"))  logo.put("width", section.get("logoWidth").asInt());
            if (section.has("logoHeight")) logo.put("height", section.get("logoHeight").asInt());
            branding.set("logo", logo);
        }

        out.set("branding", branding);
    }

    private void applyTheme(ObjectNode out, JsonNode section) {
        if (section == null) return;
        // Theme fields map 1-to-1 between DB and StorefrontTheme
        out.set("theme", section.deepCopy());
    }

    private void applyNavigation(ObjectNode out, JsonNode section) {
        if (section == null) return;
        ArrayNode nav = objectMapper.createArrayNode();
        JsonNode items = section.get("items");
        if (items != null && items.isArray()) {
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                ObjectNode navItem = objectMapper.createObjectNode();
                if (item.has("id"))        navItem.put("id", item.get("id").asText());
                if (item.has("label"))     navItem.put("label", item.get("label").asText());
                if (item.has("path"))      navItem.put("path", item.get("path").asText());
                navItem.put("external",    item.has("external") && item.get("external").asBoolean());
                navItem.put("sortOrder",   item.has("sortOrder") ? item.get("sortOrder").asInt() : i);
                nav.add(navItem);
            }
        }
        out.set("nav", nav);
    }

    private void applyFooter(ObjectNode out, JsonNode section) {
        if (section == null) return;
        ObjectNode footer = objectMapper.createObjectNode();

        if (section.has("description"))    footer.put("description", section.get("description").asText());
        if (section.has("calloutHeading") && section.has("calloutBody")) {
            ObjectNode callout = objectMapper.createObjectNode();
            callout.put("heading", section.get("calloutHeading").asText());
            callout.put("body", section.get("calloutBody").asText());
            footer.set("footerCallout", callout);
        }

        // Columns: remap links[].path → links[].to
        ArrayNode columns = objectMapper.createArrayNode();
        JsonNode rawCols = section.get("columns");
        if (rawCols != null && rawCols.isArray()) {
            for (JsonNode col : rawCols) {
                ObjectNode colNode = objectMapper.createObjectNode();
                if (col.has("heading")) colNode.put("heading", col.get("heading").asText());
                colNode.set("links", remapPathToTo(col.get("links")));
                columns.add(colNode);
            }
        }
        footer.set("columns", columns);

        // Social links: remap path → to
        footer.set("socialLinks", remapPathToTo(section.get("socialLinks")));

        // Legal links: remap path → to
        footer.set("legalLinks", remapPathToTo(section.get("legalLinks")));

        out.set("footer", footer);
    }

    private void applyContact(ObjectNode out, JsonNode section) {
        if (section == null || section.isNull()) return;
        out.set("contact", section);
    }

    private void applyHeader(ObjectNode out, JsonNode section) {
        String headerJson;
        if (section != null) {
            headerJson = section.toString();
        } else {
            headerJson = "{\"announcement\":{\"enabled\":false,\"text\":\"\",\"backgroundColor\":\"#1a1f35\",\"textColor\":\"#ffffff\"}}";
        }
        try {
            ObjectNode headerNode = (ObjectNode) objectMapper.readTree(headerJson);
            out.set("header", headerNode);
        } catch (Exception e) {
            // Fallback to safe default if parsing fails
            ObjectNode defaultHeader = objectMapper.createObjectNode();
            ObjectNode announcement = objectMapper.createObjectNode();
            announcement.put("enabled", false);
            announcement.put("text", "");
            announcement.put("backgroundColor", "#1a1f35");
            announcement.put("textColor", "#ffffff");
            defaultHeader.set("announcement", announcement);
            out.set("header", defaultHeader);
        }
    }

    private void applyAuth(ObjectNode out, String loginStyle) {
        ObjectNode auth = objectMapper.createObjectNode();
        auth.put("loginStyle", loginStyle != null ? loginStyle : "page");
        out.set("auth", auth);
    }

    private void applyHomeSections(ObjectNode out, JsonNode section) {
        if (section == null || !section.isArray() || section.isEmpty()) return;
        ArrayNode sections = objectMapper.createArrayNode();
        section.forEach(s -> {
            if (s.has("enabled") && s.get("enabled").asBoolean(true)) {
                sections.add(s);
            }
        });
        out.set("sections", sections);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ArrayNode remapPathToTo(JsonNode links) {
        ArrayNode out = objectMapper.createArrayNode();
        if (links == null || !links.isArray()) return out;
        for (JsonNode link : links) {
            ObjectNode mapped = link.deepCopy();
            if (mapped.has("path")) {
                mapped.put("to", mapped.get("path").asText());
                mapped.remove("path");
            }
            mapped.remove("sortOrder");
            out.add(mapped);
        }
        return out;
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
