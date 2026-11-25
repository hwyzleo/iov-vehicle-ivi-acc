package net.hwyz.iov.vehicle.ivi.acc.domain

import net.hwyz.iov.vehicle.ivi.acc.domain.retry.SessionPollPolicy
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-014 / R-13: the polling policy must cover the whole session lifetime.
 *
 * The former `min(ttl-60s, 540s)` cap and `maxAttempts` as a primary stop condition are gone: a phone
 * that binds late must still be observed (A-021).
 */
class SessionPollPolicyTest {

    private val policy = SessionPollPolicy()

    @Test
    fun `default ladder grows and repeats its last step`() {
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L), policy.backoffMillis)
        assertEquals(2_000L, policy.stepMillis(0))
        assertEquals(5_000L, policy.stepMillis(1))
        assertEquals(10_000L, policy.stepMillis(2))
        assertEquals(30_000L, policy.stepMillis(3))
        assertEquals(30_000L, policy.stepMillis(4))
        assertEquals(30_000L, policy.stepMillis(99))
        assertEquals(2_000L, policy.stepMillis(-3))
    }

    @Test
    fun `deadline is the session expiry and there is no local cap`() {
        val expiresAt = 1_700_000_600_000L
        assertEquals(expiresAt, policy.deadline(expiresAt))
        // Nine minutes and fifty five seconds of session lifetime is polled, not truncated to 540 s.
        assertEquals(expiresAt, policy.deadline(expiresAt))
    }

    @Test
    fun `an attempt needs the request margin before the deadline`() {
        val now = 1_700_000_000_000L
        // 4 s left: the 5 s margin does not fit, so no request is sent.
        assertFalse(policy.nextAttemptAllowed(now, now + 4_000L))
        // 6 s left: it fits.
        assertTrue(policy.nextAttemptAllowed(now, now + 6_000L))
        assertEquals(1_000L, policy.remainingBudgetMillis(now, now + 6_000L))
        assertNull(policy.attemptDelayMillis(0, now, now + 4_000L))
        assertEquals(1_000L, policy.attemptDelayMillis(0, now, now + 6_000L))
    }

    @Test
    fun `an attempt never waits past the session deadline`() {
        val now = 1_700_000_000_000L
        // 40 s left, budget 35 s, next step 30 s -> the step fits unchanged.
        assertEquals(30_000L, policy.attemptDelayMillis(3, now, now + 40_000L))
        // 12 s left, budget 7 s, next step 30 s -> clamped to the budget.
        assertEquals(7_000L, policy.attemptDelayMillis(3, now, now + 12_000L))
    }

    @Test
    fun `short remaining window is flagged for diagnostics only`() {
        val now = 1_700_000_000_000L
        assertTrue(policy.shortWindow(now, now + 20_000L))
        assertFalse(policy.shortWindow(now, now + 120_000L))
        assertFalse(policy.shortWindow(now, now + 2_000L))
    }

    @Test
    fun `custom ladders keep every entry usable`() {
        val custom = SessionPollPolicy(backoffMillis = listOf(100L), requestMarginMillis = 0L)
        assertEquals(100L, custom.stepMillis(7))
        assertTrue(custom.nextAttemptAllowed(0L, 1L))
        assertFalse(custom.nextAttemptAllowed(0L, 0L))
    }
}
