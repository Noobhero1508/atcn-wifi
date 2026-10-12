package io.github.noobhero1508.atcnwifi.monitor

import android.content.Context
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object SecretRedactor {
    private val urlQuery = Regex("(?i)(https?://[^?\\s]+)\\?[^\\s]+")
    private val macAddress = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val ipAddress = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val keyValueSecret = Regex(
        "(?i)(chap_(?:id|challenge)|password|username|client_(?:mac|ip)|token|sessionId)(=|\\s*:\\s*)[^&\\s,}\\\"]+",
    )
    private val escapedChap = Regex("(?:\\\\[0-7]{1,3}){2,}")
    private val headerSecret = Regex("(?i)(authorization|cookie|set-cookie)(\\s*:\\s*)[^\\r\\n]+")

    fun redact(value: String): String = value
        .replace(urlQuery) { match -> "${match.groupValues[1]}?<redacted>" }
        .replace(headerSecret) { match -> "${match.groupValues[1]}${match.groupValues[2]}<redacted>" }
        .replace(keyValueSecret) { match -> "${match.groupValues[1]}${match.groupValues[2]}<redacted>" }
        .replace(macAddress, "<mac>")
        .replace(ipAddress, "<ip>")
        .replace(escapedChap, "<chap>")
}

/**
 * Bounded local event log. Messages are redacted before touching memory or disk, and repeated
 * failures are collapsed to avoid battery and flash churn while a portal is unavailable.
 */
internal class RedactingEventLog(context: Context) : Closeable {
    private val logDirectory = File(context.applicationContext.filesDir, "logs")
    private val logFile = File(logDirectory, "monitor.log")
    private val oldLogFile = File(logDirectory, "monitor.log.1")
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "atcn-local-log").apply { isDaemon = true }
    }
    private val recent = LinkedHashMap<String, Long>()
    @Volatile private var closed = false

    fun info(message: String, status: MonitorStatus? = null) =
        emit(ActivityLevel.INFO, message, status)

    fun success(message: String, status: MonitorStatus? = null) =
        emit(ActivityLevel.SUCCESS, message, status)

    fun warning(message: String, status: MonitorStatus? = null) =
        emit(ActivityLevel.WARNING, message, status)

    fun error(message: String, status: MonitorStatus? = null) =
        emit(ActivityLevel.ERROR, message, status)

    fun emit(level: ActivityLevel, rawMessage: String, status: MonitorStatus? = null) {
        if (closed) return
        val message = SecretRedactor.redact(rawMessage).replace(Regex("[\\r\\n]+"), " ").trim()
        if (message.isEmpty()) return
        val now = System.currentTimeMillis()
        val key = "${level.name}:$message"
        if (isDuplicate(key, now)) {
            if (status != null) MonitorStore.setStatus(status, message)
            return
        }

        if (closed) return
        MonitorStore.record(level, message, status, deduplicate = false)
        val line = "${Instant.ofEpochMilli(now)} ${level.name.padEnd(7)} $message${System.lineSeparator()}"
        runCatching { writer.execute {
            runCatching {
                logDirectory.mkdirs()
                rotateIfNeeded(line.length)
                logFile.appendText(line, Charsets.UTF_8)
            }
        } }
    }

    fun file(): File = logFile

    override fun close() {
        closed = true
        writer.shutdown()
        runCatching { writer.awaitTermination(750, TimeUnit.MILLISECONDS) }
    }

    private fun isDuplicate(key: String, now: Long): Boolean = synchronized(recent) {
        val oldestAllowed = now - DUPLICATE_WINDOW_MILLIS
        val iterator = recent.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < oldestAllowed) iterator.remove()
        }
        val previous = recent[key]
        recent[key] = now
        previous != null && previous >= oldestAllowed
    }

    private fun rotateIfNeeded(incomingCharacters: Int) {
        if (logFile.exists() && logFile.length() + incomingCharacters > MAX_LOG_BYTES) {
            if (oldLogFile.exists()) oldLogFile.delete()
            logFile.renameTo(oldLogFile)
        }
    }

    companion object {
        private const val MAX_LOG_BYTES = 256L * 1024L
        private const val DUPLICATE_WINDOW_MILLIS = 30_000L
    }
}
