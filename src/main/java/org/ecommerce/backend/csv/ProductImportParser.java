package org.ecommerce.backend.csv;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.ecommerce.common.util.CsvImportUtils.getValue;
import static org.ecommerce.common.util.CsvImportUtils.isBlank;

/**
 * Parses product import CSV files into typed row objects.
 * Extracted from {@code ProductImportService} to separate parsing concerns
 * from validation and orchestration.
 */
@ApplicationScoped
public class ProductImportParser {

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setTrim(true)
            .build();

    /**
     * Parses an entire CSV input stream into a list of typed row objects.
     *
     * @param inputStream the CSV input stream (with header row)
     * @return list of parsed rows in file order
     * @throws IOException if the stream cannot be read
     */
    public List<StagedProductCsvRow> parse(InputStream inputStream) throws IOException {
        List<StagedProductCsvRow> rows = new ArrayList<>();

        forEachRow(inputStream, rows::add);

        return rows;
    }

    /**
     * Parses rows one at a time so callers that stage a large import can keep
     * only their current persistence chunk in memory.
     *
     * @param inputStream the CSV input stream (with header row)
     * @param rowConsumer receives rows in CSV order
     * @throws IOException if the stream cannot be read
     */
    public void forEachRow(InputStream inputStream, Consumer<StagedProductCsvRow> rowConsumer) throws IOException {

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
             CSVParser csvParser = new CSVParser(reader, CSV_FORMAT)) {

            for (CSVRecord record : csvParser) {
                rowConsumer.accept(parseRow(record));
            }
        }
    }

    /**
     * Parses a single CSV record into a typed row object.
     *
     * @param record the Apache Commons CSV record
     * @return the parsed row with any inline validation errors (e.g. unparseable stock)
     */
    public StagedProductCsvRow parseRow(CSVRecord record) {
        List<String> validationErrors = new ArrayList<>();
        Integer stock = parseStockInteger(getValue(record, "stock", "stock_quantity"), validationErrors);

        return new StagedProductCsvRow(
                record.getRecordNumber(),
                normalizeSlug(getValue(record, "product_slug", "product-slug")),
                getValue(record, "sku", "SKU"),
                getValue(record, "name", "Name"),
                getValue(record, "description", "description"),
                getValue(record, "categories_slug", "Category", "category_name"),
                getValue(record, "short_description", "short_description"),
                stock,
                getValue(record, "brand_slug", "brand_name", "Brand"),
                getValue(record, "images"),
                getValue(record, "attributes"),
                List.copyOf(validationErrors));
    }

    private String normalizeSlug(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private Integer parseStockInteger(String value, List<String> validationErrors) {
        if (isBlank(value)) {
            return 0;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            validationErrors.add("Invalid integer value for stock: " + value);
            return null;
        }
    }

    /**
     * Represents a single parsed row from a product import CSV file.
     * Contains the raw parsed values and any parse-level validation errors
     * (e.g. unparseable stock values).
     */
    public record StagedProductCsvRow(
            long recordNumber,
            String productSlug,
            String sku,
            String name,
            String description,
            String categorySlug,
            String shortDescription,
            Integer stock,
            String brandSlug,
            String images,
            String attributes,
            List<String> validationErrors
    ) {
    }
}
