package net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam

import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.store.SecureMaterialStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Vehicle credential lifecycle tests (SEC-CIAM-DSN-CR-006, DSN-CR-003 D-005).
 *
 * Covers: device proof -> device token, ticket -> user token, silent refresh, fail-closed paths and
 * the rule that a rejected token family clears local credentials.
 */
class IviTokenProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySecureStore
    private var now = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = InMemorySecureStore()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(proof: DeviceProofProvider = DeviceProofProvider {
        DeviceProof("device-1", "KEY_SIGNATURE", "c2ln", "bm9uY2U=")
    }) = IviTokenProvider(
        authClient = CiamAuthClient(
            routes = CiamRouteConfig(baseUrl = server.url("/").toString()),
            clientId = "ivi-capp",
        ),
        proofProvider = proof,
        secureStore = store,
        nowEpochMillis = { now },
    )

    private fun jwt(sub: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"$sub","client_type":"IVI"}""".toByteArray())
        return "eyJhbGciOiJSUzI1NiJ9.$payload.c2ln"
    }

    private fun tokenResponse(
        accessTtl: Long = 3_600,
        refresh: String? = null,
        sub: String = "account-001",
    ): MockResponse {
        val refreshField = refresh?.let { ""","refresh_token":"$it"""" } ?: ""
        return MockResponse().setBody(
            """{"code":"000000","data":{"access_token":"${jwt(sub)}","token_type":"Bearer","expires_in":$accessTtl,"scope":"ivi.user.session","vehicle_id":"LVINACC0000000001","client_type":"IVI"$refreshField}}""",
        )
    }

    @Test
    @DisplayName("设备证明换设备令牌，作为云端调用的 Bearer")
    fun deviceTokenIsUsedAsBearer() = runBlocking {
        server.enqueue(tokenResponse())
        val tokens = provider()
        assertTrue(tokens.authorizationToken()!!.startsWith("eyJhbGciOiJSUzI1NiJ9."))

        val request = server.takeRequest()
        assertEquals("/api/ivi/auth/v1/token", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("urn:openiov:params:oauth:grant-type:device-identity"))
        assertTrue(body.contains("\"proofType\":\"KEY_SIGNATURE\""))
        assertTrue(body.contains("\"clientId\":\"ivi-capp\""))
    }

    @Test
    @DisplayName("票据换用户令牌：返回账号、落盘 refresh 并切换 Bearer")
    fun ticketExchangeIssuesUserToken() = runBlocking {
        server.enqueue(tokenResponse(sub = "device-account")) // device token
        server.enqueue(tokenResponse(refresh = "refresh-1")) // binding ticket exchange
        val tokens = provider()

        val outcome = tokens.loginWithTicket("one-time")
        assertEquals(LoginOutcome.Success(AccountRef("account-001")), outcome)

        val deviceRequest = server.takeRequest()
        assertTrue(deviceRequest.body.readUtf8().contains(DEVICE_GRANT))
        val exchange = server.takeRequest()
        val exchangeBody = exchange.body.readUtf8()
        assertTrue(exchangeBody.contains("\"ticket\":\"one-time\""))
        assertTrue(exchangeBody.contains(BINDING_GRANT))
        // The exchange is authorized by the device token, not by the user token.
        assertEquals("Bearer ${jwt("device-account")}", exchange.getHeader("Authorization"))

        assertEquals("refresh-1", store.sealed("ivi.refresh"))
        assertEquals(AccountRef("account-001"), tokens.signedInAccount())
        // The user token is now the bearer used for business calls.
        assertEquals(jwt("account-001"), tokens.authorizationToken())
    }

    @Test
    @DisplayName("票据兑换失败按分类返回且不落盘 refresh")
    fun ticketFailure() = runBlocking {
        server.enqueue(tokenResponse())
        server.enqueue(MockResponse().setBody("""{"code":"701333","message":"票据已消费"}"""))
        val tokens = provider()
        assertEquals(LoginOutcome.Failed(ErrorCategory.CONTRACT_REJECTED), tokens.loginWithTicket("consumed"))
        assertNull(store.sealed("ivi.refresh"))
        assertNull(tokens.signedInAccount())
    }

    @Test
    @DisplayName("access 临近到期时静默续期并轮换 refresh")
    fun silentRenewal() = runBlocking {
        server.enqueue(tokenResponse())
        server.enqueue(tokenResponse(refresh = "refresh-1"))
        val tokens = provider()
        tokens.loginWithTicket("ticket-1")
        server.takeRequest()
        server.takeRequest()

        // Move into the renewal window of the 60 minute access token.
        now += 55 * 60_000L
        // The device token has also expired: the provider re-proves before renewing the user token.
        server.enqueue(tokenResponse(sub = "device-account"))
        server.enqueue(tokenResponse(refresh = "refresh-2"))
        assertTrue(tokens.authorizationToken()!!.isNotEmpty())

        server.takeRequest() // device token re-proof
        val refreshRequest = server.takeRequest()
        assertEquals("/api/open/oauth/v1/token", refreshRequest.path)
        val body = refreshRequest.body.readUtf8()
        assertTrue(body.contains("\"grantType\":\"refresh_token\""))
        assertTrue(body.contains("\"refreshToken\":\"refresh-1\""))
        assertTrue(refreshRequest.getHeader("Authorization")!!.startsWith("Bearer "))
        assertEquals("refresh-2", store.sealed("ivi.refresh"))
    }

    @Test
    @DisplayName("续期被拒（令牌族撤销）时清理本地凭据")
    fun revokedSessionClearsLocalCredentials() = runBlocking {
        server.enqueue(tokenResponse())
        server.enqueue(tokenResponse(refresh = "refresh-1"))
        val tokens = provider()
        tokens.loginWithTicket("ticket-1")
        server.takeRequest()
        server.takeRequest()

        now += 55 * 60_000L
        server.enqueue(tokenResponse(sub = "device-account"))
        server.enqueue(MockResponse().setBody("""{"code":"701304","message":"令牌无效"}"""))
        assertTrue(tokens.authorizationToken()!!.isNotEmpty())
        assertNull(store.sealed("ivi.refresh"))
    }

    @Test
    @DisplayName("未配置设备证明时 fail-closed，不发起请求")
    fun missingProofFailsClosed() = runBlocking {
        val tokens = provider(DeviceProofProvider.Unconfigured)
        assertNull(tokens.authorizationToken())
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val DEVICE_GRANT = "urn:openiov:params:oauth:grant-type:device-identity"
        const val BINDING_GRANT = "urn:openiov:params:oauth:grant-type:binding-ticket"
    }
}

/** Plain in memory sealed store for host tests. */
private class InMemorySecureStore : SecureMaterialStore {
    private val entries = linkedMapOf<String, Pair<String, Long>>()

    override suspend fun putSealed(key: String, secret: String, expiresAtEpochMillis: Long) {
        entries[key] = secret to expiresAtEpochMillis
    }

    override suspend fun sealed(key: String): String? = entries[key]?.first

    override suspend fun clear(key: String) {
        entries.remove(key)
    }

    override suspend fun clearAll() {
        entries.clear()
    }
}
