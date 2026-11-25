package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.TokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.TokenProvider
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.CloudPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.JsonCloudPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudOperation
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudRequest
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.HttpMethod
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwTransport
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementLoadOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Published agreement documents from IOV-TSP (DSN-CR-002 D-003 "读取协议").
 *
 * The language is the car Locale with a zh-CN fallback; a payload that does not contain both
 * documents is rejected so the flow stays fail-closed (A-001).
 */
class SgwAgreementGateway(
    private val transport: SgwTransport,
    private val codec: CloudPayloadCodec = JsonCloudPayloadCodec(),
    private val tokens: TokenProvider = TokenProvider.None,
    private val languageProvider: () -> String = { "zh-CN" },
    /**
     * Optional diagnostics sink. Only the HTTP status and the cloud business code are reported (D-007
     * whitelist): they are what a real联调 needs to tell "contract not published" apart from
     * "token rejected" without dumping payloads.
     */
    private val logger: DiagnosticsLogger? = null,
) : AgreementGateway {

    override suspend fun loadPublishedDocuments(): AgreementLoadOutcome {
        // Fail closed: without an IAM token the gateway would reject an anonymous call, so the car
        // reports an authentication problem instead of pretending the agreements are missing.
        val token = when (val outcome = tokens.authorizationToken()) {
            is TokenOutcome.Available -> outcome.token
            is TokenOutcome.Unavailable -> return AgreementLoadOutcome.Failed(outcome.category)
        }
        val request = CloudRequest(
            operation = CloudOperation.LOAD_AGREEMENTS,
            method = HttpMethod.GET,
            query = mapOf("language" to languageProvider()),
            bearerToken = token,
        )
        val result = transport.execute(request)
        SgwOutcomeClassifier.classifyTransport(result)?.let { category ->
            logger?.event(
                "agreement.load.transport_failed",
                "status" to result.statusCode.toString(),
                "category" to category.name,
            )
            return AgreementLoadOutcome.Failed(category)
        }
        val envelope = SgwOutcomeClassifier.parse(result.body)
            ?: return AgreementLoadOutcome.Failed(ErrorCategory.CONTRACT_REJECTED).also {
                logger?.event("agreement.load.unparsable", "status" to result.statusCode.toString())
            }
        SgwOutcomeClassifier.categoryOf(envelope.code)?.let { category ->
            logger?.event(
                "agreement.load.business_failed",
                "code" to (envelope.code ?: "-"),
                "category" to category.name,
            )
            return AgreementLoadOutcome.Failed(category)
        }
        val data = envelope.objectData
            ?: return AgreementLoadOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        val bundle = codec.decodeAgreements(data)
            ?: return AgreementLoadOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        return AgreementLoadOutcome.Loaded(bundle)
    }
}
