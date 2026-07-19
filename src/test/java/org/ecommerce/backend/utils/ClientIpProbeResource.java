package org.ecommerce.backend.utils;

import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;

/**
 * Test-only GraphQL resolver that exposes the resolved client IP.
 * Used by {@link CurrentRequestClientIpIT} to prove that {@link CurrentRequestClientIp}
 * (which injects {@code RoutingContext}) works correctly from a GraphQL resolver context.
 * <p>
 * This class lives in {@code src/test} and is only discovered during {@code @QuarkusTest}.
 */
@GraphQLApi
public class ClientIpProbeResource {

    @Inject
    CurrentRequestClientIp currentRequestClientIp;

    @Query("probeClientIp")
    public String probeClientIp() {
        return currentRequestClientIp.resolve();
    }
}
