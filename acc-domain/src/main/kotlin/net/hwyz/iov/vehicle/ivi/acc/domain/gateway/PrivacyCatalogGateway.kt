package net.hwyz.iov.vehicle.ivi.acc.domain.gateway

import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Privacy authorization catalogue (IVI-ACC-DSN-CR-005 D-013, G-1 cross platform contract).
 *
 * The catalogue is cloud authoritative: the car never owns the item list, never keeps a local
 * fallback list and never reports a decision for an item the catalogue does not contain.
 */
data class PrivacyAuthorizationItem(
    /** Directly mapped `authorizationItem`; same name, no case folding, no trimming. */
    val code: String,
    /** Directly mapped `displayName`; the dialog uses it as the title. */
    val displayName: String,
    val required: Boolean,
    /** Car side decision sequence number (persisted, +1 per user decision). Not a cloud field. */
    val decisionVersion: Int = 0,
)

sealed interface PrivacyCatalogOutcome {
    data class Loaded(
        /** Mandatory `catalogVersion`, forwarded verbatim on the report. */
        val catalogVersion: String,
        val items: List<PrivacyAuthorizationItem>,
    ) : PrivacyCatalogOutcome

    data class Failed(
        val category: ErrorCategory,
        /** 401118 / 401119 / 401108 / null. */
        val businessCode: String? = null,
    ) : PrivacyCatalogOutcome
}

/**
 * Catalogue gateway. The language is forwarded as `Accept-Language` (zh-CN / en-US, default zh-CN).
 */
interface PrivacyAuthorizationCatalogGateway {
    suspend fun catalog(language: String): PrivacyCatalogOutcome

    companion object {
        /** Assembly that has no catalogue yet: behave exactly like "catalogue unavailable". */
        val None: PrivacyAuthorizationCatalogGateway = object : PrivacyAuthorizationCatalogGateway {
            override suspend fun catalog(language: String): PrivacyCatalogOutcome =
                PrivacyCatalogOutcome.Failed(net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory.CONTRACT_REJECTED)
        }
    }
}

/** Catalogue dimension of the activation state; independent from GuideState. */
enum class PrivacyCatalogState {
    NOT_LOADED,
    LOADING,
    LOADED,
    UNAVAILABLE,
}

/**
 * Why the catalogue is unusable. Recorded so diagnostics can tell a transient outage from an empty
 * or disabled catalogue; all three share the same behaviour (no report, entry stays possible).
 */
enum class PrivacyCatalogUnavailableReason {
    CATALOG_FAILED,
    CATALOG_EMPTY,
    FEATURE_DISABLED,
}

enum class PrivacyItemState {
    GRANTED,
    NOT_GRANTED,
}

/**
 * One item of a decision: the wire-frozen G-1 shape (`authorizationItem`, `state`, `version`) that is
 * also what gets persisted locally, so no mapping mistake can invent a different version.
 */
data class PrivacyItemDecision(
    val authorizationItem: String,
    val state: PrivacyItemState,
    /** Local decision sequence number, monotonically increasing per item, >= 1. */
    val version: Int = 1,
)

/** Report payload (G-1 field set; do not add, remove or rename a field). */
data class PrivacyReportPayload(
    val reportId: String,
    val catalogVersion: String,
    val items: List<PrivacyItemDecision>,
    val occurredAtEpochMillis: Long,
)

/** The catalogue item a decision was made for, plus the decision itself. */
data class PrivacyAuthorizationDecision(
    val item: PrivacyAuthorizationItem,
    val state: PrivacyItemState,
) {
    fun toDecision(version: Int): PrivacyItemDecision =
        PrivacyItemDecision(item.code, state, version)
}
