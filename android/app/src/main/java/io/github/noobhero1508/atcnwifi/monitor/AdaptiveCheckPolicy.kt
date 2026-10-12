package io.github.noobhero1508.atcnwifi.monitor

/** Uses monotonic elapsed time, so changing the clock cannot alter the screen-off policy. */
internal object AdaptiveCheckPolicy {
    const val SCREEN_OFF_GRACE_MS = 120_000L

    fun intervalSeconds(
        interactive: Boolean,
        screenOffAtElapsedMs: Long?,
        nowElapsedMs: Long,
        priorityContinuous: Boolean,
    ): Int = when {
        priorityContinuous || interactive -> 5
        screenOffAtElapsedMs == null -> 10
        (nowElapsedMs - screenOffAtElapsedMs).coerceAtLeast(0) < SCREEN_OFF_GRACE_MS -> 10
        else -> 30
    }

    /** Slow re-identification also recovers a transient failed first gateway read. */
    fun identificationRetrySeconds(failures: Int, confirmedNonTarget: Boolean): Int =
        if (confirmedNonTarget) 300 else listOf(5, 15, 30, 60, 300)[(failures - 1).coerceIn(0, 4)]

    fun outageIntervalSeconds(baseSeconds: Int, failures: Int): Int =
        maxOf(baseSeconds, listOf(10, 30, 60)[(failures - 1).coerceIn(0, 2)])
}
