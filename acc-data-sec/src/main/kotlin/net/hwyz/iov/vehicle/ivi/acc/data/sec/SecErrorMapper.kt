package net.hwyz.iov.vehicle.ivi.acc.data.sec

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Published `SecError` codes (IVI-SEC-SPEC设计 §4.3).
 *
 * Mirrored as plain integers so this module's flavour agnostic code does not import the AIDL
 * contract (`identityLocal` builds must stay free of `sec-api`, D-018 构建门禁). The values are
 * frozen on the service side and must not be renumbered; `SecApiConformanceTest` asserts in the
 * `identitySec` flavour that the mirror still equals the published constants.
 */
object SecErrorCodes {
    const val OK = 0
    const val NOT_PROVISIONED = 1
    const val CALLER_REJECTED = 2
    const val INVALID_ARGUMENT = 3
    const val KEY_UNAVAILABLE = 4
    const val CERT_INVALID = 5
    const val CERT_EXPIRED = 6
    const val TOKEN_UNAVAILABLE = 7
    const val RATE_LIMITED = 8
    const val DEPENDENCY_UNAVAILABLE = 9
    const val DEVICE_BLOCKED = 10
    const val SESSION_CONFLICT = 11

    /** Wire prefix the service puts in exception messages (`IVI_SEC:<code>:<NAME>`). */
    const val WIRE_PREFIX = "IVI_SEC:"
}

/** Published `SecState` codes (IVI-SEC-SPEC设计 §4.4); mirrored for the same reason as the errors. */
object SecStateCodes {
    const val NOT_PROVISIONED = 0
    const val PROVISIONING = 1
    const val ISSUED_PENDING_CLOUD = 2
    const val ACTIVE = 3
    const val RENEW_DUE = 4
    const val BLOCKED = 5
    const val ERROR = 6
}

/**
 * Maps the security service contract onto the local categories of IVI-ACC
 * (IVI-SEC-SPEC设计 §4.3/§4.4, IVI-ACC-SPEC设计 D-010).
 *
 * The mapping only decides the prompt and the retry policy; it never turns a failure into a usable
 * token. Codes are the published `SecError` values and must not be renumbered on either side.
 */
object SecErrorMapper {

    fun categoryOf(failure: SecRemoteException): ErrorCategory =
        failure.code?.let(::categoryOfCode) ?: categoryOfKind(failure.kind)

    fun categoryOfCode(code: Int): ErrorCategory = when (code) {
        SecErrorCodes.NOT_PROVISIONED,
        SecErrorCodes.KEY_UNAVAILABLE,
        SecErrorCodes.CERT_INVALID,
        SecErrorCodes.CERT_EXPIRED,
        SecErrorCodes.DEVICE_BLOCKED,
        -> ErrorCategory.AUTH_REJECTED

        SecErrorCodes.CALLER_REJECTED,
        SecErrorCodes.INVALID_ARGUMENT,
        SecErrorCodes.TOKEN_UNAVAILABLE,
        -> ErrorCategory.CONTRACT_REJECTED

        SecErrorCodes.RATE_LIMITED,
        SecErrorCodes.DEPENDENCY_UNAVAILABLE,
        SecErrorCodes.SESSION_CONFLICT,
        -> ErrorCategory.RETRYABLE

        else -> ErrorCategory.CONTRACT_REJECTED
    }

    fun categoryOfKind(kind: SecFailureKind): ErrorCategory = when (kind) {
        // Not fixable by retrying: the caller has to install or provision the service.
        SecFailureKind.SERVICE_MISSING,
        SecFailureKind.CALLER_REJECTED,
        -> ErrorCategory.CONTRACT_REJECTED

        // Transient binder conditions: the user state and the refresh token must survive them.
        SecFailureKind.PROCESS_DIED,
        SecFailureKind.TIMEOUT,
        SecFailureKind.UNKNOWN,
        -> ErrorCategory.RETRYABLE
    }

    fun stateOf(code: Int): DeviceSecurityState = when (code) {
        SecStateCodes.NOT_PROVISIONED -> DeviceSecurityState.NOT_PROVISIONED
        SecStateCodes.PROVISIONING -> DeviceSecurityState.PROVISIONING
        SecStateCodes.ISSUED_PENDING_CLOUD -> DeviceSecurityState.ISSUED_PENDING_CLOUD
        SecStateCodes.ACTIVE -> DeviceSecurityState.ACTIVE
        SecStateCodes.RENEW_DUE -> DeviceSecurityState.RENEW_DUE
        SecStateCodes.BLOCKED -> DeviceSecurityState.BLOCKED
        SecStateCodes.ERROR -> DeviceSecurityState.ERROR
        else -> DeviceSecurityState.UNKNOWN
    }

    /** Reads the decimal code out of the published wire message (`IVI_SEC:<code>:<NAME>`). */
    fun codeOfWireMessage(message: String?): Int? {
        val raw = message?.substringAfter(SecErrorCodes.WIRE_PREFIX, "").orEmpty()
        return raw.substringBefore(':').trim().toIntOrNull()
    }

    /** True when a retry affordance may be offered for this state (it can actually succeed). */
    fun isRetryableState(state: DeviceSecurityState): Boolean = when (state) {
        DeviceSecurityState.ACTIVE,
        DeviceSecurityState.RENEW_DUE,
        DeviceSecurityState.ISSUED_PENDING_CLOUD,
        -> true

        DeviceSecurityState.NOT_PROVISIONED,
        DeviceSecurityState.PROVISIONING,
        DeviceSecurityState.BLOCKED,
        DeviceSecurityState.ERROR,
        DeviceSecurityState.UNKNOWN,
        -> false
    }
}
