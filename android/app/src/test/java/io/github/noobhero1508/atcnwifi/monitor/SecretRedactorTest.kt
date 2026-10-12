package io.github.noobhero1508.atcnwifi.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun `redactor removes portal identifiers and credentials`() {
        val raw = """
            http://v1.awingconnect.vn/login?sessionId=secret&client_mac=02:00:00:00:00:01
            username=awing-user password=chap-response token=server-token
            client_ip=192.0.2.10 Cookie: session=secret-cookie chap_challenge=\245\157\272
        """.trimIndent()

        val safe = SecretRedactor.redact(raw)

        listOf(
            "secret",
            "02:00:00:00:00:01",
            "awing-user",
            "chap-response",
            "server-token",
            "192.0.2.10",
            "secret-cookie",
            "\\245\\157\\272",
        ).forEach { value -> assertFalse("Leaked: $value", safe.contains(value, ignoreCase = true)) }
        assertTrue(safe.contains("<redacted>") || safe.contains("<mac>") || safe.contains("<ip>"))
    }
}
