package net.hwyz.iov.vehicle.ivi.acc.domain.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import net.hwyz.iov.vehicle.ivi.acc.domain.ActivationCoordinator
import net.hwyz.iov.vehicle.ivi.acc.domain.config.ActivationConfig
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AccountLoginGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCloudPort
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementLoadOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginTicketOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginTicketRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyConsentGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyPromptResult
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyScope
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.id.IdGenerator
import net.hwyz.iov.vehicle.ivi.acc.domain.log.RecordingDiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginTicket
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptance
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementBundle
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementDocument
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PrivacyDecision
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.RetryPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.SessionPollPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.store.AgreementAcceptanceStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.FlowContext
import net.hwyz.iov.vehicle.ivi.acc.domain.store.LocalFlowStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.PendingRecordStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.PrivacyDecisionStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.SecureMaterialStore
import net.hwyz.iov.vehicle.ivi.acc.domain.sync.RecordSyncEngine
import net.hwyz.iov.vehicle.ivi.acc.domain.time.MonotonicClock
import net.hwyz.iov.vehicle.ivi.acc.domain.time.WallClock

/** Builds a coordinator with in-memory collaborators and virtual time. */
fun harness(scope: CoroutineScope, scheduler: TestCoroutineScheduler): ActivationTestHarness =
    ActivationTestHarness(scope, scheduler)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivationTestHarness(
    private val scope: CoroutineScope,
    private val scheduler: TestCoroutineScheduler,
) {
    val vehicle = VehicleRef("LVIN0000000000001")
    val agreementGateway = FakeAgreementGateway()
    val cloud = FakeCloudPort()
    val loginGateway = FakeLoginGateway()
    val privacyGateway = FakePrivacyGateway()
    val catalogGateway = FakePrivacyCatalogGateway()
    val tokenEvents = FakeTokenLifecycleEvents()
    val flowStore = InMemoryFlowStore()
    val recordStore = InMemoryRecordStore()
    val agreementStore = InMemoryAgreementStore()
    val privacyStore = InMemoryPrivacyStore(recordStore)
    val materialStore = InMemoryMaterialStore()
    val clock = MutableClock()
    val logger = RecordingDiagnosticsLogger()
    val ids = FixedIdGenerator()
    /** Reassignable so a single test can exercise a different configuration. */
    var config = ActivationConfig(
        // R-13: a fixed 100 ms step; the deadline is the session expiry, so tests stay deterministic.
        sessionPoll = SessionPollPolicy(backoffMillis = listOf(100L), requestMarginMillis = 0L),
        recordRetry = RetryPolicy(
            maxAttempts = 2,
            initialBackoffMillis = 10L,
            maxBackoffMillis = 20L,
        ),
    )

    var coordinator: ActivationCoordinator = newCoordinator()
        private set

    /** Closes the current coordinator and builds a new one against the same stores. */
    fun restart(): ActivationCoordinator {
        coordinator.close()
        coordinator = newCoordinator()
        return coordinator
    }

    fun newCoordinator(): ActivationCoordinator = ActivationCoordinator(
        vehicle = vehicle,
        agreements = agreementGateway,
        cloud = cloud,
        loginGateway = loginGateway,
        privacyGateway = privacyGateway,
        flowStore = flowStore,
        acceptanceStore = agreementStore,
        privacyStore = privacyStore,
        recordStore = recordStore,
        recordSync = RecordSyncEngine(
            recordStore = recordStore,
            cloud = cloud,
            agreementStore = agreementStore,
            privacyStore = privacyStore,
            policy = config.recordRetry,
            wallClock = clock,
            logger = logger,
        ),
        secureMaterials = materialStore,
        config = config,
        monotonicClock = clock,
        wallClock = clock,
        logger = logger,
        ids = ids,
        scope = scope,
        dispatcher = StandardTestDispatcher(scheduler),
        privacyCatalog = catalogGateway,
        tokenEvents = tokenEvents,
        seatRoleProvider = { seatRole },
    )

    /** Seat role as seen by the domain; flipping it exercises the D-017 gates. */
    var seatRole: net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatRole =
        net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatRole.FRONT

    fun runCurrent() {
        scheduler.runCurrent()
    }

    fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    fun advanceUntilIdle() {
        scheduler.advanceUntilIdle()
    }

    fun acceptBothAgreements() {
        // DSN-CR-006: selecting is a reversible pre-selection; only confirmAgreements() submits.
        coordinator.selectAgreement(AgreementType.PRIVACY_POLICY, true)
        runCurrent()
        coordinator.selectAgreement(AgreementType.USER_AGREEMENT, true)
        runCurrent()
        coordinator.confirmAgreements()
        runCurrent()
    }

    fun startAndAcceptBoth() {
        coordinator.start()
        runCurrent()
        acceptBothAgreements()
    }
}

