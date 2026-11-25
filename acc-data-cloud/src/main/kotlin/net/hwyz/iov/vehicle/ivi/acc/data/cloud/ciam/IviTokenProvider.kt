package net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AccountLoginGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenRef
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenSource
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenInvalidationReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvent
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvents
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.log.sanitizeDiagnosticMessage
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginTicket
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.store.SecureMaterialStore

/** Timings of the vehicle token lifecycle (DSN-CR-002 D-008, DSN-CR-003 D-005). */
data class IviTokenConfig(
    /** User access token TTL is 60 minutes; renew silently inside this window. */
    val userRenewWindowMillis: Long = 10 * 60_000L,
    /** Sealed store key of the device bound refresh token. */
    val refreshKey: String = "ivi.refresh",
)

/**
 * Owns the vehicle user credentials: the user access token and the device bound refresh token.
 *
 * Rules implemented here (DSN-CR-003 D-005, DSN-CR-004 D-008):
 * - the device token is not managed here any more: it comes from [DeviceTokenSource], which in the
 *   target architecture is the unified car security service;
 * - the access token lives in memory only, the refresh token lives in the sealed platform store and
 *   never reaches logs;
 * - renewal is single flight (one request in flight), failures are classified;
 * - sign out clears local credentials only and never calls an unbind endpoint.
 */
