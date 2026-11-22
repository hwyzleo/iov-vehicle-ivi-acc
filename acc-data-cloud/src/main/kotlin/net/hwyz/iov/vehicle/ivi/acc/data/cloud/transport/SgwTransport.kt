package net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport

/** Cloud operations used by the activation flow. Paths are configured, never hardcoded here. */
enum class CloudOperation {
    REQUEST_SESSION,
    QUERY_SESSION,
    SUBMIT_SKIP,
    REQUEST_LOGIN_MATERIAL,
    REPORT_RECORD,
}

enum class TransportFailure {
    TIMEOUT,
    IO_ERROR,
}

/**
 * Raw transport answer.
 *
 * [statusCode] is only meaningful when [failure] is null; the caller classifies status codes so
 * that the mapping stays in one place instead of leaking into the domain.
 */
data class TransportResult(
    val statusCode: Int,
    val body: String? = null,
    val failure: TransportFailure? = null,
)

/**
 * HTTP transport boundary (EDD-SGW candidate route).
 *
 * The URL prefix and per operation paths are injected from configuration; the exact cloud
 * contract, request fields and status codes are still unconfirmed.
 */
interface SgwTransport {
    suspend fun execute(
        operation: CloudOperation,
        idempotencyKey: String? = null,
        body: String? = null,
    ): TransportResult
}

data class SgwRouteConfig(
    val baseUrl: String,
    val paths: Map<CloudOperation, String>,
)
