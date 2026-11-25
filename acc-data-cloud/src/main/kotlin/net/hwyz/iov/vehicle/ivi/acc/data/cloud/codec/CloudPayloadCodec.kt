package net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.boolean
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.string
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyItemState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementBundle
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementDocument
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginTicket
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordPayload
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import java.time.Instant

/** Decoded privacy catalogue response (G-1: `catalogVersion` + `items`). */
data class DecodedPrivacyCatalog(
    val catalogVersion: String,
    val items: List<PrivacyAuthorizationItem>,
)

/** Thrown when the cloud payload contract is not configured for an operation. */
class PayloadContractPendingException(operation: String) :
    IllegalStateException("Cloud payload contract for $operation is not configured")

/**
 * Request/response payload boundary.
 *
 * The wire schema is frozen by DSN-CR-002 D-003 (IOV-TSP vehicle side activation API) and, for the
 * privacy catalogue and report, by the G-1 cross platform contract (DSN-CR-005 D-013). Bodies are
 * JSON; decoders return null for a payload they cannot interpret instead of inventing defaults.
 */
interface CloudPayloadCodec {
    fun decodeAgreements(data: JsonObject): AgreementBundle?

    fun encodeSessionRequest(input: SessionRequestInput): String

    fun decodeSessionCreated(data: JsonObject, issuedAtEpochMillis: Long): SessionRef?

    fun decodeSessionObservation(data: JsonObject): SessionObservation?

    fun encodeSkip(input: SkipRequestInput): String

    fun decodeLoginTicket(data: JsonObject): LoginTicket?

    fun decodeAcknowledged(data: JsonObject): Boolean

    fun encodeLoginResult(record: PendingRecord): String?

    fun encodePrivacyReport(record: PendingRecord): String?

    /** G-1 catalogue decode; any missing or unknown field invalidates the whole payload. */
    fun decodePrivacyCatalog(data: JsonObject): DecodedPrivacyCatalog?
}

/**
 * JSON codec for the IOV-TSP vehicle side activation API.
 *
 * Field names mirror `iov-tsp-api`: agreementType/version/revisionId/contentHash/content,
 * sessionId/qrToken/expiresAt, state/bindingOperationId, reportId/accepted, ticket/expiresAt.
 */
