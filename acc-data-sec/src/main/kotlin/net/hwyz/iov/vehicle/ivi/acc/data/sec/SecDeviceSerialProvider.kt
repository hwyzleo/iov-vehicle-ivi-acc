package net.hwyz.iov.vehicle.ivi.acc.data.sec

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatusQuery
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialFailure
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOrigin
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialProvider

/**
 * `identitySec` device identity source (D-011 / Q-005): the identifier handed out by the unified
 * security service, never a platform property.
 *
 * The port stays synchronous by design (D-011 keeps it a plain `fun interface`), so it reads the
 * last status the assembly pulled from the service; [SecDeviceStatusCache] owns that refresh.
 */
class SecDeviceSerialProvider(
    private val cachedStatus: () -> DeviceSecurityStatus?,
) : DeviceSerialProvider {

    override fun serial(): DeviceSerialOutcome {
        val reported = cachedStatus()
            ?: return DeviceSerialOutcome.Unavailable(DeviceSerialFailure.SERVICE_UNAVAILABLE)
        reported.deviceId?.takeIf { it.isNotBlank() }?.let {
            return DeviceSerialOutcome.Available(it, DeviceSerialOrigin.SEC_UNIFIED_SERVICE)
        }
        return DeviceSerialOutcome.Unavailable(
            when (reported.state) {
                DeviceSecurityState.UNKNOWN -> DeviceSerialFailure.SERVICE_UNAVAILABLE
                DeviceSecurityState.BLOCKED -> DeviceSerialFailure.SERVICE_UNAVAILABLE
                else -> DeviceSerialFailure.NOT_AVAILABLE
            },
        )
    }
}

/**
 * Holds the latest status reported by the unified service so both the UI and [SecDeviceSerialProvider]
 * read the same snapshot instead of issuing binder calls on every render (D-010 状态驱动界面).
 */
class SecDeviceStatusCache(
    private val query: DeviceSecurityStatusQuery,
) {
    @Volatile
    private var latest: DeviceSecurityStatus? = null

    fun status(): DeviceSecurityStatus? = latest

    suspend fun refresh(): DeviceSecurityStatus = query.status().also { latest = it }
}
