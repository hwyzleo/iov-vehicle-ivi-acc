package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportFailure
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Response envelope used by every IOV service: `{code, message, data, traceId, timestamp}`.
 *
 * Business failures arrive as HTTP 200 with a non success [code], so the car must read the code
 * and must not treat the HTTP status alone as the outcome (DSN-CR-002 D-003).
 */
data class SgwEnvelope(
    val code: String?,
    val message: String?,
    val data: JsonElement?,
    val traceId: String?,
) {
    val isSuccess: Boolean get() = code == SgwOutcomeClassifier.SUCCESS_CODE

    val objectData: JsonObject? get() = data as? JsonObject
}

/** Reads a string value out of a JSON object; never throws. */
fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content

/** Reads a boolean value out of a JSON object; never throws. */
fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/**
 * Maps transport outcomes and business codes to the five car side categories.
 *
 * The mapping is frozen by DSN-CR-002 D-003 and mirrors IOV-TSP `UserVehicleBindingErrorCode`;
 * a retryable/terminal decision never depends on a message string.
 */
object SgwOutcomeClassifier {

    const val SUCCESS_CODE = "000000"

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null when the transport delivered a candidate success that still has to be decoded. */
    fun classifyTransport(result: TransportResult): ErrorCategory? {
        result.failure?.let { failure ->
            return when (failure) {
                // A timeout says nothing about the business outcome.
                TransportFailure.TIMEOUT -> ErrorCategory.RESULT_UNKNOWN
                TransportFailure.IO_ERROR -> ErrorCategory.RETRYABLE
            }
        }
        val status = result.statusCode
        return when {
            status in 200..299 -> null
            status == 401 || status == 403 -> ErrorCategory.AUTH_REJECTED
            status == 408 || status == 429 -> ErrorCategory.RETRYABLE
            status >= 500 -> ErrorCategory.RETRYABLE
            else -> ErrorCategory.CONTRACT_REJECTED
        }
    }

    fun parse(body: String?): SgwEnvelope? {
        if (body.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return SgwEnvelope(
            code = obj.string("code"),
            message = obj.string("message"),
            data = obj["data"],
            traceId = obj.string("traceId"),
        )
    }

    /** Business code to category; returns null for the success code. */
    fun categoryOf(code: String?): ErrorCategory? = when (code) {
        SUCCESS_CODE -> null
        // Session no longer usable: refresh the QR code.
        "401101", "401102", "401103" -> ErrorCategory.EXPIRED
        // Dependency (VSO/VMD/CMS) temporarily unavailable: retry.
        "401108" -> ErrorCategory.RETRYABLE
        // Everything else is a contract/business rejection: terminal until the user acts.
        else -> ErrorCategory.CONTRACT_REJECTED
    }
}
