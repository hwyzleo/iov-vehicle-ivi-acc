package net.hwyz.iov.vehicle.ivi.acc.data.sec

import kotlinx.coroutines.CancellationException
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatusQuery

/**
 * Reads the device security state from the unified service (DSN-CR-004 D-010).
 *
 * A caller uses this to tell "device not provisioned" apart from "the gateway rejected me": the
 * first must not offer a retry, the second may. Any failure degrades to [DeviceSecurityState.UNKNOWN]
 * so the UI never claims a state the service did not report.
 *
 * [DeviceSecurityStatus.deviceId] is filled from the service, which is the only authoritative
 * `identitySec` device identity source (D-011 / `SEC_UNIFIED_SERVICE`).
 */
class SecDeviceSecurityStatusQuery(
    private val client: SecStatusClient,
) : DeviceSecurityStatusQuery {

    override suspend fun status(): DeviceSecurityStatus {
        // The state is the authoritative answer: a decorative field must never turn an ACTIVE device
        // into "unavailable". deviceId / certificate are therefore read best effort.
        val state = try {
            SecErrorMapper.stateOf(client.state())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecRemoteException) {
            return DeviceSecurityStatus(
                state = DeviceSecurityState.UNKNOWN,
                reasonCode = failure.code ?: UNKNOWN_REASON,
            )
        } catch (error: Exception) {
            return DeviceSecurityStatus(state = DeviceSecurityState.UNKNOWN, reasonCode = UNKNOWN_REASON)
        }
        val deviceId = try {
            client.deviceId()?.takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }
        val certNotAfter = try {
            client.certificateNotAfterEpochMillis()?.takeIf { it > 0L }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }
        return DeviceSecurityStatus(
            state = state,
            deviceId = deviceId,
            certNotAfterEpochMillis = certNotAfter,
            reasonCode = 0,
        )
    }

    private companion object {
        const val UNKNOWN_REASON = -1
    }
}
