package io.github.noobhero1508.atcnwifi.monitor

import android.content.Context
import androidx.core.content.edit

/** Small, synchronous settings read by the foreground service. */
class MonitorPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    var analyticsCompatibilityEnabled: Boolean
        get() = preferences.getBoolean(KEY_ANALYTICS_COMPATIBILITY, false)
        set(value) {
            preferences.edit { putBoolean(KEY_ANALYTICS_COMPATIBILITY, value) }
        }

    /** Keeps the five-second requested cadence while the screen is off. */
    var priorityContinuous: Boolean
        get() = preferences.getBoolean(KEY_PRIORITY_CONTINUOUS, false)
        set(value) {
            preferences.edit { putBoolean(KEY_PRIORITY_CONTINUOUS, value) }
        }

    var checkIntervalSeconds: Int
        get() = preferences.getInt(KEY_CHECK_INTERVAL_SECONDS, DEFAULT_CHECK_INTERVAL_SECONDS)
            .takeIf(ALLOWED_CHECK_INTERVAL_SECONDS::contains)
            ?: DEFAULT_CHECK_INTERVAL_SECONDS
        set(value) {
            require(value in ALLOWED_CHECK_INTERVAL_SECONDS) {
                "checkIntervalSeconds must be one of $ALLOWED_CHECK_INTERVAL_SECONDS"
            }
            preferences.edit { putInt(KEY_CHECK_INTERVAL_SECONDS, value) }
        }

    internal var autoModeWasActive: Boolean
        get() = preferences.getBoolean(KEY_AUTO_MODE_WAS_ACTIVE, false)
        set(value) {
            preferences.edit { putBoolean(KEY_AUTO_MODE_WAS_ACTIVE, value) }
        }

    companion object {
        const val DEFAULT_CHECK_INTERVAL_SECONDS = 5
        val ALLOWED_CHECK_INTERVAL_SECONDS: Set<Int> = setOf(5, 15, 60)

        private const val FILE_NAME = "monitor_preferences"
        private const val KEY_ANALYTICS_COMPATIBILITY = "analytics_compatibility"
        private const val KEY_CHECK_INTERVAL_SECONDS = "check_interval_seconds"
        private const val KEY_AUTO_MODE_WAS_ACTIVE = "auto_mode_was_active"
        private const val KEY_PRIORITY_CONTINUOUS = "priority_continuous"
    }
}
