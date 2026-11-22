package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.TokenProvider
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.CloudPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.JsonCloudPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudOperation
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudRequest
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.HttpMethod
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwTransport
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementLoadOutcome
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
) : AgreementGateway {

    override suspend fun loadPublishedDocuments(): AgreementLoadOutcome {
        // Fail closed: without an IAM token the gateway would reject an anonymous call, so the car
        // reports an authentication problem instead of pretending the agreements are missing.
        val token = tokens.authorizationToken()
            ?: return AgreementLoadOutcome.Failed(ErrorCategory.AUTH_REJECTED)
        val request = CloudRequest(
            operation = CloudOperation.LOAD_AGREEMENTS,
            method = HttpMethod.GET,
            query = mapOf("language" to languageProvider()),
            bearerToken = token,
        )
        val result = transport.execute(request)
        SgwOutcomeClassifier.classifyTransport(result)?.let { return AgreementLoadOutcome.Failed(it) }
        val envelope = SgwOutcomeClassifier.parse(result.body)
            ?: return AgreementLoadOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        SgwOutcomeClassifier.categoryOf(envelope.code)?.let {
            return AgreementLoadOutcome.Failed(it)
        }
        val data = envelope.objectData
            ?: return AgreementLoadOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        val bundle = codec.decodeAgreements(data)
            ?: return AgreementLoadOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        return AgreementLoadOutcome.Loaded(bundle)
    }
}
