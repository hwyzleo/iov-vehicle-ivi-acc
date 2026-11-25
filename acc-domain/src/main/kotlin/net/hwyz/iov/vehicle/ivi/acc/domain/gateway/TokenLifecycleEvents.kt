package net.hwyz.iov.vehicle.ivi.acc.domain.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Token lifecycle events (IVI-ACC-DSN-CR-005 D-016).
 *
 * The token owner is the only component that knows a credential became unusable; it publishes that
 * fact here instead of leaving the flow in a stale signed-in state.
 */
enum class TokenInvalidationReason {
    REVOKED,
    DISABLED,
    UNBOUND,
    EXPIRED,
    LOCAL_SIGNOUT,
    UNKNOWN,
}

sealed interface TokenLifecycleEvent {
    /** The credential family was invalidated by the cloud (revoked, disabled, unbound, expired). */
    data class CredentialsInvalidated(val reason: TokenInvalidationReason) : TokenLifecycleEvent

    /** The user signed out locally (or the local store was cleared). */
    data class SignedOut(val reason: TokenInvalidationReason) : TokenLifecycleEvent

    /** Renewal failed but the credentials were kept: retry later, do not sign out. */
    data class RenewalFailed(val category: ErrorCategory) : TokenLifecycleEvent
}

interface TokenLifecycleEvents {
    fun events(): Flow<TokenLifecycleEvent>

    companion object {
        /** Used by tests and by assemblies without a real token owner. */
        val None: TokenLifecycleEvents = object : TokenLifecycleEvents {
            override fun events(): Flow<TokenLifecycleEvent> = emptyFlow()
        }
    }
}