class FakeAgreementGateway(
    var outcome: AgreementLoadOutcome = AgreementLoadOutcome.Loaded(defaultBundle()),
) : AgreementGateway {
    var loadCount = 0
        private set

    /** Simulates a slow cloud so tests can observe the loading state. */
    var delayMillis: Long = 0L

    override suspend fun loadPublishedDocuments(): AgreementLoadOutcome {
        loadCount++
        if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis)
        return outcome
    }

    companion object {
        fun document(type: AgreementType, version: String = "1.0"): AgreementDocument =
            AgreementDocument(
                type = type,
                version = version,
                revisionId = "rev-$version",
                language = "zh-CN",
                contentHash = "hash-$version",
                body = "body-$version",
            )

        fun defaultBundle(): AgreementBundle = AgreementBundle(
            listOf(
                document(AgreementType.PRIVACY_POLICY),
                document(AgreementType.USER_AGREEMENT),
            ),
        )
    }
}

class FakeCloudPort : ActivationCloudPort {
    val sessionRequests = mutableListOf<SessionRequestInput>()
    val skipRequests = mutableListOf<SkipRequestInput>()
    val reportedRecordIds = mutableListOf<String>()

    var sessionOutcome: SessionRequestOutcome? = null
    val queryOutcomes = ArrayDeque<SessionQueryOutcome>()
    var defaultQueryOutcome: SessionQueryOutcome =
        SessionQueryOutcome.Observed(SessionObservation.Active)
    var skipOutcome: SkipSubmitOutcome = SkipSubmitOutcome.Acknowledged
    var ticketOutcome: LoginTicketOutcome = LoginTicketOutcome.Ticket(
        LoginTicket(ticket = "ticket-handle", expiresAtEpochMillis = Long.MAX_VALUE),
    )
    var recordOutcome: RecordReportOutcome = RecordReportOutcome.Acknowledged
    var queryCount = 0
        private set

    override suspend fun requestSession(input: SessionRequestInput): SessionRequestOutcome {
        sessionRequests += input
        return sessionOutcome ?: SessionRequestOutcome.Created(
            SessionRef(
                sessionId = "session-${input.flowGeneration}",
                qrPayload = "qr-secret-${input.flowGeneration}",
                issuedAtEpochMillis = 0L,
                expiresAtEpochMillis = Long.MAX_VALUE,
            ),
        )
    }

    /** When set, a query suspends on the gate, which lets a test observe the in flight state. */
    var queryGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun querySession(input: SessionQueryInput): SessionQueryOutcome {
        queryCount++
        queryGate?.await()
        return if (queryOutcomes.isNotEmpty()) queryOutcomes.removeFirst() else defaultQueryOutcome
    }

    override suspend fun submitSkip(input: SkipRequestInput): SkipSubmitOutcome {
        skipRequests += input
        return skipOutcome
    }

    override suspend fun issueLoginTicket(
        input: LoginTicketRequestInput,
    ): LoginTicketOutcome = ticketOutcome

    override suspend fun reportRecord(input: RecordReportInput): RecordReportOutcome {
        reportedRecordIds += input.record.id
        return recordOutcome
    }
}

class FakeLoginGateway(
    var outcome: LoginOutcome = LoginOutcome.Success(AccountRef("account-001")),
) : AccountLoginGateway {
    val receivedTickets = mutableListOf<LoginTicket>()
    var signOutCount = 0
        private set

    override suspend fun login(ticket: LoginTicket): LoginOutcome {
        receivedTickets += ticket
        return outcome
    }

    override suspend fun signOutLocally() {
        signOutCount++
    }
}

class FakePrivacyGateway(
    var result: PrivacyPromptResult = PrivacyPromptResult.Decided(
        net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice.GRANTED,
    ),
) : PrivacyConsentGateway {
    var calls = 0
        private set
    var lastScope: PrivacyScope? = null
        private set
    var lastItems: List<net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem> = emptyList()
        private set

    override suspend fun requestFirstConsent(
        scope: PrivacyScope,
        items: List<net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem>,
    ): PrivacyPromptResult {
        calls++
        lastScope = scope
        lastItems = items
        return result
    }
}

/** Catalogue gateway fake: D-013 three-state behaviour is driven through [outcome]. */
class FakePrivacyCatalogGateway(
    var outcome: net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogOutcome =
        net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogOutcome.Loaded(
            catalogVersion = "v1",
            items = listOf(
                net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem(
                    code = "PERSONALIZED_CONTENT",
                    displayName = "\u4e2a\u6027\u5316\u5185\u5bb9",
                    required = false,
                ),
            ),
        ),
) : net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationCatalogGateway {
    /**
     * Answer of every load after the first one (used by the 401118/401119 rebuild tests, where the
     * reload must observe a newer catalogue version than the one the decision was taken against).
     */
    var reloadOutcome: net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogOutcome? = null
    var loadCount = 0
        private set
    var lastLanguage: String? = null
        private set

    override suspend fun catalog(
        language: String,
    ): net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogOutcome {
        loadCount++
        lastLanguage = language
        return if (loadCount > 1) reloadOutcome ?: outcome else outcome
    }
}

