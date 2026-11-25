package net.hwyz.iov.vehicle.ivi.acc.demo

import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationError
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState

/**
 * What the「登录待完成」area shows (DSN-CR-007 D-002/D-005).
 *
 * The guide state `LOGIN_PENDING` used to render one static hint plus a retry button no matter what
 * the login dimension was doing, so an automatic login in flight looked exactly like a login that
 * had already failed. This type keeps the mapping pure and testable: [retryVisible] is false while a
 * login is in flight (a second submission is meaningless), and [reasonRes] explains a failure in the
 * user's words without leaking a business code.
 */
data class LoginPendingPresentation(
    /** Main hint line. */
    val hintRes: Int,
    /** Failure reason line, or null when the login has not failed. */
    val reasonRes: Int?,
    /** Whether「重新登录」may be offered. */
    val retryVisible: Boolean,
)

object LoginPendingHint {

    /** Operations whose failure is explained inside the login area (never duplicated in the error bar). */
    private val INLINE_OPERATIONS = setOf(
        ActivationOperation.ISSUE_LOGIN_TICKET,
        ActivationOperation.LOGIN,
    )

    fun of(
        login: LoginState,
        error: ActivationError?,
        deviceRetryable: Boolean,
    ): LoginPendingPresentation = when (login) {
        LoginState.IN_PROGRESS -> LoginPendingPresentation(
            hintRes = R.string.login_in_progress,
            reasonRes = null,
            retryVisible = false,
        )

        LoginState.RETRY_REQUIRED -> LoginPendingPresentation(
            hintRes = R.string.login_incomplete,
            reasonRes = reasonOf(error),
            retryVisible = deviceRetryable,
        )

        // SIGNED_OUT (first entry / after revocation) and the theoretical SIGNED_IN both fall back to
        // the neutral hint; only a real failure gets a reason line.
        LoginState.SIGNED_OUT, LoginState.SIGNED_IN -> LoginPendingPresentation(
            hintRes = R.string.login_hint,
            reasonRes = null,
            retryVisible = deviceRetryable,
        )
    }

    /** True when [error] belongs to the login area instead of the generic「需要处理」bar. */
    fun isInlineOperation(error: ActivationError?): Boolean =
        error != null && error.operation in INLINE_OPERATIONS

    private fun reasonOf(error: ActivationError?): Int? {
        if (error == null || !isInlineOperation(error)) return null
        return when (error.category) {
            ErrorCategory.AUTH_REJECTED -> R.string.login_reason_auth
            ErrorCategory.CONTRACT_REJECTED -> R.string.login_reason_contract
            ErrorCategory.EXPIRED -> R.string.login_reason_expired
            ErrorCategory.RETRYABLE, ErrorCategory.RESULT_UNKNOWN -> R.string.login_reason_retryable
        }
    }
}
