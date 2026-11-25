package net.hwyz.iov.vehicle.ivi.acc.platform

import android.os.Build
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialFailure
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOrigin
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialProvider
import java.lang.reflect.Method

/**
 * Device serial from device level read only properties (D-011, `identityLocal` flavor).
 *
 * Order: `ro.serialno` system property -> `Build.getSerial()` -> legacy `Build.SERIAL`.
 *
 * The Android secure settings device identifier is deliberately **absent** (BLK-4): it is resettable,
 * it collides across devices of the same model and it is not a factory identity. A-017 enforces the
 * absence mechanically. Anything unreadable yields [DeviceSerialOutcome.Unavailable], never a
 * substitute.
 *
 * The value is never logged.
 */
class AndroidDeviceSerialProvider(
    private val systemProperty: (String) -> String? = ::readSystemProperty,
    private val apiSerial: () -> String? = { runCatching { Build.getSerial() }.getOrNull() },
    private val legacySerial: () -> String? = {
        @Suppress("DEPRECATION")
        Build.SERIAL
    },
) : DeviceSerialProvider {

    override fun serial(): DeviceSerialOutcome {
        systemProperty(PROP_SERIALNO)?.takeIf(::usable)?.let {
            return DeviceSerialOutcome.Available(it, DeviceSerialOrigin.RO_SERIALNO)
        }
        val api = runCatching { apiSerial() }.getOrNull()
        if (usable(api)) return DeviceSerialOutcome.Available(api!!, DeviceSerialOrigin.BUILD_SERIAL)
        val legacy = runCatching { legacySerial() }.getOrNull()
        if (usable(legacy)) return DeviceSerialOutcome.Available(legacy!!, DeviceSerialOrigin.LEGACY_BUILD_SERIAL)
        // A permission problem and "no property at all" are both a hard stop: fail closed.
        return DeviceSerialOutcome.Unavailable(
            if (api == UNKNOWN_VALUE || legacy == UNKNOWN_VALUE) {
                DeviceSerialFailure.INVALID_VALUE
            } else {
                DeviceSerialFailure.NOT_AVAILABLE
            },
        )
    }

    private fun usable(value: String?): Boolean =
        !value.isNullOrBlank() && value != UNKNOWN_VALUE && value != "0"

    private companion object {
        const val PROP_SERIALNO = "ro.serialno"

        /** Same literal as `android.os.Build.UNKNOWN`, kept local so the reader is unit testable. */
        const val UNKNOWN_VALUE = "unknown"

        @Suppress("PrivateApi")
        fun readSystemProperty(name: String): String? = runCatching {
            val clazz: Class<*> = Class.forName("android.os.SystemProperties")
            val get: Method = clazz.getMethod("get", String::class.java)
            (get.invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
