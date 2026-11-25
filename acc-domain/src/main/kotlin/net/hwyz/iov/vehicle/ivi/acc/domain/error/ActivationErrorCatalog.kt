package net.hwyz.iov.vehicle.ivi.acc.domain.error

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/** Which contract produced a business code. */
enum class ErrorScope {
    TSP_ACTIVATION,
    CIAM_DEVICE,
    CIAM_USER,
    SGW_GATEWAY,
}

/** What the UI may offer for a classification. Terminal rejections never retry automatically. */
enum class RetryAdvice {
    NONE,
    BACKOFF,
    MANUAL,
}

/**
 * One row of the central code table (IVI-ACC-DSN-CR-005 D-015).
 *
 * [category] is the only input of the retry decision; [retryAdvice] only drives the affordance, and
 * the `requires*` flags tell the coordinator which recovery step is mandatory before a retry.
 */
data class ErrorClassification(
    val category: ErrorCategory,
    val retryAdvice: RetryAdvice,
    val messageKey: String,
    val requiresReProof: Boolean = false,
    val requiresSessionRefresh: Boolean = false,
    val requiresCatalogReload: Boolean = false,
    /**
     * Retry exactly once with the **same** payload (concurrent conflict).
     *
     * Used by 401114: the record lost a concurrent write race, so re-sending the identical payload
     * once is safe and idempotent; a second failure is terminal.
     */
    val retrySamePayloadOnce: Boolean = false,
) {
    val retryable: Boolean
        get() = category == ErrorCategory.RETRYABLE || category == ErrorCategory.RESULT_UNKNOWN
}

/**
 * Single source of truth for "business code -> car side classification" (D-015 / R-14).
 *
 * Every adapter (TSP envelope, CIAM token client, SGW gateway codes) delegates here instead of
 * keeping a private mapping table, so a retryable code can never become a terminal failure on one
 * path and a retry on another.
 */
interface ActivationErrorCatalog {

    /** Classification of [code] inside [scope]; unknown codes never become retryable. */
    fun classify(scope: ErrorScope, code: String?): ErrorClassification

    /** Number of times an unknown code was seen, for contract drift detection (`error.unknown_code`). */
    fun unknownCodeCount(code: String?): Long
}

/**
 * Default catalogue: the complete D-015 table.
 *
 * Unknown codes degrade to `CONTRACT_REJECTED` / `MANUAL` and are counted, so a contract drift shows
 * up in diagnostics instead of turning into an automatic retry loop.
 */
object DefaultActivationErrorCatalog : ActivationErrorCatalog {

    const val SUCCESS_CODE = "000000"
    const val UNKNOWN_CODE_MESSAGE_KEY = "error.unknown_code"

    private val unknownCounts = ConcurrentHashMap<String, AtomicLong>()

    fun isSuccess(code: String?): Boolean = code == SUCCESS_CODE

    override fun classify(scope: ErrorScope, code: String?): ErrorClassification {
        val key = code?.trim().orEmpty()
        val row = TABLE[key]
        if (row != null) return row.copy(messageKey = messageKeyFor(scope, key, row))
        unknownCounts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        return ErrorClassification(
            category = ErrorCategory.CONTRACT_REJECTED,
            retryAdvice = RetryAdvice.MANUAL,
            messageKey = UNKNOWN_CODE_MESSAGE_KEY,
        )
    }

    override fun unknownCodeCount(code: String?): Long =
        unknownCounts[code?.trim().orEmpty()]?.get() ?: 0L

    /** Test hook: the counter is process wide by design. */
    fun resetUnknownCounters() {
        unknownCounts.clear()
    }

    /** True when the code is part of the frozen table (success included). */
    fun isKnown(code: String?): Boolean = code == SUCCESS_CODE || TABLE.containsKey(code?.trim().orEmpty())

    private fun messageKeyFor(scope: ErrorScope, code: String, row: ErrorClassification): String =
        when (scope) {
            ErrorScope.SGW_GATEWAY -> "error.sgw.$code"
            else -> "error.$code"
        }.ifBlank { row.messageKey }

