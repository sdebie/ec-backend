package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

@ApplicationScoped
public class HtmlSanitizer {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "h1", "h2", "h3", "strong", "b", "em", "i", "u", "ul", "ol", "li", "a")
            .allowUrlProtocols("https", "http", "mailto")
            .allowAttributes("href").onElements("a")
            .requireRelNofollowOnLinks()
            .toFactory();

    /**
     * Sanitises raw HTML to a safe allow-list of formatting tags.
     * Strips script elements, style elements, event-handler attributes, and any element
     * outside the allow-list.
     *
     * @param rawHtml the untrusted HTML input
     * @return cleaned HTML, or an empty string if input is null
     */
    public String sanitize(String rawHtml) {
        if (rawHtml == null) {
            return "";
        }
        return POLICY.sanitize(rawHtml);
    }
}
