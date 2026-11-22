package net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Minimal OkHttp transport.
 *
 * Everything environment specific (base URL, per operation path, timeouts, TLS configuration)
 * comes from [SgwRouteConfig] and the injected client, so no endpoint is baked into business code.
 */
class OkHttpSgwTransport(
    private val routes: SgwRouteConfig,
    private val client: OkHttpClient = defaultClient(),
) : SgwTransport {

    override suspend fun execute(
        operation: CloudOperation,
        idempotencyKey: String?,
        body: String?,
    ): TransportResult {
        val path = routes.paths[operation]
            ?: return TransportResult(statusCode = 0, failure = TransportFailure.IO_ERROR)
        val url = routes.baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        val requestBody = (body ?: "").toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                TransportResult(
                    statusCode = response.code,
                    body = response.body?.string(),
                )
            }
        } catch (timeout: SocketTimeoutException) {
            // A timeout means the result is unknown; it is not a business failure.
            TransportResult(statusCode = 0, failure = TransportFailure.TIMEOUT)
        } catch (error: IOException) {
            TransportResult(statusCode = 0, failure = TransportFailure.IO_ERROR)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
