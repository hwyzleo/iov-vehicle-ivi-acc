package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.ActivationPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.PayloadContractPendingException
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.UnconfiguredPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudOperation
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwTransport
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCloudPort
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginMaterialOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginMaterialRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Cloud adapter over EDD-SGW (design D-003).
 *
 * Transport framing, path configuration and error classification are concrete; the payload schema
 * is delegated to [ActivationPayloadCodec] and stays unset until the contract is confirmed.
 */
class SgwActivationCloudPort(
    private val transport: SgwTransport,
    private val codec: ActivationPayloadCodec = UnconfiguredPayloadCodec(),
) : ActivationCloudPort {

    override suspend fun requestSession(input: SessionRequestInput): SessionRequestOutcome {
        val body = encodeOrNull { codec.encodeSessionRequest(input) }
            ?: return SessionRequestOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        val result = transport.execute(CloudOperation.REQUEST_SESSION, input.requestId, body)
        SgwOutcomeClassifier.classify(result)?.let { return SessionRequestOutcome.Failed(it) }
        val session = decodeOrNull { codec.decodeSessionCreated(result.body.orEmpty()) }
            ?: return SessionRequestOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)
        return SessionRequestOutcome.Created(session)
    }

    override suspend fun querySession(input: SessionQueryInput): SessionQueryOutcome {
        val body = encodeOrNull { codec.encodeSessionQuery(input) }
            ?: return SessionQueryOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        val result = transport.execute(CloudOperation.QUERY_SESSION, null, body)
        SgwOutcomeClassifier.classify(result)?.let { return SessionQueryOutcome.Failed(it) }
        val observation = decodeOrNull { codec.decodeSessionObservation(result.body.orEmpty()) }
            ?: return SessionQueryOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)
        return SessionQueryOutcome.Observed(observation)
    }

    override suspend fun submitSkip(input: SkipRequestInput): SkipSubmitOutcome {
        val body = encodeOrNull { codec.encodeSkip(input) }
            ?: return SkipSubmitOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        val result = transport.execute(CloudOperation.SUBMIT_SKIP, input.requestId, body)
        SgwOutcomeClassifier.classify(result)?.let { return SkipSubmitOutcome.Failed(it) }
        val acknowledged = decodeOrNull { codec.decodeSkipAcknowledged(result.body.orEmpty()) }
            ?: return SkipSubmitOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)
        return if (acknowledged) {
            SkipSubmitOutcome.Acknowledged
        } else {
            SkipSubmitOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)
        }
    }

    override suspend fun requestLoginMaterial(
        input: LoginMaterialRequestInput,
    ): LoginMaterialOutcome {
        val body = encodeOrNull { codec.encodeLoginMaterialRequest(input) }
            ?: return LoginMaterialOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        val result = transport.execute(CloudOperation.REQUEST_LOGIN_MATERIAL, null, body)
        SgwOutcomeClassifier.classify(result)?.let { return LoginMaterialOutcome.Failed(it) }
        val material = decodeOrNull { codec.decodeLoginMaterial(result.body.orEmpty()) }
            ?: return LoginMaterialOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)
        return LoginMaterialOutcome.Material(material)
    }

    override suspend fun reportRecord(input: RecordReportInput): RecordReportOutcome {
        val body = encodeOrNull { codec.encodeRecord(input.record) }
            ?: return RecordReportOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        val result = transport.execute(CloudOperation.REPORT_RECORD, input.record.id, body)
        SgwOutcomeClassifier.classify(result)?.let { return RecordReportOutcome.Failed(it) }
        val acknowledged = decodeOrNull { codec.decodeRecordAcknowledged(result.body.orEmpty()) }
            ?: return RecordReportOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)
        return if (acknowledged) {
            RecordReportOutcome.Acknowledged
        } else {
            RecordReportOutcome.Failed(ErrorCategory.CONTRACT_REJECTED)
        }
    }

    private inline fun <T> encodeOrNull(block: () -> T): T? = try {
        block()
    } catch (pending: PayloadContractPendingException) {
        null
    }

    private inline fun <T> decodeOrNull(block: () -> T): T? = try {
        block()
    } catch (pending: PayloadContractPendingException) {
        null
    }
}
