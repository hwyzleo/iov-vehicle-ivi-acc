package net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam

import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/** Result of asking for the IAM authorization token used on gateway calls. */
sealed interface TokenOutcome {
    data class Available(val token: String) : TokenOutcome

    /**
     * No usable token. [reason] is for diagnostics only: it carries the IAM business code or the
     * local cause and never contains a credential.
     */
    data class Unavailable(
        val category: ErrorCategory,
        val reason: String? = null,
    ) : TokenOutcome
}

/**
 * Supplies the IAM authorization token used for gateway calls (DSN-CR-002 D-003 / D-008).
 *
 * The vehicle side never invents credentials: it presents the CIAM device token before login and
 * the user access token afterwards, and both are produced by [IviTokenProvider]. A missing token is
 * reported as [TokenOutcome.Unavailable] so the caller can surface the real cause (authentication
 * versus transport) instead of pretending the requested resource is missing.
 */
fun interface TokenProvider {
    suspend fun authorizationToken(): TokenOutcome

    companion object {
        /** Used by tests and by scripted demos that do not run the IAM handshake. */
        val None: TokenProvider = TokenProvider {
            TokenOutcome.Unavailable(ErrorCategory.AUTH_REJECTED, "no-token-provider")
        }

        fun fixed(token: String): TokenProvider = TokenProvider { TokenOutcome.Available(token) }
    }
}
