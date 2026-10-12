package io.github.noobhero1508.atcnwifi.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHttpClientTest {
    @Test(expected = IllegalArgumentException::class)
    fun `307 redirect cannot forward credentials to another origin`() {
        HttpRedirectPolicy.redirect(
            PortalHttpRequest("http://186.186.0.1/login", method = "POST", body = "password=private".toByteArray()),
            307, "http://unrelated.test/collect",
        )
    }

    @Test
    fun `302 redirect discards credential body and private headers`() {
        val redirected = HttpRedirectPolicy.redirect(
            PortalHttpRequest("http://186.186.0.1/login", method = "POST",
                headers = mapOf("Cookie" to "private=1", "Authorization" to "private", "Content-Type" to "text/plain"),
                body = "private".toByteArray()),
            302, "http://v1.awingconnect.vn/Success",
        )
        assertEquals("GET", redirected.method)
        assertEquals(null, redirected.body)
        assertTrue(redirected.headers.isEmpty())
    }

    @Test
    fun `cancelling a request disconnects its active blocking connection`() = runBlocking {
        val connection = BlockingConnection()
        val session = NetworkHttpSession(PortalOptions()) { connection }
        val request = async { session.execute(PortalHttpRequest(connection.url.toString(), totalTimeoutMs = 10_000)) }
        // async starts at the next suspension; wait off the caller thread so it can start.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertTrue(connection.entered.await(2, TimeUnit.SECONDS))
        }
        request.cancelAndJoin()
        assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
        assertTrue(request.isCancelled)
    }

    @Test
    fun `request deadline disconnects work instead of waiting for body timeout`() = runBlocking {
        val connection = BlockingConnection()
        val session = NetworkHttpSession(PortalOptions()) { connection }
        try {
            session.execute(PortalHttpRequest(connection.url.toString(), totalTimeoutMs = 100))
            throw AssertionError("Expected HTTP deadline")
        } catch (_: SocketTimeoutException) {
            assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `cancelled provider cannot submit credentials after clearing thread interruption`() = runBlocking {
        val providerEntered = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val outputRequests = AtomicInteger()
        val connection = object : HttpURLConnection(URL("http://186.186.0.1/login")) {
            override fun disconnect() { disconnected.countDown() }
            override fun usingProxy() = false
            override fun connect() { }
            override fun getOutputStream(): ByteArrayOutputStream {
                outputRequests.incrementAndGet()
                return ByteArrayOutputStream()
            }
        }
        val session = NetworkHttpSession(PortalOptions()) {
            providerEntered.countDown()
            while (providerRelease.count > 0) {
                try { providerRelease.await() } catch (_: InterruptedException) {
                    // Model a DNS/network provider that consumes the interruption signal.
                }
            }
            connection
        }
        val request = async {
            session.execute(PortalHttpRequest(connection.url.toString(), method = "POST",
                body = "password=private".toByteArray(), totalTimeoutMs = 10_000))
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertTrue(providerEntered.await(2, TimeUnit.SECONDS))
        }
        try {
            request.cancelAndJoin()
        } finally {
            providerRelease.countDown()
        }
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertEquals(0, outputRequests.get())
    }

    @Test
    fun `normal response preserves bounded body without using Android default network`() = runBlocking {
        val connection = object : HttpURLConnection(URL("https://www.google.com/generate_204")) {
            override fun disconnect() { }
            override fun usingProxy() = false
            override fun connect() { }
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream("abcdef".toByteArray())
            override fun getHeaderFields(): Map<String?, List<String>> = emptyMap()
        }
        val session = NetworkHttpSession(PortalOptions()) { connection }
        val response = session.execute(PortalHttpRequest(connection.url.toString(), maxBodyBytes = 3))
        assertEquals("abc", response.text())
        assertFalse(ConnectivityPolicy.isValidInternetResponse(response.url, response))
    }

    private class BlockingConnection : HttpURLConnection(URL("http://186.186.0.1/login")) {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        override fun disconnect() { disconnected.countDown() }
        override fun usingProxy() = false
        override fun connect() { }
        override fun getResponseCode(): Int {
            entered.countDown()
            disconnected.await(15, TimeUnit.SECONDS)
            return 204
        }
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getHeaderFields(): Map<String?, List<String>> = emptyMap()
    }
}
