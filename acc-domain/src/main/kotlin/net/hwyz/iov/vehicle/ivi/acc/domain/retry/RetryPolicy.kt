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

/** Session polling defaults; frequency, timeout and stop conditions await design review. */
data class SessionPollPolicy(
    val intervalMillis: Long = 2_000L,
    val timeoutMillis: Long = 120_000L,
    val maxAttempts: Int = 60,
) {
    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }
}
