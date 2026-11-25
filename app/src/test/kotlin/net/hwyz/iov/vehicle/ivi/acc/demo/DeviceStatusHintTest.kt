package net.hwyz.iov.vehicle.ivi.acc.demo

import kotlin.test.assertEquals
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** D-010 / CR-004 §3: 错误码到提示与重试策略的映射不得退化为「一律重新认证」。 */
class DeviceStatusHintTest {

    private fun status(state: DeviceSecurityState, reason: Int = 0) =
        DeviceSecurityStatus(state = state, reasonCode = reason)

    @Test
    @DisplayName("已开通状态可重试")
    fun activeIsRetryable() {
        assertEquals(DeviceStatusHint.RETRYABLE, deviceStatusHint(status(DeviceSecurityState.ACTIVE)))
        assertEquals(
            DeviceStatusHint.RETRYABLE,
            deviceStatusHint(status(DeviceSecurityState.RENEW_DUE)),
        )
    }

    @Test
    @DisplayName("未开通/密钥不可用/证书问题给出确定性提示，不给重试入口")
    fun unprovisionedStatesAreDeterministic() {
        assertEquals(
            DeviceStatusHint.NOT_PROVISIONED,
            deviceStatusHint(status(DeviceSecurityState.NOT_PROVISIONED)),
        )
        // 服务绑定成功但无密钥（KEY_UNAVAILABLE=4）时仍要提示「未开通」，而不是「服务不可用」。
        assertEquals(
            DeviceStatusHint.NOT_PROVISIONED,
            deviceStatusHint(status(DeviceSecurityState.UNKNOWN, 4)),
        )
        assertEquals(
            DeviceStatusHint.CERT_REQUIRED,
            deviceStatusHint(status(DeviceSecurityState.UNKNOWN, 6)),
        )
        assertEquals(
            DeviceStatusHint.BLOCKED,
            deviceStatusHint(status(DeviceSecurityState.BLOCKED)),
        )
    }

    @Test
    @DisplayName("调用方被拒（签名/白名单）单独提示")
    fun callerRejectedIsDistinct() {
        assertEquals(
            DeviceStatusHint.CALLER_REJECTED,
            deviceStatusHint(status(DeviceSecurityState.UNKNOWN, 2)),
        )
    }

    @Test
    @DisplayName("令牌不可用与服务不可用可区分")
    fun tokenUnavailableIsDistinct() {
        assertEquals(
            DeviceStatusHint.TOKEN_UNAVAILABLE,
            deviceStatusHint(status(DeviceSecurityState.UNKNOWN, 7)),
        )
        assertEquals(
            DeviceStatusHint.SERVICE_UNAVAILABLE,
            deviceStatusHint(status(DeviceSecurityState.UNKNOWN, -1)),
        )
    }
}
