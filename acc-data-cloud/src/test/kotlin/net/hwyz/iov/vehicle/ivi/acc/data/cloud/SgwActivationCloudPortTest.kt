package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.TokenProvider
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.JsonCloudPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudOperation
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.OkHttpSgwTransport
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementLoadOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginTicketOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginTicketRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordPayload
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contract tests for the frozen vehicle side activation API (DSN-CR-002 D-003).
 *
 * Every test asserts what leaves the car (path, method, bearer token, body) and how the response
 * envelope is translated into a domain outcome; business codes are read from the body, because the
 * cloud returns HTTP 200 for business failures.
 */
class SgwActivationCloudPortTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpSgwTransport
    private val vehicle = VehicleRef("LVINACC0000000001")
    private val tokens = TokenProvider { "device-token" }
    private val codec = JsonCloudPayloadCodec()

    private val references = listOf(
        AgreementReference(AgreementType.PRIVACY_POLICY, "1.0", "REV-P", "hash-p", "zh-CN"),
        AgreementReference(AgreementType.USER_AGREEMENT, "1.0", "REV-U", "hash-u", "zh-CN"),
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        transport = OkHttpSgwTransport(
            SgwRouteConfig(baseUrl = server.url("/").toString(), paths = SgwRouteConfig.activationPaths),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun port(client: OkHttpClient? = null): SgwActivationCloudPort {
        val effective = client?.let { OkHttpSgwTransport(
            SgwRouteConfig(baseUrl = server.url("/").toString(), paths = SgwRouteConfig.activationPaths),
            it,
        ) } ?: transport
        return SgwActivationCloudPort(effective, codec, tokens)
    }

    private fun agreements(count: Int = 2): SgwAgreementGateway = SgwAgreementGateway(
        transport = transport,
        codec = codec,
        tokens = tokens,
        languageProvider = { "zh-CN" },
    ).also {
        val docs = buildString {
            append("""{"agreementType":"PRIVACY_POLICY","version":"1.0","revisionId":"REV-P","language":"zh-CN","title":"隐私政策","contentHash":"hash-p","content":"<p/>"}""")
            if (count > 1) {
                append(",")
                append("""{"agreementType":"USER_AGREEMENT","version":"1.0","revisionId":"REV-U","language":"zh-CN","title":"用户协议","contentHash":"hash-u","content":"<p/>"}""")
            }
        }
        server.enqueue(
            MockResponse().setBody(
                """{"code":"000000","message":"ok","data":{"bundleId":"B-1","language":"zh-CN","agreements":[$docs]}}""",
            ),
        )
    }

    @Test
    @DisplayName("协议：GET 携带语言与 Bearer，解码两项文档")
    fun loadAgreements() = runBlocking {
        val gateway = agreements()
        val outcome = gateway.loadPublishedDocuments()
        val loaded = assertIs<AgreementLoadOutcome.Loaded>(outcome)
        assertEquals(2, loaded.bundle.documents.size)
        assertTrue(loaded.bundle.isComplete)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/vehicle/activation/v1/agreements", request.path?.substringBefore('?'))
        assertTrue(request.path!!.contains("language=zh-CN"))
        assertEquals("Bearer device-token", request.getHeader("Authorization"))
    }

    @Test
    @DisplayName("协议：缺项或不可解码时拒绝，不放行")
    fun agreementsIncomplete() = runBlocking {
        val gateway = agreements(count = 1)
        val outcome = gateway.loadPublishedDocuments()
        val loaded = assertIs<AgreementLoadOutcome.Loaded>(outcome)
        assertEquals(false, loaded.bundle.isComplete)
    }

    @Test
    @DisplayName("会话申请：POST 两项协议证据 + 幂等键，返回会话")
    fun requestSession() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"000000","data":{"sessionId":"SES-1","qrToken":"qr-secret","expiresAt":"2026-11-22T10:00:00Z"}}""",
            ),
        )
        val outcome = port().requestSession(
            SessionRequestInput(vehicle, references, flowGeneration = 1L, requestId = "req-1"),
        )
        val created = assertIs<SessionRequestOutcome.Created>(outcome)
        assertEquals("SES-1", created.session.sessionId)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"agreementType\":\"PRIVACY_POLICY\""))
        assertTrue(body.contains("\"agreed\":true"))
        assertTrue(body.contains("\"revisionId\":\"REV-U\""))
        assertEquals("req-1", request.getHeader("Idempotency-Key"))
    }

    @Test
    @DisplayName("业务错误码：401108 可重试，401111 契约拒绝")
    fun businessCodes() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":"401108","message":"依赖不可用"}"""))
        assertEquals(
            SessionRequestOutcome.Failed(ErrorCategory.RETRYABLE),
            port().requestSession(SessionRequestInput(vehicle, references, 1L, "req-2")),
        )
        server.enqueue(MockResponse().setBody("""{"code":"401111","message":"协议未同意"}"""))
        assertEquals(
            SessionRequestOutcome.Failed(ErrorCategory.CONTRACT_REJECTED),
            port().requestSession(SessionRequestInput(vehicle, references, 1L, "req-3")),
        )
    }

    @Test
    @DisplayName("会话结果：GET 路径、状态映射、过期错误码")
    fun querySession() = runBlocking {
        val session = SessionRef("SES-1", "qr", 0L, Long.MAX_VALUE)
        val port = port()
        server.enqueue(
            MockResponse().setBody(
                """{"code":"000000","data":{"sessionId":"SES-1","state":"BOUND","bindingOperationId":"OP-1","vin":"LVINACC0000000001"}}""",
            ),
        )
        val bound = assertIs<SessionQueryOutcome.Observed>(port.querySession(SessionQueryInput(vehicle, session, 1L)))
        assertEquals(SessionObservation.BoundConfirmed("OP-1"), bound.observation)

        server.enqueue(
            MockResponse().setBody("""{"code":"000000","data":{"sessionId":"SES-1","state":"EXPIRED"}}"""),
        )
        val expired = assertIs<SessionQueryOutcome.Observed>(port.querySession(SessionQueryInput(vehicle, session, 1L)))
        assertEquals(SessionObservation.Expired, expired.observation)

        assertEquals(
            "/api/vehicle/activation/v1/session/SES-1/result",
            server.takeRequest().path,
        )

        server.enqueue(MockResponse().setBody("""{"code":"401102","message":"会话已过期"}"""))
        assertEquals(
            SessionQueryOutcome.Failed(ErrorCategory.EXPIRED),
            port.querySession(SessionQueryInput(vehicle, session, 1L)),
        )
    }

    @Test
    @DisplayName("超时表示结果未知，不伪装成功")
    fun timeoutIsUnknown() = runBlocking {
        val slowClient = OkHttpClient.Builder().readTimeout(80, TimeUnit.MILLISECONDS).build()
        server.enqueue(
            MockResponse().setBody("""{"code":"000000","data":{"sessionId":"SES-1","state":"BOUND"}}""")
                .setBodyDelay(400, TimeUnit.MILLISECONDS),
        )
        val outcome = port(slowClient).querySession(
            SessionQueryInput(vehicle, SessionRef("SES-1", "qr", 0L, Long.MAX_VALUE), 1L),
        )
        assertEquals(SessionQueryOutcome.Failed(ErrorCategory.RESULT_UNKNOWN), outcome)
    }

    @Test
    @DisplayName("跳过：reportId 作为幂等键，accepted=false 视为契约拒绝")
    fun submitSkip() = runBlocking {
        val port = port()
        server.enqueue(MockResponse().setBody("""{"code":"000000","data":{"reportId":"skip-1","accepted":true}}"""))
        assertEquals(
            SkipSubmitOutcome.Acknowledged,
            port.submitSkip(SkipRequestInput(vehicle, "skip-1", references, "SES-1")),
        )
        val request = server.takeRequest()
        assertEquals("skip-1", request.getHeader("Idempotency-Key"))
        assertTrue(request.body.readUtf8().contains("\"sourceSessionId\":\"SES-1\""))

        server.enqueue(MockResponse().setBody("""{"code":"000000","data":{"reportId":"skip-2","accepted":false}}"""))
        assertEquals(
            SkipSubmitOutcome.Failed(ErrorCategory.CONTRACT_REJECTED),
            port.submitSkip(SkipRequestInput(vehicle, "skip-2", references, null)),
        )
    }

    @Test
    @DisplayName("登录票据：POST 会话路径，票据/到期解码；401115 契约拒绝")
    fun issueLoginTicket() = runBlocking {
        val port = port()
        val session = SessionRef("SES-1", "qr", 0L, Long.MAX_VALUE)
        server.enqueue(
            MockResponse().setBody(
                """{"code":"000000","data":{"ticket":"one-time","expiresAt":"2026-11-22T10:03:00Z","sessionId":"SES-1","relationId":"REL-1"}}""",
            ),
        )
        val outcome = port.issueLoginTicket(LoginTicketRequestInput(vehicle, session, "OP-1"))
        val ticket = assertIs<LoginTicketOutcome.Ticket>(outcome).ticket
        assertEquals("one-time", ticket.ticket)
        assertEquals("/api/vehicle/activation/v1/session/SES-1/login-ticket", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"code":"401115","message":"票据不可用"}"""))
        assertEquals(
            LoginTicketOutcome.Failed(ErrorCategory.CONTRACT_REJECTED),
            port.issueLoginTicket(LoginTicketRequestInput(vehicle, session, "OP-1")),
        )
    }

    @Test
    @DisplayName("回执：登录结果携带协议证据，隐私上报携带版本")
    fun reports() = runBlocking {
        val port = port()
        server.enqueue(MockResponse().setBody("""{"code":"000000","data":{"reportId":"login-1","accepted":true}}"""))
        val loginRecord = PendingRecord.create(
            id = "login-1",
            type = RecordType.LOGIN_RESULT,
            scopeKey = "account-001|LVINACC0000000001",
            payload = RecordPayload.LoginResult(
                vin = vehicle.vin,
                accountId = "account-001",
                sessionId = "SES-1",
                bindingOperationId = "OP-1",
                loggedInAtEpochMillis = 1_700_000_000_000L,
                loginStatus = "SUCCEEDED",
                agreements = references,
            ),
            createdAtEpochMillis = 1_700_000_000_000L,
            userActionAtEpochMillis = 1_700_000_000_000L,
        )
        assertEquals(RecordReportOutcome.Acknowledged, port.reportRecord(RecordReportInput(loginRecord)))
        val loginRequest = server.takeRequest()
        assertEquals("/api/vehicle/activation/v1/session/ivi-login-result", loginRequest.path)
        val loginBody = loginRequest.body.readUtf8()
        assertTrue(loginBody.contains("\"reportId\":\"login-1\""))
        assertTrue(loginBody.contains("\"loginStatus\":\"SUCCEEDED\""))
        assertTrue(loginBody.contains("\"agreementType\":\"USER_AGREEMENT\""))

        server.enqueue(MockResponse().setBody("""{"code":"000000","data":{"reportId":"privacy-1","accepted":true}}"""))
        val privacyRecord = PendingRecord.create(
            id = "privacy-1",
            type = RecordType.PRIVACY_DECISION,
            scopeKey = "account-001|LVINACC0000000001",
            payload = RecordPayload.PrivacyDecisionRecord(
                vin = vehicle.vin,
                accountId = "account-001",
                choice = PrivacyChoice.GRANTED,
                items = listOf("PERSONALIZED_CONTENT"),
                decidedAtEpochMillis = 1_700_000_000_500L,
            ),
            createdAtEpochMillis = 1_700_000_000_500L,
            userActionAtEpochMillis = 1_700_000_000_500L,
        )
        assertEquals(RecordReportOutcome.Acknowledged, port.reportRecord(RecordReportInput(privacyRecord)))
        val privacyBody = server.takeRequest().body.readUtf8()
        assertTrue(privacyBody.contains("\"authorizationItem\":\"PERSONALIZED_CONTENT\""))
        assertTrue(privacyBody.contains("\"state\":\"GRANTED\""))
        assertTrue(privacyBody.contains("\"version\":1700000000500"))
    }

    @Test
    @DisplayName("协议同意记录随登录结果上报，不单独打接口")
    fun consentRecordIsNotReportedAlone() = runBlocking {
        val record = PendingRecord.create(
            id = "agc-1",
            type = RecordType.AGREEMENT_CONSENT,
            scopeKey = "account-001|LVINACC0000000001",
            payload = RecordPayload.AgreementConsent(
                vin = vehicle.vin,
                accountId = "account-001",
                agreements = references,
                sessionId = "SES-1",
                bindingOperationId = "OP-1",
                agreedAtEpochMillis = 1_700_000_000_000L,
            ),
            createdAtEpochMillis = 1_700_000_000_000L,
            userActionAtEpochMillis = 1_700_000_000_000L,
        )
        assertEquals(RecordReportOutcome.Acknowledged, port().reportRecord(RecordReportInput(record)))
        assertEquals(0, server.requestCount)
    }

    @Test
    @DisplayName("无 IAM 令牌时 fail-closed：不发起匿名请求，上报鉴权失败")
    fun missingTokenFailsClosed() = runBlocking {
        val unauthenticated = SgwActivationCloudPort(transport, codec, TokenProvider.None)
        assertEquals(
            SessionRequestOutcome.Failed(ErrorCategory.AUTH_REJECTED),
            unauthenticated.requestSession(SessionRequestInput(vehicle, references, 1L, "req-nf")),
        )
        val gateway = SgwAgreementGateway(
            transport = transport,
            codec = codec,
            tokens = TokenProvider.None,
            languageProvider = { "zh-CN" },
        )
        assertEquals(
            AgreementLoadOutcome.Failed(ErrorCategory.AUTH_REJECTED),
            gateway.loadPublishedDocuments(),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    @DisplayName("路由未配置时快速失败，不发起请求")
    fun unconfiguredRoute() = runBlocking {
        val broken = SgwActivationCloudPort(
            OkHttpSgwTransport(SgwRouteConfig(server.url("/").toString(), emptyMap())),
            codec,
            tokens,
        )
        assertEquals(
            SessionRequestOutcome.Failed(ErrorCategory.RETRYABLE),
            broken.requestSession(SessionRequestInput(vehicle, references, 1L, "req-9")),
        )
        assertEquals(0, server.requestCount)
    }
}