    private val TABLE: Map<String, ErrorClassification> = buildMap {
        // ---- IOV-TSP vehicle side activation API (401101-401119) ------------------------------
        put("401101", row(ErrorCategory.EXPIRED, RetryAdvice.NONE)) // 会话无效
        put("401102", row(ErrorCategory.EXPIRED, RetryAdvice.NONE)) // 会话已过期
        put("401103", row(ErrorCategory.EXPIRED, RetryAdvice.NONE)) // 会话已取消
        put("401104", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL)) // 被其他账号占用
        put("401105", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE)) // 车辆不存在/不可绑定
        put("401106", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE)) // 绑车资格不符
        put("401107", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL)) // 资格无法确定
        put("401108", row(ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF)) // 依赖服务不可用
        put("401109", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE)) // 已存在主使用人
        put("401110", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL)) // 幂等键内容不一致
        put(
            "401111",
            row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL, requiresSessionRefresh = true),
        ) // 协议未同意/修订不一致
        put("401112", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE)) // 请求参数非法
        put("401113", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL)) // 回执内容冲突
        // 授权版本并发冲突：同一载荷重试一次即可收敛，第二次仍冲突才是真冲突。
        put(
            "401114",
            row(ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF, retrySamePayloadOnce = true),
        )
        put(
            "401115",
            row(ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, requiresSessionRefresh = true),
        ) // 票据无效/不可用
        put(
            "401116",
            row(ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, requiresSessionRefresh = true),
        ) // 票据已过期
        put(
            "401117",
            row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE, requiresSessionRefresh = true),
        ) // 票据已消费/重复使用
        put(
            "401118",
            row(ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF, requiresCatalogReload = true),
        ) // 授权项未在批准目录内
        put(
            "401119",
            row(ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF, requiresCatalogReload = true),
        ) // 目录版本不一致

        // ---- SEC-CIAM (701xxx) ---------------------------------------------------------------
        put("701003", row(ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF)) // 限流
        put(
            "701304",
            row(ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, requiresReProof = true),
        ) // 令牌无效
        put(
            "701325",
            row(ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, requiresReProof = true),
        ) // 设备证明无效
        put(
            "701326",
            row(ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF, requiresReProof = true),
        ) // 证明依赖不可用
        put("701327", row(ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL)) // 设备无有效车辆绑定
        put("701328", row(ErrorCategory.AUTH_REJECTED, RetryAdvice.NONE)) // 设备已被阻断或退役
        put(
            "701329",
            row(ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF, requiresReProof = true),
        ) // 设备会话冲突（有限次）
        // 平台侧未开通该 grant（开关关闭 / 客户端未登记 / 白名单未含）：车端重试不可能改变结果，
        // 但平台修复后用户手动重试即可恢复，因此是 MANUAL 而不是 NONE（DSN-CR-007）。
        put("701330", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL))
        put("701331", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE)) // 账号级 scope 越权
        put(
            "701332",
            row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL, requiresSessionRefresh = true),
        ) // 登录票据无效
        put(
            "701333",
            row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE, requiresSessionRefresh = true),
        ) // 票据已消费
        put(
            "701334",
            row(ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, requiresSessionRefresh = true),
        ) // 登录票据已过期
        put(
            "701335",
            row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL, requiresSessionRefresh = true),
        ) // 票据与设备/车辆不匹配
        put("701336", row(ErrorCategory.RETRYABLE, RetryAdvice.BACKOFF)) // 票据服务不可用
        put(
            "701337",
            row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.MANUAL, requiresSessionRefresh = true),
        ) // 票据域校验失败

        // ---- EDD-SGW gateway codes ------------------------------------------------------------
        put(
            "SGW-401001",
            row(ErrorCategory.AUTH_REJECTED, RetryAdvice.MANUAL, requiresReProof = true),
        ) // 网关令牌无效/过期
        put("SGW-403001", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE)) // 网关拒绝
        put("SGW-404001", row(ErrorCategory.CONTRACT_REJECTED, RetryAdvice.NONE)) // 路由/目标不存在
    }

    private fun row(
        category: ErrorCategory,
        retryAdvice: RetryAdvice,
        requiresReProof: Boolean = false,
        requiresSessionRefresh: Boolean = false,
        requiresCatalogReload: Boolean = false,
        retrySamePayloadOnce: Boolean = false,
    ): ErrorClassification = ErrorClassification(
        category = category,
        retryAdvice = retryAdvice,
        messageKey = "",
        requiresReProof = requiresReProof,
        requiresSessionRefresh = requiresSessionRefresh,
        requiresCatalogReload = requiresCatalogReload,
        retrySamePayloadOnce = retrySamePayloadOnce,
    )
}
