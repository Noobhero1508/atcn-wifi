package io.github.noobhero1508.atcnwifi.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import io.github.noobhero1508.atcnwifi.monitor.MonitorPreferences
import io.github.noobhero1508.atcnwifi.monitor.MonitorStore
import io.github.noobhero1508.atcnwifi.monitor.MonitorUiState
import io.github.noobhero1508.atcnwifi.monitor.WifiMonitorService
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the Compose layer independent from service implementation details. All service and
 * preference calls used by the UI belong in this small adapter.
 */
internal class MonitorUiFacade(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = MonitorPreferences(appContext)

    val state: StateFlow<MonitorUiState> = MonitorStore.state

    var compatibilityEnabled: Boolean
        get() = preferences.analyticsCompatibilityEnabled
        set(value) {
            preferences.analyticsCompatibilityEnabled = value
        }

    var priorityContinuous: Boolean
        get() = preferences.priorityContinuous
        set(value) {
            preferences.priorityContinuous = value
            WifiMonitorService.refreshSettings(appContext)
        }

    fun startAuto() = WifiMonitorService.startAuto(appContext)

    fun stopAuto() = WifiMonitorService.stopAuto(appContext)

    fun connectNow() = WifiMonitorService.connectNow(appContext)

    fun diagnose() = WifiMonitorService.diagnose(appContext)

    fun clearActivity() = MonitorStore.clearActivity()

    fun openWifiSettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openBatterySettings() {
        val detailsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${appContext.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(detailsIntent) }
            .onFailure {
                appContext.startActivity(
                    Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }
}
