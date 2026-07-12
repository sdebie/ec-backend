package org.ecommerce.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HtmlSanitizer} — tests the real OWASP policy, no mocking.
 * Validates adversarial inputs are stripped and allow-listed formatting is preserved.
 *
 * Validates: Requirements 3.7
 */
class HtmlSanitizerTest
{
    private HtmlSanitizer htmlSanitizer;

    @BeforeEach
    void setUp()
    {
        htmlSanitizer = new HtmlSanitizer();
    }

    // ── Adversarial inputs — must be stripped ───────────────────────────────

    @Test
    void sanitize_shouldStripScriptTags()
    {
        String result = htmlSanitizer.sanitize("<script>alert('xss')</script>");

        assertFalse(result.contains("<script>"));
        assertFalse(result.contains("</script>"));
        assertFalse(result.contains("alert"));
    }

    @Test
    void sanitize_shouldStripOnclickHandler()
    {
        String result = htmlSanitizer.sanitize("<p onclick=\"alert('xss')\">text</p>");

        assertEquals("<p>text</p>", result);
    }

    @Test
    void sanitize_shouldStripImgWithOnerror()
    {
        String result = htmlSanitizer.sanitize("<img onerror=\"alert('xss')\" src=\"x\">");

        assertFalse(result.contains("<img"));
        assertFalse(result.contains("onerror"));
    }

    @Test
    void sanitize_shouldStripOnmouseoverHandler()
    {
        String result = htmlSanitizer.sanitize("<p onmouseover=\"evil()\">hover</p>");

        assertEquals("<p>hover</p>", result);
    }

    @Test
    void sanitize_shouldStripStyleTags()
    {
        String result = htmlSanitizer.sanitize("<style>body{display:none}</style>");

        assertFalse(result.contains("<style>"));
        assertFalse(result.contains("</style>"));
        assertFalse(result.contains("display:none"));
    }

    @Test
    void sanitize_shouldStripJavascriptProtocolInLinks()
    {
        String result = htmlSanitizer.sanitize("<a href=\"javascript:alert('xss')\">click</a>");

        // The link element may be preserved but the href must be stripped
        assertFalse(result.contains("javascript:"));
    }

    // ── Allow-listed formatting — must be preserved ─────────────────────────

    @Test
    void sanitize_shouldPreserveHeadings()
    {
        String result = htmlSanitizer.sanitize("<h2>Title</h2>");

        assertEquals("<h2>Title</h2>", result);
    }

    @Test
    void sanitize_shouldPreserveStrongTag()
    {
        String result = htmlSanitizer.sanitize("<strong>bold</strong>");

        assertEquals("<strong>bold</strong>", result);
    }

    @Test
    void sanitize_shouldPreserveValidLinkWithRelNofollow()
    {
        String result = htmlSanitizer.sanitize("<a href=\"https://example.com\">link</a>");

        assertTrue(result.contains("href=\"https://example.com\""));
        assertTrue(result.contains("rel=\"nofollow\""));
        assertTrue(result.contains(">link</a>"));
    }

    @Test
    void sanitize_shouldPreserveUnorderedList()
    {
        String result = htmlSanitizer.sanitize("<ul><li>item</li></ul>");

        assertEquals("<ul><li>item</li></ul>", result);
    }

    @Test
    void sanitize_shouldPreserveOrderedList()
    {
        String result = htmlSanitizer.sanitize("<ol><li>first</li><li>second</li></ol>");

        assertEquals("<ol><li>first</li><li>second</li></ol>", result);
    }

    @Test
    void sanitize_shouldPreserveParagraphAndBreak()
    {
        String result = htmlSanitizer.sanitize("<p>line one<br>line two</p>");

        // br may be self-closing (<br> or <br />) depending on OWASP output format
        assertTrue(result.contains("<p>line one"));
        assertTrue(result.contains("line two</p>"));
        assertTrue(result.contains("br"));
    }

    @Test
    void sanitize_shouldPreserveEmAndItalicTags()
    {
        String resultEm = htmlSanitizer.sanitize("<em>emphasized</em>");
        String resultI = htmlSanitizer.sanitize("<i>italic</i>");

        assertEquals("<em>emphasized</em>", resultEm);
        assertEquals("<i>italic</i>", resultI);
    }

    @Test
    void sanitize_shouldPreserveUnderlineTag()
    {
        String result = htmlSanitizer.sanitize("<u>underlined</u>");

        assertEquals("<u>underlined</u>", result);
    }

    // ── Null and empty input ────────────────────────────────────────────────

    @Test
    void sanitize_shouldReturnEmptyStringForNull()
    {
        String result = htmlSanitizer.sanitize(null);

        assertEquals("", result);
    }

    @Test
    void sanitize_shouldReturnEmptyStringForEmptyInput()
    {
        String result = htmlSanitizer.sanitize("");

        assertEquals("", result);
    }
}
