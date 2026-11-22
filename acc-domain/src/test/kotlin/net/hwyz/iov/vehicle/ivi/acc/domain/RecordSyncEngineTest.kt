package net.hwyz.iov.vehicle.ivi.acc.domain

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptance
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordPayload
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.RetryPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.support.FakeCloudPort
import net.hwyz.iov.vehicle.ivi.acc.domain.support.InMemoryAgreementStore
import net.hwyz.iov.vehicle.ivi.acc.domain.support.InMemoryPrivacyStore
import net.hwyz.iov.vehicle.ivi.acc.domain.support.InMemoryRecordStore
import net.hwyz.iov.vehicle.ivi.acc.domain.support.MutableClock
import net.hwyz.iov.vehicle.ivi.acc.domain.log.RecordingDiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.sync.RecordSyncEngine
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordSyncEngineTest {

    private val clock = MutableClock()
    private val cloud = FakeCloudPort()
    private val recordStore = InMemoryRecordStore()
    private val agreementStore = InMemoryAgreementStore()
    private val privacyStore = InMemoryPrivacyStore(recordStore)
    private val logger = RecordingDiagnosticsLogger()

    private fun engine(maxAttempts: Int = 3) = RecordSyncEngine(
        recordStore = recordStore,
        cloud = cloud,
        agreementStore = agreementStore,
        privacyStore = privacyStore,
        policy = RetryPolicy(maxAttempts = maxAttempts, initialBackoffMillis = 10L, maxBackoffMillis = 20L),
        wallClock = clock,
        logger = logger,
    )

    private fun agreementRecord(id: String = "agr-1"): PendingRecord = PendingRecord.create(
        id = id,
        type = RecordType.AGREEMENT_CONSENT,
        scopeKey = "VIN:VIN1",
        payload = RecordPayload.AgreementConsent(
            vin = "VIN1",
            accountId = null,
            agreements = listOf(
                AgreementReference(AgreementType.PRIVACY_POLICY, "1.0", "rev-1.0", "hash", "zh-CN"),
            ),
            sessionId = null,
            bindingOperationId = null,
            agreedAtEpochMillis = clock.nowEpochMillis(),
        ),
        createdAtEpochMillis = clock.nowEpochMillis(),
        userActionAtEpochMillis = clock.nowEpochMillis(),
    )

    @Test
    fun `a retryable failure is retried and then acknowledged`() = runTest {
        recordStore.enqueue(agreementRecord())
        agreementStore.saveAll(
            listOf(
                AgreementAcceptance(
                    scopeKey = "VIN:VIN1",
                    type = AgreementType.PRIVACY_POLICY,
                    version = "1.0",
                    revisionId = "rev-1.0",
                    contentHash = "hash",
                    language = "zh-CN",
                    agreedAtEpochMillis = clock.nowEpochMillis(),
                    dedupeId = "agr-1",
                    syncState = AgreementSyncState.PENDING,
                ),
            ),
        )
        cloud.recordOutcome = RecordReportOutcome.Failed(ErrorCategory.RETRYABLE)
        assertEquals(0, engine(maxAttempts = 2).flush(10))
        assertEquals(SyncState.PENDING, recordStore.records.getValue("agr-1").syncState)
        assertEquals(2, recordStore.records.getValue("agr-1").attempts)

        // Once the backoff window elapsed and the transport recovered, the same record is uploaded.
        clock.wallEpochMillis += 10_000L
        cloud.recordOutcome = RecordReportOutcome.Acknowledged
        assertEquals(1, engine().flush(10))
        assertEquals(SyncState.ACKNOWLEDGED, recordStore.records.getValue("agr-1").syncState)
        assertEquals(
            AgreementSyncState.ACKNOWLEDGED,
            agreementStore.loadAcceptances("VIN:VIN1").single().syncState,
        )
    }

    @Test
    fun `terminal rejection stops retrying`() = runTest {
        recordStore.enqueue(agreementRecord("agr-2"))
        cloud.recordOutcome = RecordReportOutcome.Failed(ErrorCategory.AUTH_REJECTED)
        assertEquals(0, engine().flush(10))
        val record = recordStore.records.getValue("agr-2")
        assertEquals(SyncState.FAILED_TERMINAL, record.syncState)
        assertEquals(1, record.attempts)
    }

    @Test
    fun `duplicate enqueue keeps a single record`() = runTest {
        val record = agreementRecord("agr-3")
        recordStore.enqueue(record)
        recordStore.enqueue(record.copy(payloadDigest = "other"))
        assertEquals(1, recordStore.records.size)
    }

    @Test
    fun `acknowledgement marks the privacy decision as synced`() = runTest {
        val decision = net.hwyz.iov.vehicle.ivi.acc.domain.model.PrivacyDecision(
            scopeKey = "AV:account-1|VIN1",
            accountId = "account-1",
            vin = "VIN1",
            choice = PrivacyChoice.GRANTED,
            items = listOf("PERSONALIZED_CONTENT"),
            decidedAtEpochMillis = clock.nowEpochMillis(),
            dedupeId = "pvc-1",
            syncState = net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState.PENDING,
            firstInteractionCompleted = true,
        )
        val record = PendingRecord.create(
            id = "pvc-1",
            type = RecordType.PRIVACY_DECISION,
            scopeKey = decision.scopeKey,
            payload = RecordPayload.PrivacyDecisionRecord(
                vin = "VIN1",
                accountId = "account-1",
                choice = PrivacyChoice.GRANTED,
                items = decision.items,
                decidedAtEpochMillis = decision.decidedAtEpochMillis,
            ),
            createdAtEpochMillis = clock.nowEpochMillis(),
            userActionAtEpochMillis = clock.nowEpochMillis(),
        )
        privacyStore.commitDecision(decision, record)
        cloud.recordOutcome = RecordReportOutcome.Acknowledged

        assertEquals(1, engine().flush(10))
        assertEquals(
            net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState.ACKNOWLEDGED,
            privacyStore.loadDecision(decision.scopeKey)?.syncState,
        )
    }

    @Test
    fun `payload digest changes when content changes`() {
        val first = agreementRecord("agr-4")
        val changed = first.copy(
            payload = (first.payload as RecordPayload.AgreementConsent).copy(agreedAtEpochMillis = 42L),
        )
        assertTrue(
            first.payloadDigest != PendingRecord.sha256(changed.payload.canonical),
        )
    }
}
