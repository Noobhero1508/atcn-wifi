package io.github.noobhero1508.atcnwifi.monitor

import android.net.Network
import io.github.noobhero1508.atcnwifi.core.ConnectResult
import io.github.noobhero1508.atcnwifi.core.DiagnosticReport
import io.github.noobhero1508.atcnwifi.core.InternetCheckResult
import io.github.noobhero1508.atcnwifi.core.PortalEngine
import io.github.noobhero1508.atcnwifi.core.PortalLogEvent
import io.github.noobhero1508.atcnwifi.core.PortalLogLevel
import io.github.noobhero1508.atcnwifi.core.PortalLogger
import io.github.noobhero1508.atcnwifi.core.PortalOptions
import io.github.noobhero1508.atcnwifi.core.TargetNetworkResult

/**
 * Narrow adapter boundary between Android lifecycle code and the portal protocol implementation.
 * Keeping this interface here makes the service testable and prevents protocol changes spreading
 * into NetworkCallback/retry code.
 */
internal interface PortalSession {
    suspend fun checkInternet(): InternetCheckResult
    suspend fun isTargetNetwork(): TargetNetworkResult
    suspend fun connect(): ConnectResult
    suspend fun diagnose(): DiagnosticReport
}
internal fun interface PortalSessionFactory {
    fun create(network: Network, analyticsFallback: Boolean, log: RedactingEventLog): PortalSession
}

internal object PortalSessions {
    @Volatile
    var factory: PortalSessionFactory = PortalSessionFactory { network, analyticsFallback, log ->
        CorePortalSession(
            engine = PortalEngine(
                network = network,
                options = PortalOptions(analyticsFallback = analyticsFallback),
                logger = PortalLogger { event -> logCoreEvent(log, event) },
            ),
        )
    }

    private fun logCoreEvent(log: RedactingEventLog, event: PortalLogEvent) {
        val message = "${event.stage.name.lowercase()}: ${event.message}"
        when (event.level) {
            PortalLogLevel.DEBUG,
            PortalLogLevel.INFO,
            -> log.info(message)

            PortalLogLevel.WARNING -> log.warning(message)
            PortalLogLevel.ERROR -> log.error(message)
        }
    }
}

private class CorePortalSession(
    private val engine: PortalEngine,
) : PortalSession {
    override suspend fun checkInternet(): InternetCheckResult = engine.checkInternet()

    override suspend fun isTargetNetwork(): TargetNetworkResult = engine.isTargetNetwork()

    override suspend fun connect(): ConnectResult = engine.connect()

    override suspend fun diagnose(): DiagnosticReport = engine.diagnose()
}