class IviTokenProvider(
    private val authClient: CiamAuthClient,
    private val deviceTokens: DeviceTokenSource,
    private val secureStore: SecureMaterialStore,
    private val config: IviTokenConfig = IviTokenConfig(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val logger: DiagnosticsLogger? = null,
) : TokenProvider, TokenLifecycleEvents {

    private data class Token(val value: String, val expiresAtEpochMillis: Long)

    private val mutex = Mutex()
    private var accessToken: Token? = null
    private var accountId: String? = null

    /**
     * Hot, non blocking lifecycle stream (D-016).
     *
     * `replay = 0` and a small extra buffer: the owner must never block on a subscriber and a stale
     * event must never be replayed to a new flow.
     */
    private val lifecycle =
        MutableSharedFlow<TokenLifecycleEvent>(replay = 0, extraBufferCapacity = LIFECYCLE_BUFFER)

    override fun events(): Flow<TokenLifecycleEvent> = lifecycle.asSharedFlow()

    private fun emit(event: TokenLifecycleEvent) {
        lifecycle.tryEmit(event)
        logger?.event("token.lifecycle", "event" to event::class.simpleName.orEmpty())
    }

    /** Latest failure of a token interaction, for diagnostics and retry decisions. */
    var lastFailure: ErrorCategory? = null
        private set

    /** Human readable cause of the latest failure: an IAM business code or a local reason. */
    var lastFailureReason: String? = null
        private set

    override suspend fun authorizationToken(): TokenOutcome {
        accessToken?.let { token ->
            if (token.expiresAtEpochMillis - nowEpochMillis() > config.userRenewWindowMillis) {
                return TokenOutcome.Available(token.value)
            }
        }
        if (sealedRefreshToken() != null && renewUserSession()) {
            accessToken?.let { return TokenOutcome.Available(it.value) }
        }
        val device = requestDeviceToken()
            ?: return TokenOutcome.Unavailable(
                lastFailure ?: ErrorCategory.AUTH_REJECTED,
                lastFailureReason,
            )
        return TokenOutcome.Available(device.accessToken)
    }

    /** Current account when a user session is present. */
    fun signedInAccount(): AccountRef? = accountId?.let { AccountRef(it) }

    /**
     * Exchanges a one-time activation login ticket for a user token pair.
     *
     * A consumed or expired ticket is never retried with the same value: the caller requests a new
     * ticket from the cloud instead.
     */
    suspend fun loginWithTicket(ticket: String): LoginOutcome = mutex.withLock {
        val device = requestDeviceToken() ?: return LoginOutcome.Failed(
            lastFailure ?: ErrorCategory.AUTH_REJECTED,
        )
        when (val outcome = authClient.exchangeBindingTicket(ticket, device.accessToken)) {
            is CiamTokenOutcome.Failed -> {
                fail(
                    category = outcome.category,
                    reason = REASON_BINDING_TICKET,
                    code = outcome.code,
                    message = outcome.message,
                )
                LoginOutcome.Failed(outcome.category)
            }

            is CiamTokenOutcome.Issued -> {
                val account = accountIdOf(outcome.accessToken)
                if (account == null) {
                    fail(
                        category = ErrorCategory.CONTRACT_REJECTED,
                        reason = REASON_BINDING_TICKET,
                        message = "account-id-missing",
                        detail = "account-id-missing",
                    )
                    return LoginOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
                }
                applyUserTokens(outcome)
                accountId = account
                lastFailure = null
                LoginOutcome.Success(AccountRef(account))
            }
        }
    }

    /** Clears local credentials; the cloud binding is untouched. */
    suspend fun signOut() {
        mutex.withLock {
            accessToken = null
            accountId = null
            secureStore.clear(config.refreshKey)
        }
        emit(TokenLifecycleEvent.SignedOut(TokenInvalidationReason.LOCAL_SIGNOUT))
    }

    /** Drops the cached device token, used when the gateway rejects it (401/403). */
    suspend fun invalidateDeviceToken() {
        deviceTokens.invalidate()
    }

    /**
     * Asks the device identity source for a token and keeps the failure classification in step with
     * the diagnostic fields, so callers can retry or stop with the right reason.
     */
    private suspend fun requestDeviceToken(): DeviceTokenRef? {
        return when (val outcome = deviceTokens.deviceToken()) {
            is DeviceTokenOutcome.Available -> {
                lastFailure = null
                lastFailureReason = null
                outcome.token
            }

            is DeviceTokenOutcome.Unavailable -> {
                fail(
                    category = outcome.category,
                    reason = REASON_DEVICE_TOKEN,
                    message = outcome.reason,
                    detail = outcome.reason ?: "device-token-unavailable",
                )
                null
            }
        }
    }

    private suspend fun renewUserSession(): Boolean {
        val refresh = sealedRefreshToken() ?: return false
        return mutex.withLock {
            val device = requestDeviceToken() ?: return false
            when (val outcome = authClient.refresh(refresh, device.accessToken)) {
                is CiamTokenOutcome.Failed -> {
                    fail(
                        category = outcome.category,
                        reason = REASON_REFRESH,
                        code = outcome.code,
                        message = outcome.message,
                    )
                    if (outcome.category == ErrorCategory.AUTH_REJECTED ||
                        outcome.category == ErrorCategory.EXPIRED ||
                        outcome.category == ErrorCategory.CONTRACT_REJECTED
                    ) {
                        // The token family is no longer valid: drop local credentials.
                        accessToken = null
                        accountId = null
                        secureStore.clear(config.refreshKey)
                        emit(
                            TokenLifecycleEvent.CredentialsInvalidated(
                                outcome.category.toInvalidationReason(),
                            ),
                        )
                    } else {
                        // Credentials are kept: the flow only shows a retry hint.
                        emit(TokenLifecycleEvent.RenewalFailed(outcome.category))
                    }
                    false
                }

                is CiamTokenOutcome.Issued -> {
                    applyUserTokens(outcome)
                    accountId = accountId ?: accountIdOf(outcome.accessToken)
                    lastFailure = null
                    true
                }
            }
        }
    }

    private suspend fun applyUserTokens(outcome: CiamTokenOutcome.Issued) {
        accessToken = Token(
            value = outcome.accessToken,
            expiresAtEpochMillis = nowEpochMillis() + outcome.expiresInSeconds * 1_000L,
        )
        outcome.refreshToken?.let { refresh ->
            // Refresh TTL is decided by the IAM; keep a local upper bound of 30 days.
            secureStore.putSealed(
                config.refreshKey,
                refresh,
                nowEpochMillis() + DEFAULT_REFRESH_TTL_MILLIS,
            )
        }
    }

    /**
     * Records a token interaction failure (DSN-CR-007 D-007).
     *
     * [reason] is a stable enum-like label (`binding-ticket` / `refresh` / `device-token`), while
     * [code] carries the cloud business code and [message] its already public text (sanitised and
     * truncated before logging). Secrets never reach this method: no ticket, token or refresh value
     * is ever passed as [message].
     */
    private fun fail(
        category: ErrorCategory,
        reason: String,
        code: String? = null,
        message: String? = null,
        detail: String? = null,
    ) {
        lastFailure = category
        lastFailureReason = detail ?: code ?: reason
        val sanitized = sanitizeDiagnosticMessage(message)
        logger?.event(
            "token.failed",
            "category" to category.name,
            "reason" to reason,
            "code" to (code ?: "-"),
            "message" to (sanitized ?: "-"),
        )
    }

    private suspend fun sealedRefreshToken(): String? = secureStore.sealed(config.refreshKey)

    private fun ErrorCategory.toInvalidationReason(): TokenInvalidationReason = when (this) {
        ErrorCategory.EXPIRED -> TokenInvalidationReason.EXPIRED
        ErrorCategory.AUTH_REJECTED -> TokenInvalidationReason.REVOKED
        else -> TokenInvalidationReason.UNKNOWN
    }

    private companion object {
        const val REASON_BINDING_TICKET = "binding-ticket"
        const val REASON_REFRESH = "refresh"
        const val REASON_DEVICE_TOKEN = "device-token"
        const val DEFAULT_REFRESH_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
        const val LIFECYCLE_BUFFER = 8
    }
}

/**
 * Production login capability: exchanges the activation login ticket at SEC-CIAM.
 *
 * The gateway keeps no user token itself; it only drives [IviTokenProvider].
 */
class CiamAccountLoginGateway(
    private val tokens: IviTokenProvider,
) : AccountLoginGateway {

    override suspend fun login(ticket: LoginTicket): LoginOutcome {
        if (ticket.isExpired(System.currentTimeMillis())) {
            return LoginOutcome.Failed(ErrorCategory.EXPIRED)
        }
        return tokens.loginWithTicket(ticket.ticket)
    }

    override suspend fun signOutLocally() {
        tokens.signOut()
    }
}
