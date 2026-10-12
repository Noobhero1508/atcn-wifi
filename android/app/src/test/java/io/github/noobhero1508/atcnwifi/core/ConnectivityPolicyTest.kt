package io.github.noobhero1508.atcnwifi.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectivityPolicyTest {
    private val options = PortalOptions()

    @Test
    fun `HTTP walled garden and captive HTML cannot report Internet online`() {
        val http = "http://connectivitycheck.gstatic.com/generate_204"
        assertFalse(ConnectivityPolicy.isValidInternetResponse(http, response(http, 204)))
        val https = "https://connectivitycheck.gstatic.com/generate_204"
        assertFalse(ConnectivityPolicy.isValidInternetResponse(https, response(https, 200, "Login to Wi-Fi")))
        assertFalse(ConnectivityPolicy.isValidInternetResponse(https, response(https, 302)))
        assertTrue(ConnectivityPolicy.isValidInternetResponse(https, response(https, 204)))
    }

    @Test
    fun `ordinary HTTP success and unrelated 404 are not KTX identity`() {
        assertFalse(ConnectivityPolicy.gatewayEvidence(response(options.gatewayLoginUrl, 200, "It works"), options).recognized)
        assertFalse(ConnectivityPolicy.gatewayEvidence(response(options.gatewayLoginUrl, 404, "Not Found"), options).recognized)
        assertFalse(ConnectivityPolicy.isValidInternetResponse("https://example.org/", response("https://example.org/", 200)))
    }

    @Test
    fun `logged in router with broken Internet does not authorize logout`() {
        val body = """<script>const wifiInfo = {'logged-in':'yes',mac:'02:00:00:00:00:01',ip:'192.0.2.10'};</script>"""
        val evidence = ConnectivityPolicy.gatewayEvidence(response(options.gatewayLoginUrl, 200, body), options)
        assertTrue(evidence.recognized)
        assertTrue(evidence.loggedIn)
        assertFalse(evidence.loginRequired)
    }

    @Test
    fun `fresh router challenge and known portal redirect identify required login`() {
        val body = """<script>const wifiInfo = {'logged-in':'no',mac:'02:00:00:00:00:01',ip:'192.0.2.10',chap_id:'G',chap_challenge:'AB'};</script>"""
        assertTrue(ConnectivityPolicy.gatewayEvidence(response(options.gatewayLoginUrl, 200, body), options).loginRequired)
        val redirected = PortalHttpResponse(302, "http://connectivitycheck.gstatic.com/generate_204",
            mapOf("Location" to listOf("http://v1.awingconnect.vn/login?redacted=1")), ByteArray(0))
        assertTrue(ConnectivityPolicy.gatewayEvidence(redirected, options).loginRequired)
        assertFalse(ConnectivityPolicy.isKnownPortalLocation("http://v1.awingconnect.vn.evil.test/login", redirected.url, options))
    }

    @Test
    fun `explicit logged out session overrides allowlisted HTTPS success`() {
        val body = """<script>const wifiInfo = {'logged-in':'no',mac:'02:00:00:00:00:01',ip:'192.0.2.10'};</script>"""
        val evidence = ConnectivityPolicy.gatewayEvidence(response(options.gatewayLoginUrl, 200, body), options)
        assertEquals(ConnectivityState.LOGIN_REQUIRED, ConnectivityPolicy.stateForProbe(true, evidence))
        assertEquals(ConnectivityState.ONLINE, ConnectivityPolicy.stateForProbe(true, GatewayEvidence()))
        assertEquals(ConnectivityState.UNKNOWN, ConnectivityPolicy.stateForProbe(false, GatewayEvidence()))
    }

    @Test
    fun `authenticated status and success redirects cannot trigger reauthentication`() {
        val statusRedirect = PortalHttpResponse(302, options.gatewayLoginUrl,
            mapOf("Location" to listOf("http://186.186.0.1/status")), ByteArray(0))
        val status = ConnectivityPolicy.gatewayEvidence(statusRedirect, options)
        assertTrue(status.recognized)
        assertTrue(status.loggedIn)
        assertFalse(status.loginRequired)
        assertEquals(ConnectivityState.ONLINE, ConnectivityPolicy.stateForProbe(true, status))
        val successRedirect = PortalHttpResponse(302, "http://connectivitycheck.gstatic.com/generate_204",
            mapOf("Location" to listOf("http://v1.awingconnect.vn/Success")),
            "Continue to http://v1.awingconnect.vn/Success".toByteArray())
        assertFalse(ConnectivityPolicy.gatewayEvidence(successRedirect, options).loginRequired)
        assertFalse(ConnectivityPolicy.isKnownPortalLocation("http://v1.awingconnect.vn/Success", successRedirect.url, options))
        assertFalse(ConnectivityPolicy.isKnownPortalLocation("/logout?", options.gatewayLoginUrl, options))
    }

    private fun response(url: String, code: Int, body: String = "") =
        PortalHttpResponse(code, url, emptyMap(), body.toByteArray())
}
