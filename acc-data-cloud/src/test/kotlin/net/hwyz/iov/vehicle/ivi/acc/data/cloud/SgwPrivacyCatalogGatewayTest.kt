package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.TokenProvider
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.OkHttpSgwTransport
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D-013 / G-1 contract tests for the privacy authorization catalogue.
 *
 * What leaves the car (path, `Accept-Language`, bearer token) and how the catalogue is decoded is
 * asserted here; the catalogue is cloud authoritative, so any missing field must reject the whole
 * payload instead of producing a smaller catalogue.
 */
class SgwPrivacyCatalogGatewayTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun gateway(
        tokens: TokenProvider = TokenProvider.fixed("device-token"),
        now: () -> Long = { 1_700_000_000_000L },
    ): SgwPrivacyCatalogGateway = SgwPrivacyCatalogGateway(
        transport = OkHttpSgwTransport(
            SgwRouteConfig(baseUrl = server.url("/").toString(), paths = SgwRouteConfig.activationPaths),
        ),
        tokens = tokens,
        languageProvider = { "zh-CN" },
        nowEpochMillis = now,
    )

    private fun catalogueBody(): String = """
        {"code":"000000","message":"ok","data":{"catalogVersion":"v1","items":[
          {"authorizationItem":"GPS_LOCATION","displayName":"位置信息","required":false},
          {"authorizationItem":"PERSONALIZED_CONTENT","displayName":"个性化内容","required":true}
        ]}}
    """.trimIndent()

    @Test
    @DisplayName("G-1 目录：GET 携带 Accept-Language 与 Bearer，按字段集解码")
    fun loadCatalog() = runBlocking {
        server.enqueue(
            MockResponse().setBody(catalogueBody()).setHeader("Cache-Control", "private, max-age=300"),
        )
        val outcome = gateway().catalog("en-US")
        val loaded = assertIs<PrivacyCatalogOutcome.Loaded>(outcome)
        assertEquals("v1", loaded.catalogVersion)
        assertEquals(2, loaded.items.size)
        assertEquals("GPS_LOCATION", loaded.items[0].code)
        assertEquals("位置信息", loaded.items[0].displayName)
        assertEquals(false, loaded.items[0].required)
        assertEquals(true, loaded.items[1].required)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/api/vehicle/activation/v1/privacy-authorization/items",
            request.path,
        )
        assertEquals("en-US", request.getHeader("Accept-Language"))
        assertEquals("Bearer device-token", request.getHeader("Authorization"))
    }

    @Test
    @DisplayName("G-1 目录：缺少 catalogVersion 整包拒绝")
    fun missingCatalogVersionIsRejected() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"000000","data":{"items":[{"authorizationItem":"GPS_LOCATION","displayName":"位置信息","required":false}]}}""",
            ),
        )
        val outcome = assertIs<PrivacyCatalogOutcome.Failed>(gateway().catalog("zh-CN"))
        assertEquals(ErrorCategory.CONTRACT_REJECTED, outcome.category)
    }

    @Test
    @DisplayName("G-1 目录：缺少 displayName / required 时整包拒绝（不展示半份目录）")
    fun missingItemFieldIsRejected() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"000000","data":{"catalogVersion":"v1","items":[{"authorizationItem":"GPS_LOCATION","required":false}]}}""",
            ),
        )
        assertEquals(
            ErrorCategory.CONTRACT_REJECTED,
            assertIs<PrivacyCatalogOutcome.Failed>(gateway().catalog("zh-CN")).category,
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":"000000","data":{"catalogVersion":"v1","items":[{"authorizationItem":"GPS_LOCATION","displayName":"位置信息"}]}}""",
            ),
        )
        assertEquals(
            ErrorCategory.CONTRACT_REJECTED,
            assertIs<PrivacyCatalogOutcome.Failed>(gateway().catalog("zh-CN")).category,
        )
    }

    @Test
    @DisplayName("G-1 目录：空列表是合法响应，由协调器判定 CATALOG_EMPTY")
    fun emptyCatalogIsLoaded() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":"000000","data":{"catalogVersion":"v1","items":[]}}"""),
        )
        val loaded = assertIs<PrivacyCatalogOutcome.Loaded>(gateway().catalog("zh-CN"))
        assertEquals("v1", loaded.catalogVersion)
        assertTrue(loaded.items.isEmpty())
    }

    @Test
    @DisplayName("业务码：401118/401119 与 401108 都是可重试，不是终态")
    fun businessCodesAreRetryable() = runBlocking {
        val codes = listOf("401108", "401118", "401119")
        val gateway = gateway()
        codes.forEach { code ->
            server.enqueue(MockResponse().setBody("""{"code":"$code","message":"x"}"""))
            val failed = assertIs<PrivacyCatalogOutcome.Failed>(gateway.catalog("zh-CN"))
            assertEquals(ErrorCategory.RETRYABLE, failed.category, code)
            assertEquals(code, failed.businessCode)
        }
    }

    @Test
    @DisplayName("5xx 与超时：可重试/结果未知，目录不可用")
    fun transportFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody(""))
        assertEquals(
            ErrorCategory.RETRYABLE,
            assertIs<PrivacyCatalogOutcome.Failed>(gateway().catalog("zh-CN")).category,
        )
    }

    @Test
    @DisplayName("缓存：max-age=300 之内命中内存缓存；无 Cache-Control 不缓存")
    fun cacheHonoursMaxAge() = runBlocking {
        var now = 1_700_000_000_000L
        val gateway = gateway(now = { now })
        server.enqueue(
            MockResponse().setBody(catalogueBody()).setHeader("Cache-Control", "private, max-age=300"),
        )
        gateway.catalog("zh-CN")
        now += 200_000L
        gateway.catalog("zh-CN")
        assertEquals(1, server.requestCount, "the catalogue must be cached inside max-age")

        now += 200_000L
        server.enqueue(
            MockResponse().setBody(catalogueBody()).setHeader("Cache-Control", "private, max-age=300"),
        )
        gateway.catalog("zh-CN")
        assertEquals(2, server.requestCount, "the cache must expire after max-age")

        val uncached = gateway(now = { now })
        server.enqueue(MockResponse().setBody(catalogueBody()))
        uncached.catalog("zh-CN")
        uncached.catalog("zh-CN")
        assertEquals(4, server.requestCount, "no Cache-Control means no caching")
    }

    @Test
    @DisplayName("无 IAM 令牌时失败关闭：不发起匿名目录请求")
    fun missingTokenFailsClosed() = runBlocking {
        val outcome = assertIs<PrivacyCatalogOutcome.Failed>(
            gateway(tokens = TokenProvider.None).catalog("zh-CN"),
        )
        assertEquals(ErrorCategory.AUTH_REJECTED, outcome.category)
        assertEquals(0, server.requestCount)
    }
}
