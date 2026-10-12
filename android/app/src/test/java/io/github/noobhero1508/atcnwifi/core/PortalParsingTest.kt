package io.github.noobhero1508.atcnwifi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalParsingTest {
    @Test
    fun `wifiInfo parser decodes JS escapes without evaluating code`() {
        val html = """
            <script>
              const wifiInfo = {
                'logged-in': 'no',
                mac: '02:00:00:00:00:01',
                ip: "192.0.2.10",
                chap_id: "\107",
                chap_challenge: '\x41\u0042\103',
                a_boolean: true,
                a_null: null,
                ignored: window.stealCookies(),
                note: "a } brace and an escaped \" quote"
              };
              window.evil = true;
            </script>
        """.trimIndent()

        val info = requireNotNull(PortalParsers.extractWifiInfo(html))
        assertEquals("no", info["logged_in"])
        assertEquals("G", info["chap_id"])
        assertEquals("ABC", info["chap_challenge"])
        assertEquals(true, info["a_boolean"])
        assertTrue(info.containsKey("a_null"))
        assertFalse(info.containsKey("ignored"))
        assertEquals("a } brace and an escaped \" quote", info["note"])
    }

    @Test
    fun `wifiInfo parser supports var and let and rejects missing assignment`() {
        assertEquals("yes", PortalParsers.extractWifiInfo("let wifiInfo={authenticated:'yes'}")?.get("authenticated"))
        assertEquals("1", PortalParsers.extractWifiInfo("var wifiInfo = { logged_in: 1 };")?.get("logged_in"))
        assertNull(PortalParsers.extractWifiInfo("const somethingElse = { mac: 'x' };"))
        assertNull(PortalParsers.extractWifiInfo("const wifiInfo = { mac: 'unterminated'"))
    }

    @Test
    fun `JS decoder covers hex unicode octal controls and line continuation`() {
        val token = """"\x48\u0069\041\nline\
continued\\end""""
        assertEquals("Hi!\nlinecontinued\\end", PortalParsers.decodeJsString(token))
    }

    @Test
    fun `serial discovery prefers wifiInfo then HTML then Khu A fallback`() {
        val fallback = "CC:2D:E0:19:00:6C"
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            PortalParsers.discoverSerial("serial=11:22:33:44:55:66", mapOf("router_serial" to "aa:bb:cc:dd:ee:ff"), fallback),
        )
        assertEquals(
            "11:22:33:44:55:66",
            PortalParsers.discoverSerial("/login?serial=11%3A22%3A33%3A44%3A55%3A66", null, fallback),
        )
        assertEquals(fallback, PortalParsers.discoverSerial("<html>none</html>", emptyMap(), fallback))
    }

    @Test
    fun `form parser chooses MikroTik POST form and resolves HTML entities`() {
        val html = """
            <form action="/search" method="get"><input name="username" value="decoy"></form>
            <FORM id='frmLogin' ACTION='../login?one=1&amp;two=2' METHOD='POST'>
              <input type='hidden' name='username' value='user&amp;name'>
              <input type="hidden" name="password" value="abc&#x21;">
              <input type=hidden name=dst value='http://v1.awingconnect.vn/Success'>
            </FORM>
        """.trimIndent()

        val form = requireNotNull(PortalParsers.chooseLoginForm(html, "http://186.186.0.1/portal/page"))
        assertEquals("http://186.186.0.1/login?one=1&two=2", form.action)
        assertEquals("user&name", form.fields["username"])
        assertEquals("abc!", form.fields["password"])
        assertEquals("http://v1.awingconnect.vn/Success", form.fields["dst"])
    }

    @Test
    fun `form parser tolerates greater-than signs inside quoted attributes`() {
        val html = """<form name="mikrotik" action="http://186.186.0.1/login" method="post">
            <input name="username" value="a>b"><input name="password" value="secret"></form>"""
        val form = requireNotNull(PortalParsers.chooseLoginForm(html, "http://v1.awingconnect.vn/login"))
        assertEquals("a>b", form.fields["username"])
    }

    @Test
    fun `form parser matches the hidden AWING MikroTik form shape`() {
        val html = """
            <button>CONNECT TO INTERNET</button>
            <form name="mikrotik" id="frmLogin" action="http://186.186.0.1/login"
                  method="post" style="display: none;">
              <input type="hidden" name="username" value="temporary-user">
              <input type="hidden" name="password" value="temporary-chap-response">
              <input type="hidden" name="dst" value="http://v1.awingconnect.vn/Success">
              <input type="hidden" name="popup" value="false">
            </form>
        """.trimIndent()

        val form = requireNotNull(
            PortalParsers.chooseLoginForm(html, "http://v1.awingconnect.vn/Connecting?sessionId=redacted"),
        )
        assertEquals("http://186.186.0.1/login", form.action)
        assertEquals("temporary-user", form.fields["username"])
        assertEquals("temporary-chap-response", form.fields["password"])
        assertEquals("false", form.fields["popup"])
    }

    @Test
    fun `action allowlist rejects lookalike hosts and non-http schemes`() {
        val allowlist = setOf("186.186.0.1", "v1.awingconnect.vn")
        assertTrue(PortalParsers.isAllowedFormAction("http://186.186.0.1/login", allowlist))
        assertTrue(PortalParsers.isAllowedFormAction("https://v1.awingconnect.vn/login", allowlist))
        assertFalse(PortalParsers.isAllowedFormAction("http://v1.awingconnect.vn.evil.test/login", allowlist))
        assertFalse(PortalParsers.isAllowedFormAction("javascript:alert(1)", allowlist))
        assertFalse(PortalParsers.isAllowedFormAction("file:///etc/passwd", allowlist))
    }

    @Test
    fun `AWING URL contains fresh context encoded as URL octal`() {
        val options = PortalOptions()
        val url = PortalProtocol.buildAwingLoginUrl(
            linkedMapOf(
                "mac" to "02:00:00:00:00:01",
                "ip" to "192.0.2.10",
                "chap_id" to "G",
                "chap_challenge" to "AB",
            ),
            "CC:2D:E0:19:00:6C",
            options,
        )
        assertTrue(url.startsWith("http://v1.awingconnect.vn/login?"))
        assertTrue(url.contains("serial=CC%3A2D%3AE0%3A19%3A00%3A6C"))
        assertTrue(url.contains("client_mac=02%3A00%3A00%3A00%3A00%3A01"))
        assertTrue(url.contains("chap_id=%5C107"))
        assertTrue(url.contains("chap_challenge=%5C101%5C102"))
        assertFalse(url.contains(" "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `AWING URL refuses incomplete router context`() {
        PortalProtocol.buildAwingLoginUrl(
            mapOf("mac" to "02:00:00:00:00:01"),
            "CC:2D:E0:19:00:6C",
            PortalOptions(),
        )
    }

    @Test
    fun `percent encoding is RFC3986 and UTF-8 safe`() {
        assertEquals("a%20b%2Bc%2F%C4%91", PortalEncoding.percentEncode("a b+c/đ"))
    }

    @Test
    fun `JSON parser round trips AWING DTO and escaped form HTML`() {
        val source = """{"captiveContext":{"contentAuthenForm":"<form action=\"/login\">\u0111</form>","userContext":{"old":true}},"token":"a\tb","items":[1,false,null]}"""
        val root = SimpleJson.obj(SimpleJson.parse(source))!!
        val captive = SimpleJson.obj(root["captiveContext"])!!
        assertTrue(SimpleJson.string(captive["contentAuthenForm"])!!.contains("đ"))
        captive["userContext"] = JsonValue.ObjectValue(linkedMapOf())

        val reparsed = SimpleJson.obj(SimpleJson.parse(SimpleJson.stringify(root)))!!
        assertEquals("a\tb", SimpleJson.string(reparsed["token"]))
        assertTrue(SimpleJson.obj(SimpleJson.obj(reparsed["captiveContext"])?.get("userContext"))?.values?.isEmpty() == true)
    }

    @Test
    fun `diagnostic serial mask keeps router family but hides middle bytes`() {
        assertEquals("CC:2D:**:**:00:6C", PortalParsers.maskSerial("CC:2D:E0:19:00:6C"))
        assertNull(PortalParsers.maskSerial("not-a-serial"))
    }
}