/** Token lifecycle emission for D-016 tests. */
class FakeTokenLifecycleEvents : net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvents {
    private val flow = kotlinx.coroutines.flow.MutableSharedFlow<
        net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvent,
        >(replay = 0, extraBufferCapacity = 16)
    private val pending = mutableListOf<net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvent>()

    override fun events(): kotlinx.coroutines.flow.Flow<
        net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvent,
        > = flow

    /** Emits now (a subscriber is already collecting because the coordinator starts in the harness). */
    fun emit(event: net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvent) {
        pending += event
        flow.tryEmit(event)
    }

    val emitted: List<net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvent>
        get() = pending.toList()
}

class InMemoryFlowStore : LocalFlowStore {
    var context: FlowContext? = null
        private set
    var clearCount = 0
        private set

    override suspend fun save(context: FlowContext) {
        this.context = context
    }

    override suspend fun loadContext(): FlowContext? = context

    override suspend fun clear() {
        context = null
        clearCount++
    }
}

class InMemoryAgreementStore : AgreementAcceptanceStore {
    private val records = linkedMapOf<Pair<String, AgreementType>, AgreementAcceptance>()

    /** Number of persisted snapshot batches; DSN-CR-006 submits both agreements in one batch. */
    var saveAllCalls = 0
        private set

    override suspend fun saveAll(records: List<AgreementAcceptance>) {
        saveAllCalls++
        records.forEach { record -> this.records[record.scopeKey to record.type] = record }
    }

    override suspend fun loadAcceptances(scopeKey: String): List<AgreementAcceptance> =
        records.values.filter { it.scopeKey == scopeKey }

    override suspend fun markSync(scopeKey: String, sync: AgreementSyncState) {
        records.values
            .filter { it.scopeKey == scopeKey }
            .forEach { record -> records[record.scopeKey to record.type] = record.copy(syncState = sync) }
    }
}

class InMemoryPrivacyStore(
    private val recordStore: InMemoryRecordStore,
) : PrivacyDecisionStore {
    private val decisions = mutableMapOf<String, PrivacyDecision>()
    var failCommit = false

    override suspend fun loadDecision(scopeKey: String): PrivacyDecision? = decisions[scopeKey]

    override suspend fun isFirstInteractionCompleted(scopeKey: String): Boolean =
        decisions[scopeKey]?.firstInteractionCompleted == true

    override suspend fun commitDecision(decision: PrivacyDecision, record: PendingRecord): Boolean {
        if (failCommit) return false
        decisions[decision.scopeKey] = decision
        recordStore.enqueue(record)
        return true
    }

    override suspend fun markSync(scopeKey: String, sync: PrivacySyncState) {
        decisions[scopeKey]?.let { decisions[scopeKey] = it.copy(syncState = sync) }
    }
}

class InMemoryRecordStore : PendingRecordStore {
    val records = linkedMapOf<String, PendingRecord>()

    override suspend fun enqueue(record: PendingRecord) {
        records.putIfAbsent(record.id, record)
    }

    override suspend fun pending(limit: Int): List<PendingRecord> = records.values
        .filter { it.syncState == SyncState.PENDING }
        .sortedBy { it.createdAtEpochMillis }
        .take(limit)

    override suspend fun markAttempt(id: String, attempts: Int, nextEligibleAtEpochMillis: Long) {
        records[id]?.let {
            records[id] = it.copy(attempts = attempts, nextEligibleAtEpochMillis = nextEligibleAtEpochMillis)
        }
    }

    override suspend fun markAcknowledged(id: String) {
        records[id]?.let { records[id] = it.copy(syncState = SyncState.ACKNOWLEDGED) }
    }

    override suspend fun markFailed(id: String, attempts: Int) {
        records[id]?.let { records[id] = it.copy(syncState = SyncState.FAILED_TERMINAL, attempts = attempts) }
    }

    override suspend fun clearAll() {
        records.clear()
    }
}

class InMemoryMaterialStore : SecureMaterialStore {
    val entries = mutableMapOf<String, String>()

    override suspend fun putSealed(key: String, secret: String, expiresAtEpochMillis: Long) {
        entries[key] = secret
    }

    override suspend fun sealed(key: String): String? = entries[key]

    override suspend fun clear(key: String) {
        entries.remove(key)
    }

    override suspend fun clearAll() {
        entries.clear()
    }
}

/** Wall clock and monotonic clock with manual control. */
class MutableClock(
    var wallEpochMillis: Long = 1_700_000_000_000L,
) : WallClock, MonotonicClock {
    var monotonicMillis: Long = 0L

    override fun nowEpochMillis(): Long = wallEpochMillis

    override fun elapsedMillis(): Long = monotonicMillis
}

class FixedIdGenerator : IdGenerator {
    private var counter = 0

    override fun next(prefix: String): String = "$prefix-${counter++}"

    override fun stable(prefix: String, vararg parts: String): String =
        "$prefix-${PendingRecord.sha256(parts.joinToString("|")).take(8)}"
}
