package net.hwyz.iov.vehicle.ivi.acc.domain.event

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogUnavailableReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatBlockReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenInvalidationReason
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptanceSnapshot
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementDocument
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState

/** Everything the reducer needs to restore a flow after restart. */
data class RestoredFlow(
    val flowGeneration: Long,
    val guide: GuideState,
    val sessionId: String?,
    val sessionExpiresAtEpochMillis: Long?,
    val bindingOperationId: String?,
    val bindingConfirmed: Boolean,
    val login: LoginState,
    val accountId: String?,
    val acceptances: List<AgreementAcceptanceSnapshot>,
    val agreementSync: AgreementSyncState,
    val privacyChoice: PrivacyChoice,
    val privacySync: PrivacySyncState,
    val privacyFirstInteractionCompleted: Boolean,
)

/** Inputs to the pure activation reducer. */
sealed interface ActivationEvent {
    data class FlowStarted(val restored: RestoredFlow?) : ActivationEvent

    data object DocumentsLoadStarted : ActivationEvent

    data class DocumentsLoaded(val documents: List<AgreementDocument>) : ActivationEvent

    data class DocumentsLoadFailed(val category: ErrorCategory) : ActivationEvent

    data class AgreementAccepted(
        val type: AgreementType,
        val version: String,
        val revisionId: String?,
        val contentHash: String?,
        val language: String?,
        val agreedAtEpochMillis: Long,
    ) : ActivationEvent

    /**
     * A checkbox was toggled before submission (DSN-CR-006). Reversible: it must never create an
     * acceptance, a record or a cloud request.
     */
    data class AgreementSelected(
        val type: AgreementType,
        val selected: Boolean,
    ) : ActivationEvent

    data class SessionPreparationStarted(val generation: Long) : ActivationEvent

    data class SessionCreated(val generation: Long, val session: SessionRef) : ActivationEvent

    data class SessionRequestFailed(val generation: Long, val category: ErrorCategory) : ActivationEvent

    data class SessionObserved(
        val generation: Long,
        val sessionId: String,
        val observation: SessionObservation,
    ) : ActivationEvent

    data object SkipRequested : ActivationEvent

    data class SkipAcknowledged(val generation: Long, val sessionId: String?) : ActivationEvent

    data class SkipFailed(
        val generation: Long,
        val sessionId: String?,
        val category: ErrorCategory,
    ) : ActivationEvent

    data object AccountContinuationAccepted : ActivationEvent

    data class LoginStarted(val generation: Long) : ActivationEvent

    data class LoginSucceeded(
        val generation: Long,
        val account: AccountRef,
        val firstInteractionCompleted: Boolean,
    ) : ActivationEvent

    data class LoginFailed(val generation: Long, val category: ErrorCategory) : ActivationEvent

    data class PrivacyDecisionCommitted(
        val choice: PrivacyChoice,
        val firstInteractionCompleted: Boolean,
    ) : ActivationEvent

    /** Catalogue loading started (D-013): LOADING is a state dimension, never a GuideState value. */
    data object PrivacyCatalogLoading : ActivationEvent

    /** Catalogue loaded; [items] may be empty, which the reducer maps to CATALOG_EMPTY. */
    data class PrivacyCatalogLoaded(
        val catalogVersion: String,
        val items: List<PrivacyAuthorizationItem>,
    ) : ActivationEvent

    /**
     * Catalogue cannot be used (failed / empty / feature disabled): only the report is blocked, the
     * user still enters the entertainment system (BLK-3 / Q-001).
     */
    data class PrivacyCatalogUnavailable(
        val reason: PrivacyCatalogUnavailableReason,
    ) : ActivationEvent

    /** Credentials were invalidated or the user signed out (D-016). */
    data class CredentialsRevoked(val reason: TokenInvalidationReason) : ActivationEvent

    /** Token renewal failed but credentials were kept: only a retry hint (D-016). */
    data class TokenRenewalFailed(val category: ErrorCategory) : ActivationEvent

    /** Seat gate re-evaluated; null means the screen may drive the flow (D-017). */
    data class SeatGateEvaluated(val reason: SeatBlockReason?) : ActivationEvent

    /** A front seat only action was attempted on a rear seat screen. */
    data class SeatBlocked(val reason: SeatBlockReason) : ActivationEvent

    /**
     * A receipt cannot be sent as is (e.g. SUCCEEDED without `bindingOperationId`): the cloud would
     * reject it, so the car blocks locally and asks for a re-query (D-005 / BLK-11).
     */
    data class ReceiptBlocked(val category: ErrorCategory) : ActivationEvent

    data object PrivacyDecisionPersistFailed : ActivationEvent

    data class PrivacyPromptRequiresFrontSeat(val required: Boolean) : ActivationEvent

    data class PrivacySyncUpdated(val sync: PrivacySyncState) : ActivationEvent

    data class AgreementSyncUpdated(val sync: AgreementSyncState) : ActivationEvent

    data class ForegroundChanged(val foreground: Boolean) : ActivationEvent

    data class ErrorRaised(val operation: ActivationOperation, val category: ErrorCategory) : ActivationEvent

    data object ErrorCleared : ActivationEvent
}
