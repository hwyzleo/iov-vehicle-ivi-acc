package net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AccountLoginGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginTicket
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.store.SecureMaterialStore

/** Timings of the vehicle token lifecycle (DSN-CR-002 D-008, DSN-CR-003 D-005). */
data class IviTokenConfig(
    /** Device token TTL is 15 minutes; re-prove when less than this remains. */
    val deviceRenewWindowMillis: Long = 5 * 60_000L,
    /** User access token TTL is 60 minutes; renew silently inside this window. */
    val userRenewWindowMillis: Long = 10 * 60_000L,
    /** Sealed store key of the device bound refresh token. */
    val refreshKey: String = "ivi.refresh",
)

/**
 * Owns the vehicle credentials: device token, user access token and the device bound refresh token.
 *
 * Rules implemented here (DSN-CR-003 D-005):
 * - the access token and the device token live in memory only;
 * - the refresh token lives in the sealed platform store and never reaches logs;
 * - renewal is single-flight (one request in flight), failures are classified;
 * - sign out clears local credentials only and never calls an unbind endpoint.
 */
class IviTokenProvider(
    private val authClient: CiamAuthClient,
    private val proofProvider: DeviceProofProvider,
    private val secureStore: SecureMaterialStore,
    private val config: IviTokenConfig = IviTokenConfig(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val logger: DiagnosticsLogger? = null,
) : TokenProvider {

    private data class Token(val value: String, val expiresAtEpochMillis: Long)

    private val mutex = Mutex()
    private var deviceToken: Token? = null
    private var accessToken: Token? = null
    private var accountId: String? = null

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
        val device = ensureDeviceToken()
            ?: return TokenOutcome.Unavailable(
                lastFailure ?: ErrorCategory.AUTH_REJECTED,
                lastFailureReason,
            )
        return TokenOutcome.Available(device.value)
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
        val device = deviceTokenLocked() ?: return LoginOutcome.Failed(
            lastFailure ?: ErrorCategory.AUTH_REJECTED,
        )
        when (val outcome = authClient.exchangeBindingTicket(ticket, device.value)) {
            is CiamTokenOutcome.Failed -> {
                fail(outcome.category, "binding-ticket:${outcome.code ?: "-"}")
                LoginOutcome.Failed(outcome.category)
            }

            is CiamTokenOutcome.Issued -> {
                val account = accountIdOf(outcome.accessToken)
                    ?: return LoginOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
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
    }

    /** Drops only the device token, used when the gateway rejects it (401/403). */
    suspend fun invalidateDeviceToken() {
        mutex.withLock { deviceToken = null }
    }

    private suspend fun ensureDeviceToken(): Token? = mutex.withLock { deviceTokenLocked() }

    private suspend fun deviceTokenLocked(): Token? {
        deviceToken?.let { token ->
            if (token.expiresAtEpochMillis - nowEpochMillis() > config.deviceRenewWindowMillis) {
                return token
            }
        }
        val proof = runCatching { proofProvider.deviceProof() }
            .getOrElse { error ->
                // Fail closed: no proof, no call. The cause is logged without any secret.
                fail(ErrorCategory.AUTH_REJECTED, "device-proof-unavailable:${error.javaClass.simpleName}")
                return null
            }
        return when (val outcome = authClient.requestDeviceToken(proof)) {
            is CiamTokenOutcome.Failed -> {
                fail(outcome.category, "device-identity:${outcome.code ?: "-"}")
                null
            }

            is CiamTokenOutcome.Issued -> Token(
                value = outcome.accessToken,
                expiresAtEpochMillis = nowEpochMillis() + outcome.expiresInSeconds * 1_000L,
            ).also {
                deviceToken = it
                lastFailure = null
                lastFailureReason = null
                logger?.event("token.device_issued", "expires_in" to outcome.expiresInSeconds.toString())
            }
        }
    }

    private suspend fun renewUserSession(): Boolean {
        val refresh = sealedRefreshToken() ?: return false
        return mutex.withLock {
            val device = deviceTokenLocked() ?: return false
            when (val outcome = authClient.refresh(refresh, device.value)) {
                is CiamTokenOutcome.Failed -> {
                    lastFailure = outcome.category
                    if (outcome.category == ErrorCategory.AUTH_REJECTED ||
                        outcome.category == ErrorCategory.EXPIRED ||
                        outcome.category == ErrorCategory.CONTRACT_REJECTED
                    ) {
                        // The token family is no longer valid: drop local credentials.
                        accessToken = null
                        accountId = null
                        secureStore.clear(config.refreshKey)
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

    private fun fail(category: ErrorCategory, reason: String) {
        lastFailure = category
        lastFailureReason = reason
        logger?.event("token.failed", "category" to category.name, "reason" to reason)
    }

    private suspend fun sealedRefreshToken(): String? = secureStore.sealed(config.refreshKey)

    private companion object {
        const val DEFAULT_REFRESH_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000
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
