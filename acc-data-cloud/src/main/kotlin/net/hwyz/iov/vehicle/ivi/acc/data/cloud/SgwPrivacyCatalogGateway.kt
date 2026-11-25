package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.TokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.TokenProvider
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.CloudPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.JsonCloudPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudOperation
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudRequest
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.HttpMethod
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwTransport
import net.hwyz.iov.vehicle.ivi.acc.domain.error.ErrorScope
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationCatalogGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Cloud authoritative privacy authorization catalogue (IVI-ACC-DSN-CR-005 D-013, G-1).
 *
 * `GET /api/vehicle/activation/v1/privacy-authorization/items` with `Accept-Language`, response
 * `Cache-Control: private, max-age=300`. The payload is decoded strictly: a missing `catalogVersion`,
 * `authorizationItem`, `displayName` or `required` rejects the whole response, because a partially
 * understood catalogue must never be shown or reported against.
 *
 * The cache is in memory only, bounded by the response `max-age` (never longer than the configured
 * ceiling), and it is never the source of a report nor a fallback list (R-5).
 */
class SgwPrivacyCatalogGateway(
    private val transport: SgwTransport,
    private val codec: CloudPayloadCodec = JsonCloudPayloadCodec(),
    private val tokens: TokenProvider = TokenProvider.None,
    private val languageProvider: () -> String = { "zh-CN" },
    private val cacheTtlCeilingMillis: Long = DEFAULT_CACHE_TTL_CEILING_MILLIS,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val logger: net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger? = null,
) : PrivacyAuthorizationCatalogGateway {

    private data class Cached(
        val language: String,
        val outcome: PrivacyCatalogOutcome.Loaded,
        val expiresAtEpochMillis: Long,
    )

    private var cache: Cached? = null

    override suspend fun catalog(language: String): PrivacyCatalogOutcome {
        val normalized = language.ifBlank { DEFAULT_LANGUAGE }
        cache?.let { cached ->
            if (cached.language == normalized && cached.expiresAtEpochMillis > nowEpochMillis()) {
                return cached.outcome
            }
        }
        val token = when (val outcome = tokens.authorizationToken()) {
            is TokenOutcome.Available -> outcome.token
            // Fail closed: an anonymous catalogue request would be rejected by the gateway.
            is TokenOutcome.Unavailable -> return PrivacyCatalogOutcome.Failed(outcome.category)
        }
        val request = CloudRequest(
            operation = CloudOperation.LOAD_PRIVACY_CATALOG,
            method = HttpMethod.GET,
            bearerToken = token,
            headers = mapOf(ACCEPT_LANGUAGE_HEADER to normalized),
        )
        val result = transport.execute(request)
        SgwOutcomeClassifier.classifyTransport(result)?.let { return fail(it, null) }
        val envelope = SgwOutcomeClassifier.parse(result.body)
            ?: return fail(ErrorCategory.CONTRACT_REJECTED, null)
        if (!SgwOutcomeClassifier.isSuccess(envelope.code)) {
            val classification = SgwOutcomeClassifier.classificationOf(envelope.code, ErrorScope.TSP_ACTIVATION)
            return fail(classification.category, envelope.code)
        }
        val data = envelope.objectData ?: return fail(ErrorCategory.CONTRACT_REJECTED, envelope.code)
        val decoded = codec.decodePrivacyCatalog(data)
            ?: return fail(ErrorCategory.CONTRACT_REJECTED, envelope.code)
        val loaded = PrivacyCatalogOutcome.Loaded(
            catalogVersion = decoded.catalogVersion,
            items = decoded.items,
        )
        cache = Cached(
            language = normalized,
            outcome = loaded,
            expiresAtEpochMillis = nowEpochMillis() + cacheTtlMillis(result.headers[HEADER_CACHE_CONTROL]),
        )
        return loaded
    }

    /** Test/diagnostics hook: drops the in-memory cache (feature flag off, account switch). */
    fun clearCache() {
        cache = null
    }

    private fun fail(category: ErrorCategory, code: String?): PrivacyCatalogOutcome.Failed {
        // A rejected or unavailable catalogue is never cached.
        cache = null
        logger?.event(
            "privacy.catalog.request_failed",
            "category" to category.name,
            "code" to (code ?: "-"),
        )
        return PrivacyCatalogOutcome.Failed(category, code)
    }

    /** `max-age` of the response, capped by the configured ceiling; no header means no caching. */
    private fun cacheTtlMillis(cacheControl: String?): Long {
        val header = cacheControl ?: return 0L
        val maxAge = header.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith(MAX_AGE_PREFIX, ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.toLongOrNull()
            ?: return 0L
        return (maxAge * 1_000L).coerceIn(0L, cacheTtlCeilingMillis)
    }

    private companion object {
        const val DEFAULT_LANGUAGE = "zh-CN"
        const val ACCEPT_LANGUAGE_HEADER = "Accept-Language"
        const val HEADER_CACHE_CONTROL = "Cache-Control"
        const val MAX_AGE_PREFIX = "max-age"
        const val DEFAULT_CACHE_TTL_CEILING_MILLIS = 300_000L
    }
}
