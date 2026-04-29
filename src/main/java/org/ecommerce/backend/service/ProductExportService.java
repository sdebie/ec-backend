package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.common.repository.ProductImportRepository;

import java.io.PrintWriter;
import java.util.stream.Stream;

@ApplicationScoped
public class ProductExportService {

    @Inject
    ProductImportRepository productImportRepository;

    private static final String CSV_INFO_HEADER =
            "product_slug,sku,name,description,short_description,category_slug,brand_slug,retail_price,retail_sale_price,wholesale_price,wholesale_sale_price,stock,images,attributes";

    private static final String CSV_LIST_HEADER =
            "product_slug,sku,name,description,short_description,category_slug,brand_slug,stock,images,attributes";

    private static final String CSV_PRICE_HEADER =
            "sku,retail_price,wholesale_price";


    private static final String EXPORT_PRD_DETAIL_SQL = """
            SELECT
                p.slug AS product_slug,
                v.sku,
                p.name,
                p.description,
                p.short_description,
                c.slug AS category_slug,
                b.slug AS brand_slug,
                (SELECT price FROM variant_prices WHERE variant_id = v.id AND price_type = 'RETAIL_PRICE' ORDER BY created_at DESC LIMIT 1) as retail_price,
                (SELECT price FROM variant_prices WHERE variant_id = v.id AND price_type = 'RETAIL_SALE_PRICE' ORDER BY created_at DESC LIMIT 1) as retail_sale_price,
                (SELECT price FROM variant_prices WHERE variant_id = v.id AND price_type = 'WHOLESALE_PRICE' ORDER BY created_at DESC LIMIT 1) as wholesale_price,
                (SELECT price FROM variant_prices WHERE variant_id = v.id AND price_type = 'WHOLESALE_SALE_PRICE' ORDER BY created_at DESC LIMIT 1) as wholesale_sale_price,
                v.stock_quantity as stock,
                (SELECT STRING_AGG(image_url, ',') FROM product_images WHERE variant_id = v.id) as images,
                v.attributes
            FROM product_variants v
            JOIN products p ON v.product_id = p.id
            LEFT JOIN product_categories pc ON p.id = pc.product_id
            LEFT JOIN categories c ON pc.category_id = c.id
            LEFT JOIN brands b ON p.brand_id = b.id
            """;

    //"sku,retail_price,wholesale_price";
    private static final String EXPORT_PRD_PRICE_SQL = """
            SELECT
                v.sku,
                (SELECT price FROM variant_prices WHERE variant_id = v.id AND price_type = 'RETAIL_PRICE' ORDER BY created_at DESC LIMIT 1) as retail_price,
                (SELECT price FROM variant_prices WHERE variant_id = v.id AND price_type = 'WHOLESALE_PRICE' ORDER BY created_at DESC LIMIT 1) as wholesale_price
            FROM product_variants v
            """;

    private static final String EXPORT_PRD_LIST_SQL = """
            SELECT
                p.slug AS product_slug,
                v.sku,
                p.name,
                p.description,
                p.short_description,
                c.slug AS category_slug,
                b.slug AS brand_slug,
                v.stock_quantity as stock,
                (SELECT STRING_AGG(image_url, ',') FROM product_images WHERE variant_id = v.id) as images,
                v.attributes
            FROM product_variants v
            JOIN products p ON v.product_id = p.id
            LEFT JOIN product_categories pc ON p.id = pc.product_id
            LEFT JOIN categories c ON pc.category_id = c.id
            LEFT JOIN brands b ON p.brand_id = b.id
            """;

    @Transactional(Transactional.TxType.SUPPORTS)
    public void writeFullProductsInfoCsv(PrintWriter writer) {
        writer.println(CSV_INFO_HEADER);

        @SuppressWarnings("unchecked")
        Stream<Object[]> resultStream = Panache.getEntityManager()
                .createNativeQuery(EXPORT_PRD_DETAIL_SQL)
                .getResultStream();

        try (resultStream) {
            resultStream.forEach(row -> writer.println(formatCsvLine(row)));
        }

        writer.flush();
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public void writeProductsListCsv(PrintWriter writer) {
        writer.println(CSV_LIST_HEADER);

        @SuppressWarnings("unchecked")
        Stream<Object[]> resultStream = Panache.getEntityManager()
                .createNativeQuery(EXPORT_PRD_LIST_SQL)
                .getResultStream();

        try (resultStream) {
            resultStream.forEach(row -> writer.println(formatCsvLine(row)));
        }

        writer.flush();
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public void writeProductsPriceCsv(PrintWriter writer) {
        writer.println(CSV_PRICE_HEADER);

        @SuppressWarnings("unchecked")
        Stream<Object[]> resultStream = Panache.getEntityManager()
                .createNativeQuery(EXPORT_PRD_PRICE_SQL)
                .getResultStream();

        try (resultStream) {
            resultStream.forEach(row -> writer.println(formatCsvLine(row)));
        }

        writer.flush();
    }

    private String formatCsvLine(Object[] row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsvValue(row[i]));
        }
        return sb.toString();
    }

    private String escapeCsvValue(Object value) {
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
