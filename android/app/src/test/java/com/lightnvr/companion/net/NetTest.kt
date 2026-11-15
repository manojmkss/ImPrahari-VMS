package com.lightnvr.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class NetTest {
    @Test
    fun normalizesWhatPeopleType() {
        assertEquals("https://192.168.68.209:8443", ServerUrl.normalize("192.168.68.209:8443"))
        assertEquals("https://nvr.example:8443", ServerUrl.normalize("  https://nvr.example:8443/  "))
        assertEquals("https://nvr.example:8443", ServerUrl.normalize("https://nvr.example:8443/api/"))
        assertEquals("http://10.0.0.5", ServerUrl.normalize("http://10.0.0.5"))
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("ftp://nvr"))
        assertNull(ServerUrl.normalize("my nvr"))
    }

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{\"alg\":\"HS256\"}".toByteArray()) + "." +
            enc.encodeToString(payloadJson.toByteArray()) + ".sig"
    }

    @Test
    fun readsTokenExpiry() {
        assertEquals(1_785_414_222L, Jwt.expiresAt(jwt("{\"sub\":\"1\",\"exp\":1785414222}")))
        assertNull(Jwt.expiresAt(jwt("{\"sub\":\"1\"}")))
        assertNull(Jwt.expiresAt("not-a-token"))
        assertNull(Jwt.expiresAt("a.!!!.c"))
    }

    @Test
    fun fingerprintFormatting() {
        assertEquals("AB:12:CD", PinnedTrust.pretty("AB12CD"))
        assertEquals(64, PinnedTrust.sha256Hex("x".toByteArray()).length)
    }
}
