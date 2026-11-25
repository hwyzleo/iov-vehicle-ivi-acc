package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import kotlinx.serialization.json.JsonObject
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.TokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.TokenProvider
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.CloudPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.JsonCloudPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.cloudReport
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudOperation
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudRequest
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.HttpMethod
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwTransport
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCloudPort
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
import net.hwyz.iov.vehicle.ivi.acc.domain.error.ErrorScope
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Cloud adapter over EDD-SGW for the IOV-TSP vehicle side activation API (DSN-CR-002 D-003).
 *
 * Every call carries the IAM authorization token; business outcomes are read from the response
 * envelope code, never from the HTTP status alone. Ticket issuance is the only place a login
 * credential enters the car.
 */
class SgwActivationCloudPort(
    private val transport: SgwTransport,
    private val codec: CloudPayloadCodec = JsonCloudPayloadCodec(),
    private val tokens: TokenProvider = TokenProvider.None,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ActivationCloudPort {

    override suspend fun requestSession(input: SessionRequestInput): SessionRequestOutcome {
        val token = when (val outcome = tokens.authorizationToken()) {
            is TokenOutcome.Available -> outcome.token
            is TokenOutcome.Unavailable -> return SessionRequestOutcome.Failed(outcome.category)
        }
        val request = CloudRequest(
            operation = CloudOperation.REQUEST_SESSION,
            method = HttpMethod.POST,
            idempotencyKey = input.requestId,
            body = runCatching { codec.encodeSessionRequest(input) }.getOrNull()
                ?: return SessionRequestOutcome.Failed(ErrorCategory.CONTRACT_REJECTED),
            bearerToken = token,
        )
        return when (val call = call(request)) {
            is CloudCall.Failed -> SessionRequestOutcome.Failed(call.category)
            is CloudCall.Success -> codec.decodeSessionCreated(call.data, nowEpochMillis())
                ?.let { SessionRequestOutcome.Created(it) }
                ?: SessionRequestOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        }
    }

    override suspend fun querySession(input: SessionQueryInput): SessionQueryOutcome {
        val token = when (val outcome = tokens.authorizationToken()) {
            is TokenOutcome.Available -> outcome.token
            is TokenOutcome.Unavailable -> return SessionQueryOutcome.Failed(outcome.category)
        }
        val request = CloudRequest(
            operation = CloudOperation.QUERY_SESSION,
            method = HttpMethod.GET,
            pathParams = mapOf("sessionId" to input.session.sessionId),
            bearerToken = token,
        )
        return when (val call = call(request)) {
            is CloudCall.Failed -> SessionQueryOutcome.Failed(call.category)
            is CloudCall.Success -> codec.decodeSessionObservation(call.data)
                ?.let { SessionQueryOutcome.Observed(it) }
                ?: SessionQueryOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        }
    }

    override suspend fun submitSkip(input: SkipRequestInput): SkipSubmitOutcome {
        val token = when (val outcome = tokens.authorizationToken()) {
            is TokenOutcome.Available -> outcome.token
            is TokenOutcome.Unavailable -> return SkipSubmitOutcome.Failed(outcome.category)
        }
        val request = CloudRequest(
            operation = CloudOperation.SUBMIT_SKIP,
            method = HttpMethod.POST,
            idempotencyKey = input.requestId,
            body = runCatching { codec.encodeSkip(input) }.getOrNull()
                ?: return SkipSubmitOutcome.Failed(ErrorCategory.CONTRACT_REJECTED),
            bearerToken = token,
        )
        return when (val call = call(request)) {
            is CloudCall.Failed -> SkipSubmitOutcome.Failed(call.category)
            is CloudCall.Success ->
                if (codec.decodeAcknowledged(call.data)) {
                    SkipSubmitOutcome.Acknowledged
                } else {
                    SkipSubmitOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
                }
        }
    }

    override suspend fun issueLoginTicket(input: LoginTicketRequestInput): LoginTicketOutcome {
        val token = when (val outcome = tokens.authorizationToken()) {
            is TokenOutcome.Available -> outcome.token
            is TokenOutcome.Unavailable -> return LoginTicketOutcome.Failed(outcome.category)
        }
        val request = CloudRequest(
            operation = CloudOperation.ISSUE_LOGIN_TICKET,
            method = HttpMethod.POST,
            pathParams = mapOf("sessionId" to input.session.sessionId),
            bearerToken = token,
        )
        return when (val call = call(request)) {
            is CloudCall.Failed -> LoginTicketOutcome.Failed(call.category)
            is CloudCall.Success -> codec.decodeLoginTicket(call.data)
                ?.let { LoginTicketOutcome.Ticket(it) }
                ?: LoginTicketOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        }
    }

    override suspend fun reportRecord(input: RecordReportInput): RecordReportOutcome {
        val record = input.record
        val report = record.type.cloudReport()
            // Agreement consent evidence travels inside the login result / skip submit.
            ?: return RecordReportOutcome.Acknowledged
        val operation = when (report) {
            net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.CloudReport.LOGIN_RESULT ->
                CloudOperation.REPORT_LOGIN_RESULT

            net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.CloudReport.PRIVACY_AUTHORIZATION ->
                CloudOperation.REPORT_PRIVACY_AUTHORIZATION
        }
        val body = when (report) {
            net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.CloudReport.LOGIN_RESULT ->
                codec.encodeLoginResult(record)

            net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.CloudReport.PRIVACY_AUTHORIZATION ->
                codec.encodePrivacyReport(record)
        } ?: return RecordReportOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        val token = when (val outcome = tokens.authorizationToken()) {
            is TokenOutcome.Available -> outcome.token
            is TokenOutcome.Unavailable -> return RecordReportOutcome.Failed(outcome.category)
        }
        val request = CloudRequest(
            operation = operation,
            method = HttpMethod.POST,
            idempotencyKey = record.id,
            body = body,
            bearerToken = token,
        )
        return when (val call = call(request)) {
            is CloudCall.Failed -> RecordReportOutcome.Failed(call.category, call.businessCode)
            is CloudCall.Success ->
                if (codec.decodeAcknowledged(call.data)) {
                    RecordReportOutcome.Acknowledged
                } else {
                    RecordReportOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
                }
        }
    }

    private suspend fun call(
        request: CloudRequest,
        scope: ErrorScope = ErrorScope.TSP_ACTIVATION,
    ): CloudCall {
        val result = transport.execute(request)
        SgwOutcomeClassifier.classifyTransport(result)?.let { return CloudCall.Failed(it, null) }
        val envelope = SgwOutcomeClassifier.parse(result.body)
            ?: return CloudCall.Failed(ErrorCategory.CONTRACT_REJECTED, null)
        if (!SgwOutcomeClassifier.isSuccess(envelope.code)) {
            // D-015: the category, and with it the retry decision, comes from the central catalogue.
            val classification = SgwOutcomeClassifier.classificationOf(envelope.code, scope)
            return CloudCall.Failed(classification.category, envelope.code)
        }
        val data = envelope.objectData
            ?: return CloudCall.Failed(ErrorCategory.CONTRACT_REJECTED, envelope.code)
        return CloudCall.Success(data)
    }

    private sealed interface CloudCall {
        data class Success(val data: JsonObject) : CloudCall

        data class Failed(val category: ErrorCategory, val businessCode: String?) : CloudCall
    }
}
