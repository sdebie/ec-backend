package org.ecommerce.backend.utils;

/**
 * Shared utility for resolving the real client IP from proxy headers.
 * The platform runs behind Cloudflare (quarkus.http.proxy.* configured).
 * <p>
 * Resolution order (docs/KNOWN-LIMITATIONS.md §2, §3): {@code CF-Connecting-IP} first,
 * since Cloudflare always sets it from the true TCP connection and clients cannot forge
 * it through the edge; then the <b>last</b> {@code X-Forwarded-For} entry, the one
 * appended by the trusted proxy — the FIRST entry is client-controlled (Cloudflare
 * appends the real IP to any client-supplied XFF), so it must never be used for
 * rate-limit keying; then {@code X-Real-IP}, then {@code "unknown"}.
 * <p>
 * Behind a self-managed (non-Cloudflare) proxy, the proxy must append or overwrite
 * X-Forwarded-For with the socket peer — see KNOWN-LIMITATIONS §3.
 */
public class ClientIpUtils
{

    private ClientIpUtils()
    {
        // Utility class — no instantiation
    }

    /**
     * Resolve the client IP from proxy headers.
     * Prefers CF-Connecting-IP, then the LAST X-Forwarded-For entry (the
     * proxy-appended one), then X-Real-IP, then "unknown".
     */
    public static String resolveClientIp(String cfConnectingIp, String xForwardedFor, String xRealIp)
    {
        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
            return cfConnectingIp.trim();
        }
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            int commaIndex = xForwardedFor.lastIndexOf(',');
            String lastEntry = commaIndex > -1
                    ? xForwardedFor.substring(commaIndex + 1).trim()
                    : xForwardedFor.trim();
            if (!lastEntry.isBlank()) {
                return lastEntry;
            }
        }
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return "unknown";
    }
}
