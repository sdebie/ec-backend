package org.ecommerce.backend.service.sage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pages through Sage {@code Item/Get} results in the background, using the
 * highest {@code ID} seen so far as an ID-based cursor ({@code $filter=ID gt <lastId>})
 * instead of an offset, until every result reported by {@code TotalResults} has
 * been fetched. Each page is logged; nothing is persisted yet.
 */
@ApplicationScoped
public class SageItemSyncService
{
    private static final Logger LOG = Logger.getLogger(SageItemSyncService.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Inject
    SageApiClient sageApiClient;

    @Inject
    ObjectMapper objectMapper;

    public void syncItemsAsync(String type, Map<String, String> baseParams, String firstPageBody)
    {
        executor.submit(() -> {
            // Executor threads carry no CDI request context, but SageApiClient reads
            // SageSettingsEntity via Panache on every page, which needs one. Activate
            // it manually here — @ActivateRequestContext would not fire on this direct
            // self-invocation of runSync since it bypasses the bean's CDI proxy.
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                runSync(type, baseParams, firstPageBody);
            } finally {
                requestContext.terminate();
            }
        });
    }

    void runSync(String type, Map<String, String> baseParams, String firstPageBody)
    {
        try {
            String body = firstPageBody;
            int page = 1;
            long totalReceived = 0;

            while (true) {
                JsonNode root = objectMapper.readTree(body);
                int totalResults = root.path("TotalResults").asInt();
                int returnedResults = root.path("ReturnedResults").asInt();
                JsonNode results = root.path("Results");
                totalReceived += returnedResults;

                LOG.infof("Sage Item sync page %d (%d/%d results): %s", page, totalReceived, totalResults, body);

                if (returnedResults == 0 || !results.isArray() || results.isEmpty() || totalReceived >= totalResults) {
                    LOG.infof("Sage Item sync complete: %d/%d results fetched", totalReceived, totalResults);
                    return;
                }

                long lastId = results.get(results.size() - 1).path("ID").asLong();

                Map<String, String> nextParams = new LinkedHashMap<>(baseParams);
                nextParams.put("$filter", "ID gt " + lastId);

                body = sageApiClient.call(type, nextParams);
                page++;
            }
        } catch (Exception ex) {
            LOG.error("Sage Item sync failed", ex);
        }
    }

    @PreDestroy
    void shutdown()
    {
        executor.shutdown();
    }
}
