package net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginMaterialRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginMaterial
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef

/** Thrown when the cloud payload contract has not been frozen yet. */
class PayloadContractPendingException(operation: String) :
    IllegalStateException("Cloud payload contract for $operation is not configured")

/**
 * Request/response payload boundary.
 *
 * The cloud contract (URL, field names, status codes, numeric error codes) is still unconfirmed,
 * so the default implementation refuses every operation instead of inventing a schema. A concrete
 * codec is added once the contract is frozen, and test codecs implement the same interface.
 */
interface ActivationPayloadCodec {
    fun encodeSessionRequest(input: SessionRequestInput): String

    fun decodeSessionCreated(body: String): SessionRef?

    fun encodeSessionQuery(input: SessionQueryInput): String

    fun decodeSessionObservation(body: String): SessionObservation?

    fun encodeSkip(input: SkipRequestInput): String

    fun decodeSkipAcknowledged(body: String): Boolean

    fun encodeLoginMaterialRequest(input: LoginMaterialRequestInput): String

    fun decodeLoginMaterial(body: String): LoginMaterial?

    fun encodeRecord(record: PendingRecord): String

    fun decodeRecordAcknowledged(body: String): Boolean
}

/**
 * Default codec used until the cloud contract is confirmed.
 *
 * Every call fails fast with [PayloadContractPendingException]; the cloud port maps that to a
 * CONTRACT_REJECTED category, so the car shows a retryable/recovery entry instead of pretending
 * that the interaction succeeded.
 */
class UnconfiguredPayloadCodec : ActivationPayloadCodec {
    override fun encodeSessionRequest(input: SessionRequestInput): String = pending("REQUEST_SESSION")

    override fun decodeSessionCreated(body: String): SessionRef? = pending("REQUEST_SESSION")

    override fun encodeSessionQuery(input: SessionQueryInput): String = pending("QUERY_SESSION")

    override fun decodeSessionObservation(body: String): SessionObservation? = pending("QUERY_SESSION")

    override fun encodeSkip(input: SkipRequestInput): String = pending("SUBMIT_SKIP")

    override fun decodeSkipAcknowledged(body: String): Boolean = pending("SUBMIT_SKIP")

    override fun encodeLoginMaterialRequest(input: LoginMaterialRequestInput): String =
        pending("REQUEST_LOGIN_MATERIAL")

    override fun decodeLoginMaterial(body: String): LoginMaterial? = pending("REQUEST_LOGIN_MATERIAL")

    override fun encodeRecord(record: PendingRecord): String = pending("REPORT_RECORD")

    override fun decodeRecordAcknowledged(body: String): Boolean = pending("REPORT_RECORD")

    private fun pending(operation: String): Nothing = throw PayloadContractPendingException(operation)
}
