package io.github.noobhero1508.atcnwifi.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.noobhero1508.atcnwifi.R
import io.github.noobhero1508.atcnwifi.core.ConnectResult
import io.github.noobhero1508.atcnwifi.core.ConnectivityState
import io.github.noobhero1508.atcnwifi.core.InternetCheckResult
import java.io.IOException
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * User-started monitor. One mutex serializes gateway reads, probes and logins. Lifecycle state
 * stays on Main; the protocol adapter performs cancellable I/O off Main. Every request receives
 * an exact Wi-Fi Network, while Android's default (including cellular/VPN) is only observed.
 * Delays are requested cadences, not wake-up alarms: the monitor does not defeat Doze.
 */
class WifiMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operationMutex = Mutex()
    private val wakeSignals = Channel<WakeSignal>(Channel.CONFLATED)
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private lateinit var preferences: MonitorPreferences
    private lateinit var eventLog: RedactingEventLog

    private var autoModeActive = false
    private var destroyed = false
    private var callbacksRegistered = false
    private var receiverRegistered = false
    private var wifiNetwork: Network? = null
    private var targetNetwork: Network? = null
    private var defaultNetwork: Network? = null
    private var capabilitiesSignature: Pair<Boolean, Boolean>? = null
    private var monitorJob: Job? = null
    private var oneShotJob: Job? = null
    private var busyCount = 0
    private var screenOffAtElapsedMs: Long? = null
    private var nextIdentificationAt = 0L
    private var identificationFailures = 0
    private var outageFailures = 0
    private var retryAt: Long? = null
    private var retryIndex = 0
    private var lastReportedOnline: Boolean? = null
    private var lastReportedAt = 0L
    private var lastNotificationContent: String? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!isInternetWifi(capabilities)) return
            serviceScope.launch { observeWifi(network, capabilities) }
        }

        override fun onLost(network: Network) {
            serviceScope.launch {
                if (wifiNetwork != network) return@launch
                clearWifiNetwork()
                if (autoModeActive) evaluateExistingWifi()
            }
        }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            serviceScope.launch {
                defaultNetwork = network
                MonitorStore.setDefaultNetwork(defaultKind(capabilities))
            }
        }

        override fun onLost(network: Network) {
            serviceScope.launch {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    MonitorStore.setDefaultNetwork(DefaultNetworkKind.NONE)
                }
            }
        }
    }

    private val settingsAndScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAtElapsedMs = SystemClock.elapsedRealtime()
                    refreshSchedule()
                    wakeSignals.trySend(WakeSignal.RECALCULATE)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOffAtElapsedMs = null
                    refreshSchedule()
                    requestImmediateCheck()
                }
                ACTION_REFRESH_SETTINGS -> {
                    refreshSchedule()
                    requestImmediateCheck()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        preferences = MonitorPreferences(this)
        eventLog = RedactingEventLog(this)
        if (!powerManager.isInteractive) screenOffAtElapsedMs = SystemClock.elapsedRealtime()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUTO -> startAutomaticMonitoring()
            ACTION_CONNECT_NOW -> startOneShot(diagnose = false)
            ACTION_DIAGNOSE -> startOneShot(diagnose = true)
            ACTION_STOP -> stopAutomaticMonitoring()
            null -> if (preferences.autoModeWasActive) startAutomaticMonitoring() else stopSelf(startId)
            else -> stopSelf(startId)
        }
        return if (autoModeActive) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        autoModeActive = false
        unregisterObservers()
        serviceScope.cancel()
        wakeSignals.close()
        eventLog.close()
        MonitorStore.setBusy(false)
        MonitorStore.setMonitoring(false)
        super.onDestroy()
    }

    private fun startAutomaticMonitoring() {
        startInForeground()
        if (!autoModeActive) {
            autoModeActive = true
            preferences.autoModeWasActive = true
            MonitorStore.setMonitoring(true)
            eventLog.info(localized("Đã bật tự động theo dõi Wi-Fi.", "Automatic Wi-Fi monitoring enabled."))
        }
        registerObservers()
        refreshSchedule()
        evaluateExistingWifi()
    }

    private fun stopAutomaticMonitoring() {
        autoModeActive = false
        preferences.autoModeWasActive = false
        monitorJob?.cancel()
        oneShotJob?.cancel()
        unregisterObservers()
        resetBackoff()
        MonitorStore.setMonitoring(false)
        eventLog.info(localized("Đã dừng tự động theo dõi.", "Automatic monitoring stopped."), MonitorStatus.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerObservers() {
        if (!callbacksRegistered) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, wifiCallback)
            try {
                connectivityManager.registerDefaultNetworkCallback(defaultCallback)
                callbacksRegistered = true
            } catch (error: Exception) {
                connectivityManager.unregisterNetworkCallback(wifiCallback)
                throw error
            }
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(ACTION_REFRESH_SETTINGS)
            }
            ContextCompat.registerReceiver(this, settingsAndScreenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        val active = connectivityManager.activeNetwork
        defaultNetwork = active
        MonitorStore.setDefaultNetwork(defaultKind(active?.let(connectivityManager::getNetworkCapabilities)))
    }

    private fun unregisterObservers() {
        if (callbacksRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
            runCatching { connectivityManager.unregisterNetworkCallback(defaultCallback) }
            callbacksRegistered = false
        }
        if (receiverRegistered) {
            runCatching { unregisterReceiver(settingsAndScreenReceiver) }
            receiverRegistered = false
        }
    }

    private fun evaluateExistingWifi() {
        val network = findCurrentWifiNetwork()
        if (network == null) {
            showWaitingForWifi()
            return
        }
        observeWifi(network, connectivityManager.getNetworkCapabilities(network))
    }

    @Suppress("DEPRECATION")
    private fun findCurrentWifiNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        if (active != null && connectivityManager.getNetworkCapabilities(active)?.let(::isInternetWifi) == true) return active
        // Captive Wi-Fi may remain available while cellular is Android's default.
        return connectivityManager.allNetworks.firstOrNull {
            connectivityManager.getNetworkCapabilities(it)?.let(::isInternetWifi) == true
        }
    }

    private fun isInternetWifi(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    private fun defaultKind(capabilities: NetworkCapabilities?): DefaultNetworkKind = when {
        capabilities == null -> DefaultNetworkKind.NONE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> DefaultNetworkKind.VPN
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DefaultNetworkKind.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DefaultNetworkKind.WIFI
        else -> DefaultNetworkKind.OTHER
    }

    private fun observeWifi(network: Network, capabilities: NetworkCapabilities?) {
        if (!autoModeActive || destroyed) return
        val changed = adoptWifiNetwork(network)
        val signature = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) to
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
        val capabilityChanged = signature != capabilitiesSignature
        capabilitiesSignature = signature
        if (monitorJob?.isActive != true) startMonitorLoop(network)
        else if (!changed && capabilityChanged) requestImmediateCheck()
    }

    private fun adoptWifiNetwork(network: Network, cancelManual: Boolean = true): Boolean {
        if (wifiNetwork == network) return false
        monitorJob?.cancel()
        if (cancelManual) oneShotJob?.cancel()
        wifiNetwork = network
        targetNetwork = null
        capabilitiesSignature = null
        nextIdentificationAt = 0L
        identificationFailures = 0
        outageFailures = 0
        lastReportedOnline = null
        lastReportedAt = 0L
        resetBackoff()
        MonitorStore.clearNetworkChecks()
        refreshSchedule()
        return true
    }

    private fun clearWifiNetwork() {
        monitorJob?.cancel()
        oneShotJob?.cancel()
        wifiNetwork = null
        targetNetwork = null
        capabilitiesSignature = null
        resetBackoff()
        MonitorStore.clearNetworkChecks()
        refreshSchedule()
    }

    private fun startMonitorLoop(network: Network) {
        monitorJob = serviceScope.launch {
            while (isActive && autoModeActive && wifiNetwork == network) {
                try {
                    operationMutex.withLock {
                        ensureCurrent(network)
                        performNetworkWork(network, manual = false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (wifiNetwork == network && autoModeActive && !destroyed) {
                        outageFailures++
                        resetBackoff()
                        if (targetNetwork != network) {
                            identificationFailures++
                            nextIdentificationAt = SystemClock.elapsedRealtime() +
                                AdaptiveCheckPolicy.identificationRetrySeconds(identificationFailures, false) * 1_000L
                        }
                        eventLog.warning(localized("Kiểm tra mạng thất bại: " + safeError(error), "Network check failed: " + safeError(error)), MonitorStatus.CONNECTION_UNKNOWN)
                        updateNotification()
                    }
                }
                awaitNextCheck(network)
            }
        }
    }

    private suspend fun awaitNextCheck(network: Network) {
        val completedAt = SystemClock.elapsedRealtime()
        while (autoModeActive && wifiNetwork == network) {
            refreshSchedule()
            val now = SystemClock.elapsedRealtime()
            val dueAt = if (targetNetwork != network) {
                nextIdentificationAt
            } else {
                val periodicAt = completedAt + currentIntervalSeconds() * 1_000L
                retryAt?.let { min(periodicAt, it) } ?: periodicAt
            }
            val remaining = (dueAt - now).coerceAtLeast(250L)
            when (withTimeoutOrNull(remaining) { wakeSignals.receive() }) {
                WakeSignal.RECALCULATE -> continue
                WakeSignal.CHECK, null -> return
            }
        }
    }

    private fun requestImmediateCheck() {
        if (autoModeActive && wifiNetwork != null) {
            if (targetNetwork == null) nextIdentificationAt = 0L
            wakeSignals.trySend(WakeSignal.CHECK)
        }
    }

    private fun currentIntervalSeconds(): Int {
        val base = AdaptiveCheckPolicy.intervalSeconds(
            powerManager.isInteractive,
            screenOffAtElapsedMs,
            SystemClock.elapsedRealtime(),
            preferences.priorityContinuous,
        )
        return if (outageFailures > 0) AdaptiveCheckPolicy.outageIntervalSeconds(base, outageFailures) else base
    }

    private fun refreshSchedule() {
        MonitorStore.setSchedule(
            if (autoModeActive && targetNetwork == wifiNetwork && targetNetwork != null) currentIntervalSeconds() else null,
            preferences.priorityContinuous,
        )
    }

    private suspend fun performNetworkWork(network: Network, manual: Boolean) {
        if (targetNetwork != network) {
            if (!manual && SystemClock.elapsedRealtime() < nextIdentificationAt) return
            if (!classifyTargetNetwork(network)) return
        }
        val probe = probeInternet(network)
        ensureCurrent(network)
        recordProbe(probe)
        when (probe.state) {
            ConnectivityState.ONLINE -> markInternetAvailable(network)
            ConnectivityState.LOGIN_REQUIRED -> {
                outageFailures = 0
                reportConnectivity(network, false)
                // Do not replace an active retry status with a fresh login message on each probe.
                if (retryAt == null || manual) {
                    setStatus(MonitorStatus.LOGIN_REQUIRED, localized("Wi-Fi cần đăng nhập lại.", "Wi-Fi needs to sign in again."))
                }
                if (manual || retryAt?.let { SystemClock.elapsedRealtime() >= it } != false) connect(network)
            }
            ConnectivityState.UNAVAILABLE -> {
                outageFailures++
                resetBackoff()
                reportConnectivity(network, false)
                setStatus(MonitorStatus.NETWORK_UNAVAILABLE, localized("Wi-Fi chưa có Internet; sẽ kiểm tra lại.", "Wi-Fi has no Internet; checking again later."))
            }
            ConnectivityState.UNKNOWN -> {
                outageFailures++
                resetBackoff()
                setStatus(MonitorStatus.CONNECTION_UNKNOWN, localized("Chưa xác định được Internet. Đang chờ kiểm tra lại.", "Internet could not be verified. Waiting to check again."))
            }
        }
        refreshSchedule()
    }

    private suspend fun classifyTargetNetwork(network: Network): Boolean {
        val result = withBoundedWork(PROBE_TIMEOUT_MS) { createSession(network).isTargetNetwork() }
        ensureCurrent(network)
        if (result.matches) {
            targetNetwork = network
            identificationFailures = 0
            eventLog.info(localized("Đã nhận diện Wi-Fi KTX.", "Dormitory Wi-Fi recognized."))
            refreshSchedule()
            return true
        }
        identificationFailures++
        // Reachability without portal identity is inconclusive: blank/error HTML can be transient.
        // Retry quickly at first, then sparsely; never permanently cache this negative result.
        val delaySeconds = AdaptiveCheckPolicy.identificationRetrySeconds(identificationFailures, false)
        nextIdentificationAt = SystemClock.elapsedRealtime() + delaySeconds * 1_000L
        targetNetwork = null
        setStatus(MonitorStatus.CONNECTION_UNKNOWN, localized(
            "Chưa nhận diện được cổng KTX; sẽ thử lại sau " + delaySeconds + " giây.",
            "Dormitory gateway not identified; retrying in " + delaySeconds + " seconds.",
        ))
        refreshSchedule()
        return false
    }

    private suspend fun connect(network: Network) {
        beginBusy()
        try {
            setStatus(MonitorStatus.CONNECTING, localized("Đang đăng nhập AWING…", "Signing in to AWING…"))
            val result = withBoundedWork(ATTEMPT_TIMEOUT_MS) { createSession(network).connect() }
            ensureCurrent(network)
            if (result.online) {
                val verified = probeInternet(network)
                ensureCurrent(network)
                recordProbe(verified)
                if (verified.state == ConnectivityState.ONLINE) {
                    markInternetAvailable(network)
                    if (result is ConnectResult.Connected) {
                        eventLog.success(localized(
                            "Đăng nhập hoàn tất trong " + result.durationMs + " ms.",
                            "Sign-in completed in " + result.durationMs + " ms.",
                        ))
                    }
                } else {
                    val status = when (verified.state) {
                        ConnectivityState.LOGIN_REQUIRED -> MonitorStatus.LOGIN_REQUIRED
                        ConnectivityState.UNAVAILABLE -> MonitorStatus.NETWORK_UNAVAILABLE
                        else -> MonitorStatus.CONNECTION_UNKNOWN
                    }
                    if (verified.state != ConnectivityState.UNKNOWN) reportConnectivity(network, false)
                    setStatus(status, localized(
                        "Đăng nhập đã phản hồi nhưng Internet chưa được xác nhận.",
                        "Sign-in responded but Internet access has not been verified.",
                    ))
                    if (verified.state == ConnectivityState.LOGIN_REQUIRED && autoModeActive) scheduleRetry()
                    else { resetBackoff(); outageFailures++ }
                }
            } else {
                val failure = (result as? ConnectResult.Failed)?.failures?.lastOrNull()
                val detail = failure?.let { ": " + it.code.name }.orEmpty()
                eventLog.error(localized(
                    "Đăng nhập chưa thành công" + detail + " (" + result.durationMs + " ms).",
                    "Sign-in did not succeed" + detail + " (" + result.durationMs + " ms).",
                ), MonitorStatus.ERROR)
                if (autoModeActive) scheduleRetry()
                updateNotification()
            }
        } finally {
            endBusy()
        }
    }

    /** Retry state belongs to the loop; resetting it cannot cancel the current login itself. */
    private fun scheduleRetry() {
        val seconds = RETRY_DELAYS_SECONDS[min(retryIndex, RETRY_DELAYS_SECONDS.lastIndex)]
        retryIndex = min(retryIndex + 1, RETRY_DELAYS_SECONDS.lastIndex)
        retryAt = SystemClock.elapsedRealtime() + seconds * 1_000L
        setStatus(MonitorStatus.RETRY_WAIT, localized(
            "Sẽ thử đăng nhập lại sau " + seconds + " giây.",
            "Will retry sign-in in " + seconds + " seconds.",
        ))
    }

    private fun resetBackoff() {
        retryAt = null
        retryIndex = 0
    }

    private fun markInternetAvailable(network: Network) {
        resetBackoff()
        outageFailures = 0
        reportConnectivity(network, true)
        setStatus(MonitorStatus.INTERNET_AVAILABLE, localized(
            "Internet trên Wi-Fi KTX đang hoạt động.", "Internet on dormitory Wi-Fi is available.",
        ), success = true)
        refreshSchedule()
    }

    private fun recordProbe(probe: InternetCheckResult) {
        MonitorStore.recordCheck(System.currentTimeMillis(), probe.elapsedMs)
    }

    private suspend fun probeInternet(network: Network): InternetCheckResult {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            withBoundedWork(PROBE_TIMEOUT_MS) { createSession(network).checkInternet() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ensureCurrent(network)
            MonitorStore.recordCheck(System.currentTimeMillis(), SystemClock.elapsedRealtime() - startedAt)
            throw error
        }
    }

    private fun reportConnectivity(network: Network, online: Boolean) {
        if (targetNetwork != network || wifiNetwork != network || destroyed) return
        val now = SystemClock.elapsedRealtime()
        if (lastReportedOnline == online || (lastReportedOnline != null && now - lastReportedAt < 5_000L)) return
        runCatching { connectivityManager.reportNetworkConnectivity(network, online) }
            .onSuccess { lastReportedOnline = online; lastReportedAt = now }
    }

    private fun setStatus(status: MonitorStatus, message: String, success: Boolean = false) {
        if (destroyed) return
        val previous = MonitorStore.state.value
        if (previous.status == status && previous.latestMessage == message) return
        if (success) eventLog.success(message, status) else eventLog.info(message, status)
        updateNotification()
    }

    private fun startOneShot(diagnose: Boolean) {
        startInForeground()
        registerObservers()
        if (oneShotJob?.isActive == true) return
        oneShotJob = serviceScope.launch {
            beginBusy()
            try {
                val network = findCurrentWifiNetwork()
                if (network == null) {
                    showWaitingForWifi()
                    return@launch
                }
                adoptWifiNetwork(network, cancelManual = false)
                operationMutex.withLock {
                    ensureCurrent(network)
                    if (diagnose) runDiagnosis(network) else performNetworkWork(network, manual = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!destroyed) eventLog.warning(localized(
                    "Tác vụ thất bại: " + safeError(error), "Action failed: " + safeError(error),
                ), MonitorStatus.CONNECTION_UNKNOWN)
            } finally {
                endBusy()
                if (autoModeActive && wifiNetwork != null && monitorJob?.isActive != true) startMonitorLoop(wifiNetwork!!)
                if (!autoModeActive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runDiagnosis(network: Network) {
        setStatus(MonitorStatus.CHECKING, localized("Đang chẩn đoán kết nối…", "Diagnosing connection…"))
        val report = withBoundedWork(ATTEMPT_TIMEOUT_MS) { createSession(network).diagnose() }
        ensureCurrent(network)
        recordProbe(report.internet)
        val status = when {
            !report.targetNetwork.matches -> MonitorStatus.NOT_TARGET_NETWORK
            report.internet.state == ConnectivityState.ONLINE -> MonitorStatus.INTERNET_AVAILABLE
            report.internet.state == ConnectivityState.LOGIN_REQUIRED -> MonitorStatus.LOGIN_REQUIRED
            report.internet.state == ConnectivityState.UNAVAILABLE -> MonitorStatus.NETWORK_UNAVAILABLE
            else -> MonitorStatus.CONNECTION_UNKNOWN
        }
        eventLog.info(localized(
            "Chẩn đoán: Internet=" + report.internet.state + ", mạng KTX=" + report.targetNetwork.matches +
                ", kiểm tra=" + report.internet.elapsedMs + " ms, lỗi=" + report.errors.size + ".",
            "Diagnosis: Internet=" + report.internet.state + ", dormitory network=" + report.targetNetwork.matches +
                ", check=" + report.internet.elapsedMs + " ms, errors=" + report.errors.size + ".",
        ), status)
        updateNotification()
    }

    private suspend fun ensureCurrent(network: Network) {
        currentCoroutineContext().ensureActive()
        if (destroyed || wifiNetwork != network) throw CancellationException("Wi-Fi network changed")
    }

    private suspend fun <T> withBoundedWork(timeoutMs: Long, work: suspend () -> T): T {
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName + ":portal-work")
            .apply { setReferenceCounted(false) }
        wakeLock.acquire(timeoutMs + 1_000L)
        try {
            return withTimeout(timeoutMs) { work() }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Operation timed out", timeout)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun beginBusy() { busyCount++; MonitorStore.setBusy(true) }
    private fun endBusy() {
        busyCount = (busyCount - 1).coerceAtLeast(0)
        if (!destroyed) MonitorStore.setBusy(busyCount > 0)
    }

    private fun showWaitingForWifi() {
        setStatus(MonitorStatus.WAITING_FOR_WIFI, localized(
            "Đang chờ người dùng kết nối Wi-Fi KTX.", "Waiting for the user to connect to dormitory Wi-Fi.",
        ))
        refreshSchedule()
    }

    private fun createSession(network: Network): PortalSession = PortalSessions.factory.create(
        network, preferences.analyticsCompatibilityEnabled, eventLog,
    )

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        lastNotificationContent = notificationContent()
    }

    private fun updateNotification() {
        if (destroyed || (!autoModeActive && oneShotJob?.isActive != true)) return
        val content = notificationContent()
        if (lastNotificationContent == content) return
        runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification()) }
            .onSuccess { lastNotificationContent = content }
    }

    private fun notificationContent(): String = MonitorStore.state.value.latestMessage.ifBlank {
        localized("Đang theo dõi Wi-Fi KTX.", "Monitoring dormitory Wi-Fi.")
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, REQUEST_OPEN_APP, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val connectIntent = PendingIntent.getService(this, REQUEST_CONNECT_NOW,
            Intent(this, WifiMonitorService::class.java).setAction(ACTION_CONNECT_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, REQUEST_STOP,
            Intent(this, WifiMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_atcn_wifi)
            .setContentTitle("ATCN Wifi")
            .setContentText(notificationContent())
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_stat_atcn_wifi, localized("Kết nối ngay", "Connect now"), connectIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, localized("Dừng", "Stop"), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL_ID, localized("Theo dõi kết nối Wi-Fi", "Wi-Fi connection monitor"), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localized("Hiển thị khi ATCN Wifi đang tự động theo dõi.", "Shown while ATCN Wifi is monitoring automatically.")
            setShowBadge(false)
        })
    }

    private fun safeError(error: Throwable): String =
        SecretRedactor.redact(error.message ?: error.javaClass.simpleName).take(180)

    private fun localized(vietnamese: String, english: String): String =
        if (resources.configuration.locales[0]?.language?.lowercase(Locale.ROOT) == "vi") vietnamese else english

    private enum class WakeSignal { CHECK, RECALCULATE }

    companion object {
        private const val ACTION_START_AUTO = "io.github.noobhero1508.atcnwifi.action.START_AUTO"
        private const val ACTION_CONNECT_NOW = "io.github.noobhero1508.atcnwifi.action.CONNECT_NOW"
        private const val ACTION_DIAGNOSE = "io.github.noobhero1508.atcnwifi.action.DIAGNOSE"
        private const val ACTION_STOP = "io.github.noobhero1508.atcnwifi.action.STOP"
        private const val ACTION_REFRESH_SETTINGS = "io.github.noobhero1508.atcnwifi.action.REFRESH_SETTINGS"
        private const val NOTIFICATION_CHANNEL_ID = "atcn_wifi_monitor"
        private const val NOTIFICATION_ID = 1201
        private const val REQUEST_OPEN_APP = 1200
        private const val REQUEST_CONNECT_NOW = 1201
        private const val REQUEST_STOP = 1202
        private const val PROBE_TIMEOUT_MS = 12_000L
        private const val ATTEMPT_TIMEOUT_MS = 40_000L
        private val RETRY_DELAYS_SECONDS = longArrayOf(2, 5, 10, 30, 60)

        fun startAuto(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WifiMonitorService::class.java).setAction(ACTION_START_AUTO))
        }
        fun connectNow(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WifiMonitorService::class.java).setAction(ACTION_CONNECT_NOW))
        }
        fun diagnose(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WifiMonitorService::class.java).setAction(ACTION_DIAGNOSE))
        }
        fun refreshSettings(context: Context) {
            // A private broadcast updates the existing monitor without starting a stopped service.
            context.sendBroadcast(Intent(ACTION_REFRESH_SETTINGS).setPackage(context.packageName))
            MonitorStore.setSchedule(MonitorStore.state.value.currentIntervalSeconds, MonitorPreferences(context).priorityContinuous)
        }
        fun stopAuto(context: Context) {
            MonitorPreferences(context).autoModeWasActive = false
            MonitorStore.setMonitoring(false)
            runCatching { context.startService(Intent(context, WifiMonitorService::class.java).setAction(ACTION_STOP)) }
                .onFailure { context.stopService(Intent(context, WifiMonitorService::class.java)) }
        }
    }
}
