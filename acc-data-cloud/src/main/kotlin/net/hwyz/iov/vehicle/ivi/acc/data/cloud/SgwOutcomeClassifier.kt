package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportFailure
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult

/**
 * Maps transport outcomes to car side categories.
 *
 * The mapping is intentionally transport level: no numeric cloud error code is assumed before the
 * contract is confirmed. 2xx means "payload decodable", everything else is one of the five local
 * categories.
 */
object SgwOutcomeClassifier {

    /** Returns null when the response is a candidate success that still has to be decoded. */
    fun classify(result: TransportResult): ErrorCategory? {
        result.failure?.let { failure ->
            return when (failure) {
                // Timeout says nothing about the business outcome.
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
}
