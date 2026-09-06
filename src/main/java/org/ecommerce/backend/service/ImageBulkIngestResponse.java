package org.ecommerce.backend.service;

import java.util.List;
import java.util.UUID;

public record ImageBulkIngestResponse(UUID jobId, List<String> accepted, List<String> skipped)
{
}
