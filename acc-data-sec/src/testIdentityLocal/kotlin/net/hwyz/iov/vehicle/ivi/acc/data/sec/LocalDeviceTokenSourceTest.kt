package net.hwyz.iov.vehicle.ivi.acc.data.sec

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.DeviceProof
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.DeviceProofProvider
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Migration period device token source tests (DSN-CR-004 D-010, stage one).
 *
 * The point of this layer is that the rest of the app cannot tell how the token is obtained, while
 * the behaviour of the in process implementation stays exactly as before: reuse until the renewal
 * window, re-prove inside it, single flight, no negative caching and fail closed without a proof.
 */
class LocalDeviceTokenSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var grant: DeviceIdentityGrantClient
    private var now = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        grant = CiamDeviceIdentityGrantClient(
            routes = CiamRouteConfig(baseUrl = server.url("/").toString()),
            clientId = "ivi-capp",
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun source(
        proof: DeviceProofProvider = DeviceProofProvider {
            DeviceProof("device-1", "KEY_SIGNATURE", "c2ln", "bm9uY2U=")
        },
    ) = LocalDeviceTokenSource(
        proofProvider = proof,
        grantClient = grant,
        nowEpochMillis = { now },
    )

    private fun tokenResponse(accessTtl: Long = 900): MockResponse = MockResponse().setBody(
        """{"code":"000000","data":{"access_token":"device-token","token_type":"Bearer","expires_in":$accessTtl,"scope":"vehicle.activation"}}""",
    )

    @Test
    @DisplayName("D-011：设备序列号不可得时失败关闭，不生成证明也不发起 CIAM 调用")
    fun unavailableDeviceSerialFailsClosedWithoutProof() = runBlocking {
        var proofCalls = 0
        val tokens = LocalDeviceTokenSource(
            proofProvider = DeviceProofProvider {
                proofCalls++
                DeviceProof("device-1", "KEY_SIGNATURE", "c2ln", "bm9uY2U=")
            },
            grantClient = grant,
            deviceSerial = net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialProvider {
                net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOutcome.Unavailable(
                    net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialFailure.NOT_AVAILABLE,
                )
            },
            nowEpochMillis = { now },
        )

        val outcome = tokens.deviceToken()
        val unavailable = outcome as DeviceTokenOutcome.Unavailable
        assertEquals(ErrorCategory.CONTRACT_REJECTED, unavailable.category)
        assertEquals("device-id-unavailable", unavailable.reason)
        // No proof was created and no network call was made: the failure is closed, not degraded.
        assertEquals(0, proofCalls)
        assertEquals(0, server.requestCount)
    }

    @Test
    @DisplayName("令牌在续期窗口外复用，不重复签发")
    fun tokenIsReusedOutsideRenewWindow() = runBlocking {
        server.enqueue(tokenResponse())
        val tokens = source()

        assertEquals("device-token", available(tokens.deviceToken()))
        now += 5 * 60_000L
        assertEquals("device-token", available(tokens.deviceToken()))
        assertEquals(1, server.requestCount)
    }

    @Test
    @DisplayName("进入 5 分钟续期窗口后重新证明换取新令牌")
    fun tokenIsReissuedInsideRenewWindow() = runBlocking {
        server.enqueue(tokenResponse())
        val tokens = source()
        available(tokens.deviceToken())

        now += 11 * 60_000L
        server.enqueue(tokenResponse())
        assertEquals("device-token", available(tokens.deviceToken()))
        assertEquals(2, server.requestCount)
    }

    @Test
    @DisplayName("并发请求只产生一次云端签发（单飞）")
    fun concurrentRequestsIssueOneToken() = runBlocking {
        server.enqueue(tokenResponse())
        val tokens = source()

        val results = List(5) { async(Dispatchers.Default) { tokens.deviceToken() } }.awaitAll()
        results.forEach { assertEquals("device-token", available(it)) }
        assertEquals(1, server.requestCount)
    }

    @Test
    @DisplayName("失效后重新证明")
    fun invalidateForcesNewProof() = runBlocking {
        server.enqueue(tokenResponse())
        server.enqueue(tokenResponse())
        val tokens = source()

        available(tokens.deviceToken())
        tokens.invalidate()
        available(tokens.deviceToken())
        assertEquals(2, server.requestCount)
    }

    @Test
    @DisplayName("签发失败按分类返回且不缓存负结果")
    fun failureIsClassifiedAndNotCached() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(tokenResponse())
        val tokens = source()

        val failed = tokens.deviceToken()
        assertTrue(failed is DeviceTokenOutcome.Unavailable)
        assertEquals(ErrorCategory.RETRYABLE, (failed as DeviceTokenOutcome.Unavailable).category)
        assertTrue(failed.reason!!.startsWith("device-identity:"))

        // The next call must try again instead of replaying the failure.
        assertEquals("device-token", available(tokens.deviceToken()))
        assertEquals(2, server.requestCount)
    }

    @Test
    @DisplayName("没有设备证明时 fail-closed，不发起云端请求")
    fun missingProofFailsClosed() = runBlocking {
        val tokens = source(DeviceProofProvider.Unconfigured)

        val outcome = tokens.deviceToken()
        assertTrue(outcome is DeviceTokenOutcome.Unavailable)
        assertEquals(ErrorCategory.AUTH_REJECTED, (outcome as DeviceTokenOutcome.Unavailable).category)
        assertTrue(outcome.reason!!.startsWith("device-proof-unavailable"))
        assertEquals(0, server.requestCount)
    }

    private fun available(outcome: DeviceTokenOutcome): String {
        assertTrue(outcome is DeviceTokenOutcome.Available, "expected an available device token")
        return (outcome as DeviceTokenOutcome.Available).token.accessToken
    }
}
