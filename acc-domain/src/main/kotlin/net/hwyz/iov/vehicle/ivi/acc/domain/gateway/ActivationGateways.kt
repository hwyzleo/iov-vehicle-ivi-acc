package net.hwyz.iov.vehicle.ivi.acc.domain.gateway

import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementBundle
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginTicket
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice

/** Agreement source (cloud orchestrated through the gateway). */
interface AgreementGateway {
    suspend fun loadPublishedDocuments(): AgreementLoadOutcome
}

sealed interface AgreementLoadOutcome {
    data class Loaded(val bundle: AgreementBundle) : AgreementLoadOutcome

    data class Failed(val category: ErrorCategory) : AgreementLoadOutcome
}

/**
 * Candidate cloud interaction contract.
 *
 * Only semantics are fixed here. URL, transport fields, status codes and numeric error codes are
 * still unconfirmed, so implementations must not invent them outside a codec/transport boundary.
 */
interface ActivationCloudPort {
    suspend fun requestSession(input: SessionRequestInput): SessionRequestOutcome

    suspend fun querySession(input: SessionQueryInput): SessionQueryOutcome

    suspend fun submitSkip(input: SkipRequestInput): SkipSubmitOutcome

    suspend fun issueLoginTicket(input: LoginTicketRequestInput): LoginTicketOutcome

    suspend fun reportRecord(input: RecordReportInput): RecordReportOutcome
}

data class SessionRequestInput(
    val vehicle: VehicleRef,
    val agreements: List<AgreementReference>,
    val flowGeneration: Long,
    val requestId: String,
)

sealed interface SessionRequestOutcome {
    data class Created(val session: SessionRef) : SessionRequestOutcome

    data class Failed(val category: ErrorCategory) : SessionRequestOutcome
}

data class SessionQueryInput(
    val vehicle: VehicleRef,
    val session: SessionRef,
    val flowGeneration: Long,
)

sealed interface SessionQueryOutcome {
    data class Observed(val observation: SessionObservation) : SessionQueryOutcome

    data class Failed(val category: ErrorCategory) : SessionQueryOutcome
}

data class SkipRequestInput(
    val vehicle: VehicleRef,
    val requestId: String,
    val agreements: List<AgreementReference>,
    val sessionId: String?,
)

sealed interface SkipSubmitOutcome {
    data object Acknowledged : SkipSubmitOutcome

    data class Failed(val category: ErrorCategory) : SkipSubmitOutcome
}

data class LoginTicketRequestInput(
    val vehicle: VehicleRef,
    val session: SessionRef,
    val bindingOperationId: String?,
)

sealed interface LoginTicketOutcome {
    data class Ticket(val ticket: LoginTicket) : LoginTicketOutcome

    data class Failed(val category: ErrorCategory) : LoginTicketOutcome
}

data class RecordReportInput(val record: PendingRecord)

sealed interface RecordReportOutcome {
    data object Acknowledged : RecordReportOutcome

    data class Failed(val category: ErrorCategory) : RecordReportOutcome
}

/** Local secure login capability. The domain never generates user tokens itself. */
interface AccountLoginGateway {
    suspend fun login(ticket: LoginTicket): LoginOutcome

    suspend fun signOutLocally()
}

sealed interface LoginOutcome {
    data class Success(val account: AccountRef) : LoginOutcome

    data class Failed(val category: ErrorCategory) : LoginOutcome
}

/** First interaction privacy consent, delegated to the vehicle desktop. */
interface PrivacyConsentGateway {
    suspend fun requestFirstConsent(scope: PrivacyScope, items: List<String>): PrivacyPromptResult
}

data class PrivacyScope(val vehicle: VehicleRef, val account: AccountRef)

sealed interface PrivacyPromptResult {
    data class Decided(val choice: PrivacyChoice) : PrivacyPromptResult

    /** No answer is available right now (for example the desktop service is busy). */
    data class Unavailable(val category: ErrorCategory) : PrivacyPromptResult

    /** Requested from a rear seat screen: only an instruction to operate on the front screen. */
    data object FrontSeatRequired : PrivacyPromptResult
}
