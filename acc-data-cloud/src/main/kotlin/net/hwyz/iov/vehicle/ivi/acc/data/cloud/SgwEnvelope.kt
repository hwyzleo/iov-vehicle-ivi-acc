package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportFailure
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult
import net.hwyz.iov.vehicle.ivi.acc.domain.error.ActivationErrorCatalog
import net.hwyz.iov.vehicle.ivi.acc.domain.error.DefaultActivationErrorCatalog
import net.hwyz.iov.vehicle.ivi.acc.domain.error.ErrorClassification
import net.hwyz.iov.vehicle.ivi.acc.domain.error.ErrorScope
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

/**
 * Reads a boolean value out of a JSON object; never throws.
 *
 * A JSON string (`"false"`) is not a boolean: contract payloads must use the real type, and a
 * silently coerced string would hide a schema drift.
 */
fun JsonObject.boolean(name: String): Boolean? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toBooleanStrictOrNull()
}

/**
 * Maps transport outcomes and business codes to the five car side categories.
 *
 * The business code mapping is **not** owned here any more: it delegates to the central
 * [net.hwyz.iov.vehicle.ivi.acc.domain.error.ActivationErrorCatalog] (D-015 / R-14), so TSP, CIAM and
 * SGW codes share one table. This object only extracts the code and classifies the transport.
 */
object SgwOutcomeClassifier {

    const val SUCCESS_CODE = DefaultActivationErrorCatalog.SUCCESS_CODE

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

    /** Business code to category inside one scope; null only for the success code. */
    fun categoryOf(code: String?, scope: ErrorScope = ErrorScope.TSP_ACTIVATION): ErrorCategory? {
        if (DefaultActivationErrorCatalog.isSuccess(code)) return null
        return catalog.classify(scope, code).category
    }

    /** Full classification (category + retry advice + recovery flags) of a business code. */
    fun classificationOf(
        code: String?,
        scope: ErrorScope = ErrorScope.TSP_ACTIVATION,
    ): ErrorClassification = catalog.classify(scope, code)

    fun isSuccess(code: String?): Boolean = DefaultActivationErrorCatalog.isSuccess(code)

    /** Replaceable for tests; the default is the single frozen table. */
    var catalog: ActivationErrorCatalog = DefaultActivationErrorCatalog
}
