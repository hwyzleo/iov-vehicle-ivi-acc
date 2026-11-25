package net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam

import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenRef
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenSource
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.log.RecordingDiagnosticsLogger
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
 * Device tokens now arrive through the injected [DeviceTokenSource] port (DSN-CR-004 D-008 / D-010),
 * so this suite covers only the user session: ticket -> user token, silent refresh, revocation, and
 * the rule that a device token failure never clears the refresh token. Device identity behaviour
 * (proof, single flight, fail closed) is covered by `acc-data-sec` flavour tests.
 */
class IviTokenProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySecureStore
    private lateinit var auth: CiamAuthClient
    private var now = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = InMemorySecureStore()
        auth = CiamAuthClient(
            routes = CiamRouteConfig(baseUrl = server.url("/").toString()),
            clientId = "ivi-capp",
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(
        deviceTokens: DeviceTokenSource = StubDeviceTokenSource(
            DeviceTokenOutcome.Available(DeviceTokenRef(DEVICE_TOKEN, now + 15 * 60_000L)),
        ),
        logger: DiagnosticsLogger? = null,
    ) = IviTokenProvider(
        authClient = auth,
        deviceTokens = deviceTokens,
        secureStore = store,
        nowEpochMillis = { now },
        logger = logger,
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
    @DisplayName("无用户会话时直接使用设备令牌作为 Bearer，不发起用户域请求")
    fun deviceTokenIsUsedAsBearer() = runBlocking {
        val tokens = provider()

        assertEquals(DEVICE_TOKEN, (tokens.authorizationToken() as TokenOutcome.Available).token)
        assertEquals(0, server.requestCount)
    }

    @Test
    @DisplayName("票据换用户令牌：返回账号、落盘 refresh 并切换 Bearer")
    fun ticketExchangeIssuesUserToken() = runBlocking {
        server.enqueue(tokenResponse(refresh = "refresh-1")) // binding ticket exchange
        val tokens = provider()

        val outcome = tokens.loginWithTicket("one-time")
        assertEquals(LoginOutcome.Success(AccountRef("account-001")), outcome)

        val exchange = server.takeRequest()
        val exchangeBody = exchange.body.readUtf8()
        assertTrue(exchangeBody.contains("\"ticket\":\"one-time\""))
        assertTrue(exchangeBody.contains(BINDING_GRANT))
        // The exchange is authorized by the device token, not by the user token.
        assertEquals("Bearer $DEVICE_TOKEN", exchange.getHeader("Authorization"))

        assertEquals("refresh-1", store.sealed("ivi.refresh"))
        assertEquals(AccountRef("account-001"), tokens.signedInAccount())
        // The user token is now the bearer used for business calls.
        assertEquals(jwt("account-001"), tokenOf(tokens))
    }

    @Test
    @DisplayName("票据兑换失败按分类返回且不落盘 refresh")
    fun ticketFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":"701333","message":"票据已消费"}"""))
        val tokens = provider()
        assertEquals(LoginOutcome.Failed(ErrorCategory.CONTRACT_REJECTED), tokens.loginWithTicket("consumed"))
        assertNull(store.sealed("ivi.refresh"))
        assertNull(tokens.signedInAccount())
    }

    @Test
    @DisplayName("票据兑换失败可诊断：日志含业务码与脱敏文案，且不含票据与令牌原文")
    fun ticketFailureIsDiagnosable() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":"701330","message":"绑定票据\n换令牌能力未启用"}"""),
        )
        val logger = RecordingDiagnosticsLogger()
        val tokens = provider(logger = logger)

        tokens.loginWithTicket("ticket-secret-value")

        val line = logger.entries.single { it.startsWith("token.failed") }
        assertTrue(line.contains("code=701330"), line)
        // 云端文案去换行后记录，便于现场判定是开关未开还是客户端未登记。
        assertTrue(line.contains("message=绑定票据 换令牌能力未启用"), line)
        assertTrue(line.contains("reason=binding-ticket"), line)
        assertFalse(line.contains("ticket-secret-value"), line)
        assertFalse(line.contains(DEVICE_TOKEN), line)
    }

    @Test
    @DisplayName("access 临近到期时静默续期并轮换 refresh")
    fun silentRenewal() = runBlocking {
        server.enqueue(tokenResponse(refresh = "refresh-1"))
        val tokens = provider()
        tokens.loginWithTicket("ticket-1")
        server.takeRequest()

        // Move into the renewal window of the 60 minute access token.
        now += 55 * 60_000L
        server.enqueue(tokenResponse(refresh = "refresh-2"))
        assertTrue(tokenOf(tokens).isNotEmpty())

        val refreshRequest = server.takeRequest()
        assertEquals("/api/open/oauth/v1/token", refreshRequest.path)
        val body = refreshRequest.body.readUtf8()
        assertTrue(body.contains("\"grantType\":\"refresh_token\""))
        assertTrue(body.contains("\"refreshToken\":\"refresh-1\""))
        // Renewal is authorized by the device token taken from the port.
        assertEquals("Bearer $DEVICE_TOKEN", refreshRequest.getHeader("Authorization"))
        assertEquals("refresh-2", store.sealed("ivi.refresh"))
    }

    @Test
    @DisplayName("续期被拒（令牌族撤销）时清理本地凭据")
    fun revokedSessionClearsLocalCredentials() = runBlocking {
        server.enqueue(tokenResponse(refresh = "refresh-1"))
        val tokens = provider()
        tokens.loginWithTicket("ticket-1")
        server.takeRequest()

        now += 55 * 60_000L
        server.enqueue(MockResponse().setBody("""{"code":"701304","message":"令牌无效"}"""))
        assertTrue(tokenOf(tokens).isNotEmpty())
        assertNull(store.sealed("ivi.refresh"))
    }

    @Test
    @DisplayName("设备令牌来自注入的来源，令牌提供者不再自行签发")
    fun deviceTokenComesFromInjectedSource() = runBlocking {
        val source = StubDeviceTokenSource(
            DeviceTokenOutcome.Available(DeviceTokenRef("source-token", now + 15 * 60_000L)),
        )
        val tokens = provider(source)

        assertEquals("source-token", tokenOf(tokens))
        tokens.invalidateDeviceToken()
        assertEquals(1, source.invalidations)
        assertEquals(0, server.requestCount)
    }

    @Test
    @DisplayName("设备令牌不可用时按分类透出，不伪造令牌也不清 refresh")
    fun deviceTokenUnavailableIsClassified() = runBlocking {
        server.enqueue(tokenResponse(refresh = "refresh-1"))
        val tokens = provider()
        tokens.loginWithTicket("ticket-1")

        // The user session is valid, so the failure must come from the device token source only.
        now += 55 * 60_000L
        val failing = IviTokenProvider(
            authClient = auth,
            deviceTokens = StubDeviceTokenSource(
                DeviceTokenOutcome.Unavailable(ErrorCategory.CONTRACT_REJECTED, "sec:1"),
            ),
            secureStore = store,
            nowEpochMillis = { now },
        )
        val outcome = failing.authorizationToken()
        assertEquals(ErrorCategory.CONTRACT_REJECTED, (outcome as TokenOutcome.Unavailable).category)
        assertEquals("sec:1", outcome.reason)
        assertEquals("refresh-1", store.sealed("ivi.refresh"))
    }

    private suspend fun tokenOf(tokens: IviTokenProvider): String =
        (tokens.authorizationToken() as TokenOutcome.Available).token

    private companion object {
        const val DEVICE_TOKEN = "device-token-from-port"
        const val BINDING_GRANT = "urn:openiov:params:oauth:grant-type:binding-ticket"
    }
}

/** Device token source stub: the port is the only seam the user session layer may rely on. */
internal class StubDeviceTokenSource(
    private val outcome: DeviceTokenOutcome,
) : DeviceTokenSource {
    var invalidations: Int = 0
        private set

    override suspend fun deviceToken(): DeviceTokenOutcome = outcome

    override suspend fun invalidate() {
        invalidations++
    }
}

/** In memory stand-in for the keystore backed store. */
internal class InMemorySecureStore : SecureMaterialStore {
    private val entries = mutableMapOf<String, Pair<String, Long>>()

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
