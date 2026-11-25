package net.hwyz.iov.vehicle.ivi.acc.demo

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationError
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** DSN-CR-007: 登录待完成态按 login 维度渲染，三态不得共用同一套文案与按钮。 */
class LoginPendingPresentationTest {

    private fun error(operation: ActivationOperation, category: ErrorCategory) =
        ActivationError(operation = operation, category = category)

    @Test
    @DisplayName("自动登录进行中：显示进行中文案并隐藏重新登录")
    fun inProgressHasNoRetryEntry() {
        val presentation = LoginPendingHint.of(
            login = LoginState.IN_PROGRESS,
            error = null,
            deviceRetryable = true,
        )
        assertEquals(R.string.login_in_progress, presentation.hintRes)
        assertNull(presentation.reasonRes)
        assertFalse(presentation.retryVisible)
    }

    @Test
    @DisplayName("登录失败：文案与原因按分类区分，重试按钮受设备状态约束")
    fun retryRequiredExplainsTheFailure() {
        val cases = mapOf(
            ErrorCategory.AUTH_REJECTED to R.string.login_reason_auth,
            ErrorCategory.CONTRACT_REJECTED to R.string.login_reason_contract,
            ErrorCategory.EXPIRED to R.string.login_reason_expired,
            ErrorCategory.RETRYABLE to R.string.login_reason_retryable,
            ErrorCategory.RESULT_UNKNOWN to R.string.login_reason_retryable,
        )
        cases.forEach { (category, expectedReason) ->
            val presentation = LoginPendingHint.of(
                login = LoginState.RETRY_REQUIRED,
                error = error(ActivationOperation.LOGIN, category),
                deviceRetryable = true,
            )
            assertEquals(R.string.login_incomplete, presentation.hintRes, "hint of $category")
            assertEquals(expectedReason, presentation.reasonRes, "reason of $category")
            assertTrue(presentation.retryVisible, "retry of $category")
        }

        // 设备身份不可修复时不得给「重新登录」死循环入口。
        val blocked = LoginPendingHint.of(
            login = LoginState.RETRY_REQUIRED,
            error = error(ActivationOperation.ISSUE_LOGIN_TICKET, ErrorCategory.AUTH_REJECTED),
            deviceRetryable = false,
        )
        assertFalse(blocked.retryVisible)
        assertEquals(R.string.login_reason_auth, blocked.reasonRes)
    }

    @Test
    @DisplayName("等待登录（未失败）：保留初始文案，不带原因行")
    fun signedOutKeepsNeutralHint() {
        val presentation = LoginPendingHint.of(
            login = LoginState.SIGNED_OUT,
            error = null,
            deviceRetryable = true,
        )
        assertEquals(R.string.login_hint, presentation.hintRes)
        assertNull(presentation.reasonRes)
        assertTrue(presentation.retryVisible)
    }

    @Test
    @DisplayName("只有登录类失败并入登录区，其他错误仍走通用错误条")
    fun onlyLoginOperationsAreInline() {
        assertTrue(LoginPendingHint.isInlineOperation(error(ActivationOperation.LOGIN, ErrorCategory.RETRYABLE)))
        assertTrue(
            LoginPendingHint.isInlineOperation(
                error(ActivationOperation.ISSUE_LOGIN_TICKET, ErrorCategory.CONTRACT_REJECTED),
            ),
        )
        assertFalse(
            LoginPendingHint.isInlineOperation(error(ActivationOperation.REPORT_RECORD, ErrorCategory.RETRYABLE)),
        )
        assertFalse(
            LoginPendingHint.isInlineOperation(error(ActivationOperation.QUERY_SESSION, ErrorCategory.EXPIRED)),
        )
        assertFalse(LoginPendingHint.isInlineOperation(null))
    }

    @Test
    @DisplayName("回执阻塞（REPORT_RECORD）不伪造登录失败原因")
    fun receiptBlockedKeepsGenericReason() {
        val presentation = LoginPendingHint.of(
            login = LoginState.RETRY_REQUIRED,
            error = error(ActivationOperation.REPORT_RECORD, ErrorCategory.RETRYABLE),
            deviceRetryable = true,
        )
        assertEquals(R.string.login_incomplete, presentation.hintRes)
        assertNull(presentation.reasonRes)
    }
}
