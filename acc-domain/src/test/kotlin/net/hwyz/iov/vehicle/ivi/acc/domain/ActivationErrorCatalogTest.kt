package net.hwyz.iov.vehicle.ivi.acc.domain

import net.hwyz.iov.vehicle.ivi.acc.domain.error.DefaultActivationErrorCatalog
import net.hwyz.iov.vehicle.ivi.acc.domain.error.ErrorScope
import net.hwyz.iov.vehicle.ivi.acc.domain.error.RetryAdvice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-015 / R-14: the complete "code -> classification / retry advice" table.
 *
 * The table is the single source of truth for TSP, CIAM and SGW codes; a missing row or a wrong
 * category here would silently turn a retryable condition into a terminal failure (or the reverse).
 */
class ActivationErrorCatalogTest {

    private val catalog = DefaultActivationErrorCatalog

    @BeforeEach
    fun resetCounters() {
        DefaultActivationErrorCatalog.resetUnknownCounters()
    }

    private data class Row(
        val code: String,
        val category: ErrorCategory,
        val advice: RetryAdvice,
        val reProof: Boolean = false,
        val sessionRefresh: Boolean = false,
        val catalogReload: Boolean = false,
    )

    private val table = listOf(
        Row("401101", ErrorCategory.EXPIRED, RetryAdvice.NONE),
        Row("401102", ErrorCategory.EXPIRED, RetryAdvice.NONE),
        Row("401103", ErrorCategory.EXPIRED, RetryAdvice.NONE),
        Row("401104", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL),
        Row("401105", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE),
        Row("401106", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE),
        Row("401107", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL),
        Row("401108", ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF),
        Row("401109", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE),
        Row("401110", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL),
        Row("401111", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL, sessionRefresh = true),
        Row("401112", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE),
        Row("401113", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL),
        // 401114 is a concurrent conflict: one retry with the same payload is allowed.
        Row("401114", ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF),
        Row("401115", ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, sessionRefresh = true),
        Row("401116", ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, sessionRefresh = true),
        Row("401117", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE, sessionRefresh = true),
        Row("401118", ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF, catalogReload = true),
        Row("401119", ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF, catalogReload = true),
        Row("701003", ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF),
        Row("701304", ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, reProof = true),
        Row("701325", ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, reProof = true),
        Row("701326", ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF, reProof = true),
        Row("701327", ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL),
        Row("701328", ErrorCategory.AUTH_REJECTED, RetryAdvice.NONE),
        Row("701329", ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF, reProof = true),
        // 701330：平台未开通该能力（开关/白名单/登记），车端不得自动重试但保留手动入口。
        Row("701330", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL),
        Row("701331", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE),
        Row("701332", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL, sessionRefresh = true),
        Row("701333", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE, sessionRefresh = true),
        Row("701334", ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, sessionRefresh = true),
        Row("701335", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL, sessionRefresh = true),
        Row("701336", ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF),
        Row("701337", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL, sessionRefresh = true),
        Row("SGW-401001", ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, reProof = true),
        Row("SGW-403001", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE),
        Row("SGW-404001", ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE),
    )

    @Test
    fun `every frozen code is classified exactly as the design table says`() {
        table.forEach { row ->
            val classification = catalog.classify(ErrorScope.TSP_ACTIVATION, row.code)
            assertEquals(row.category, classification.category, "category of ${row.code}")
            assertEquals(row.advice, classification.retryAdvice, "retry advice of ${row.code}")
            assertEquals(row.reProof, classification.requiresReProof, "re-proof of ${row.code}")
            assertEquals(
                row.sessionRefresh,
                classification.requiresSessionRefresh,
                "session refresh of ${row.code}",
            )
            assertEquals(
                row.catalogReload,
                classification.requiresCatalogReload,
                "catalog reload of ${row.code}",
            )
            assertTrue(classification.messageKey.isNotBlank(), "message key of ${row.code}")
        }
        // 37 rows in the frozen table (401101-401119 = 19, 701xxx = 15, SGW = 3).
        assertEquals(37, table.size)
        table.forEach { assertTrue(catalog.isKnown(it.code), "${it.code} must be known") }
    }

    @Test
    fun `retryable codes can never become a terminal failure`() {
        val retryable = table.filter { it.category == ErrorCategory.RETRYABLE }
        assertTrue(retryable.isNotEmpty())
        retryable.forEach { row ->
            val classification = catalog.classify(ErrorScope.TSP_ACTIVATION, row.code)
            assertTrue(classification.retryable, "${row.code} must stay retryable")
            assertEquals(
                RetryAdvice.BACKOFF,
                classification.retryAdvice,
                "${row.code} must be retried with backoff",
            )
        }
    }

    @Test
    fun `a concurrent conflict retries the same payload exactly once`() {
        val classification = catalog.classify(ErrorScope.TSP_ACTIVATION, "401114")
        assertEquals(ErrorCategory.RETRYABLE, classification.category)
        assertEquals(RetryAdvice.BACKOFF, classification.retryAdvice)
        assertTrue(classification.retrySamePayloadOnce)
        // No other row may opt into that policy.
        table.filter { it.code != "401114" }.forEach { row ->
            assertFalse(
                catalog.classify(ErrorScope.TSP_ACTIVATION, row.code).retrySamePayloadOnce,
                "${row.code} must not retry the same payload",
            )
        }
    }

    @Test
    fun `catalog reload codes are exactly 401118 and 401119`() {
        val reload = table.filter { it.catalogReload }.map { it.code }
        assertEquals(setOf("401118", "401119"), reload.toSet())
        reload.forEach { code ->
            assertTrue(catalog.classify(ErrorScope.TSP_ACTIVATION, code).requiresCatalogReload)
        }
    }

    @Test
    fun `unknown code defaults to contract rejected and is counted`() {
        val unknown = "999999"
        assertEquals(0L, catalog.unknownCodeCount(unknown))
        val classification = catalog.classify(ErrorScope.TSP_ACTIVATION, unknown)
        assertEquals(ErrorCategory.CONTRACT_REJECTED, classification.category)
        assertEquals(RetryAdvice.MANUAL, classification.retryAdvice)
        assertFalse(classification.retryable)
        assertEquals(DefaultActivationErrorCatalog.UNKNOWN_CODE_MESSAGE_KEY, classification.messageKey)
        assertEquals(1L, catalog.unknownCodeCount(unknown))

        catalog.classify(ErrorScope.SGW_GATEWAY, unknown)
        assertEquals(2L, catalog.unknownCodeCount(unknown))

        // A missing code is treated exactly like an unknown one, never as a success.
        val missing = catalog.classify(ErrorScope.CIAM_DEVICE, null)
        assertEquals(ErrorCategory.CONTRACT_REJECTED, missing.category)
        assertEquals(1L, catalog.unknownCodeCount(null))
    }

    @Test
    fun `scope only changes the message key, never the classification`() {
        val tsp = catalog.classify(ErrorScope.TSP_ACTIVATION, "401108")
        val ciam = catalog.classify(ErrorScope.CIAM_DEVICE, "401108")
        val sgw = catalog.classify(ErrorScope.SGW_GATEWAY, "SGW-403001")
        assertEquals(tsp.category, ciam.category)
        assertEquals("error.401108", tsp.messageKey)
        assertEquals("error.sgw.SGW-403001", sgw.messageKey)
    }

    @Test
    fun `success code is not a failure classification`() {
        assertTrue(DefaultActivationErrorCatalog.isSuccess("000000"))
        assertFalse(DefaultActivationErrorCatalog.isSuccess("401101"))
        assertFalse(DefaultActivationErrorCatalog.isSuccess(null))
    }
}
