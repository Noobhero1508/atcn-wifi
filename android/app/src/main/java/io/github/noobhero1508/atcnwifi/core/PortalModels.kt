package io.github.noobhero1508.atcnwifi.core

/**
 * Runtime options for one captive-portal engine.
 *
 * Analytics emulation is deliberately disabled by default. The UI may expose it as an explicit
 * compatibility switch, but it is never required by the normal fast path.
 */
data class PortalOptions(
    val gatewayLoginUrl: String = "http://186.186.0.1/login",
    val gatewayLogoutUrl: String = "http://186.186.0.1/logout?",
    val gatewayStatusUrl: String = "http://186.186.0.1/status",
    val awingBaseUrl: String = "http://v1.awingconnect.vn",
    val fallbackSerial: String = "CC:2D:E0:19:00:6C",
    val analyticsFallback: Boolean = false,
    val connectivityCheckUrls: List<String> = listOf(
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://www.google.com/generate_204",
    ),
    val genericProbeUrls: List<String> = listOf(
        "http://awifi.vn",
        "http://connectivitycheck.gstatic.com/generate_204",
        "http://www.msftconnecttest.com/connecttest.txt",
        "http://neverssl.com/",
    ),
    val allowedFormActionHosts: Set<String> = setOf(
        "186.186.0.1",
        "v1.awingconnect.vn",
    ),
    val connectivityTimeoutMs: Int = 2_500,
    val connectivityBudgetMs: Long = 5_000,
    val connectionBudgetMs: Long = 35_000,
    val requestTimeoutMs: Int = 7_000,
    val awingRefreshAttempts: Int = 3,
    val routerReadAttempts: Int = 2,
    val logoutWaitMs: Long = 3_000,
    val submitVerificationAttempts: Int = 3,
    val submitVerificationDelayMs: Long = 400,
) {
    val awingLoginUrl: String get() = "${awingBaseUrl.trimEnd('/')}/login"
    val awingSuccessUrl: String get() = "${awingBaseUrl.trimEnd('/')}/Success"
    val awingVerifyUrl: String get() = "${awingBaseUrl.trimEnd('/')}/Home/VerifyUrl"
    val awingAnalyticsUrl: String get() = "${awingBaseUrl.trimEnd('/')}/Analytic/Send"
}

enum class PortalLogLevel { DEBUG, INFO, WARNING, ERROR }

enum class PortalStage {
    CONNECTIVITY,
    GATEWAY,
    AWING,
    SUBMIT,
    FALLBACK,
    DIAGNOSTIC,
}

data class PortalLogEvent(
    val level: PortalLogLevel,
    val stage: PortalStage,
    /** Safe for a local log: the engine removes query strings and client identifiers. */
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

fun interface PortalLogger {
    fun log(event: PortalLogEvent)

    companion object {
        val NONE = PortalLogger { }
    }
}

enum class ConnectivityState { ONLINE, LOGIN_REQUIRED, UNAVAILABLE, UNKNOWN }

data class InternetCheckResult(
    val online: Boolean,
    /** Query-free endpoint, suitable for diagnostics. */
    val successfulProbe: String? = null,
    val statusCode: Int? = null,
    val reason: String? = null,
    val state: ConnectivityState = if (online) ConnectivityState.ONLINE else ConnectivityState.UNKNOWN,
    val elapsedMs: Long = 0,
)

data class TargetNetworkResult(
    val matches: Boolean,
    val gatewayReachable: Boolean,
    val wifiInfoPresent: Boolean,
    val reason: String? = null,
)

enum class ConnectionMethod {
    DIRECT_API,
    COMPATIBILITY_ANALYTICS,
    GENERIC_HTTP,
}

enum class PortalFailureCode {
    NETWORK_ERROR,
    ROUTER_UNREACHABLE,
    ROUTER_CONTEXT_MISSING,
    ROUTER_CONTEXT_INCOMPLETE,
    STUCK_SESSION,
    AWING_VERIFY_FAILED,
    LOGIN_FORM_MISSING,
    UNSAFE_FORM_ACTION,
    GATEWAY_REJECTED,
    INTERNET_NOT_GRANTED,
    UNEXPECTED_RESPONSE,
    ATTEMPT_TIMED_OUT,
}

data class PortalFailure(
    val stage: PortalStage,
    val code: PortalFailureCode,
    /** Redacted human-readable detail. Prefer [code] for localized UI strings. */
    val message: String,
)

sealed class ConnectResult {
    abstract val durationMs: Long
    abstract val online: Boolean

    data class AlreadyOnline(
        override val durationMs: Long,
    ) : ConnectResult() {
        override val online: Boolean = true
    }

    data class Connected(
        val method: ConnectionMethod,
        override val durationMs: Long,
    ) : ConnectResult() {
        override val online: Boolean = true
    }

    data class Failed(
        val failures: List<PortalFailure>,
        override val durationMs: Long,
    ) : ConnectResult() {
        override val online: Boolean = false
    }
}

/** Read-only report. It intentionally contains no MAC, IP, CHAP value, cookie, or token. */
data class DiagnosticReport(
    val internet: InternetCheckResult,
    val targetNetwork: TargetNetworkResult,
    val gatewayEndpoint: String,
    val wifiInfoFields: Set<String>,
    val maskedSerial: String?,
    val errors: List<PortalFailure>,
    val generatedAtMs: Long = System.currentTimeMillis(),
)
