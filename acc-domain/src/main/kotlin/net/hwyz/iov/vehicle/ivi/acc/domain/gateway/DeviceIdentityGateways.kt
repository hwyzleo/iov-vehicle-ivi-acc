package net.hwyz.iov.vehicle.ivi.acc.domain.gateway

import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * A device token issued by the unified car security service (DSN-CR-004 D-010).
 *
 * The token itself stays in memory only; [expiresAtEpochMillis] lets a caller reuse it inside one
 * request chain without asking the service again.
 */
data class DeviceTokenRef(
    val accessToken: String,
    val expiresAtEpochMillis: Long,
)

/** Outcome of asking for a device token; never carries a forged or anonymous fallback. */
sealed interface DeviceTokenOutcome {
    data class Available(val token: DeviceTokenRef) : DeviceTokenOutcome

    /**
     * No usable device token. [reason] is diagnostic only: a service error code or a local cause,
     * never a credential.
     */
    data class Unavailable(
        val category: ErrorCategory,
        val reason: String? = null,
    ) : DeviceTokenOutcome
}

/**
 * Source of the device token used for gateway calls and for user token renewal (DSN-CR-004 D-008).
 *
 * IVI-ACC never owns the device key nor calls the device identity grant itself: the unified security
 * service signs the proof and issues the token, and this port is the only way the app reaches it.
 * The migration-period implementation runs in process; the target implementation is a cross-process
 * client of that service.
 */
interface DeviceTokenSource {
    suspend fun deviceToken(): DeviceTokenOutcome

    /** Drops the cached token, e.g. after the gateway rejected it with 401/403. */
    suspend fun invalidate()
}

/** Device security state reported by the unified service (DSN-CR-004 D-010). */
enum class DeviceSecurityState {
    NOT_PROVISIONED,
    PROVISIONING,
    ISSUED_PENDING_CLOUD,
    ACTIVE,
    RENEW_DUE,
    BLOCKED,
    ERROR,

    /** The service is not reachable or not installed; treat as a retryable authentication problem. */
    UNKNOWN,
}

/** Read only view of the device security state, used to decide the UI, never to derive a token. */
data class DeviceSecurityStatus(
    val state: DeviceSecurityState,
    val deviceId: String? = null,
    val certNotAfterEpochMillis: Long? = null,
    val reasonCode: Int = 0,
)

/**
 * Cheap status lookup that does not require a token.
 *
 * A caller uses it to tell "device not provisioned" apart from "gateway rejected the request", so
 * the retry affordance is not offered for a state that cannot be fixed by retrying.
 */
fun interface DeviceSecurityStatusQuery {
    suspend fun status(): DeviceSecurityStatus
}
