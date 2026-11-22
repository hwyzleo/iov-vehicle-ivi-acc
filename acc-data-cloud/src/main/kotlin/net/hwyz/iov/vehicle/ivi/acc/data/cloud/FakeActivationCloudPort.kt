package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCloudPort
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementLoadOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginTicketOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginTicketRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementBundle
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementDocument
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginTicket
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Scriptable cloud adapter for the demo build and for adapter level tests.
 *
 * It is intentionally NOT a production implementation: it lets a developer drive timeout,
 * duplicate and out of order scenarios without a cloud environment.
 */
class FakeActivationCloudPort(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val sessionTtlMillis: Long = 120_000L,
) : ActivationCloudPort {

    val sessionRequests = mutableListOf<SessionRequestInput>()
    val skipRequests = mutableListOf<SkipRequestInput>()
    val reportedRecords = mutableListOf<PendingRecord>()
    var queryCount = 0
        private set

    var sessionOutcomeFactory: (SessionRequestInput) -> SessionRequestOutcome = { input ->
        SessionRequestOutcome.Created(
            SessionRef(
                sessionId = "session-${input.flowGeneration}",
                qrPayload = "fake-qr-${input.flowGeneration}",
                issuedAtEpochMillis = nowEpochMillis(),
                expiresAtEpochMillis = nowEpochMillis() + sessionTtlMillis,
            ),
        )
    }

    /** Queued query answers; when empty [defaultQueryOutcome] is used. */
    val queryOutcomes = ArrayDeque<SessionQueryOutcome>()

    var defaultQueryOutcome: SessionQueryOutcome =
        SessionQueryOutcome.Observed(SessionObservation.Active)

    var skipOutcome: SkipSubmitOutcome = SkipSubmitOutcome.Acknowledged

    var loginTicketOutcomeFactory: (LoginTicketRequestInput) -> LoginTicketOutcome = {
        LoginTicketOutcome.Ticket(
            LoginTicket(
                ticket = "ticket-handle",
                expiresAtEpochMillis = nowEpochMillis() + 60_000L,
            ),
        )
    }

    var recordOutcome: RecordReportOutcome = RecordReportOutcome.Acknowledged

    override suspend fun requestSession(input: SessionRequestInput): SessionRequestOutcome {
        sessionRequests += input
        return sessionOutcomeFactory(input)
    }

    override suspend fun querySession(input: SessionQueryInput): SessionQueryOutcome {
        queryCount++
        return if (queryOutcomes.isNotEmpty()) queryOutcomes.removeFirst() else defaultQueryOutcome
    }

    override suspend fun submitSkip(input: SkipRequestInput): SkipSubmitOutcome {
        skipRequests += input
        return skipOutcome
    }

    override suspend fun issueLoginTicket(
        input: LoginTicketRequestInput,
    ): LoginTicketOutcome = loginTicketOutcomeFactory(input)

    override suspend fun reportRecord(input: RecordReportInput): RecordReportOutcome {
        reportedRecords += input.record
        return recordOutcome
    }
}

/** Agreement source for the demo build; production uses the SGW agreement operation. */
class FakeAgreementGateway(
    var bundle: AgreementBundle = defaultBundle(),
    var failure: ErrorCategory? = null,
) : AgreementGateway {

    var loadCount = 0
        private set

    override suspend fun loadPublishedDocuments(): AgreementLoadOutcome {
        loadCount++
        failure?.let { return AgreementLoadOutcome.Failed(it) }
        return AgreementLoadOutcome.Loaded(bundle)
    }

    companion object {
        fun document(type: AgreementType, version: String = "1.0"): AgreementDocument =
            AgreementDocument(
                type = type,
                version = version,
                revisionId = "rev-$version",
                language = "zh-CN",
                contentHash = "hash-$version",
                body = "Fake ${type.name} body $version",
            )

        fun defaultBundle(): AgreementBundle = AgreementBundle(
            listOf(
                document(AgreementType.PRIVACY_POLICY),
                document(AgreementType.USER_AGREEMENT),
            ),
        )
    }
}
