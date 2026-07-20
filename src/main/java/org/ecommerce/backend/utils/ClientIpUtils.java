package org.ecommerce.backend.utils;

/**
 * Shared utility for resolving the real client IP from proxy headers.
 * The platform runs behind Cloudflare (quarkus.http.proxy.* configured),
 * so the first value in X-Forwarded-For is the original client IP.
 */
public class ClientIpUtils {

    private ClientIpUtils() {
        // Utility class — no instantiation
    }

    /**
     * Resolve the client IP from proxy headers.
     * Prefers X-Forwarded-For (first entry before any comma), falls back to X-Real-IP,
     * then "unknown" if neither is available.
     */
    public static String resolveClientIp(String xForwardedFor, String xRealIp) {
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            int commaIndex = xForwardedFor.indexOf(',');
            if (commaIndex > -1) {
                return xForwardedFor.substring(0, commaIndex).trim();
            }
            return xForwardedFor.trim();
        }
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return "unknown";
    }
}
