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
     * Privacy authorization catalogue switch (D-013 / R-5).
     *
     * The item list is owned by the cloud, never by configuration: `privacyPromptItems` was removed.
     * When the switch is off the behaviour is identical to "catalogue unavailable": no report, no
     * local fallback list, entry into the entertainment system stays possible (FEATURE_DISABLED).
     */
    val privacyCatalogEnabled: Boolean = true,
    /** `Accept-Language` of the catalogue request (zh-CN / en-US, default zh-CN). */
    val privacyCatalogLanguage: String = "zh-CN",
    /** In process catalogue cache TTL ceiling; the response `max-age` may only shorten it. */
    val privacyCatalogCacheTtlMillis: Long = 300_000L,
)
