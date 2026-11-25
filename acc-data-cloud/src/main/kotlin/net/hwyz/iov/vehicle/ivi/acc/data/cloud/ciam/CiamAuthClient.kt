package net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.SgwOutcomeClassifier
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.string
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportFailure
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** CIAM endpoints used by the vehicle (they may be routed through the same gateway). */
data class CiamRouteConfig(
    val baseUrl: String,
    val tokenPath: String = "/api/ivi/auth/v1/token",
    val refreshPath: String = "/api/open/oauth/v1/token",
)

/** Proof of device identity: certificate based or key signature based (D-008, D-011). */
data class DeviceProof(
    /**
     * Device serial used as the CIAM `device_id` claim. Mandatory (BLK-4): CIAM enforces `device_id`
     * on KEY_SIGNATURE, and a `null` value used to fail every real device with a misleading
     * AUTH_REJECTED loop.
     */
    val deviceId: String,
    val proofType: String,
    val proof: String,
    val nonce: String,
)

/** Supplies a fresh device proof for every token request. */
fun interface DeviceProofProvider {
    suspend fun deviceProof(): DeviceProof

    companion object {
        /**
         * Fail closed when no device certificate is installed: activation must not fall back to an
         * anonymous call or to a self reported VIN.
         */
        val Unconfigured: DeviceProofProvider = DeviceProofProvider {
            throw IllegalStateException("Device identity proof is not configured")
        }
    }
}

/** Result of an IAM token request. */
sealed interface CiamTokenOutcome {
    data class Issued(
        val accessToken: String,
        val expiresInSeconds: Long,
        val refreshToken: String? = null,
        val scope: String? = null,
        val vehicleId: String? = null,
        val clientType: String? = null,
    ) : CiamTokenOutcome

    data class Failed(
        val category: ErrorCategory,
        val code: String? = null,
        val message: String? = null,
    ) : CiamTokenOutcome
}

/**
 * SEC-CIAM vehicle token client (DSN-CR-002 D-003 / D-008, SEC-CIAM-DSN-CR-006).
 *
 * Two interactions remain here: binding ticket (device token + ticket -> user token) and refresh
 * (device token + refresh token -> rotated pair). The device identity grant moved out with the
 * device identity seam (CR-004 §6): the unified security service owns it, or — inside the migration
 * window only — the `identityLocal` implementation of `acc-data-sec`.
 */
class CiamAuthClient(
    private val routes: CiamRouteConfig,
    private val clientId: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exchangeBindingTicket(ticket: String, deviceToken: String): CiamTokenOutcome {
        val body = buildJsonObject {
            put("grantType", GRANT_BINDING_TICKET)
            put("clientId", clientId)
            put("ticket", ticket)
        }.toString()
        return post(routes.tokenPath, body, bearer = deviceToken)
    }

    suspend fun refresh(refreshToken: String, deviceToken: String): CiamTokenOutcome {
        val body = buildJsonObject {
            put("grantType", "refresh_token")
            put("clientId", clientId)
            put("refreshToken", refreshToken)
        }.toString()
        return post(routes.refreshPath, body, bearer = deviceToken)
    }

    private suspend fun post(path: String, body: String, bearer: String?): CiamTokenOutcome {
        val url = routes.baseUrl.trimEnd('/') + path
        val builder = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        val result = try {
            client.newCall(builder.build()).execute().use { response ->
                TransportResult(statusCode = response.code, body = response.body?.string())
            }
        } catch (timeout: SocketTimeoutException) {
            TransportResult(statusCode = 0, failure = TransportFailure.TIMEOUT)
        } catch (error: IOException) {
            TransportResult(statusCode = 0, failure = TransportFailure.IO_ERROR)
        }
        SgwOutcomeClassifier.classifyTransport(result)?.let {
            return CiamTokenOutcome.Failed(it, code = "http-${result.statusCode}")
        }
        val envelope = SgwOutcomeClassifier.parse(result.body)
            ?: return CiamTokenOutcome.Failed(ErrorCategory.CONTRACT_REJECTED, code = "unparsable")
        SgwOutcomeClassifier.categoryOf(envelope.code)?.let {
            return CiamTokenOutcome.Failed(it, code = envelope.code, message = envelope.message)
        }
        val data = envelope.objectData
            ?: return CiamTokenOutcome.Failed(ErrorCategory.CONTRACT_REJECTED, code = "no-data")
        val accessToken = data.string("access_token")
            ?: return CiamTokenOutcome.Failed(ErrorCategory.CONTRACT_REJECTED, code = "no-access-token")
        val expiresIn = (data["expires_in"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toLongOrNull() ?: DEFAULT_ACCESS_TTL_SECONDS
        return CiamTokenOutcome.Issued(
            accessToken = accessToken,
            expiresInSeconds = expiresIn,
            refreshToken = data.string("refresh_token"),
            scope = data.string("scope"),
            vehicleId = data.string("vehicle_id"),
            clientType = data.string("client_type"),
        )
    }

    companion object {
        const val GRANT_BINDING_TICKET = "urn:openiov:params:oauth:grant-type:binding-ticket"
        private const val DEFAULT_ACCESS_TTL_SECONDS = 3_600L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** Reads `sub` out of a JWT payload without verifying it locally (the gateway verifies). */
internal fun accountIdOf(accessToken: String): String? {
    val payload = accessToken.split('.').getOrNull(1) ?: return null
    val decoded = runCatching {
        java.util.Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))
    }.getOrNull() ?: return null
    val obj = runCatching { Json.parseToJsonElement(String(decoded)) as? JsonObject }.getOrNull() ?: return null
    return obj.string("sub")?.takeIf { it.isNotBlank() }
}
