package net.hwyz.iov.vehicle.ivi.acc.data.sec

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.SgwOutcomeClassifier
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamTokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.DeviceProof
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.string
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportFailure
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Device identity grant (proof -> device token) used by the migration period LOCAL implementation.
 *
 * CR-004 §6 keeps the device identity grant out of `acc-data-cloud`: that module only exchanges
 * login tickets and refreshes user tokens now. This class is therefore the only remaining caller of
 * `urn:openiov:params:oauth:grant-type:device-identity`, and it disappears together with the
 * `identityLocal` flavour in stage three.
 */
fun interface DeviceIdentityGrantClient {
    suspend fun requestDeviceToken(proof: DeviceProof): CiamTokenOutcome
}

/** SEC-CIAM client of the device identity grant. It never touches user tickets. */
class CiamDeviceIdentityGrantClient(
    private val routes: CiamRouteConfig,
    private val clientId: String,
    private val deviceScope: String = DEFAULT_DEVICE_SCOPE,
    private val client: OkHttpClient = defaultClient(),
) : DeviceIdentityGrantClient {

    override suspend fun requestDeviceToken(proof: DeviceProof): CiamTokenOutcome {
        val body = buildJsonObject {
            put("grantType", GRANT_DEVICE_IDENTITY)
            put("clientId", clientId)
            put("deviceId", proof.deviceId)
            put("proofType", proof.proofType)
            put("proof", proof.proof)
            put("nonce", proof.nonce)
            put("scope", deviceScope)
        }.toString()
        val url = routes.baseUrl.trimEnd('/') + routes.tokenPath
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val result = try {
            client.newCall(request).execute().use { response ->
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
            ?.content?.toLongOrNull() ?: DEFAULT_DEVICE_TTL_SECONDS
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
        const val GRANT_DEVICE_IDENTITY = "urn:openiov:params:oauth:grant-type:device-identity"
        const val DEFAULT_DEVICE_SCOPE = "vehicle.activation vehicle.privacy.report"
        private const val DEFAULT_DEVICE_TTL_SECONDS = 3_600L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
