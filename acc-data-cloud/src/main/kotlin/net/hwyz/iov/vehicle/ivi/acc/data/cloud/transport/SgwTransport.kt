package net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport

/**
 * Cloud operations used by the activation flow (DSN-CR-002 D-003).
 *
 * Paths are configured, never hardcoded here: the gateway route table decides the final URL.
 */
enum class CloudOperation {
    LOAD_AGREEMENTS,
    REQUEST_SESSION,
    QUERY_SESSION,
    SUBMIT_SKIP,
    ISSUE_LOGIN_TICKET,
    REPORT_LOGIN_RESULT,
    REPORT_PRIVACY_AUTHORIZATION,
}

enum class HttpMethod {
    GET,
    POST,
}

enum class TransportFailure {
    TIMEOUT,
    IO_ERROR,
}

/**
 * One cloud call.
 *
 * [pathParams] are substituted into the configured path template (`{name}`), [query] is appended
 * as URL parameters, and [bearerToken] carries the IAM device/user token.
 */
data class CloudRequest(
    val operation: CloudOperation,
    val method: HttpMethod = HttpMethod.POST,
    val pathParams: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val idempotencyKey: String? = null,
    val body: String? = null,
    val bearerToken: String? = null,
)

/**
 * Raw transport answer.
 *
 * [statusCode] is only meaningful when [failure] is null; the business code inside the response
 * envelope is classified by the cloud adapter.
 */
data class TransportResult(
    val statusCode: Int,
    val body: String? = null,
    val failure: TransportFailure? = null,
)

/** HTTP transport boundary (vehicle side activation routes through EDD-SGW). */
interface SgwTransport {
    suspend fun execute(request: CloudRequest): TransportResult
}

/** Base URL plus one path template per operation. */
data class SgwRouteConfig(
    val baseUrl: String,
    val paths: Map<CloudOperation, String>,
) {
    companion object {
        /** Vehicle side activation routes of IOV-TSP (DSN-CR-002 D-003). */
        private const val PREFIX = "/api/vehicle/activation/v1"

        val activationPaths: Map<CloudOperation, String> = mapOf(
            CloudOperation.LOAD_AGREEMENTS to "$PREFIX/agreements",
            CloudOperation.REQUEST_SESSION to "$PREFIX/session",
            CloudOperation.QUERY_SESSION to "$PREFIX/session/{sessionId}/result",
            CloudOperation.SUBMIT_SKIP to "$PREFIX/session/skip",
            CloudOperation.ISSUE_LOGIN_TICKET to "$PREFIX/session/{sessionId}/login-ticket",
            CloudOperation.REPORT_LOGIN_RESULT to "$PREFIX/session/ivi-login-result",
            CloudOperation.REPORT_PRIVACY_AUTHORIZATION to "$PREFIX/privacy-authorization",
        )
    }
}
