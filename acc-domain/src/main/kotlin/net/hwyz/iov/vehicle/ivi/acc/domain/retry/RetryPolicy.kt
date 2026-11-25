package net.hwyz.iov.vehicle.ivi.acc.domain.retry

/**
 * Bounded retry policy for uploadable records.
 *
 * Values are defaults for the first implementation; queue capacity, intervals and limits still
 * have to be confirmed against the cloud contract.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialBackoffMillis: Long = 1_000L,
    val maxBackoffMillis: Long = 30_000L,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(initialBackoffMillis > 0) { "initialBackoffMillis must be positive" }
        require(maxBackoffMillis >= initialBackoffMillis) { "maxBackoffMillis must not be smaller" }
    }

    fun backoffMillis(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val factor = 1L shl (safeAttempt - 1).coerceAtMost(20)
        return (initialBackoffMillis * factor).coerceAtMost(maxBackoffMillis)
    }
}

/**
 * Session polling policy (IVI-ACC-DSN-CR-005 D-014 / R-13).
 *
 * The cloud session lifetime is authoritative: polling runs until `expiresAt` minus a per request
 * margin and there is deliberately no local hard cap (`min(ttl-60s, 540s)` and `maxAttempts` as a
 * primary stop condition are removed). A phone that finishes binding late must still be observed
 * (A-021).
 */
data class SessionPollPolicy(
    /** Backoff ladder; the last entry repeats. Only "consecutive undecided polls" advance it. */
    val backoffMillis: List<Long> = listOf(2_000L, 5_000L, 10_000L, 30_000L),
    /** Head room kept before the session deadline so a request never crosses it. */
    val requestMarginMillis: Long = 5_000L,
    /** A short remaining lifetime is not a reason to give up: used to avoid a delayed start. */
    val minTimeoutMillis: Long = 30_000L,
) {
    init {
        require(backoffMillis.isNotEmpty()) { "backoffMillis must not be empty" }
        require(backoffMillis.all { it > 0 }) { "backoffMillis entries must be positive" }
        require(requestMarginMillis >= 0) { "requestMarginMillis must not be negative" }
        require(minTimeoutMillis > 0) { "minTimeoutMillis must be positive" }
    }

    fun stepMillis(step: Int): Long = backoffMillis[step.coerceIn(0, backoffMillis.lastIndex)]

    /** Polling deadline = end of the session validity; there is no local cap. */
    fun deadline(sessionExpiresAt: Long): Long = sessionExpiresAt

    /** A single request must be sent with [requestMarginMillis] still left before the deadline. */
    fun nextAttemptAllowed(now: Long, sessionExpiresAt: Long): Boolean =
        now + requestMarginMillis < sessionExpiresAt

    /** Remaining poll budget, never negative. */
    fun remainingBudgetMillis(now: Long, sessionExpiresAt: Long): Long =
        (sessionExpiresAt - now - requestMarginMillis).coerceAtLeast(0L)

    /**
     * Delay before the next poll, clamped to the remaining budget so the last request still fits.
     *
     * Returns null when no further attempt is allowed (session deadline reached).
     * Implemented as a pure function so the ladder and its boundaries are unit testable.
     */
    fun attemptDelayMillis(step: Int, now: Long, sessionExpiresAt: Long): Long? {
        val budget = remainingBudgetMillis(now, sessionExpiresAt)
        if (!nextAttemptAllowed(now, sessionExpiresAt) || budget <= 0L) return null
        return stepMillis(step).coerceAtMost(budget)
    }

    /** True when the remaining lifetime is shorter than [minTimeoutMillis] (diagnostics only). */
    fun shortWindow(now: Long, sessionExpiresAt: Long): Boolean =
        nextAttemptAllowed(now, sessionExpiresAt) && remainingBudgetMillis(now, sessionExpiresAt) < minTimeoutMillis
}
