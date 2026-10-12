package io.github.noobhero1508.atcnwifi.core

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class PortalHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val followRedirects: Boolean = true,
    val connectTimeoutMs: Int? = null,
    val readTimeoutMs: Int? = null,
    /** One deadline covering DNS, TLS, body reads and all redirects. */
    val totalTimeoutMs: Long? = null,
    val maxBodyBytes: Int = 2_000_000,
)

internal data class PortalHttpResponse(
    val status: Int,
    val url: String,
    val headers: Map<String?, List<String>>,
    val body: ByteArray,
) {
    fun text(): String {
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull().orEmpty()
        val charsetName = Regex("""charset\s*=\s*["']?([^;"'\s]+)""", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
        val charset = runCatching { charsetName?.let(Charset::forName) }.getOrNull() ?: StandardCharsets.UTF_8
        return body.toString(charset)
    }
}

/** One cookie-isolated HTTP session whose sockets are always opened by the supplied Wi-Fi network. */
internal class NetworkHttpSession(
    private val options: PortalOptions,
    private val openConnection: (URL) -> HttpURLConnection,
) {
    constructor(network: Network, options: PortalOptions) : this(
        options,
        { url -> network.openConnection(url) as HttpURLConnection },
    )

    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    suspend fun execute(initial: PortalHttpRequest): PortalHttpResponse {
        val budget = (initial.totalTimeoutMs ?: options.requestTimeoutMs.toLong()).coerceAtLeast(1)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget)
        return withTimeoutOrNull(budget) {
            suspendCancellableCoroutine { continuation ->
                val connection = AtomicReference<HttpURLConnection?>()
                val cancelled = AtomicBoolean(false)
                val task = AtomicReference<Future<*>?>()
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    connection.getAndSet(null)?.disconnect()
                    task.get()?.cancel(true)
                }
                try {
                    task.set(HTTP_WORKERS.submit {
                        try {
                            val result = executeBlocking(initial, deadline, connection, cancelled)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally {
                            connection.getAndSet(null)?.disconnect()
                        }
                    })
                    if (!continuation.isActive) task.get()?.cancel(true)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        } ?: throw SocketTimeoutException("HTTP request deadline exceeded")
    }

    private fun executeBlocking(
        initial: PortalHttpRequest,
        deadline: Long,
        activeConnection: AtomicReference<HttpURLConnection?>,
        cancelled: AtomicBoolean,
    ): PortalHttpResponse {
        var request = initial
        var redirects = 0
        while (true) {
            remainingMs(deadline, cancelled)
            val response = executeOnce(request, deadline, activeConnection, cancelled)
            val location = response.header("Location")
            if (!request.followRedirects || response.status !in REDIRECT_CODES || location.isNullOrBlank()) {
                return response
            }
            require(redirects++ < MAX_REDIRECTS) { "Too many HTTP redirects" }
            request = HttpRedirectPolicy.redirect(request, response.status, location)
        }
    }

    private fun executeOnce(
        request: PortalHttpRequest,
        deadline: Long,
        activeConnection: AtomicReference<HttpURLConnection?>,
        cancelled: AtomicBoolean,
    ): PortalHttpResponse {
        val url = URL(request.url)
        val uri = URI(request.url)
        val connection = openConnection(url)
        activeConnection.set(connection)
        try {
            // A DNS/provider call may clear thread interruption; use a separate cancellation flag.
            remainingMs(deadline, cancelled)
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method.uppercase(Locale.ROOT)
            connection.connectTimeout = minOf(request.connectTimeoutMs ?: options.requestTimeoutMs, remainingMs(deadline, cancelled))
            connection.readTimeout = minOf(request.readTimeoutMs ?: options.requestTimeoutMs, remainingMs(deadline, cancelled))
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("Connection", "close")
            cookies.get(uri, emptyMap()).forEach { (name, values) ->
                if (!name.equals("Cookie2", ignoreCase = true)) {
                    connection.setRequestProperty(name, values.joinToString("; "))
                }
            }
            request.headers.forEach(connection::setRequestProperty)
            request.body?.let { body ->
                remainingMs(deadline, cancelled)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            remainingMs(deadline, cancelled)
            val status = connection.responseCode
            val responseHeaders = connection.headerFields.mapValues { it.value?.filterNotNull().orEmpty() }
            cookies.put(uri, responseHeaders)
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val body = input?.use { readLimited(it, request.maxBodyBytes, deadline, cancelled) } ?: ByteArray(0)
            return PortalHttpResponse(status, request.url, responseHeaders, body)
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int, deadline: Long, cancelled: AtomicBoolean): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var remaining = limit
        while (remaining > 0) {
            remainingMs(deadline, cancelled)
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun PortalHttpResponse.header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()

    private fun remainingMs(deadline: Long, cancelled: AtomicBoolean): Int {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw SocketTimeoutException("HTTP request cancelled")
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining <= 0) throw SocketTimeoutException("HTTP request deadline exceeded")
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 8
        // Bound stalled DNS/TLS work instead of creating unlimited threads after repeated failures.
        private val HTTP_WORKERS = ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            { worker -> Thread(worker, "atcn-http").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android; ATCN-Wifi/1.2) AppleWebKit/537.36 (KHTML, like Gecko)"
    }
}

internal object HttpRedirectPolicy {
    fun redirect(request: PortalHttpRequest, status: Int, location: String): PortalHttpRequest {
        val previous = URL(request.url)
        val next = URL(previous, location)
        require(next.protocol.lowercase(Locale.ROOT) in setOf("http", "https") && next.userInfo == null) {
            "Unsupported redirect target"
        }
        require(!previous.protocol.equals("https", true) || next.protocol.equals("https", true)) {
            "HTTPS downgrade redirect rejected"
        }
        val sameOrigin = previous.protocol.equals(next.protocol, true) &&
            previous.host.equals(next.host, true) && effectivePort(previous) == effectivePort(next)
        val switchToGet = status == 303 || (status in setOf(301, 302) &&
            !request.method.equals("GET", true) && !request.method.equals("HEAD", true))
        require(sameOrigin || switchToGet || (request.body == null &&
            request.method.uppercase(Locale.ROOT) in setOf("GET", "HEAD"))) {
            "Cross-origin redirect cannot forward a request body"
        }
        val headers = request.headers.filterKeys {
            (sameOrigin || it.lowercase(Locale.ROOT) !in setOf("authorization", "cookie", "referer", "origin")) &&
                (!switchToGet || it.lowercase(Locale.ROOT) !in setOf("content-type", "content-length"))
        }
        return request.copy(
            url = next.toString(),
            method = if (switchToGet) "GET" else request.method,
            headers = headers,
            body = if (switchToGet) null else request.body,
        )
    }

    private fun effectivePort(url: URL): Int = if (url.port >= 0) url.port else url.defaultPort
}
