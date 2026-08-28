package org.ecommerce.backend.csv;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.ecommerce.common.util.CsvImportUtils.getValue;
import static org.ecommerce.common.util.CsvImportUtils.parseBigDecimal;

/**
 * Parses price-import CSV files into typed {@link ParsedPriceRow} objects: a blank price
 * cell maps to {@code ZERO}, an unparseable one to {@code null} plus a validation error.
 */
@ApplicationScoped
public class ProductPriceImportParser
{

    /**
     * Parses a single {@link CSVRecord} into a typed {@link ParsedPriceRow}. Reads
     * {@code sku} from either "sku" or "SKU"; reads {@code retail_price}/"Retail Price" and
     * {@code wholesale_price}/"Wholesale Price" the same way, each blank cell mapping to
     * {@code BigDecimal.ZERO} and each unparseable one to {@code null} plus a validation error.
     */
    public ParsedPriceRow parsePriceCsvRow(CSVRecord record)
    {
        List<String> validationErrors = new ArrayList<>();
        return new ParsedPriceRow(
                record.getRecordNumber(),
                getValue(record, "sku", "SKU"),
                parseBigDecimal(record, validationErrors, "retail_price", "Retail Price"),
                parseBigDecimal(record, validationErrors, "wholesale_price", "Wholesale Price"),
                List.copyOf(validationErrors));
    }

    /**
     * Parses all rows from an input stream of CSV data into a list of {@link ParsedPriceRow}
     * objects, using a case-insensitive header row and trimmed values.
     */
    public List<ParsedPriceRow> parseAll(InputStream is) throws IOException
    {
        List<ParsedPriceRow> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is));
             CSVParser csvParser = new CSVParser(
                     reader,
                     CSVFormat.DEFAULT.builder()
                             .setHeader()
                             .setSkipHeaderRecord(true)
                             .setIgnoreHeaderCase(true)
                             .setTrim(true)
                             .build()
             )) {

            for (CSVRecord record : csvParser) {
                rows.add(parsePriceCsvRow(record));
            }
        }

        return rows;
    }

    /**
     * Typed representation of a parsed price CSV row.
     * <p>
     * Contains the raw parsed values and any validation errors
     * accumulated during parsing (e.g. invalid decimal format).
     *
     * @param recordNumber     the 1-based row number from the CSV file
     * @param sku              the SKU value (maybe null if header not found)
     * @param retailPrice      the parsed retail price (ZERO if blank, null if invalid)
     * @param wholesalePrice   the parsed wholesale price (ZERO if blank, null if invalid)
     * @param validationErrors list of parsing errors (empty if row parsed cleanly)
     */
    public record ParsedPriceRow(
            long recordNumber,
            String sku,
            BigDecimal retailPrice,
            BigDecimal wholesalePrice,
            List<String> validationErrors
    )
    {
    }
}
