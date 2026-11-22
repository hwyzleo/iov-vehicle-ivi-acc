package net.hwyz.iov.vehicle.ivi.acc.domain.store

import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptance
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginMaterial
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PrivacyDecision
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState

/** Minimal recovery context. No QR payload and no credential is kept here. */
data class FlowContext(
    val flowGeneration: Long,
    val guide: GuideState,
    val login: LoginState,
    val sessionId: String?,
    val sessionExpiresAtEpochMillis: Long?,
    val bindingOperationId: String?,
    val bindingConfirmed: Boolean,
    val accountId: String?,
    val updatedAtEpochMillis: Long,
)

interface LocalFlowStore {
    suspend fun save(context: FlowContext)

    suspend fun loadContext(): FlowContext?

    suspend fun clear()
}

interface AgreementAcceptanceStore {
    suspend fun saveAll(records: List<AgreementAcceptance>)

    suspend fun loadAcceptances(scopeKey: String): List<AgreementAcceptance>

    suspend fun markSync(scopeKey: String, sync: AgreementSyncState)
}

interface PrivacyDecisionStore {
    suspend fun loadDecision(scopeKey: String): PrivacyDecision?

    suspend fun isFirstInteractionCompleted(scopeKey: String): Boolean

    /**
     * Single atomic write of the user selection, its pending upload record and the
     * first interaction flag. Returns false when persistence failed.
     */
    suspend fun commitDecision(decision: PrivacyDecision, record: PendingRecord): Boolean

    suspend fun markSync(scopeKey: String, sync: PrivacySyncState)
}

interface PendingRecordStore {
    suspend fun enqueue(record: PendingRecord)

    suspend fun pending(limit: Int): List<PendingRecord>

    suspend fun markAttempt(
        id: String,
        attempts: Int,
        nextEligibleAtEpochMillis: Long,
    )

    suspend fun markAcknowledged(id: String)

    /** Terminal rejection: keep the record for diagnostics but stop retrying it. */
    suspend fun markFailed(id: String, attempts: Int)

    suspend fun clearAll()
}

/** Platform secure storage for short lived login material. */
interface SecureMaterialStore {
    suspend fun put(material: LoginMaterial, secret: String)

    suspend fun get(handle: String): String?

    suspend fun clear(handle: String)

    suspend fun clearAll()
}
