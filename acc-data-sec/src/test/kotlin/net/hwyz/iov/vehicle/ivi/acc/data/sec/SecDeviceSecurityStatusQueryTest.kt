package net.hwyz.iov.vehicle.ivi.acc.data.sec

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Status lookup must degrade to UNKNOWN instead of claiming a state the service did not report. */
class SecDeviceSecurityStatusQueryTest {

    private fun query(
        state: Int,
        deviceId: String? = null,
        notAfter: Long? = null,
        failure: SecRemoteException? = null,
    ) = SecDeviceSecurityStatusQuery(
        object : SecStatusClient {
            override suspend fun state(): Int {
                failure?.let { throw it }
                return state
            }

            override suspend fun deviceId(): String? = deviceId

            override suspend fun certificateNotAfterEpochMillis(): Long? = notAfter
        },
    )

    @Test
    @DisplayName("已开通状态直接透出，并带出服务下发的设备标识")
    fun activeStateIsReported() = runBlocking {
        val status = query(
            SecStateCodes.ACTIVE,
            deviceId = "00000005AA00000001",
            notAfter = 1_800_000_000_000L,
        ).status()
        assertEquals(DeviceSecurityState.ACTIVE, status.state)
        assertEquals("00000005AA00000001", status.deviceId)
        assertEquals(1_800_000_000_000L, status.certNotAfterEpochMillis)
        assertEquals(0, status.reasonCode)
    }

    @Test
    @DisplayName("未开通与阻断状态可区分")
    fun blockingStatesAreDistinguishable() = runBlocking {
        assertEquals(
            DeviceSecurityState.NOT_PROVISIONED,
            query(SecStateCodes.NOT_PROVISIONED).status().state,
        )
        assertEquals(
            DeviceSecurityState.BLOCKED,
            query(SecStateCodes.BLOCKED).status().state,
        )
    }

    @Test
    @DisplayName("服务无设备标识时状态仍以服务为准，不得降级为 KEY_UNAVAILABLE")
    fun missingDeviceIdDoesNotMaskTheState() = runBlocking {
        val status = query(SecStateCodes.ACTIVE, deviceId = null).status()
        assertEquals(DeviceSecurityState.ACTIVE, status.state)
        assertNull(status.deviceId)
        assertEquals(0, status.reasonCode)
    }

    @Test
    @DisplayName("未下发标识时不为空串，保持 null")
    fun blankDeviceIdIsNormalised() = runBlocking {
        assertNull(query(SecStateCodes.NOT_PROVISIONED, deviceId = "").status().deviceId)
    }

    @Test
    @DisplayName("服务报错时降级为 UNKNOWN 并保留原因码")
    fun serviceFailureDegradesToUnknown() = runBlocking {
        val status = query(
            SecStateCodes.ACTIVE,
            failure = SecRemoteException(SecFailureKind.UNKNOWN, SecErrorCodes.CERT_EXPIRED),
        ).status()
        assertEquals(DeviceSecurityState.UNKNOWN, status.state)
        assertEquals(SecErrorCodes.CERT_EXPIRED, status.reasonCode)
    }

    @Test
    @DisplayName("连接失败同样降级为 UNKNOWN")
    fun binderFailureDegradesToUnknown() = runBlocking {
        val status = query(
            SecStateCodes.ACTIVE,
            failure = SecRemoteException(SecFailureKind.SERVICE_MISSING),
        ).status()
        assertEquals(DeviceSecurityState.UNKNOWN, status.state)
        assertEquals(-1, status.reasonCode)
    }

    @Test
    @DisplayName("服务下发的标识作为 identitySec 设备标识来源")
    fun secDeviceSerialComesFromTheService() = runBlocking {
        val cache = SecDeviceStatusCache(query(SecStateCodes.ACTIVE, deviceId = "SEC-42"))
        val serial = SecDeviceSerialProvider { cache.status() }

        // Before the first refresh there is no snapshot: fail closed, never a platform fallback.
        assertEquals(
            net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialFailure.SERVICE_UNAVAILABLE,
            (
                serial.serial()
                    as net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOutcome.Unavailable
                ).reason,
        )

        cache.refresh()
        val available = serial.serial()
                as net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOutcome.Available
        assertEquals("SEC-42", available.serial)
        assertEquals(
            net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOrigin.SEC_UNIFIED_SERVICE,
            available.origin,
        )
    }
}
