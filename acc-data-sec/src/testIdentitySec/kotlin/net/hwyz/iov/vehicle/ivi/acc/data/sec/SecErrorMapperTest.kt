package net.hwyz.iov.vehicle.ivi.acc.data.sec

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.sec.api.SecError
import net.hwyz.iov.vehicle.ivi.sec.api.SecState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Contract mapping tests: the service codes must keep their meaning on the IVI-ACC side. */
class SecErrorMapperTest {

    @Test
    @DisplayName("统一服务错误码逐项映射到车端分类")
    fun everyServiceCodeMapsToACategory() {
        val expected = mapOf(
            SecError.NOT_PROVISIONED to ErrorCategory.AUTH_REJECTED,
            SecError.CALLER_REJECTED to ErrorCategory.CONTRACT_REJECTED,
            SecError.INVALID_ARGUMENT to ErrorCategory.CONTRACT_REJECTED,
            SecError.KEY_UNAVAILABLE to ErrorCategory.AUTH_REJECTED,
            SecError.CERT_INVALID to ErrorCategory.AUTH_REJECTED,
            SecError.CERT_EXPIRED to ErrorCategory.AUTH_REJECTED,
            SecError.TOKEN_UNAVAILABLE to ErrorCategory.CONTRACT_REJECTED,
            SecError.RATE_LIMITED to ErrorCategory.RETRYABLE,
            SecError.DEPENDENCY_UNAVAILABLE to ErrorCategory.RETRYABLE,
            SecError.DEVICE_BLOCKED to ErrorCategory.AUTH_REJECTED,
            SecError.SESSION_CONFLICT to ErrorCategory.RETRYABLE,
        )
        expected.forEach { (code, category) ->
            assertEquals(category, SecErrorMapper.categoryOfCode(code), "SecError=$code")
        }
        // An unpublished code is a contract violation, never a retryable network problem.
        assertEquals(ErrorCategory.CONTRACT_REJECTED, SecErrorMapper.categoryOfCode(99))
    }

    @Test
    @DisplayName("无服务错误码时按连接失败类型分类")
    fun binderFailuresAreClassifiedByKind() {
        assertEquals(
            ErrorCategory.CONTRACT_REJECTED,
            SecErrorMapper.categoryOfKind(SecFailureKind.SERVICE_MISSING),
        )
        assertEquals(
            ErrorCategory.CONTRACT_REJECTED,
            SecErrorMapper.categoryOfKind(SecFailureKind.CALLER_REJECTED),
        )
        assertEquals(ErrorCategory.RETRYABLE, SecErrorMapper.categoryOfKind(SecFailureKind.PROCESS_DIED))
        assertEquals(ErrorCategory.RETRYABLE, SecErrorMapper.categoryOfKind(SecFailureKind.TIMEOUT))
        assertEquals(ErrorCategory.RETRYABLE, SecErrorMapper.categoryOfKind(SecFailureKind.UNKNOWN))
    }

    @Test
    @DisplayName("带码的失败优先按码分类")
    fun publishedCodeWinsOverKind() {
        val failure = SecRemoteException(SecFailureKind.UNKNOWN, SecError.NOT_PROVISIONED)
        assertEquals(ErrorCategory.AUTH_REJECTED, SecErrorMapper.categoryOf(failure))
    }

    @Test
    @DisplayName("安全状态码映射完整")
    fun everyStateMaps() {
        assertEquals(DeviceSecurityState.NOT_PROVISIONED, SecErrorMapper.stateOf(SecState.NOT_PROVISIONED))
        assertEquals(DeviceSecurityState.PROVISIONING, SecErrorMapper.stateOf(SecState.PROVISIONING))
        assertEquals(
            DeviceSecurityState.ISSUED_PENDING_CLOUD,
            SecErrorMapper.stateOf(SecState.ISSUED_PENDING_CLOUD),
        )
        assertEquals(DeviceSecurityState.ACTIVE, SecErrorMapper.stateOf(SecState.ACTIVE))
        assertEquals(DeviceSecurityState.RENEW_DUE, SecErrorMapper.stateOf(SecState.RENEW_DUE))
        assertEquals(DeviceSecurityState.BLOCKED, SecErrorMapper.stateOf(SecState.BLOCKED))
        assertEquals(DeviceSecurityState.ERROR, SecErrorMapper.stateOf(SecState.ERROR))
        assertEquals(DeviceSecurityState.UNKNOWN, SecErrorMapper.stateOf(42))
    }

    @Test
    @DisplayName("只有已开通或待确认的状态才提供重试入口")
    fun retryAffordanceFollowsState() {
        assertTrue(SecErrorMapper.isRetryableState(DeviceSecurityState.ACTIVE))
        assertTrue(SecErrorMapper.isRetryableState(DeviceSecurityState.RENEW_DUE))
        assertTrue(SecErrorMapper.isRetryableState(DeviceSecurityState.ISSUED_PENDING_CLOUD))
        assertFalse(SecErrorMapper.isRetryableState(DeviceSecurityState.NOT_PROVISIONED))
        assertFalse(SecErrorMapper.isRetryableState(DeviceSecurityState.BLOCKED))
        assertFalse(SecErrorMapper.isRetryableState(DeviceSecurityState.UNKNOWN))
    }
}
