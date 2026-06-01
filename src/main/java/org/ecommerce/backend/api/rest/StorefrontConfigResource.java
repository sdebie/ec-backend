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

        Map<String, JsonNode> sections = rows.stream()
                .filter(r -> r.key.startsWith("storefront."))
                .collect(Collectors.toMap(
                        r -> r.key,
                        r -> parseJson(r.value)
                ));

        ObjectNode config = objectMapper.createObjectNode();

        applyConfigSection(config, sections.get("storefront.config"));
        applyBranding(config, sections.get("storefront.branding"));
        applyTheme(config, sections.get("storefront.theme"));
        applyNavigation(config, sections.get("storefront.navigation"));
        applyFooter(config, sections.get("storefront.footer"));
        applyHomeSections(config, sections.get("storefront.home_sections"));

        return Response.ok(config).build();
    }

    // ── Section assemblers ────────────────────────────────────────────────────

    private void applyConfigSection(ObjectNode out, JsonNode section) {
        if (section == null) return;
        // slug is stored as the machine id — map to "id" for StorefrontClientConfig
        if (section.has("slug"))              out.put("id", section.get("slug").asText());
        if (section.has("displayName"))       out.put("displayName", section.get("displayName").asText());
        if (section.has("locale"))            out.put("locale", section.get("locale").asText());
        if (section.has("defaultCountryCode"))out.put("defaultCountryCode", section.get("defaultCountryCode").asText());
        if (section.has("stickyHeader"))      out.put("stickyHeader", section.get("stickyHeader").asBoolean());
        // hostnames not managed through settings — provide empty array so shape is valid
        out.putArray("hostnames");
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
        ObjectNode navigation = objectMapper.createObjectNode();

        // productsLabel sits on the top-level config but logically belongs with navigation
        if (out.has("productsLabel")) {
            navigation.put("productsLabel", out.get("productsLabel").asText());
            out.remove("productsLabel");
        }

        // Remap items[].path → menuItems[].to (NavMenuItem contract)
        ArrayNode menuItems = objectMapper.createArrayNode();
        JsonNode items = section.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                ObjectNode menuItem = objectMapper.createObjectNode();
                if (item.has("id"))       menuItem.put("id", item.get("id").asText());
                if (item.has("label"))    menuItem.put("label", item.get("label").asText());
                if (item.has("path"))     menuItem.put("to", item.get("path").asText());
                if (item.has("external")) menuItem.put("external", item.get("external").asBoolean());
                menuItems.add(menuItem);
            }
        }
        navigation.set("menuItems", menuItems);
        out.set("navigation", navigation);
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

    private void applyHomeSections(ObjectNode out, JsonNode section) {
        if (section == null || !section.isArray() || section.isEmpty()) return;
        ObjectNode home = objectMapper.createObjectNode();
        // Filter to enabled sections, preserve sortOrder ordering
        ArrayNode enabled = objectMapper.createArrayNode();
        section.forEach(s -> {
            if (s.has("enabled") && s.get("enabled").asBoolean(true)) {
                enabled.add(s);
            }
        });
        home.set("sections", enabled);
        out.set("home", home);
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
