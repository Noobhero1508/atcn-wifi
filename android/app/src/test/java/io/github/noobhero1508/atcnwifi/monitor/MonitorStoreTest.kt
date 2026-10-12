package io.github.noobhero1508.atcnwifi.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorStoreTest {
    @Test
    fun `ending a manual service preserves verified result but clears requested cadence`() {
        MonitorStore.setBusy(false)
        MonitorStore.setMonitoring(true)
        MonitorStore.setStatus(MonitorStatus.INTERNET_AVAILABLE, "Verified Wi-Fi Internet")
        MonitorStore.setSchedule(5, false)

        MonitorStore.setMonitoring(false)

        assertEquals(MonitorStatus.INTERNET_AVAILABLE, MonitorStore.state.value.status)
        assertNull(MonitorStore.state.value.currentIntervalSeconds)
    }

    @Test
    fun `changing WiFi discards timing from previous network`() {
        MonitorStore.recordCheck(10_000L, 123L)
        MonitorStore.setSchedule(30, false)

        MonitorStore.clearNetworkChecks()

        assertNull(MonitorStore.state.value.lastCheckAtMs)
        assertNull(MonitorStore.state.value.lastCheckDurationMs)
        assertNull(MonitorStore.state.value.currentIntervalSeconds)
    }

    @Test
    fun `default cellular observation does not claim that WiFi Internet is available`() {
        MonitorStore.setStatus(MonitorStatus.LOGIN_REQUIRED)

        MonitorStore.setDefaultNetwork(DefaultNetworkKind.CELLULAR)

        assertEquals(DefaultNetworkKind.CELLULAR, MonitorStore.state.value.defaultNetwork)
        assertEquals(MonitorStatus.LOGIN_REQUIRED, MonitorStore.state.value.status)
    }
}
