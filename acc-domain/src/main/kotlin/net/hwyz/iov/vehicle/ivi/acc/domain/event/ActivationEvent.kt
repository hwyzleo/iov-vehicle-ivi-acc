package net.hwyz.iov.vehicle.ivi.acc.domain.event

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

    data object PrivacyDecisionPersistFailed : ActivationEvent

    data class PrivacyPromptRequiresFrontSeat(val required: Boolean) : ActivationEvent

    data class PrivacySyncUpdated(val sync: PrivacySyncState) : ActivationEvent

    data class AgreementSyncUpdated(val sync: AgreementSyncState) : ActivationEvent

    data class ForegroundChanged(val foreground: Boolean) : ActivationEvent

    data class ErrorRaised(val operation: ActivationOperation, val category: ErrorCategory) : ActivationEvent

    data object ErrorCleared : ActivationEvent
}
