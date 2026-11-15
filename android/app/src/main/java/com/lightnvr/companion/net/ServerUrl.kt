package com.lightnvr.companion.net

object ServerUrl {
    /** Where LightNVR serves HTTPS unless set up otherwise (HTTPS_PORT). */
    const val NVR_HTTPS_PORT = 8443

    /**
     * Turn what a person types into a base URL: "192.168.1.5:8443" becomes
     * "https://192.168.1.5:8443". Trailing slashes and a trailing "/api" are
     * dropped (people paste either). Returns null if it can't be an address.
     */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        s = s.trimEnd('/')
        if (s.endsWith("/api")) s = s.removeSuffix("/api").trimEnd('/')
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "https" && scheme != "http") return null
        val rest = s.substringAfter("://")
        if (rest.isBlank() || rest.any { it.isWhitespace() }) return null
        return "$scheme://$rest"
    }

    /**
     * The addresses to try, best first, for what was typed. People type the
     * NVR's IP alone, or the address they use in the browser - which may be
     * the plain-HTTP port (8080) that only redirects to HTTPS. The app only
     * speaks HTTPS (Android blocks plain HTTP, and the session must not go
     * over it), so:
     *  - "192.168.1.5"          -> https://192.168.1.5:8443, https://192.168.1.5
     *  - "http://host:8080"     -> https://host:8443, https://host
     *  - "http://host:9000"     -> https://host:9000, https://host:8443
     *  - "https://host:9443"    -> https://host:9443 (exactly what was asked)
     *  - "nvr.tail1234.ts.net"  -> https://nvr.tail1234.ts.net, then :8443 -
     *    LightNVR's Tailscale access is `tailscale serve` on the standard port
     *
     * [tailnetName] turns a Tailscale IP or short name into its full
     * MagicDNS name ("100.73.103.23" / "nvr" -> "nvr.tail1234.ts.net"), which
     * then comes first: Tailscale's certificate is for that name only, so the
     * IP or the short name can't complete the secure connection.
     */
    fun candidates(input: String, tailnetName: (String) -> String? = { null }): List<String> {
        val base = normalize(input) ?: return emptyList()
        val scheme = base.substringBefore("://")
        val rest = base.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = rest.substring(authority.length)
        val (host, port) = splitPort(authority)
        if (host.isEmpty()) return emptyList()
        fun https(p: Int?) = "https://" + host + (p?.let { ":$it" } ?: "") + path
        val full = tailnetName(host)?.trimEnd('.')?.takeIf { it.endsWith(".ts.net") && it != host }
        if (full != null) {
            // Typed as a Tailscale IP or short name: its full name, as served by Tailscale.
            val asked = if (port != null) "https://$host:$port$path" else null
            return listOfNotNull("https://$full$path", asked).distinct()
        }
        val out = when {
            host.endsWith(".ts.net") && port == null -> listOf(https(null), https(NVR_HTTPS_PORT))
            scheme == "https" && port != null -> listOf(https(port))
            scheme == "https" -> listOf(https(NVR_HTTPS_PORT), https(null))
            port == null || port == 80 || port == 8080 -> listOf(https(NVR_HTTPS_PORT), https(null))
            else -> listOf(https(port), https(NVR_HTTPS_PORT))
        }
        return out.distinct()
    }

    /** "host:8443" -> (host, 8443); "[fd7a::1]:8443" -> ([fd7a::1], 8443); no port -> null. */
    private fun splitPort(authority: String): Pair<String, Int?> {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return authority to null
            val port = authority.substring(end + 1).removePrefix(":").toIntOrNull()
            return authority.substring(0, end + 1) to port
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to null
        val port = authority.substring(colon + 1).toIntOrNull() ?: return authority to null
        return authority.substring(0, colon) to port
    }

    /**
     * The full MagicDNS name of a Tailscale IP or short name, via a reverse
     * lookup (the Tailscale app answers it) - or null if it isn't on a
     * tailnet. Blocking: call off the main thread.
     */
    fun lookupTailnetName(host: String): String? = try {
        val addr = java.net.InetAddress.getByName(host.removePrefix("[").removeSuffix("]"))
        if (isTailscale(addr.address)) addr.canonicalHostName.trimEnd('.').takeIf { it.endsWith(".ts.net") } else null
    } catch (e: java.io.IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /** Tailscale addresses: 100.64.0.0/10, and fd7a:115c:a1e0::/48. */
    fun isTailscale(raw: ByteArray): Boolean = when (raw.size) {
        4 -> (raw[0].toInt() and 0xFF) == 100 && (raw[1].toInt() and 0xC0) == 0x40
        16 -> (raw[0].toInt() and 0xFF) == 0xFD && (raw[1].toInt() and 0xFF) == 0x7A &&
            (raw[2].toInt() and 0xFF) == 0x11 && (raw[3].toInt() and 0xFF) == 0x5C &&
            (raw[4].toInt() and 0xFF) == 0xA1 && (raw[5].toInt() and 0xFF) == 0xE0
        else -> false
    }
}
