package org.ecommerce.backend.service.sage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SageItemSyncServiceTest
{
    @Mock
    private SageApiClient sageApiClient;

    private SageItemSyncService service;

    @BeforeEach
    void setUp()
    {
        service = new SageItemSyncService();
        service.sageApiClient = sageApiClient;
        service.objectMapper = new ObjectMapper();
    }

    private static String page(int total, int returned, int... ids)
    {
        StringBuilder results = new StringBuilder();
        for (int id : ids) {
            if (!results.isEmpty()) results.append(',');
            results.append("{\"Code\":\"SKU").append(id).append("\",\"ID\":").append(id).append('}');
        }
        return "{\"TotalResults\":" + total + ",\"ReturnedResults\":" + returned
                + ",\"Results\":[" + results + "]}";
    }

    @Test
    void stopsWithoutFurtherCallsWhenFirstPageAlreadyCoversTotalResults()
    {
        String firstPage = page(3, 3, 1, 2, 3);

        service.runSync("Item/Get", Map.of("$top", "10"), firstPage);

        verify(sageApiClient, never()).call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void pagesUsingLastIdAsCursorUntilTotalResultsReached()
    {
        String firstPage = page(15, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String secondPage = page(15, 5, 11, 12, 13, 14, 15);

        when(sageApiClient.call(eq("Item/Get"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(secondPage);

        service.runSync("Item/Get", Map.of("$top", "10"), firstPage);

        verify(sageApiClient).call(eq("Item/Get"), eq(Map.of("$top", "10", "$filter", "ID gt 10")));
    }

    @Test
    void stopsOnEmptyResultsPageWithoutThrowing()
    {
        String firstPage = page(100, 0);

        service.runSync("Item/Get", Map.of("$top", "10"), firstPage);

        verify(sageApiClient, never()).call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
    }
}
