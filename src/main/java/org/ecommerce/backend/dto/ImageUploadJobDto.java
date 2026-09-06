package org.ecommerce.backend.dto;

import java.util.List;

public record ImageUploadJobDto(
        String id,
        String status,
        String directory,
        int accepted,
        int processed,
        int failed,
        int skipped,
        List<String> errorSamples
)
{
}
