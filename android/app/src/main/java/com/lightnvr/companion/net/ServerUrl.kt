package com.lightnvr.companion.net

object ServerUrl {
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
}
