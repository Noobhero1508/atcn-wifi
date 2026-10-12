package io.github.noobhero1508.atcnwifi.monitor

import java.time.Instant

/** Stable, presentation-neutral states exposed to the Compose UI. */
enum class MonitorStatus {
    IDLE,
    WAITING_FOR_WIFI,
    CHECKING,
    INTERNET_AVAILABLE,
    LOGIN_REQUIRED,
    CONNECTING,
    RETRY_WAIT,
    NOT_TARGET_NETWORK,
    NETWORK_UNAVAILABLE,
    CONNECTION_UNKNOWN,
    ERROR,
}

/** The system default is observed, never changed by this application. */
enum class DefaultNetworkKind { NONE, WIFI, CELLULAR, VPN, OTHER }

enum class ActivityLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

data class ActivityItem(
    val id: Long,
    val timestamp: Instant,
    val level: ActivityLevel,
    val message: String,
)

data class MonitorUiState(
    val status: MonitorStatus = MonitorStatus.IDLE,
    val monitoringEnabled: Boolean = false,
    val busy: Boolean = false,
    val latestMessage: String = "",
    val activity: List<ActivityItem> = emptyList(),
    val currentIntervalSeconds: Int? = null,
    val lastCheckAtMs: Long? = null,
    val lastCheckDurationMs: Long? = null,
    val defaultNetwork: DefaultNetworkKind = DefaultNetworkKind.NONE,
    val priorityContinuous: Boolean = false,
)
