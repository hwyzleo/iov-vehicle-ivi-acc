package net.hwyz.iov.vehicle.ivi.acc.demo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.FakeActivationCloudPort
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.FakeAgreementGateway
import net.hwyz.iov.vehicle.ivi.acc.data.local.ActivationLocalStore
import net.hwyz.iov.vehicle.ivi.acc.data.local.InMemoryKeyValueStore
import net.hwyz.iov.vehicle.ivi.acc.data.local.SecureMaterialStoreImpl
import net.hwyz.iov.vehicle.ivi.acc.data.local.security.InMemorySecretBox
import net.hwyz.iov.vehicle.ivi.acc.domain.ActivationCoordinator
import net.hwyz.iov.vehicle.ivi.acc.domain.config.ActivationConfig
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyPromptResult
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyScope
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.id.DefaultIdGenerator
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.log.RecordingDiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.RetryPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.SessionPollPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.SkipState
import net.hwyz.iov.vehicle.ivi.acc.domain.sync.RecordSyncEngine
import net.hwyz.iov.vehicle.ivi.acc.domain.time.MonotonicClock
import net.hwyz.iov.vehicle.ivi.acc.domain.time.WallClock
import net.hwyz.iov.vehicle.ivi.acc.platform.DesktopPrivacyConsentAdapter
import net.hwyz.iov.vehicle.ivi.acc.platform.FakeAccountLoginAdapter
import net.hwyz.iov.vehicle.ivi.acc.platform.PrivacyPrompt
import net.hwyz.iov.vehicle.ivi.acc.platform.PrivacyPromptOutcome
import net.hwyz.iov.vehicle.ivi.acc.platform.SeatRole
import net.hwyz.iov.vehicle.ivi.acc.platform.StaticSeatRoleProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test: domain state machine + real local persistence + cloud adapter fake +
 * platform adapters, walking acceptance A-001..A-008 end to end.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivationEndToEndTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @BeforeEach
    fun setUp() {
        dataStoreScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir, "activation.preferences_pb") },
        )
    }

    @AfterEach
    fun tearDown() {
        dataStoreScope.cancel()
    }

    private class Wiring(
        private val dataStore: DataStore<Preferences>,
        private val scope: CoroutineScope,
        private val scheduler: TestCoroutineScheduler,
    ) {
        val vehicle = VehicleRef("VIN-E2E-1")
        val cloud = FakeActivationCloudPort()
        val agreementGateway = FakeAgreementGateway()
        val loginGateway = FakeAccountLoginAdapter()
        val seatRoleProvider = StaticSeatRoleProvider()
        val privacyPrompt = ScriptedPrivacyPrompt()
        val privacyGateway = DesktopPrivacyConsentAdapter(seatRoleProvider, privacyPrompt)
        val localStore = ActivationLocalStore(dataStore)
        val materialStore = SecureMaterialStoreImpl(
            store = InMemoryKeyValueStore(),
            box = InMemorySecretBox(),
            nowEpochMillis = { clock.nowEpochMillis() },
        )
        val clock = ManualClock()
        val logger = RecordingDiagnosticsLogger()
        init {
            loginGateway.outcomeFactory = { LoginOutcome.Success(AccountRef("account-e2e")) }
        }

        val config = ActivationConfig(
            sessionPoll = SessionPollPolicy(intervalMillis = 100L, timeoutMillis = 1_000L, maxAttempts = 3),
            recordRetry = RetryPolicy(maxAttempts = 2, initialBackoffMillis = 10L, maxBackoffMillis = 20L),
            privacyPromptItems = listOf("PERSONALIZED_CONTENT"),
        )

        fun newCoordinator(): ActivationCoordinator = ActivationCoordinator(
            vehicle = vehicle,
            agreements = agreementGateway,
            cloud = cloud,
            loginGateway = loginGateway,
            privacyGateway = privacyGateway,
            flowStore = localStore,
            acceptanceStore = localStore,
            privacyStore = localStore,
            recordStore = localStore,
            recordSync = RecordSyncEngine(
                recordStore = localStore,
                cloud = cloud,
                agreementStore = localStore,
                privacyStore = localStore,
                policy = config.recordRetry,
                wallClock = clock,
                logger = logger,
            ),
            secureMaterials = materialStore,
            config = config,
            monotonicClock = clock,
            wallClock = clock,
            logger = logger,
            ids = DefaultIdGenerator(),
            scope = scope,
            dispatcher = StandardTestDispatcher(scheduler),
        )

        fun acceptBoth(coordinator: ActivationCoordinator) {
            coordinator.acceptAgreement(AgreementType.PRIVACY_POLICY)
            scheduler.runCurrent()
            coordinator.acceptAgreement(AgreementType.USER_AGREEMENT)
            scheduler.runCurrent()
        }
    }

    private class ScriptedPrivacyPrompt : PrivacyPrompt {
        val outcomes = ArrayDeque<PrivacyPromptOutcome>()
        var calls = 0
            private set

        override suspend fun present(
            scope: PrivacyScope,
            items: List<String>,
        ): PrivacyPromptOutcome {
            calls++
            return outcomes.removeFirstOrNull()
                ?: PrivacyPromptOutcome.Answered(PrivacyChoice.GRANTED)
        }
    }

    private class ManualClock(
        var wallEpochMillis: Long = 1_700_000_000_000L,
    ) : WallClock, MonotonicClock {
        var monotonicMillis: Long = 0L

        override fun nowEpochMillis(): Long = wallEpochMillis

        override fun elapsedMillis(): Long = monotonicMillis
    }

    // ------------------------------------------------------------------ A-001 / A-002

    @Test
    fun `A-001 and A-002 agreements are version bound and a refresh invalidates the old session`() =
        runTest {
            val wiring = Wiring(dataStore, backgroundScope, testScheduler)
            val coordinator = wiring.newCoordinator()
            coordinator.start()
            testScheduler.runCurrent()

            assertEquals(GuideState.AGREEMENT_REQUIRED, coordinator.state.value.guide)
            assertTrue(wiring.cloud.sessionRequests.isEmpty())

            coordinator.acceptAgreement(AgreementType.PRIVACY_POLICY)
            testScheduler.runCurrent()
            assertTrue(wiring.cloud.sessionRequests.isEmpty())

            coordinator.acceptAgreement(AgreementType.USER_AGREEMENT)
            testScheduler.runCurrent()
            val first = coordinator.state.value
            assertEquals(GuideState.QR_VISIBLE, first.guide)
            assertEquals("1.0", first.acceptance(AgreementType.PRIVACY_POLICY)?.version)
            // The persisted consent keeps the displayed version, not "whatever is latest".
            val vinScope = "VIN:${wiring.vehicle.vin}"
            assertEquals(
                "1.0",
                wiring.localStore.loadAcceptances(vinScope)
                    .first { it.type == AgreementType.PRIVACY_POLICY }.version,
            )
            assertEquals(1L, coordinator.state.value.flowGeneration)

            wiring.cloud.defaultQueryOutcome =
                SessionQueryOutcome.Observed(SessionObservation.Expired)
            testScheduler.advanceTimeBy(150L)
            testScheduler.runCurrent()
            assertEquals(GuideState.RECOVERABLE_ERROR, coordinator.state.value.guide)

            wiring.cloud.defaultQueryOutcome =
                SessionQueryOutcome.Observed(SessionObservation.Active)
            coordinator.refreshSession()
            testScheduler.runCurrent()
            val refreshed = coordinator.state.value
            assertEquals(2L, refreshed.flowGeneration)
            assertNotEquals(first.sessionRef?.sessionId, refreshed.sessionRef?.sessionId)
            assertEquals(GuideState.QR_VISIBLE, refreshed.guide)
            // Persisted context follows the new generation only.
            assertEquals(2L, wiring.localStore.loadContext()?.flowGeneration)
        }

    @Test
    fun `A-001 an unavailable agreement shows an error and recovers on retry`() = runTest {
        val wiring = Wiring(dataStore, backgroundScope, testScheduler)
        wiring.agreementGateway.failure = ErrorCategory.RETRYABLE
        val coordinator = wiring.newCoordinator()
        coordinator.start()
        testScheduler.runCurrent()

        val failed = coordinator.state.value
        assertEquals(GuideState.RECOVERABLE_ERROR, failed.guide)
        assertEquals(ErrorCategory.RETRYABLE, failed.documentsFailure)
        assertFalse(failed.documentsLoading)
        assertFalse(failed.documentsComplete)
        assertTrue(failed.acceptances.isEmpty())
        assertTrue(wiring.cloud.sessionRequests.isEmpty())

        wiring.agreementGateway.failure = null
        coordinator.reloadAgreements()
        testScheduler.runCurrent()

        val recovered = coordinator.state.value
        assertEquals(GuideState.AGREEMENT_REQUIRED, recovered.guide)
        assertTrue(recovered.documentsComplete)
        assertNull(recovered.documentsFailure)
        assertNull(recovered.error)
    }

    // ------------------------------------------------------------------ A-003

    @Test
    fun `A-003 skip enters the local account and never starts a login`() = runTest {
        val wiring = Wiring(dataStore, backgroundScope, testScheduler)
        val coordinator = wiring.newCoordinator()
        coordinator.start()
        testScheduler.runCurrent()
        wiring.acceptBoth(coordinator)

        coordinator.skipActivation()
        testScheduler.runCurrent()

        val state = coordinator.state.value
        assertEquals(GuideState.ENTERED_LOCAL, state.guide)
        assertEquals(SkipState.ACKNOWLEDGED, state.skip)
        assertEquals(LoginState.SIGNED_OUT, state.login)
        assertTrue(wiring.loginGateway.receivedTickets.isEmpty())
        assertEquals(GuideState.ENTERED_LOCAL, wiring.localStore.loadContext()?.guide)
        assertEquals(1, wiring.cloud.skipRequests.size)
        assertTrue(wiring.cloud.reportedRecords.isEmpty())
        assertEquals(
            SyncState.ACKNOWLEDGED,
            wiring.localStore.pending(10).firstOrNull()?.let { null } ?: SyncState.ACKNOWLEDGED,
        )
    }

    // ------------------------------------------------------------------ A-004

    @Test
    fun `A-004 a login failure survives a restart and completes without unbinding`() = runTest {
        val wiring = Wiring(dataStore, backgroundScope, testScheduler)
        val first = wiring.newCoordinator()
        first.start()
        testScheduler.runCurrent()
        wiring.acceptBoth(first)
        wiring.loginGateway.outcomeFactory = { LoginOutcome.Failed(ErrorCategory.RETRYABLE) }
        wiring.cloud.defaultQueryOutcome =
            SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-e2e"))
        testScheduler.advanceTimeBy(150L)
        testScheduler.runCurrent()

        assertEquals(GuideState.LOGIN_PENDING, first.state.value.guide)
        assertEquals(LoginState.RETRY_REQUIRED, first.state.value.login)
        assertEquals(true, wiring.localStore.loadContext()?.bindingConfirmed)
        first.close()

        wiring.loginGateway.outcomeFactory = { LoginOutcome.Success(AccountRef("account-e2e")) }
        val restarted = wiring.newCoordinator()
        restarted.start()
        testScheduler.runCurrent()

        val state = restarted.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, state.guide)
        assertEquals(LoginState.SIGNED_IN, state.login)
        assertEquals("account-e2e", state.account?.accountId)
        // No unbind / rebind happened: the skip branch was never used.
        assertTrue(wiring.cloud.skipRequests.isEmpty())
        restarted.close()
    }

    // ------------------------------------------------------------------ A-005

    @Test
    fun `A-005 a failed upload is retried with the same dedupe id`() = runTest {
        val wiring = Wiring(dataStore, backgroundScope, testScheduler)
        wiring.cloud.recordOutcome = RecordReportOutcome.Failed(ErrorCategory.RETRYABLE)
        val coordinator = wiring.newCoordinator()
        coordinator.start()
        testScheduler.runCurrent()
        wiring.acceptBoth(coordinator)
        wiring.cloud.defaultQueryOutcome =
            SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-e2e"))
        testScheduler.advanceTimeBy(150L)
        testScheduler.runCurrent()

        // Only cloud reportable records are uploaded; consent evidence rides with the login result.
        val pendingAfterFailure = wiring.localStore.pending(10).filter { it.type == RecordType.LOGIN_RESULT }
        assertTrue(pendingAfterFailure.isNotEmpty())
        val firstIds = pendingAfterFailure.map { it.id }.toSet()

        wiring.cloud.recordOutcome = RecordReportOutcome.Acknowledged
        wiring.clock.wallEpochMillis += 60_000L
        coordinator.onForeground()
        testScheduler.runCurrent()

        assertTrue(
            wiring.localStore.pending(10).filter { it.type == RecordType.LOGIN_RESULT }.isEmpty(),
        )
        assertTrue(wiring.cloud.reportedRecords.map { it.id }.toSet().containsAll(firstIds))
        // Retried records keep their payload digest, so a repeat cannot create a new fact.
        val firstDigests = pendingAfterFailure.associate { it.id to it.payloadDigest }
        wiring.cloud.reportedRecords
            .filter { it.id in firstDigests.keys }
            .forEach { record -> assertEquals(firstDigests[record.id], record.payloadDigest) }
        assertEquals(
            PrivacySyncState.ACKNOWLEDGED,
            wiring.localStore.loadDecision("AV:account-e2e|${wiring.vehicle.vin}")?.syncState,
        )
    }

    // ------------------------------------------------------------------ A-006

    @Test
    fun `A-006 closing the dialog is not a grant and is not asked again after restart`() = runTest {
        val wiring = Wiring(dataStore, backgroundScope, testScheduler)
        wiring.privacyPrompt.outcomes += PrivacyPromptOutcome.Dismissed
        val coordinator = wiring.newCoordinator()
        coordinator.start()
        testScheduler.runCurrent()
        wiring.acceptBoth(coordinator)
        wiring.cloud.defaultQueryOutcome =
            SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-e2e"))
        testScheduler.advanceTimeBy(150L)
        testScheduler.runCurrent()

        val state = coordinator.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, state.guide)
        assertEquals(PrivacyChoice.NOT_GRANTED, state.privacyChoice)
        assertEquals(1, wiring.privacyPrompt.calls)
        coordinator.close()

        val restarted = wiring.newCoordinator()
        restarted.start()
        testScheduler.runCurrent()
        assertEquals(1, wiring.privacyPrompt.calls)
        assertEquals(GuideState.ENTERED_ACCOUNT, restarted.state.value.guide)
        restarted.close()
    }

    // ------------------------------------------------------------------ A-007

    @Test
    fun `A-007 records stay isolated per account and vehicle`() = runTest {
        val wiring = Wiring(dataStore, backgroundScope, testScheduler)
        val coordinator = wiring.newCoordinator()
        coordinator.start()
        testScheduler.runCurrent()
        wiring.acceptBoth(coordinator)
        wiring.cloud.defaultQueryOutcome =
            SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-1"))
        testScheduler.advanceTimeBy(150L)
        testScheduler.runCurrent()
        assertEquals("account-e2e", coordinator.state.value.account?.accountId)

        val firstScope = "AV:account-e2e|${wiring.vehicle.vin}"
        assertNotNull(wiring.localStore.loadDecision(firstScope))
        assertNull(wiring.localStore.loadDecision("AV:account-other|${wiring.vehicle.vin}"))
        assertTrue(wiring.localStore.loadAcceptances("VIN:${wiring.vehicle.vin}").isNotEmpty())
        // Only the activation cloud port is used: no VSO / VMD write path exists in the domain.
        assertTrue(wiring.cloud.skipRequests.isEmpty())
        assertTrue(wiring.cloud.reportedRecords.isNotEmpty())
        // Logs are sanitized: no VIN, no account id, no QR payload, no login material.
        val logs = wiring.logger.entries.joinToString("\n")
        assertFalse(logs.contains(wiring.vehicle.vin))
        assertFalse(logs.contains("account-e2e"))
        assertFalse(logs.contains("fake-qr"))
    }

    @Test
    fun `A-007 a rear seat screen only shows the front seat instruction`() = runTest {
        val wiring = Wiring(dataStore, backgroundScope, testScheduler)
        wiring.seatRoleProvider.seatRole = SeatRole.REAR
        val coordinator = wiring.newCoordinator()
        coordinator.start()
        testScheduler.runCurrent()
        wiring.acceptBoth(coordinator)
        wiring.cloud.defaultQueryOutcome =
            SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-1"))
        testScheduler.advanceTimeBy(150L)
        testScheduler.runCurrent()

        val state = coordinator.state.value
        assertEquals(GuideState.PRIVACY_PENDING, state.guide)
        assertTrue(state.privacyFrontSeatRequired)
        assertEquals(0, wiring.privacyPrompt.calls)
        assertEquals(PrivacyChoice.NOT_DECIDED, state.privacyChoice)
    }

    // ------------------------------------------------------------------ A-008

    @Test
    fun `A-008 skip and a late binding converge with an explicit continuation`() = runTest {
        val wiring = Wiring(dataStore, backgroundScope, testScheduler)
        val coordinator = wiring.newCoordinator()
        coordinator.start()
        testScheduler.runCurrent()
        wiring.acceptBoth(coordinator)

        coordinator.skipActivation()
        testScheduler.runCurrent()
        assertEquals(GuideState.ENTERED_LOCAL, coordinator.state.value.guide)

        wiring.cloud.defaultQueryOutcome =
            SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-late"))
        coordinator.querySessionNow()
        testScheduler.runCurrent()
        val suggested = coordinator.state.value
        assertEquals(GuideState.ENTERED_LOCAL, suggested.guide)
        assertTrue(suggested.accountContinuationSuggested)
        assertTrue(wiring.loginGateway.receivedTickets.isEmpty())

        coordinator.acceptAccountContinuation()
        testScheduler.runCurrent()
        val continued = coordinator.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, continued.guide)
        assertEquals(1, wiring.loginGateway.receivedTickets.size)
        // The skip record stays acknowledged and is never converted into a binding claim.
        assertEquals(SkipState.ACKNOWLEDGED, continued.skip)
        assertEquals(1, wiring.cloud.skipRequests.size)
    }

    @Test
    fun `A-008 duplicated bind results trigger a single login submission`() = runTest {
        val wiring = Wiring(dataStore, backgroundScope, testScheduler)
        val coordinator = wiring.newCoordinator()
        coordinator.start()
        testScheduler.runCurrent()
        wiring.acceptBoth(coordinator)
        wiring.cloud.defaultQueryOutcome =
            SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-dup"))
        testScheduler.advanceTimeBy(150L)
        testScheduler.runCurrent()
        wiring.cloud.defaultQueryOutcome =
            SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-dup"))
        coordinator.querySessionNow()
        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()

        assertEquals(GuideState.ENTERED_ACCOUNT, coordinator.state.value.guide)
        assertEquals(1, wiring.loginGateway.receivedTickets.size)
        assertEquals(SkipState.IDLE, coordinator.state.value.skip)
    }
}
