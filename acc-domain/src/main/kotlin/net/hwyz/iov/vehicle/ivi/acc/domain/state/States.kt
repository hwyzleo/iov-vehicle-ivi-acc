package net.hwyz.iov.vehicle.ivi.acc.domain.state

/**
 * UI facing guide state. It describes the current screen/action only; it is not the cloud binding
 * fact and must never be derived from order state.
 */
enum class GuideState {
    AGREEMENT_REQUIRED,
    SESSION_PREPARING,
    QR_VISIBLE,
    LOGIN_PENDING,
    PRIVACY_PENDING,
    ENTERED_LOCAL,
    ENTERED_ACCOUNT,
    RECOVERABLE_ERROR,
}

/** Local login dimension. SIGNED_IN is only set by a real login success. */
enum class LoginState {
    SIGNED_OUT,
    IN_PROGRESS,
    SIGNED_IN,
    RETRY_REQUIRED,
}

/** Agreement consent sync dimension, bound to the displayed document version. */
enum class AgreementSyncState {
    NOT_ACCEPTED,
    PENDING,
    ACKNOWLEDGED,
}

/** Privacy decision sync dimension; separate from the user selection itself. */
enum class PrivacySyncState {
    NOT_SYNCED,
    PENDING,
    ACKNOWLEDGED,
}

/** Privacy selection dimension. Closing the dialog maps to NOT_GRANTED. */
enum class PrivacyChoice {
    NOT_DECIDED,
    GRANTED,
    NOT_GRANTED,
}

/** Skip submission progress; a timeout keeps RESULT_UNKNOWN instead of claiming success. */
enum class SkipState {
    IDLE,
    SUBMITTING,
    RESULT_UNKNOWN,
    ACKNOWLEDGED,
}

/**
 * Car side outcome classification. These are local categories, not cloud numeric error codes;
 * the cloud contract is still unconfirmed.
 */
enum class ErrorCategory {
    RETRYABLE,
    EXPIRED,
    AUTH_REJECTED,
    CONTRACT_REJECTED,
    RESULT_UNKNOWN,
}

val ErrorCategory.isRetryable: Boolean
    get() = this == ErrorCategory.RETRYABLE || this == ErrorCategory.RESULT_UNKNOWN

/** Which operation produced the current recoverable problem. */
enum class ActivationOperation {
    LOAD_AGREEMENTS,
    REQUEST_SESSION,
    QUERY_SESSION,
    SUBMIT_SKIP,
    ISSUE_LOGIN_TICKET,
    LOGIN,
    REPORT_RECORD,
    PRIVACY_PERSIST,
    PRIVACY_PROMPT,
}

data class ActivationError(
    val operation: ActivationOperation,
    val category: ErrorCategory,
) {
    val retryable: Boolean get() = category.isRetryable
}
