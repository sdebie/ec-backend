package org.ecommerce.backend.mapper;

// Feature: service-layer-refactor, Property 4 (partial): Import parser row equivalence

import net.jqwik.api.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.ecommerce.backend.mapper.ProductImportParser.StagedProductCsvRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test verifying that {@link ProductImportParser#parseRow(CSVRecord)}
 * produces output identical to a reference implementation that duplicates the old
 * inline parsing logic from {@code ProductImportService}.
 * <p>
 * The reference implementation is a straightforward transcription of the pre-extraction
 * CSV field extraction, slug normalization, and stock parsing logic.
 * <p>
 */
public class ProductImportParserPropertyTest
{
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setTrim(true)
            .build();

    private final ProductImportParser parser = new ProductImportParser();

    // ── Reference implementation (old inline logic) ──────────────────────────────

    /**
     * Replicates the old inline parsing that existed in ProductImportService
     * before extraction. This is a direct transcription of the field extraction,
     * slug normalization (lowercase), and stock parsing (blank→0, invalid→null+error).
     */
    private StagedProductCsvRow referenceParseRow(CSVRecord record)
    {
        List<String> validationErrors = new ArrayList<>();

        String productSlugRaw = referenceGetValue(record, "product_slug", "product-slug");
        String productSlug = referenceNormalizeSlug(productSlugRaw);

        String sku = referenceGetValue(record, "sku", "SKU");
        String name = referenceGetValue(record, "name", "Name");
        String description = referenceGetValue(record, "description", "description");
        String categorySlug = referenceGetValue(record, "categories_slug", "Category", "category_name");
        String shortDescription = referenceGetValue(record, "short_description", "short_description");
        String stockRaw = referenceGetValue(record, "stock", "stock_quantity");
        Integer stock = referenceParseStockInteger(stockRaw, validationErrors);
        String brandSlug = referenceGetValue(record, "brand_slug", "brand_name", "Brand");
        String images = referenceGetValue(record, "images");
        String attributes = referenceGetValue(record, "attributes");

        return new StagedProductCsvRow(
                record.getRecordNumber(),
                productSlug,
                sku,
                name,
                description,
                categorySlug,
                shortDescription,
                stock,
                brandSlug,
                images,
                attributes,
                List.copyOf(validationErrors));
    }

    private String referenceGetValue(CSVRecord record, String... headers)
    {
        for (String header : headers) {
            if (record.isMapped(header)) {
                String value = record.get(header);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String referenceNormalizeSlug(String value)
    {
        String normalized = referenceIsBlank(value) ? null : value.trim();
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private boolean referenceIsBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    private Integer referenceParseStockInteger(String value, List<String> validationErrors)
    {
        if (referenceIsBlank(value)) {
            return 0;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            validationErrors.add("Invalid integer value for stock: " + value);
            return null;
        }
    }

    // ── Property test ────────────────────────────────────────────────────────────

    @Property(tries = 200)
    void parserOutputMatchesReferenceImplementation(
            @ForAll("csvRowStrings") String csvContent
    ) throws IOException
    {
        // Parse the CSV content into a CSVRecord
        CSVRecord record = parseSingleRecord(csvContent);

        // Act: parse through actual parser and reference implementation
        StagedProductCsvRow parserResult = parser.parseRow(record);
        StagedProductCsvRow referenceResult = referenceParseRow(record);

        // Assert all fields match
        assertEquals(referenceResult.recordNumber(), parserResult.recordNumber(), "recordNumber mismatch");
        assertEquals(referenceResult.productSlug(), parserResult.productSlug(), "productSlug mismatch");
        assertEquals(referenceResult.sku(), parserResult.sku(), "sku mismatch");
        assertEquals(referenceResult.name(), parserResult.name(), "name mismatch");
        assertEquals(referenceResult.description(), parserResult.description(), "description mismatch");
        assertEquals(referenceResult.categorySlug(), parserResult.categorySlug(), "categorySlug mismatch");
        assertEquals(referenceResult.shortDescription(), parserResult.shortDescription(), "shortDescription mismatch");
        assertEquals(referenceResult.stock(), parserResult.stock(), "stock mismatch");
        assertEquals(referenceResult.brandSlug(), parserResult.brandSlug(), "brandSlug mismatch");
        assertEquals(referenceResult.images(), parserResult.images(), "images mismatch");
        assertEquals(referenceResult.attributes(), parserResult.attributes(), "attributes mismatch");
        assertEquals(referenceResult.validationErrors(), parserResult.validationErrors(), "validationErrors mismatch");
    }

    @Property(tries = 100)
    void slugNormalizationAlwaysLowercases(
            @ForAll("csvRowStringsWithMixedCaseSlugs") String csvContent
    ) throws IOException
    {
        CSVRecord record = parseSingleRecord(csvContent);
        StagedProductCsvRow result = parser.parseRow(record);

        // Slug should always be lowercase (or null if blank)
        if (result.productSlug() != null) {
            assertEquals(result.productSlug().toLowerCase(Locale.ROOT), result.productSlug(),
                    "Product slug should always be lowercase after normalization");
        }
    }

    @Property(tries = 100)
    void blankStockDefaultsToZero(
            @ForAll("csvRowStringsWithBlankStock") String csvContent
    ) throws IOException
    {
        CSVRecord record = parseSingleRecord(csvContent);
        StagedProductCsvRow result = parser.parseRow(record);

        assertEquals(0, result.stock(), "Blank stock should default to 0");
        assertTrue(result.validationErrors().isEmpty(), "Blank stock should not produce validation errors");
    }

    @Property(tries = 100)
    void invalidStockProducesNullAndError(
            @ForAll("csvRowStringsWithInvalidStock") String csvContent
    ) throws IOException
    {
        CSVRecord record = parseSingleRecord(csvContent);
        StagedProductCsvRow result = parser.parseRow(record);

        assertNull(result.stock(), "Invalid stock should result in null");
        assertEquals(1, result.validationErrors().size(), "Invalid stock should produce exactly one error");
        assertTrue(result.validationErrors().get(0).startsWith("Invalid integer value for stock:"),
                "Error message should describe the invalid stock value");
    }

    @Example
    void streamsMoreThanFiveThousandRowsInOrder() throws IOException
    {
        int rowCount = 5_001;
        StringBuilder csv = new StringBuilder("product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n");
        for (int index = 0; index < rowCount; index++) {
            csv.append("product-").append(index)
                    .append(",SKU-").append(index)
                    .append(",Product ").append(index)
                    .append(",Description,category,Short,1,brand,image.jpg,{}\n");
        }

        List<StagedProductCsvRow> received = new ArrayList<>();
        parser.forEachRow(
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)),
                received::add);

        assertEquals(rowCount, received.size());
        assertEquals("SKU-0", received.get(0).sku());
        assertEquals("SKU-5000", received.get(rowCount - 1).sku());
    }

    // ── Providers ────────────────────────────────────────────────────────────────

    /**
     * Generates random CSV content (header + data row) using the canonical header names.
     * Stock values include: valid integers, blank, and invalid strings.
     */
    @Provide
    Arbitrary<String> csvRowStrings()
    {
        return buildCsvRow(stockValues());
    }

    /**
     * Generates CSV rows where the product_slug has mixed case characters.
     */
    @Provide
    Arbitrary<String> csvRowStringsWithMixedCaseSlugs()
    {
        Arbitrary<String> mixedCaseSlugs = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_0123456789")
                .ofMinLength(1).ofMaxLength(30);

        return Combinators.combine(
                mixedCaseSlugs,
                safeFieldValues(),
                safeFieldValues(),
                safeFieldValues(),
                safeFieldValues(),
                safeFieldValues(),
                validStockValues(),
                safeFieldValues()
        ).as((slug, sku, name, desc, cat, shortDesc, stock, brand) ->
                "product_slug,sku,name,description,categories_slug,short_description,stock,brand_slug,images,attributes\n"
                        + escapeCsv(slug) + ","
                        + escapeCsv(sku) + ","
                        + escapeCsv(name) + ","
                        + escapeCsv(desc) + ","
                        + escapeCsv(cat) + ","
                        + escapeCsv(shortDesc) + ","
                        + escapeCsv(stock) + ","
                        + escapeCsv(brand) + ","
                        + "img.jpg,{}"
        );
    }

    /**
     * Generates CSV rows with blank stock values (empty or whitespace-only).
     */
    @Provide
    Arbitrary<String> csvRowStringsWithBlankStock()
    {
        Arbitrary<String> blankStocks = Arbitraries.oneOf(
                Arbitraries.just(""),
                Arbitraries.just("   "),
                Arbitraries.just("\t")
        );
        return buildCsvRow(blankStocks);
    }

    /**
     * Generates CSV rows with invalid (non-numeric, non-blank) stock values.
     */
    @Provide
    Arbitrary<String> csvRowStringsWithInvalidStock()
    {
        Arbitrary<String> invalidStocks = Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10),
                Arbitraries.just("12.5"),
                Arbitraries.just("1e2"),
                Arbitraries.just("--3"),
                Arbitraries.just("abc123")
        );
        return buildCsvRow(invalidStocks);
    }

    // ── Builder helpers ──────────────────────────────────────────────────────────

    private Arbitrary<String> buildCsvRow(Arbitrary<String> stockArbitrary)
    {
        // Randomly pick between canonical and alternate header sets
        Arbitrary<String[]> headerSets = Arbitraries.oneOf(
                Arbitraries.just(new String[]{
                        "product_slug", "sku", "name", "description",
                        "categories_slug", "short_description", "stock",
                        "brand_slug", "images", "attributes"
                }),
                Arbitraries.just(new String[]{
                        "product-slug", "SKU", "Name", "description",
                        "Category", "short_description", "stock_quantity",
                        "Brand", "images", "attributes"
                }),
                Arbitraries.just(new String[]{
                        "product_slug", "sku", "Name", "description",
                        "category_name", "short_description", "stock",
                        "brand_name", "images", "attributes"
                })
        );

        // Split into two combine stages to stay within jqwik's 8-arbitrary limit
        return Combinators.combine(
                headerSets,
                slugFieldValues(),
                safeFieldValues(),
                safeFieldValues(),
                safeFieldValues(),
                safeFieldValues(),
                stockArbitrary,
                safeFieldValues()
        ).as((headers, slug, sku, name, desc, cat, stock, brand) -> {
            String headerLine = String.join(",", headers);
            String dataLine = escapeCsv(slug) + ","
                    + escapeCsv(sku) + ","
                    + escapeCsv(name) + ","
                    + escapeCsv(desc) + ","
                    + escapeCsv(cat) + ","
                    + "short desc,"
                    + escapeCsv(stock) + ","
                    + escapeCsv(brand) + ","
                    + "img.jpg,{}";
            return headerLine + "\n" + dataLine;
        });
    }

    private Arbitrary<String> stockValues()
    {
        return Arbitraries.oneOf(
                // Valid integers
                Arbitraries.integers().between(0, 99999).map(String::valueOf),
                // Blank (empty string)
                Arbitraries.just(""),
                // Invalid non-numeric strings
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8)
        );
    }

    private Arbitrary<String> validStockValues()
    {
        return Arbitraries.integers().between(0, 99999).map(String::valueOf);
    }

    private Arbitrary<String> slugFieldValues()
    {
        return Arbitraries.oneOf(
                // Normal slug
                Arbitraries.strings()
                        .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_0123456789")
                        .ofMinLength(1).ofMaxLength(30),
                // Blank (will normalize to null)
                Arbitraries.just(""),
                // With leading/trailing whitespace
                Arbitraries.strings()
                        .withChars("abcdefghijklmnopqrstuvwxyz-")
                        .ofMinLength(1).ofMaxLength(15)
                        .map(s -> "  " + s + "  ")
        );
    }

    /**
     * Generates safe field values that won't break CSV parsing.
     * Avoids newlines and double-quotes to keep row structure valid.
     */
    private Arbitrary<String> safeFieldValues()
    {
        return Arbitraries.oneOf(
                Arbitraries.strings()
                        .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_.")
                        .ofMinLength(0).ofMaxLength(30),
                Arbitraries.just("")
        );
    }

    // ── CSV utility ──────────────────────────────────────────────────────────────

    /**
     * Escapes a value for inclusion in a CSV field.
     * Wraps in double-quotes if the value contains commas, quotes, or newlines.
     */
    private static String escapeCsv(String value)
    {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private CSVRecord parseSingleRecord(String csv) throws IOException
    {
        try (CSVParser csvParser = new CSVParser(
                new StringReader(csv),
                CSV_FORMAT)) {
            List<CSVRecord> records = csvParser.getRecords();
            if (records.isEmpty()) {
                throw new IllegalStateException("No records parsed from CSV content");
            }
            return records.get(0);
        }
    }
}
