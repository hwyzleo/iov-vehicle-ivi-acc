package net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Minimal OkHttp transport (DSN-CR-002 D-003).
 *
 * Everything environment specific (base URL, per operation path, timeouts, TLS configuration)
 * comes from [SgwRouteConfig] and the injected client, so no endpoint is baked into business code.
 * GET calls carry query parameters, POST calls carry the encoded body; both may carry a Bearer
 * token issued by SEC-CIAM.
 */
class OkHttpSgwTransport(
    private val routes: SgwRouteConfig,
    private val client: OkHttpClient = defaultClient(),
) : SgwTransport {

    override suspend fun execute(request: CloudRequest): TransportResult {
        val template = routes.paths[request.operation]
            ?: return TransportResult(statusCode = 0, failure = TransportFailure.IO_ERROR)
        val url = (routes.baseUrl.trimEnd('/') + resolvePath(template, request.pathParams))
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply { request.query.forEach { (name, value) -> addQueryParameter(name, value) } }
            ?.build()
            ?: return TransportResult(statusCode = 0, failure = TransportFailure.IO_ERROR)

        val builder = Request.Builder().url(url)
        request.bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        request.idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val httpRequest = when (request.method) {
            HttpMethod.GET -> builder.get().build()
            HttpMethod.POST -> builder.post((request.body ?: "").toRequestBody(JSON_MEDIA_TYPE)).build()
        }

        return try {
            client.newCall(httpRequest).execute().use { response ->
                TransportResult(
                    statusCode = response.code,
                    body = response.body?.string(),
                    // Response headers are part of the contract for the catalogue (Cache-Control).
                    headers = response.headers.toMap(),
                )
            }
        } catch (timeout: SocketTimeoutException) {
            // A timeout means the result is unknown; it is not a business failure.
            TransportResult(statusCode = 0, failure = TransportFailure.TIMEOUT)
        } catch (error: IOException) {
            TransportResult(statusCode = 0, failure = TransportFailure.IO_ERROR)
        }
    }

    private fun resolvePath(template: String, pathParams: Map<String, String>): String {
        var resolved = template
        pathParams.forEach { (name, value) ->
            resolved = resolved.replace("{$name}", encodePathSegment(value))
        }
        return resolved
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
