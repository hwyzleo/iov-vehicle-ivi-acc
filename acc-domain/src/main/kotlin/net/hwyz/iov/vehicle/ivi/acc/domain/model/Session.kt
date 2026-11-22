package net.hwyz.iov.vehicle.ivi.acc.domain.model

import net.hwyz.iov.vehicle.ivi.acc.domain.log.mask

/**
 * Short lived activation session returned by the cloud.
 *
 * [qrPayload] is a display secret: it must not be persisted long term and must not be logged.
 */
data class SessionRef(
    val sessionId: String,
    val qrPayload: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis >= expiresAtEpochMillis

    override fun toString(): String =
        "SessionRef(id=${mask(sessionId)}, expiresAt=$expiresAtEpochMillis)"
}

/** Session observation dimension. Kept separate from the binding fact owned by the cloud. */
sealed interface SessionObservation {
    data object None : SessionObservation

    data object Active : SessionObservation

    data object Expired : SessionObservation

    /** Transport timeout or undecidable answer: the result is unknown, not a business failure. */
    data object ResultUnknown : SessionObservation

    /** Only produced from an authoritative result of the current flow generation and session. */
    data class BoundConfirmed(val bindingOperationId: String? = null) : SessionObservation
}

/**
 * Opaque short lived material used to complete the local account login.
 *
 * The value is held by the platform secure store; only a [handle] travels through the domain.
 */
data class LoginMaterial(
    val handle: String,
    val expiresAtEpochMillis: Long,
) {
    fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis >= expiresAtEpochMillis

    override fun toString(): String = "LoginMaterial(handle=${mask(handle)})"
}
