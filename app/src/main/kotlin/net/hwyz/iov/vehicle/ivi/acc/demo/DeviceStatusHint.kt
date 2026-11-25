package net.hwyz.iov.vehicle.ivi.acc.demo

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus

/**
 * Deterministic prompt for a device identity that retrying cannot fix (D-010「状态驱动界面」).
 *
 * The service state is authoritative when it is usable; a failure reported as [DeviceSecurityState.UNKNOWN]
 * still carries the published reason code, so the prompt stays specific instead of degrading to a
 * generic "service unreachable": `KEY_UNAVAILABLE` / `NOT_PROVISIONED` / certificate codes mean the
 * device is not provisioned, `CALLER_REJECTED` means the caller (signing or whitelist) is wrong.
 */
enum class DeviceStatusHint {
    RETRYABLE,
    NOT_PROVISIONED,
    CERT_REQUIRED,
    BLOCKED,
    CALLER_REJECTED,
    TOKEN_UNAVAILABLE,
    SERVICE_UNAVAILABLE,
}

/** Published `SecError` codes that matter to the prompt (mirrors `acc-data-sec`). */
private object HintCodes {
    const val NOT_PROVISIONED = 1
    const val CALLER_REJECTED = 2
    const val KEY_UNAVAILABLE = 4
    const val CERT_INVALID = 5
    const val CERT_EXPIRED = 6
    const val TOKEN_UNAVAILABLE = 7
    const val DEVICE_BLOCKED = 10
}

fun deviceStatusHint(status: DeviceSecurityStatus): DeviceStatusHint {
    if (isDeviceStateRetryable(status.state)) return DeviceStatusHint.RETRYABLE
    return when (status.state) {
        DeviceSecurityState.NOT_PROVISIONED -> DeviceStatusHint.NOT_PROVISIONED
        DeviceSecurityState.PROVISIONING -> DeviceStatusHint.NOT_PROVISIONED
        DeviceSecurityState.BLOCKED -> DeviceStatusHint.BLOCKED
        DeviceSecurityState.ERROR -> DeviceStatusHint.NOT_PROVISIONED
        else -> when (status.reasonCode) {
            HintCodes.NOT_PROVISIONED, HintCodes.KEY_UNAVAILABLE, HintCodes.CERT_INVALID ->
                DeviceStatusHint.NOT_PROVISIONED

            HintCodes.CERT_EXPIRED -> DeviceStatusHint.CERT_REQUIRED
            HintCodes.DEVICE_BLOCKED -> DeviceStatusHint.BLOCKED
            HintCodes.CALLER_REJECTED -> DeviceStatusHint.CALLER_REJECTED
            HintCodes.TOKEN_UNAVAILABLE -> DeviceStatusHint.TOKEN_UNAVAILABLE
            else -> DeviceStatusHint.SERVICE_UNAVAILABLE
        }
    }
}
