package net.hwyz.iov.vehicle.ivi.acc.domain.gateway

/**
 * Device identity source port (IVI-ACC-DSN-CR-005 D-011 / BLK-4).
 *
 * The only two authoritative sources are:
 *  1. the unified car security service (`DeviceSecurityStatus.deviceId`), and
 *  2. a device level read only platform property (serial number).
 *
 * The Android secure settings device identifier is explicitly **not** a source: it can be reset, it
 * collides across devices of the same model and it is not a factory identity. A-017 enforces that
 * mechanically (CI gate: zero occurrences of that identifier in `acc-*` and `app/`).
 */
enum class DeviceSerialOrigin {
    /** Device identifier delivered by the unified security service (identitySec flavor). */
    SEC_UNIFIED_SERVICE,

    /** `ro.serialno` system property (identityLocal flavor). */
    RO_SERIALNO,

    /** `Build.getSerial()` (identityLocal flavor, restricted permission). */
    BUILD_SERIAL,

    /** Legacy `Build.SERIAL` (identityLocal flavor, old API levels). */
    LEGACY_BUILD_SERIAL,
}

/** Why no device serial is available. Diagnostic only; never carries the value. */
enum class DeviceSerialFailure {
    /** No authoritative source is readable on this unit. */
    NOT_AVAILABLE,

    /** The read only property exists but the caller may not read it. */
    PERMISSION_DENIED,

    /** The unified security service is not reachable or reported an error. */
    SERVICE_UNAVAILABLE,

    /** The source returned a placeholder such as `android.os.Build.UNKNOWN`. */
    INVALID_VALUE,
}

/** Outcome of asking for the device serial. A miss is never papered over with a fabricated value. */
sealed interface DeviceSerialOutcome {
    data class Available(
        val serial: String,
        val origin: DeviceSerialOrigin,
    ) : DeviceSerialOutcome

    data class Unavailable(val reason: DeviceSerialFailure) : DeviceSerialOutcome
}

/**
 * Supplies the device serial used as the CIAM `device_id` claim.
 *
 * A caller must fail closed on [DeviceSerialOutcome.Unavailable]: no device proof, no CIAM call, no
 * fallback to VIN / client id / random UUID / a secure settings identifier.
 */
fun interface DeviceSerialProvider {
    fun serial(): DeviceSerialOutcome
}
