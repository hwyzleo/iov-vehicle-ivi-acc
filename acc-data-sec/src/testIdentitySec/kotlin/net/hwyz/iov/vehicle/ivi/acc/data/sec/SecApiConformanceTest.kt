package net.hwyz.iov.vehicle.ivi.acc.data.sec

import kotlin.test.assertEquals
import net.hwyz.iov.vehicle.ivi.sec.api.SecError
import net.hwyz.iov.vehicle.ivi.sec.api.SecState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Conformance guard for the code mirror (D-018 构建门禁 / CR-004 映射表).
 *
 * `identitySec` compiles against the published AIDL contract while the flavour agnostic code only
 * sees [SecErrorCodes]/[SecStateCodes]. If the service renumbers anything, this test fails instead of
 * silently mis-mapping a device state.
 */
class SecApiConformanceTest {

    private val errorMirror = mapOf(
        SecError.OK to SecErrorCodes.OK,
        SecError.NOT_PROVISIONED to SecErrorCodes.NOT_PROVISIONED,
        SecError.CALLER_REJECTED to SecErrorCodes.CALLER_REJECTED,
        SecError.INVALID_ARGUMENT to SecErrorCodes.INVALID_ARGUMENT,
        SecError.KEY_UNAVAILABLE to SecErrorCodes.KEY_UNAVAILABLE,
        SecError.CERT_INVALID to SecErrorCodes.CERT_INVALID,
        SecError.CERT_EXPIRED to SecErrorCodes.CERT_EXPIRED,
        SecError.TOKEN_UNAVAILABLE to SecErrorCodes.TOKEN_UNAVAILABLE,
        SecError.RATE_LIMITED to SecErrorCodes.RATE_LIMITED,
        SecError.DEPENDENCY_UNAVAILABLE to SecErrorCodes.DEPENDENCY_UNAVAILABLE,
        SecError.DEVICE_BLOCKED to SecErrorCodes.DEVICE_BLOCKED,
        SecError.SESSION_CONFLICT to SecErrorCodes.SESSION_CONFLICT,
    )

    private val stateMirror = mapOf(
        SecState.NOT_PROVISIONED to SecStateCodes.NOT_PROVISIONED,
        SecState.PROVISIONING to SecStateCodes.PROVISIONING,
        SecState.ISSUED_PENDING_CLOUD to SecStateCodes.ISSUED_PENDING_CLOUD,
        SecState.ACTIVE to SecStateCodes.ACTIVE,
        SecState.RENEW_DUE to SecStateCodes.RENEW_DUE,
        SecState.BLOCKED to SecStateCodes.BLOCKED,
        SecState.ERROR to SecStateCodes.ERROR,
    )

    @Test
    @DisplayName("错误码镜像与服务发布的常量逐项相等")
    fun errorCodesMatchThePublishedContract() {
        errorMirror.forEach { (published, mirror) ->
            assertEquals(published, mirror, "SecError mirror drifted: $published vs $mirror")
        }
    }

    @Test
    @DisplayName("状态码镜像与服务发布的常量逐项相等")
    fun stateCodesMatchThePublishedContract() {
        stateMirror.forEach { (published, mirror) ->
            assertEquals(published, mirror, "SecState mirror drifted: $published vs $mirror")
        }
    }

    @Test
    @DisplayName("线上异常消息里的码可被解析回同一个值")
    fun wireMessageIsParsedBackToTheSameCode() {
        errorMirror.keys.forEach { code ->
            assertEquals(code, SecErrorMapper.codeOfWireMessage(SecError.wireMessage(code)))
        }
    }
}
