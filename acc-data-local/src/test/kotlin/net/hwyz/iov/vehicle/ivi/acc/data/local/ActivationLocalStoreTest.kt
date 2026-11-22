package net.hwyz.iov.vehicle.ivi.acc.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptance
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PrivacyDecision
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordPayload
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.store.FlowContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivationLocalStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var scope: CoroutineScope
    private lateinit var store: ActivationLocalStore

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        store = createStore()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun createStore(): ActivationLocalStore = ActivationLocalStore(
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tempDir, "activation.preferences_pb") },
        ),
    )

    private fun flowContext(generation: Long = 3L) = FlowContext(
        flowGeneration = generation,
        guide = GuideState.LOGIN_PENDING,
        login = LoginState.RETRY_REQUIRED,
        sessionId = "session-$generation",
        sessionExpiresAtEpochMillis = 99L,
        bindingOperationId = "binding-1",
        bindingConfirmed = true,
        accountId = "account-1",
        updatedAtEpochMillis = 100L,
    )

    private fun acceptance(
        scopeKey: String,
        type: AgreementType = AgreementType.PRIVACY_POLICY,
    ) = AgreementAcceptance(
        scopeKey = scopeKey,
        type = type,
        version = "1.0",
        revisionId = "rev-1.0",
        contentHash = "hash-1.0",
        language = "zh-CN",
        agreedAtEpochMillis = 10L,
        dedupeId = "agr-$scopeKey-${type.name}",
        syncState = AgreementSyncState.PENDING,
    )

    @Test
    fun `flow context round trip and clear`() = runTest {
        store.save(flowContext())
        val loaded = store.loadContext()
        assertEquals(GuideState.LOGIN_PENDING, loaded?.guide)
        assertEquals("session-3", loaded?.sessionId)
        assertTrue(loaded?.bindingConfirmed == true)

        store.clear()
        assertNull(store.loadContext())
    }

    @Test
    fun `agreement acceptances are isolated per scope`() = runTest {
        store.saveAll(
            listOf(
                acceptance("VIN:VIN1"),
                acceptance("VIN:VIN1", AgreementType.USER_AGREEMENT),
                acceptance("AV:account-1|VIN1"),
            ),
        )
        assertEquals(2, store.loadAcceptances("VIN:VIN1").size)
        assertEquals(1, store.loadAcceptances("AV:account-1|VIN1").size)
        assertTrue(store.loadAcceptances("VIN:VIN2").isEmpty())
    }

    @Test
    fun `markSync upgrades every acceptance of one scope`() = runTest {
        store.saveAll(listOf(acceptance("VIN:VIN1"), acceptance("VIN:VIN1", AgreementType.USER_AGREEMENT)))
        store.markSync("VIN:VIN1", AgreementSyncState.ACKNOWLEDGED)
        assertTrue(
            store.loadAcceptances("VIN:VIN1").all { it.syncState == AgreementSyncState.ACKNOWLEDGED },
        )
    }

    @Test
    fun `privacy decision and its pending record are committed together`() = runTest {
        val decision = PrivacyDecision(
            scopeKey = "AV:account-1|VIN1",
            accountId = "account-1",
            vin = "VIN1",
            choice = PrivacyChoice.NOT_GRANTED,
            items = listOf("PERSONALIZED_CONTENT"),
            decidedAtEpochMillis = 20L,
            dedupeId = "pvc-1",
            syncState = PrivacySyncState.PENDING,
            firstInteractionCompleted = true,
        )
        val record = PendingRecord.create(
            id = decision.dedupeId,
            type = RecordType.PRIVACY_DECISION,
            scopeKey = decision.scopeKey,
            payload = RecordPayload.PrivacyDecisionRecord(
                vin = "VIN1",
                accountId = "account-1",
                choice = decision.choice,
                items = decision.items,
                decidedAtEpochMillis = decision.decidedAtEpochMillis,
            ),
            createdAtEpochMillis = 20L,
            userActionAtEpochMillis = 20L,
        )

        assertTrue(store.commitDecision(decision, record))

        assertEquals(PrivacyChoice.NOT_GRANTED, store.loadDecision(decision.scopeKey)?.choice)
        assertTrue(store.isFirstInteractionCompleted(decision.scopeKey))
        assertEquals(1, store.pending(10).size)
        assertEquals(SyncState.PENDING, store.pending(10).single().syncState)
    }

    @Test
    fun `privacy decisions of different accounts stay isolated`() = runTest {
        val first = PrivacyDecision(
            scopeKey = "AV:account-1|VIN1",
            accountId = "account-1",
            vin = "VIN1",
            choice = PrivacyChoice.GRANTED,
            items = emptyList(),
            decidedAtEpochMillis = 20L,
            dedupeId = "pvc-1",
            syncState = PrivacySyncState.PENDING,
            firstInteractionCompleted = true,
        )
        val second = first.copy(
            scopeKey = "AV:account-2|VIN1",
            accountId = "account-2",
            choice = PrivacyChoice.NOT_GRANTED,
            dedupeId = "pvc-2",
        )
        store.commitDecision(
            first,
            PendingRecord.create(
                "pvc-1",
                RecordType.PRIVACY_DECISION,
                first.scopeKey,
                RecordPayload.PrivacyDecisionRecord("VIN1", "account-1", PrivacyChoice.GRANTED, emptyList(), 20L),
                20L,
                20L,
            ),
        )
        store.commitDecision(
            second,
            PendingRecord.create(
                "pvc-2",
                RecordType.PRIVACY_DECISION,
                second.scopeKey,
                RecordPayload.PrivacyDecisionRecord("VIN1", "account-2", PrivacyChoice.NOT_GRANTED, emptyList(), 20L),
                20L,
                20L,
            ),
        )
        assertEquals(PrivacyChoice.GRANTED, store.loadDecision(first.scopeKey)?.choice)
        assertEquals(PrivacyChoice.NOT_GRANTED, store.loadDecision(second.scopeKey)?.choice)
        assertTrue(store.isFirstInteractionCompleted(first.scopeKey))
        assertTrue(store.isFirstInteractionCompleted(second.scopeKey))
    }

    @Test
    fun `pending record lifecycle keeps the dedupe id`() = runTest {
        val record = PendingRecord.create(
            id = "skip-1",
            type = RecordType.SKIP_ACTIVATION,
            scopeKey = "VIN:VIN1",
            payload = RecordPayload.SkipActivation(
                vin = "VIN1",
                agreements = listOf(AgreementReference(AgreementType.PRIVACY_POLICY, "1.0")),
                sessionId = "session-1",
                requestedAtEpochMillis = 30L,
            ),
            createdAtEpochMillis = 30L,
            userActionAtEpochMillis = 30L,
        )
        store.enqueue(record)
        // Enqueueing the same dedupe id twice must not duplicate the business fact.
        store.enqueue(record.copy(attempts = 5))
        assertEquals(1, store.pending(10).size)

        store.markAttempt("skip-1", 2, 50L)
        val retried = store.pending(10).single()
        assertEquals(2, retried.attempts)
        assertEquals(50L, retried.nextEligibleAtEpochMillis)

        store.markAcknowledged("skip-1")
        assertTrue(store.pending(10).isEmpty())

        store.clearAll()
        assertTrue(store.pending(10).isEmpty())
    }

    @Test
    fun `terminal failure keeps the record out of the pending queue`() = runTest {
        val record = PendingRecord.create(
            id = "login-1",
            type = RecordType.LOGIN_RESULT,
            scopeKey = "AV:account-1|VIN1",
            payload = RecordPayload.LoginResult("VIN1", "account-1", "session-1", null, 40L),
            createdAtEpochMillis = 40L,
            userActionAtEpochMillis = 40L,
        )
        store.enqueue(record)
        store.markFailed("login-1", 3)
        assertTrue(store.pending(10).isEmpty())
    }

    @Test
    fun `flow context survives an app restart`() = runTest {
        store.save(flowContext(7L))
        scope.cancel()

        scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val reopened = createStore()
        assertEquals(7L, reopened.loadContext()?.flowGeneration)
        assertEquals("session-7", reopened.loadContext()?.sessionId)
    }
}
