package io.github.noobhero1508.atcnwifi.monitor

import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-local source of truth for the service and UI.
 *
 * It intentionally contains no Android component reference, so collecting it cannot leak an
 * Activity or Service. Persistent diagnostic history is handled by [RedactingEventLog].
 */
object MonitorStore {
    private const val MAX_ACTIVITY_ITEMS = 100
    private const val DUPLICATE_WINDOW_MILLIS = 15_000L

    private val ids = AtomicLong(0L)
    private val mutableState = MutableStateFlow(MonitorUiState())
    private val recentMessages = LinkedHashMap<String, Long>()
    private var clock: Clock = Clock.systemUTC()

    val state: StateFlow<MonitorUiState> = mutableState.asStateFlow()

    fun clearActivity() {
        synchronized(recentMessages) {
            recentMessages.clear()
        }
        mutableState.update { it.copy(activity = emptyList()) }
    }

    internal fun setMonitoring(enabled: Boolean) {
        mutableState.update { current ->
            current.copy(
                monitoringEnabled = enabled,
                // Ending a manual service must preserve its result for the dashboard.
                // An explicit user stop sets IDLE through the service's stop event.
                currentIntervalSeconds = if (enabled) current.currentIntervalSeconds else null,
            )
        }
    }

    internal fun setBusy(busy: Boolean) {
        mutableState.update { it.copy(busy = busy) }
    }

    internal fun setSchedule(intervalSeconds: Int?, priorityContinuous: Boolean) {
        mutableState.update {
            it.copy(currentIntervalSeconds = intervalSeconds, priorityContinuous = priorityContinuous)
        }
    }

    internal fun recordCheck(timestampMs: Long, durationMs: Long) {
        mutableState.update {
            it.copy(lastCheckAtMs = timestampMs, lastCheckDurationMs = durationMs.coerceAtLeast(0))
        }
    }

    internal fun setDefaultNetwork(kind: DefaultNetworkKind) {
        mutableState.update { it.copy(defaultNetwork = kind) }
    }

    internal fun clearNetworkChecks() {
        mutableState.update {
            it.copy(currentIntervalSeconds = null, lastCheckAtMs = null, lastCheckDurationMs = null)
        }
    }

    internal fun setStatus(status: MonitorStatus, message: String? = null) {
        val safeMessage = message?.let(SecretRedactor::redact)
        mutableState.update { current ->
            current.copy(
                status = status,
                latestMessage = safeMessage ?: current.latestMessage,
            )
        }
    }

    internal fun record(
        level: ActivityLevel,
        message: String,
        status: MonitorStatus? = null,
        deduplicate: Boolean = true,
    ) {
        val cleanMessage = SecretRedactor.redact(message).trim()
        if (cleanMessage.isEmpty()) return

        val now = clock.instant()
        if (deduplicate && isRecentDuplicate("${level.name}:$cleanMessage", now.toEpochMilli())) {
            mutableState.update { current ->
                current.copy(
                    status = status ?: current.status,
                    latestMessage = cleanMessage,
                )
            }
            return
        }

        val item = ActivityItem(
            id = ids.incrementAndGet(),
            timestamp = now,
            level = level,
            message = cleanMessage,
        )
        mutableState.update { current ->
            current.copy(
                status = status ?: current.status,
                latestMessage = cleanMessage,
                activity = (current.activity + item).takeLast(MAX_ACTIVITY_ITEMS),
            )
        }
    }

    private fun isRecentDuplicate(key: String, nowMillis: Long): Boolean {
        synchronized(recentMessages) {
            val oldestAllowed = nowMillis - DUPLICATE_WINDOW_MILLIS
            val iterator = recentMessages.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value < oldestAllowed) iterator.remove()
            }

            val previous = recentMessages[key]
            recentMessages[key] = nowMillis
            return previous != null && previous >= oldestAllowed
        }
    }
}
