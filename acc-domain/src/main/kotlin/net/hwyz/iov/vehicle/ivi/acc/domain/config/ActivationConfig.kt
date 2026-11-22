package net.hwyz.iov.vehicle.ivi.acc.domain.config

import net.hwyz.iov.vehicle.ivi.acc.domain.retry.RetryPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.SessionPollPolicy

/**
 * Activation flow configuration.
 *
 * All timing and capacity values are first implementation defaults: polling frequency, timeout,
 * queue capacity and retention still have to be confirmed against the cloud contract.
 */
data class ActivationConfig(
    val sessionPoll: SessionPollPolicy = SessionPollPolicy(),
    val recordRetry: RetryPolicy = RetryPolicy(),
    val recordQueueLimit: Int = 50,
    /**
     * Privacy authorization items. The concrete list is owned by the corresponding settings PRD;
     * an empty list means the decision is recorded at account + vehicle scope only.
     */
    val privacyPromptItems: List<String> = emptyList(),
)
