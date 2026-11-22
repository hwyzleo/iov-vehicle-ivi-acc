package net.hwyz.iov.vehicle.ivi.acc.domain.time

/** Monotonic clock: timeouts, backoff and durations. Never derived from wall clock changes. */
interface MonotonicClock {
    fun elapsedMillis(): Long
}

/** Wall clock: user action timestamps and audit hints. Not authoritative on the cloud side. */
interface WallClock {
    fun nowEpochMillis(): Long
}

object SystemMonotonicClock : MonotonicClock {
    private val startedNanos: Long = System.nanoTime()

    override fun elapsedMillis(): Long = (System.nanoTime() - startedNanos) / 1_000_000L
}

object SystemWallClock : WallClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
