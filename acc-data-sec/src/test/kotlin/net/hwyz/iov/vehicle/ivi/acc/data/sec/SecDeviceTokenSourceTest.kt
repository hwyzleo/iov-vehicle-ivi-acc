package net.hwyz.iov.vehicle.ivi.acc.data.sec

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Token lifecycle on the caller side (DSN-CR-004 D-008 / D-010).
 *
 * The service is faked: what matters here is that IVI-ACC reuses the token inside the renewal window,
 * never caches a failure and turns every service failure into the category the guide expects.
 */
class SecDeviceTokenSourceTest {

    private var now = 1_700_000_000_000L

    private fun token(ttlSeconds: Long = 900) = IssuedDeviceToken(
        accessToken = "device-token",
        scopes = listOf("vehicle.activation"),
        issuedAtEpochMillis = now,
        expiresAtEpochMillis = now + ttlSeconds * 1_000L,
    )

    private fun source(vararg responses: Any) = SecDeviceTokenSource(
        client = FakeSecServiceClient(responses.toMutableList()),
        nowEpochMillis = { now },
    )

    @Test
    @DisplayName("令牌在续期窗口外复用，不重复调用服务")
    fun tokenIsReusedOutsideRenewWindow() = runBlocking {
        val client = FakeSecServiceClient(mutableListOf(token()))
        val tokens = SecDeviceTokenSource(client, nowEpochMillis = { now })

        assertEquals("device-token", available(tokens.deviceToken()))
        now += 5 * 60_000L
        assertEquals("device-token", available(tokens.deviceToken()))
        assertEquals(1, client.calls)
    }

    @Test
    @DisplayName("进入 5 分钟续期窗口后重新向服务申请")
    fun tokenIsReissuedInsideRenewWindow() = runBlocking {
        val client = FakeSecServiceClient(mutableListOf(token(), token()))
        val tokens = SecDeviceTokenSource(client, nowEpochMillis = { now })
        available(tokens.deviceToken())

        now += 11 * 60_000L
        assertEquals("device-token", available(tokens.deviceToken()))
        assertEquals(2, client.calls)
    }

    @Test
    @DisplayName("服务未开通映射为鉴权拒绝，且不缓存负结果")
    fun notProvisionedMapsToAuthenticationRejection() = runBlocking {
        val client = FakeSecServiceClient(
            mutableListOf(
                SecRemoteException(SecFailureKind.UNKNOWN, SecErrorCodes.NOT_PROVISIONED),
                token(),
            ),
        )
        val tokens = SecDeviceTokenSource(client, nowEpochMillis = { now })

        val failed = tokens.deviceToken()
        assertTrue(failed is DeviceTokenOutcome.Unavailable)
        assertEquals(ErrorCategory.AUTH_REJECTED, (failed as DeviceTokenOutcome.Unavailable).category)
        assertEquals("sec:1", failed.reason)

        // The next call must ask again instead of replaying the failure.
        assertEquals("device-token", available(tokens.deviceToken()))
        assertEquals(2, client.calls)
    }

    @Test
    @DisplayName("限流与进程死亡按可重试返回")
    fun retryableFailuresStayRetryable() = runBlocking {
        val rateLimited = source(SecRemoteException(SecFailureKind.UNKNOWN, SecErrorCodes.RATE_LIMITED)).deviceToken()
        assertEquals(ErrorCategory.RETRYABLE, (rateLimited as DeviceTokenOutcome.Unavailable).category)

        val died = source(SecRemoteException(SecFailureKind.PROCESS_DIED)).deviceToken()
        assertEquals(ErrorCategory.RETRYABLE, (died as DeviceTokenOutcome.Unavailable).category)
        assertEquals("sec:PROCESS_DIED", died.reason)
    }

    @Test
    @DisplayName("服务未安装是终态，不当作网络问题")
    fun missingServiceIsTerminal() = runBlocking {
        val outcome = source(SecRemoteException(SecFailureKind.SERVICE_MISSING)).deviceToken()
        assertEquals(ErrorCategory.CONTRACT_REJECTED, (outcome as DeviceTokenOutcome.Unavailable).category)
    }

    @Test
    @DisplayName("失效清空本地缓存并通知服务")
    fun invalidateClearsCacheAndForwards() = runBlocking {
        val client = FakeSecServiceClient(mutableListOf(token(), token()))
        val tokens = SecDeviceTokenSource(client, nowEpochMillis = { now })
        available(tokens.deviceToken())

        tokens.invalidate()
        assertEquals(1, client.invalidations)
        available(tokens.deviceToken())
        assertEquals(2, client.calls)
    }

    @Test
    @DisplayName("失效通知失败不影响本地已清空的状态")
    fun invalidateFailureDoesNotThrow() = runBlocking {
        val client = FakeSecServiceClient(mutableListOf(token(), SecRemoteException(SecFailureKind.PROCESS_DIED)))
        val tokens = SecDeviceTokenSource(client, nowEpochMillis = { now })
        available(tokens.deviceToken())

        tokens.invalidate()
        assertEquals(1, client.invalidations)
    }

    private fun available(outcome: DeviceTokenOutcome): String {
        assertTrue(outcome is DeviceTokenOutcome.Available, "expected an available device token")
        return (outcome as DeviceTokenOutcome.Available).token.accessToken
    }

    private class FakeSecServiceClient(
        private val responses: MutableList<Any>,
    ) : SecServiceClient {
        var calls = 0
            private set
        var invalidations = 0
            private set

        override suspend fun deviceToken(scopes: Array<String>): IssuedDeviceToken {
            calls++
            return when (val next = responses.removeAt(0)) {
                is IssuedDeviceToken -> next
                else -> throw next as SecRemoteException
            }
        }

        override suspend fun deviceId(): String? = "device-1"

        override suspend fun certificateNotAfterEpochMillis(): Long? = null

        override suspend fun invalidateDeviceToken() {
            invalidations++
            val next = responses.firstOrNull()
            if (next is SecRemoteException) {
                responses.removeAt(0)
                throw next
            }
        }
    }
}
