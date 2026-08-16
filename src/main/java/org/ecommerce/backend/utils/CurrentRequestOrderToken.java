package org.ecommerce.backend.utils;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * Request-scoped helper that resolves the {@code X-Order-Token} header from the current
 * HTTP request. Designed for use in GraphQL resolvers where {@code @HeaderParam} is
 * unavailable — mirrors {@link CurrentRequestClientIp} exactly (guest-order-authorization
 * design.md §3.2).
 */
@RequestScoped
public class CurrentRequestOrderToken
{
    @Inject
    RoutingContext routingContext;

    /**
     * @return the order capability token from {@code X-Order-Token}, or {@code null} if absent
     */
    public String resolve()
    {
        return routingContext.request().headers().get("X-Order-Token");
    }
}
