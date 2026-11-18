package com.imprahari.app.net

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Finding and trusting the NVR, against a real HTTPS server with a
 * self-signed certificate - as ImPrahari ships.
 */
class ConnectTest {
    @Test
    fun whatPeopleTypeBecomesTheNvrsHttpsAddress() {
        assertEquals(listOf("https://192.168.68.209:8443", "https://192.168.68.209"),
                     ServerUrl.candidates("192.168.68.209"))
        // The browser's plain-HTTP address: Android won't speak HTTP, the NVR redirects anyway.
        assertEquals(listOf("https://192.168.68.209:8443", "https://192.168.68.209"),
                     ServerUrl.candidates("http://192.168.68.209:8080/"))
        assertEquals(listOf("https://192.168.68.209:8443"), ServerUrl.candidates("192.168.68.209:8443"))
        assertEquals(listOf("https://nvr.tail1234.ts.net:9443"), ServerUrl.candidates("https://nvr.tail1234.ts.net:9443"))
        assertEquals(listOf("https://host:9000", "https://host:8443"), ServerUrl.candidates("http://host:9000"))
        assertEquals(listOf("https://[fd7a::1]:8443", "https://[fd7a::1]"), ServerUrl.candidates("[fd7a::1]"))
        assertEquals(emptyList<String>(), ServerUrl.candidates("my nvr"))
    }

    @Test
    fun tailscaleAddressesBecomeTheNameTailscaleServes() {
        // What the phone's Tailscale DNS answers (reverse / short-name lookup).
        val tailnet = mapOf(
            "100.73.103.23" to "imprahari-1.tail73d15d.ts.net.",
            "imprahari-1" to "imprahari-1.tail73d15d.ts.net.",
        )
        val lookup: (String) -> String? = { tailnet[it] }
        assertEquals(listOf("https://imprahari-1.tail73d15d.ts.net"), ServerUrl.candidates("100.73.103.23", lookup))
        assertEquals(listOf("https://imprahari-1.tail73d15d.ts.net"), ServerUrl.candidates("imprahari-1", lookup))
        // The full name: Tailscale serves it on the standard HTTPS port.
        assertEquals(
            listOf("https://imprahari-1.tail73d15d.ts.net", "https://imprahari-1.tail73d15d.ts.net:8443"),
            ServerUrl.candidates("imprahari-1.tail73d15d.ts.net", lookup),
        )
        // A home address is never looked up into something else.
        assertEquals(listOf("https://192.168.68.209:8443", "https://192.168.68.209"),
                     ServerUrl.candidates("192.168.68.209", lookup))
    }

    @Test
    fun recognisesTailscaleAddresses() {
        fun v4(vararg b: Int) = b.map { it.toByte() }.toByteArray()
        assertTrue(ServerUrl.isTailscale(v4(100, 73, 103, 23)))
        assertTrue(ServerUrl.isTailscale(v4(100, 64, 0, 1)))
        assertTrue(ServerUrl.isTailscale(v4(100, 127, 255, 254)))
        assertTrue(!ServerUrl.isTailscale(v4(100, 128, 0, 1)))
        assertTrue(!ServerUrl.isTailscale(v4(192, 168, 68, 209)))
        val v6 = java.net.InetAddress.getByName("fd7a:115c:a1e0::402c:6718").address
        assertTrue(ServerUrl.isTailscale(v6))
        assertTrue(!ServerUrl.isTailscale(java.net.InetAddress.getByName("fd00::1").address))
    }

    private val servers = ArrayList<MockWebServer>()

    @After
    fun tearDown() = servers.forEach { it.shutdown() }

    /** An HTTPS server like the NVR's nginx: self-signed, for a name/IP of its own. */
    private fun nvr(certFor: String = "localhost"): Pair<MockWebServer, HeldCertificate> {
        val cert = HeldCertificate.Builder().commonName(certFor).addSubjectAlternativeName(certFor).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        repeat(4) { server.enqueue(MockResponse().setBody("""{"setup_required":false}""")) }
        server.start()
        servers += server
        return server to cert
    }

    private fun client(trust: PinnedTrust) = OkHttpClient.Builder()
        .sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
        .hostnameVerifier(trust.hostnameVerifier)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    @Test
    fun aSelfSignedNvrAsksOnceThenConnects() {
        val (server, cert) = nvr()
        var pin: String? = null
        val trust = PinnedTrust { pin }
        val http = client(trust)
        val base = "https://localhost:${server.port}"

        val first = ApiClient.probeWith(http, trust, base)
        assertTrue("got $first", first is ProbeResult.UntrustedCertificate)
        assertEquals(PinnedTrust.sha256Hex(cert.certificate.encoded), (first as ProbeResult.UntrustedCertificate).sha256)

        pin = first.sha256 // the user taps "Trust & connect"
        assertEquals(ProbeResult.Ok, ApiClient.probeWith(http, trust, base))
    }

    @Test
    fun theTrustedCertificateStandsInForTheNameItsReachedBy() {
        // Reached by a name the certificate doesn't carry (e.g. a Tailscale name).
        val (server, cert) = nvr(certFor = "nvr-new")
        val trust = PinnedTrust { PinnedTrust.sha256Hex(cert.certificate.encoded) }
        assertEquals(ProbeResult.Ok, ApiClient.probeWith(client(trust), trust, "https://localhost:${server.port}"))
    }

    @Test
    fun aDifferentCertificateIsNeverAccepted() {
        val (server, _) = nvr()
        val other = HeldCertificate.Builder().commonName("someone else").build()
        val trust = PinnedTrust { PinnedTrust.sha256Hex(other.certificate.encoded) }
        val result = ApiClient.probeWith(client(trust), trust, "https://localhost:${server.port}")
        assertTrue("got $result", result is ProbeResult.UntrustedCertificate)
    }

    @Test
    fun theNvrIsFoundOnItsHttpsPortWhenTheFirstAddressHasNothing() {
        val (server, _) = nvr()
        val closed = ServerSocket(0).use { it.localPort } // nothing listens here now
        val trust = PinnedTrust { null }
        val (base, result) = ApiClient.findWith(
            client(trust), trust, listOf("https://localhost:$closed", "https://localhost:${server.port}"),
        )
        assertEquals("https://localhost:${server.port}", base)
        assertTrue("got $result", result is ProbeResult.UntrustedCertificate) // then the trust prompt
    }

    @Test
    fun nothingListeningIsSaidPlainly() {
        val closed = ServerSocket(0).use { it.localPort }
        val trust = PinnedTrust { null }
        val (base, result) = ApiClient.findWith(client(trust), trust, listOf("https://localhost:$closed"))
        assertEquals("https://localhost:$closed", base)
        assertEquals("nothing answered on that port", (result as ProbeResult.Failed).message)
    }
}
