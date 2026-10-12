package io.github.noobhero1508.atcnwifi.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.noobhero1508.atcnwifi.BuildConfig
import io.github.noobhero1508.atcnwifi.R
import io.github.noobhero1508.atcnwifi.monitor.ActivityItem
import io.github.noobhero1508.atcnwifi.monitor.ActivityLevel
import io.github.noobhero1508.atcnwifi.monitor.DefaultNetworkKind
import io.github.noobhero1508.atcnwifi.monitor.MonitorStatus
import io.github.noobhero1508.atcnwifi.monitor.MonitorUiState
import io.github.noobhero1508.atcnwifi.ui.theme.ErrorRed
import io.github.noobhero1508.atcnwifi.ui.theme.ErrorRedDark
import io.github.noobhero1508.atcnwifi.ui.theme.SuccessGreen
import io.github.noobhero1508.atcnwifi.ui.theme.SuccessGreenDark
import io.github.noobhero1508.atcnwifi.ui.theme.WarningAmber
import io.github.noobhero1508.atcnwifi.ui.theme.WarningAmberDark
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private enum class AppScreen {
    HOME,
    ACTIVITY,
    SETTINGS,
}

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AtcnWifiApp(facade: MonitorUiFacade) {
    val context = LocalContext.current
    val state by facade.state.collectAsStateWithLifecycle()
    var screenName by rememberSaveable { mutableStateOf(AppScreen.HOME.name) }
    val screen = remember(screenName) { AppScreen.valueOf(screenName) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showNotificationExplanation by rememberSaveable { mutableStateOf(false) }

    val permissionDeniedMessage = stringResource(R.string.notification_permission_denied)
    val exportSuccessMessage = stringResource(R.string.export_success)
    val exportFailedMessage = stringResource(R.string.export_failed)
    val exportHeader = stringResource(R.string.export_header)
    val exportFileName = stringResource(R.string.export_file_name)

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            coroutineScope.launch { snackbarHostState.showSnackbar(permissionDeniedMessage) }
        }
        // Android does not require POST_NOTIFICATIONS to run a foreground service. When denied,
        // the service remains visible in Android's Active apps surface instead of the drawer.
        facade.startAuto()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            val exported = runCatching {
                val text = buildExportText(exportHeader, state)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(text)
                } ?: error("Output stream unavailable")
            }.isSuccess
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    if (exported) exportSuccessMessage else exportFailedMessage,
                )
            }
        }
    }

    fun requestAutoMonitoring() {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                POST_NOTIFICATIONS_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) {
            facade.startAuto()
        } else {
            showNotificationExplanation = true
        }
    }

    BackHandler(enabled = screen != AppScreen.HOME) {
        screenName = AppScreen.HOME.name
    }

    Scaffold(
        topBar = {
            AtcnTopBar(
                screen = screen,
                onBack = { screenName = AppScreen.HOME.name },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == AppScreen.HOME,
                    onClick = { screenName = AppScreen.HOME.name },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_home)) },
                )
                NavigationBarItem(
                    selected = screen == AppScreen.ACTIVITY,
                    onClick = { screenName = AppScreen.ACTIVITY.name },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(R.string.nav_activity)) },
                )
                NavigationBarItem(
                    selected = screen == AppScreen.SETTINGS,
                    onClick = { screenName = AppScreen.SETTINGS.name },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        AnimatedContent(
            targetState = screen,
            label = "main-screen",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { target ->
            when (target) {
                AppScreen.HOME -> DashboardScreen(
                    state = state,
                    onAutoChange = { enabled ->
                        if (enabled) requestAutoMonitoring() else facade.stopAuto()
                    },
                    onConnectNow = facade::connectNow,
                    onDiagnose = facade::diagnose,
                    onViewActivity = { screenName = AppScreen.ACTIVITY.name },
                )
                AppScreen.ACTIVITY -> ActivityScreen(
                    activity = state.activity,
                    onClear = facade::clearActivity,
                    onExport = {
                        exportLauncher.launch(exportFileName)
                    },
                )
                AppScreen.SETTINGS -> SettingsScreen(facade = facade)
            }
        }
    }

    if (showNotificationExplanation) {
        AlertDialog(
            onDismissRequest = { showNotificationExplanation = false },
            icon = { Icon(Icons.Default.NotificationsActive, contentDescription = null) },
            title = { Text(stringResource(R.string.notification_permission_title)) },
            text = { Text(stringResource(R.string.notification_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationExplanation = false
                        notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
                    },
                ) {
                    Text(stringResource(R.string.notification_permission_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationExplanation = false }) {
                    Text(stringResource(R.string.dismiss))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AtcnTopBar(screen: AppScreen, onBack: () -> Unit) {
    TopAppBar(
        title = {
            if (screen == AppScreen.HOME) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(size = 38)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(
                        if (screen == AppScreen.ACTIVITY) {
                            R.string.activity_title
                        } else {
                            R.string.settings_title
                        },
                    ),
                )
            }
        },
        navigationIcon = {
            if (screen != AppScreen.HOME) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun DashboardScreen(
    state: MonitorUiState,
    onAutoChange: (Boolean) -> Unit,
    onConnectNow: () -> Unit,
    onDiagnose: () -> Unit,
    onViewActivity: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { ConnectionStatusCard(state) }
        item { MonitoringDetailsCard(state) }
        item {
            AutoMonitoringCard(
                enabled = state.monitoringEnabled,
                busy = state.busy,
                onEnabledChange = onAutoChange,
            )
        }
        item {
            ActionButtons(
                busy = state.busy,
                onConnectNow = onConnectNow,
                onDiagnose = onDiagnose,
            )
        }
        item {
            RecentActivityCard(
                items = state.activity.takeLast(4).asReversed(),
                onViewAll = onViewActivity,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.version_format, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.beta_label),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitoringDetailsCard(state: MonitorUiState) {
    val networkLabel = stringResource(
        when (state.defaultNetwork) {
            DefaultNetworkKind.NONE -> if (!state.monitoringEnabled && !state.busy && state.lastCheckAtMs == null)
                R.string.default_network_not_observed else R.string.default_network_none
            DefaultNetworkKind.WIFI -> R.string.default_network_wifi
            DefaultNetworkKind.CELLULAR -> R.string.default_network_cellular
            DefaultNetworkKind.VPN -> R.string.default_network_vpn
            DefaultNetworkKind.OTHER -> R.string.default_network_other
        },
    )
    val lastCheck = state.lastCheckAtMs?.let {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
    } ?: stringResource(R.string.check_not_yet)
    SettingsCard {
        Text(stringResource(R.string.default_network_format, networkLabel), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.default_network_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(stringResource(R.string.last_check_format, lastCheck), style = MaterialTheme.typography.bodyMedium)
        state.lastCheckDurationMs?.let {
            Text(stringResource(R.string.check_duration_format, it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val interval = state.currentIntervalSeconds
        Text(
            if (interval != null && state.monitoringEnabled) pluralStringResource(R.plurals.current_interval_format, interval, interval)
            else stringResource(R.string.no_periodic_check),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConnectionStatusCard(state: MonitorUiState) {
    val visual = statusVisual(state.status)
    val detail = state.latestMessage.ifBlank {
        stringResource(
            when (state.status) {
                MonitorStatus.INTERNET_AVAILABLE -> R.string.status_connected_detail
                MonitorStatus.WAITING_FOR_WIFI -> R.string.status_waiting_detail
                else -> R.string.status_idle_detail
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = visual.containerColor),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                text = stringResource(R.string.status_title),
                style = MaterialTheme.typography.labelLarge,
                color = visual.contentColor.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = visual.contentColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                color = visual.contentColor,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                imageVector = visual.icon,
                                contentDescription = stringResource(visual.contentDescription),
                                tint = visual.contentColor,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(visual.title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = visual.contentColor,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = visual.contentColor.copy(alpha = 0.82f),
                    )
                }
            }
            if (state.busy) {
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = visual.contentColor,
                    trackColor = visual.contentColor.copy(alpha = 0.16f),
                )
            }
        }
    }
}

@Composable
private fun AutoMonitoringCard(
    enabled: Boolean,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val switchDescription = stringResource(
        if (enabled) R.string.auto_switch_off else R.string.auto_switch_on,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (enabled) Icons.Default.Sync else Icons.Default.PauseCircle,
                        contentDescription = null,
                        tint = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(25.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.auto_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        if (enabled) R.string.auto_enabled else R.string.auto_disabled,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                enabled = !busy || enabled,
                modifier = Modifier.semantics {
                    contentDescription = switchDescription
                },
            )
        }
        Text(
            text = stringResource(R.string.auto_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun ActionButtons(
    busy: Boolean,
    onConnectNow: () -> Unit,
    onDiagnose: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val horizontal = maxWidth >= 520.dp
        if (horizontal) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectButton(busy, onConnectNow, Modifier.weight(1f))
                DiagnoseButton(busy, onDiagnose, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectButton(busy, onConnectNow, Modifier.fillMaxWidth())
                DiagnoseButton(busy, onDiagnose, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ConnectButton(busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(
            if (busy) Icons.Default.Refresh else Icons.Default.PlayArrow,
            contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (busy) R.string.action_in_progress else R.string.connect_now),
        )
    }
}

@Composable
private fun DiagnoseButton(busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(Icons.Default.Troubleshoot, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.diagnose))
    }
}

@Composable
private fun RecentActivityCard(items: List<ActivityItem>, onViewAll: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 12.dp, end = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.activity_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onViewAll) {
                Text(stringResource(R.string.view_all))
            }
        }
        if (items.isEmpty()) {
            EmptyActivity(modifier = Modifier.padding(20.dp))
        } else {
            items.forEachIndexed { index, item ->
                ActivityRow(item = item, compact = true)
                if (index != items.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActivityScreen(
    activity: List<ActivityItem>,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onClear,
                enabled = activity.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.clear_log))
            }
            Button(
                onClick = onExport,
                enabled = activity.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.export_log))
            }
        }
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            if (activity.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyActivity(modifier = Modifier.padding(32.dp))
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(
                        items = activity.asReversed(),
                        key = { it.id },
                    ) { item ->
                        ActivityRow(item = item, compact = false)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, compact: Boolean) {
    val visual = activityVisual(item.level)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = if (compact) 11.dp else 15.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = stringResource(visual.description),
            tint = visual.color,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (compact) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = formatTime(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyActivity(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ReceiptLong,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.activity_empty),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.activity_empty_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsScreen(facade: MonitorUiFacade) {
    var compatibilityEnabled by rememberSaveable { mutableStateOf(facade.compatibilityEnabled) }
    var priorityContinuous by rememberSaveable { mutableStateOf(facade.priorityContinuous) }
    val compatibilityDescription = stringResource(R.string.compatibility_accessibility)
    val priorityDescription = stringResource(R.string.priority_accessibility)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionLabel(R.string.connection_section) }
        item {
            SettingsCard {
                SettingsHeader(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.check_frequency),
                    description = stringResource(R.string.check_frequency_description),
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsHeader(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(R.string.priority_title),
                        description = stringResource(R.string.priority_description),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = priorityContinuous,
                        onCheckedChange = { enabled ->
                            priorityContinuous = enabled
                            facade.priorityContinuous = enabled
                        },
                        modifier = Modifier.semantics { contentDescription = priorityDescription },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsHeader(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.compatibility_title),
                        description = stringResource(R.string.compatibility_description),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = compatibilityEnabled,
                        onCheckedChange = { enabled ->
                            compatibilityEnabled = enabled
                            facade.compatibilityEnabled = enabled
                        },
                        modifier = Modifier.semantics {
                            contentDescription = compatibilityDescription
                        },
                    )
                }
            }
        }
        item {
            SettingsCard {
                SettingsHeader(
                    icon = Icons.Default.Wifi,
                    title = stringResource(R.string.mobile_data_title),
                    description = stringResource(R.string.mobile_data_description),
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = facade::openWifiSettings) {
                    Text(stringResource(R.string.open_wifi_settings))
                }
            }
        }
        item { SectionLabel(R.string.battery_section) }
        item {
            SettingsCard {
                SettingsHeader(
                    icon = Icons.Default.BatterySaver,
                    title = stringResource(R.string.battery_title),
                    description = stringResource(R.string.battery_description),
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = facade::openBatterySettings) {
                    Text(stringResource(R.string.open_battery_settings))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 18.dp))
                SettingsHeader(
                    icon = Icons.Default.NotificationsActive,
                    title = stringResource(R.string.notification_title),
                    description = stringResource(R.string.notification_description),
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = facade::openNotificationSettings) {
                    Text(stringResource(R.string.open_notification_settings))
                }
            }
        }
        item { SectionLabel(R.string.privacy_section) }
        item {
            SettingsCard {
                SettingsHeader(
                    icon = Icons.Default.PrivacyTip,
                    title = stringResource(R.string.privacy_title),
                    description = stringResource(R.string.privacy_description),
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(modifier = Modifier.padding(14.dp)) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.privacy_http_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun SectionLabel(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun SettingsHeader(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppLogo(size: Int) {
    val description = stringResource(R.string.cd_app_logo)
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size((size * 0.62f).dp),
        )
    }
}

private data class StatusVisual(
    @param:StringRes val title: Int,
    val icon: ImageVector,
    val contentColor: Color,
    val containerColor: Color,
    @param:StringRes val contentDescription: Int,
)

@Composable
private fun statusVisual(status: MonitorStatus): StatusVisual {
    val dark = isSystemInDarkTheme()
    return when (status) {
        MonitorStatus.INTERNET_AVAILABLE -> StatusVisual(
            R.string.status_connected,
            Icons.Default.CloudDone,
            if (dark) SuccessGreenDark else SuccessGreen,
            if (dark) Color(0xFF173A27) else Color(0xFFE0F4E7),
            R.string.cd_connected,
        )
        MonitorStatus.LOGIN_REQUIRED,
        MonitorStatus.RETRY_WAIT,
        MonitorStatus.CONNECTION_UNKNOWN,
        MonitorStatus.NETWORK_UNAVAILABLE,
        -> StatusVisual(
            when (status) {
                MonitorStatus.LOGIN_REQUIRED -> R.string.status_login_required
                MonitorStatus.NETWORK_UNAVAILABLE -> R.string.status_network_unavailable
                MonitorStatus.CONNECTION_UNKNOWN -> R.string.status_connection_unknown
                else -> R.string.status_retry_wait
            },
            Icons.Default.Warning,
            if (dark) WarningAmberDark else WarningAmber,
            if (dark) Color(0xFF422B14) else Color(0xFFFFEEDB),
            R.string.cd_warning,
        )
        MonitorStatus.ERROR -> StatusVisual(
            R.string.status_error,
            Icons.Default.Error,
            if (dark) ErrorRedDark else ErrorRed,
            if (dark) Color(0xFF4B1F1C) else Color(0xFFFFEDEA),
            R.string.cd_error,
        )
        MonitorStatus.CHECKING,
        MonitorStatus.CONNECTING,
        -> StatusVisual(
            if (status == MonitorStatus.CONNECTING) {
                R.string.status_connecting
            } else {
                R.string.status_checking
            },
            if (status == MonitorStatus.CONNECTING) Icons.Default.Sync else Icons.Default.WifiFind,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
            R.string.cd_loading,
        )
        MonitorStatus.WAITING_FOR_WIFI -> StatusVisual(
            R.string.status_waiting_wifi,
            Icons.Default.WifiOff,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant,
            R.string.cd_warning,
        )
        MonitorStatus.NOT_TARGET_NETWORK -> StatusVisual(
            R.string.status_not_target,
            Icons.Default.WifiOff,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant,
            R.string.cd_warning,
        )
        MonitorStatus.IDLE -> StatusVisual(
            R.string.status_idle,
            Icons.Default.Wifi,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
            R.string.cd_app_logo,
        )
    }
}

private data class ActivityVisual(
    val icon: ImageVector,
    val color: Color,
    @param:StringRes val description: Int,
)

@Composable
private fun activityVisual(level: ActivityLevel): ActivityVisual {
    val dark = isSystemInDarkTheme()
    return when (level) {
        ActivityLevel.INFO -> ActivityVisual(
            Icons.Default.Info,
            MaterialTheme.colorScheme.primary,
            R.string.log_level_info,
        )
        ActivityLevel.SUCCESS -> ActivityVisual(
            Icons.Default.CheckCircle,
            if (dark) SuccessGreenDark else SuccessGreen,
            R.string.log_level_success,
        )
        ActivityLevel.WARNING -> ActivityVisual(
            Icons.Default.Warning,
            if (dark) WarningAmberDark else WarningAmber,
            R.string.log_level_warning,
        )
        ActivityLevel.ERROR -> ActivityVisual(
            Icons.Default.Error,
            MaterialTheme.colorScheme.error,
            R.string.log_level_error,
        )
    }
}

private fun formatTime(item: ActivityItem): String = runCatching {
    DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(item.timestamp)
}.getOrElse { item.timestamp.toString() }

private fun buildExportText(header: String, state: MonitorUiState): String = buildString {
    appendLine(header)
    appendLine("App: ${BuildConfig.VERSION_NAME}")
    appendLine("WiFi status: ${state.status}")
    appendLine("Android default network: ${state.defaultNetwork}")
    appendLine("Auto monitoring: ${state.monitoringEnabled}; priority: ${state.priorityContinuous}")
    appendLine("Check interval seconds: ${state.currentIntervalSeconds ?: "none"}")
    appendLine("Last check UTC: ${state.lastCheckAtMs?.let(Instant::ofEpochMilli) ?: "none"}")
    appendLine("Last check duration ms: ${state.lastCheckDurationMs ?: "none"}")
    appendLine("---")
    state.activity.forEach { item ->
        append('[')
        append(item.timestamp)
        append("] [")
        append(item.level.name)
        append("] ")
        appendLine(item.message)
    }
}
