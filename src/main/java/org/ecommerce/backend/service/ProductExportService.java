package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.common.repository.ProductRepository;

import java.io.PrintWriter;
import java.util.stream.Stream;

@ApplicationScoped
public class ProductExportService
{
    private static final String CSV_INFO_HEADER =
            "product_id,sku,name,description,short_description,product_categories,product_type,brand_slug,retail_price,wholesale_price,stock,images,attributes";

    private static final String CSV_LIST_HEADER =
            "product_id,sku,name,description,short_description,product_categories,product_type,brand_slug,stock,images,attributes";

    private static final String CSV_PRICE_HEADER =
            "sku,retail_price,wholesale_price";

    @Inject
    ProductRepository productRepository;

    @Transactional(Transactional.TxType.SUPPORTS)
    public void writeFullProductsInfoCsv(PrintWriter writer)
    {
        writer.println(CSV_INFO_HEADER);

        try (Stream<Object[]> resultStream = productRepository.streamExportRows()) {
            resultStream.forEach(row -> writer.println(formatCsvLine(row)));
        }

        writer.flush();
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public void writeProductsListCsv(PrintWriter writer)
    {
        writer.println(CSV_LIST_HEADER);

        try (Stream<Object[]> resultStream = productRepository.streamListExportRows()) {
            resultStream.forEach(row -> writer.println(formatCsvLine(row)));
        }

        writer.flush();
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public void writeProductsPriceCsv(PrintWriter writer)
    {
        writer.println(CSV_PRICE_HEADER);

        try (Stream<Object[]> resultStream = productRepository.streamPriceExportRows()) {
            resultStream.forEach(row -> writer.println(formatCsvLine(row)));
        }

        writer.flush();
    }

    private String formatCsvLine(Object[] row)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsvValue(row[i]));
        }
        return sb.toString();
    }

    private String escapeCsvValue(Object value)
    {
        if (value == null) {
            return "";
        }

        String text = String.valueOf(value);
        boolean needsQuotes = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
        if (text.contains("\"")) {
            text = text.replace("\"", "\"\"");
        }

        return needsQuotes ? "\"" + text + "\"" : text;
    }
}