class JsonCloudPayloadCodec : CloudPayloadCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun decodeAgreements(data: JsonObject): AgreementBundle? {
        val array = data["agreements"] as? JsonArray ?: return null
        val documents = array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val type = item.string("agreementType")?.let { name ->
                runCatching { AgreementType.valueOf(name) }.getOrNull()
            } ?: return@mapNotNull null
            val version = item.string("version") ?: return@mapNotNull null
            AgreementDocument(
                type = type,
                version = version,
                revisionId = item.string("revisionId"),
                language = item.string("language") ?: data.string("language"),
                contentHash = item.string("contentHash"),
                body = item.string("content"),
            )
        }
        if (documents.isEmpty()) return null
        return AgreementBundle(documents)
    }

    override fun encodeSessionRequest(input: SessionRequestInput): String = buildJsonObject {
        put(
            "agreements",
            buildJsonArray { input.agreements.sortedBy { it.type.name }.forEach { add(acceptance(it)) } },
        )
    }.toString()

    override fun decodeSessionCreated(data: JsonObject, issuedAtEpochMillis: Long): SessionRef? {
        val sessionId = data.string("sessionId") ?: return null
        val qrToken = data.string("qrToken") ?: return null
        val expiresAt = data.string("expiresAt")?.toEpochMilli() ?: return null
        return SessionRef(
            sessionId = sessionId,
            qrPayload = qrToken,
            issuedAtEpochMillis = issuedAtEpochMillis,
            expiresAtEpochMillis = expiresAt,
        )
    }

    override fun decodeSessionObservation(data: JsonObject): SessionObservation? {
        return when (data.string("state")) {
            "BOUND" -> SessionObservation.BoundConfirmed(data.string("bindingOperationId"))
            "EXPIRED", "CANCELLED", "SKIPPED" -> SessionObservation.Expired
            "ISSUED", "SCANNED" -> SessionObservation.Active
            else -> null
        }
    }

    override fun encodeSkip(input: SkipRequestInput): String = buildJsonObject {
        put("reportId", input.requestId)
        input.sessionId?.let { put("sourceSessionId", it) }
        put(
            "agreements",
            buildJsonArray { input.agreements.sortedBy { it.type.name }.forEach { add(acceptance(it)) } },
        )
        put("occurredAt", Instant.now().toString())
    }.toString()

    override fun decodeLoginTicket(data: JsonObject): LoginTicket? {
        val ticket = data.string("ticket") ?: return null
        val expiresAt = data.string("expiresAt")?.toEpochMilli() ?: return null
        return LoginTicket(ticket = ticket, expiresAtEpochMillis = expiresAt)
    }

    override fun decodeAcknowledged(data: JsonObject): Boolean = data.boolean("accepted") == true

    override fun encodeLoginResult(record: PendingRecord): String? {
        val payload = record.payload as? RecordPayload.LoginResult ?: return null
        // R-15: a FAILED receipt without a failure reason is not deliverable.
        if (payload.loginStatus == FAILED_STATUS && payload.failureReason == null) return null
        return buildJsonObject {
            put("sessionId", payload.sessionId)
            // BLK-11: the field is optional on the wire, the receipt itself is mandatory.
            payload.bindingOperationId?.let { put("bindingOperationId", it) }
            put("reportId", record.id)
            put("loginStatus", payload.loginStatus)
            put("failureReason", payload.failureReason?.name ?: "UNKNOWN")
            if (payload.agreements.isNotEmpty()) {
                put(
                    "agreements",
                    buildJsonArray { payload.agreements.sortedBy { it.type.name }.forEach { add(acceptance(it)) } },
                )
            }
            put("occurredAt", Instant.ofEpochMilli(payload.loggedInAtEpochMillis).toString())
        }.toString()
    }

    override fun encodePrivacyReport(record: PendingRecord): String? {
        val payload = record.payload as? RecordPayload.PrivacyDecisionRecord ?: return null
        // G-1: `catalogVersion` is mandatory and the item list may never be empty.
        if (payload.catalogVersion.isBlank()) return null
        if (payload.items.isEmpty()) return null
        return buildJsonObject {
            put("reportId", record.id)
            put("catalogVersion", payload.catalogVersion)
            put(
                "items",
                buildJsonArray {
                    payload.items.sortedBy { it.authorizationItem }.forEach { item ->
                        add(
                            buildJsonObject {
                                put("authorizationItem", item.authorizationItem)
                                put(
                                    "state",
                                    when (item.state) {
                                        PrivacyItemState.GRANTED -> "GRANTED"
                                        PrivacyItemState.NOT_GRANTED -> "NOT_GRANTED"
                                    },
                                )
                                // Local decision sequence number, not a timestamp and not a catalogue
                                // version: the cloud only uses it for same item convergence.
                                put("version", item.version)
                            },
                        )
                    }
                },
            )
            put("occurredAt", Instant.ofEpochMilli(payload.decidedAtEpochMillis).toString())
        }.toString()
    }

    override fun decodePrivacyCatalog(data: JsonObject): DecodedPrivacyCatalog? {
        val catalogVersion = data.string("catalogVersion")?.takeIf { it.isNotBlank() } ?: return null
        val array = data["items"] as? JsonArray ?: return null
        val items = array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val code = item.string("authorizationItem")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val displayName = item.string("displayName")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val required = item.boolean("required") ?: return@mapNotNull null
            PrivacyAuthorizationItem(code = code, displayName = displayName, required = required)
        }
        // A partially decoded list is a contract violation, not a smaller catalogue.
        if (items.size != array.size) return null
        return DecodedPrivacyCatalog(catalogVersion = catalogVersion, items = items)
    }

    private fun acceptance(reference: AgreementReference): JsonObject = buildJsonObject {
        put("agreementType", reference.type.name)
        put("agreed", true)
        put("version", reference.version)
        put("revisionId", reference.revisionId ?: reference.version)
        reference.language?.let { put("language", it) }
        reference.contentHash?.let { put("contentHash", it) }
    }
}

private fun String.toEpochMilli(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

private const val FAILED_STATUS = "FAILED"

/** Kind of cloud report a pending record maps to; unknown kinds are never reported. */
fun RecordType.cloudReport(): CloudReport? = when (this) {
    RecordType.LOGIN_RESULT -> CloudReport.LOGIN_RESULT
    RecordType.PRIVACY_DECISION -> CloudReport.PRIVACY_AUTHORIZATION
    // Agreement consent evidence travels inside the login result (Account+VIN) or the skip submit.
    RecordType.AGREEMENT_CONSENT, RecordType.SKIP_ACTIVATION -> null
}

enum class CloudReport {
    LOGIN_RESULT,
    PRIVACY_AUTHORIZATION,
}
