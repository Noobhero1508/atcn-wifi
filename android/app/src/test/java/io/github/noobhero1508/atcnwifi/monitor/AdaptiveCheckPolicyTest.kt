package io.github.noobhero1508.atcnwifi.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveCheckPolicyTest {
    @Test
    fun `interactive device requests five second checks`() {
        assertEquals(5, AdaptiveCheckPolicy.intervalSeconds(true, 0L, 500_000L, false))
    }

    @Test
    fun `screen off grace ends exactly at two minutes`() {
        assertEquals(10, AdaptiveCheckPolicy.intervalSeconds(false, 1_000L, 120_999L, false))
        assertEquals(30, AdaptiveCheckPolicy.intervalSeconds(false, 1_000L, 121_000L, false))
    }

    @Test
    fun `priority overrides screen off and wake immediately returns to five seconds`() {
        assertEquals(5, AdaptiveCheckPolicy.intervalSeconds(false, 0L, 900_000L, true))
        assertEquals(5, AdaptiveCheckPolicy.intervalSeconds(true, null, 900_001L, false))
    }

    @Test
    fun `unknown screen off time and future timestamps safely use grace cadence`() {
        assertEquals(10, AdaptiveCheckPolicy.intervalSeconds(false, null, 200_000L, false))
        assertEquals(10, AdaptiveCheckPolicy.intervalSeconds(false, 300_000L, 200_000L, false))
    }

    @Test
    fun `gateway failures retry then become sparse while confirmed non target is sparse immediately`() {
        assertEquals(listOf(5, 15, 30, 60, 300, 300), (1..6).map {
            AdaptiveCheckPolicy.identificationRetrySeconds(it, false)
        })
        assertEquals(300, AdaptiveCheckPolicy.identificationRetrySeconds(1, true))
    }

    @Test
    fun `persistent outages reduce probe churn without shortening screen off cadence`() {
        assertEquals(10, AdaptiveCheckPolicy.outageIntervalSeconds(5, 1))
        assertEquals(30, AdaptiveCheckPolicy.outageIntervalSeconds(5, 2))
        assertEquals(60, AdaptiveCheckPolicy.outageIntervalSeconds(5, 3))
        assertEquals(60, AdaptiveCheckPolicy.outageIntervalSeconds(30, 20))
    }
}
