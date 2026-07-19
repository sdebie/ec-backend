package org.ecommerce.backend.utils;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * Request-scoped helper that resolves the client IP from the current HTTP request's
 * proxy headers. Designed for use in GraphQL resolvers where {@code @HeaderParam} is
 * unavailable.
 * <p>
 * Delegates to {@link ClientIpUtils#resolveClientIp(String, String)} so the resolution
 * logic is never duplicated.
 */
@RequestScoped
public class CurrentRequestClientIp {

    @Inject
    RoutingContext routingContext;

    /**
     * Resolve the client IP for the current request using X-Forwarded-For / X-Real-IP headers.
     *
     * @return the resolved client IP, or "unknown" if no proxy headers are present
     */
    public String resolve() {
        var headers = routingContext.request().headers();
        return ClientIpUtils.resolveClientIp(
                headers.get("X-Forwarded-For"),
                headers.get("X-Real-IP")
        );
    }
}
