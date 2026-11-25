package net.hwyz.iov.vehicle.ivi.acc.domain

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.acc.domain.config.ActivationConfig
import net.hwyz.iov.vehicle.ivi.acc.domain.error.ActivationErrorCatalog
import net.hwyz.iov.vehicle.ivi.acc.domain.error.DefaultActivationErrorCatalog
import net.hwyz.iov.vehicle.ivi.acc.domain.error.ErrorScope
import net.hwyz.iov.vehicle.ivi.acc.domain.event.ActivationEvent
import net.hwyz.iov.vehicle.ivi.acc.domain.event.RestoredFlow
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AccountLoginGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCloudPort
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementLoadOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginTicketOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginTicketRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationCatalogGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogUnavailableReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyConsentGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyItemDecision
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyItemState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyPromptResult
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyScope
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatBlockReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatGate
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatRole
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatRoleProvider
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenInvalidationReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvent
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvents
import net.hwyz.iov.vehicle.ivi.acc.domain.id.IdGenerator
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.log.mask
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptance
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptanceSnapshot
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.IviLoginFailureReason
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PrivacyDecision
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordPayload
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.ScopeKeys
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.SkipState
import net.hwyz.iov.vehicle.ivi.acc.domain.store.AgreementAcceptanceStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.FlowContext
import net.hwyz.iov.vehicle.ivi.acc.domain.store.LocalFlowStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.PendingRecordStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.PrivacyDecisionStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.SecureMaterialStore
import net.hwyz.iov.vehicle.ivi.acc.domain.sync.RecordSyncEngine
import net.hwyz.iov.vehicle.ivi.acc.domain.time.MonotonicClock
import net.hwyz.iov.vehicle.ivi.acc.domain.time.WallClock

/**
 * The single car side entry point of the activation flow (design D-001).
 *
 * All user actions, network results and lifecycle events are serialized through one intent
 * channel, so two asynchronous callbacks can never interleave their state transitions. Screens
 * only observe [state]; they never mutate it.
 */
class ActivationCoordinator(
    private val vehicle: VehicleRef,
    private val agreements: AgreementGateway,
    private val cloud: ActivationCloudPort,
    private val loginGateway: AccountLoginGateway,
    private val privacyGateway: PrivacyConsentGateway,
    private val flowStore: LocalFlowStore,
    private val acceptanceStore: AgreementAcceptanceStore,
    private val privacyStore: PrivacyDecisionStore,
    private val recordStore: PendingRecordStore,
    private val recordSync: RecordSyncEngine,
    private val secureMaterials: SecureMaterialStore,
    private val config: ActivationConfig,
    private val monotonicClock: MonotonicClock,
    private val wallClock: WallClock,
    private val logger: DiagnosticsLogger,
    private val ids: IdGenerator,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Cloud authoritative privacy authorization catalogue (D-013). A caller that does not pass one
     * gets `UNAVAILABLE(CATALOG_FAILED)`, never a local fallback list (R-5).
     */
    private val privacyCatalog: PrivacyAuthorizationCatalogGateway =
        PrivacyAuthorizationCatalogGateway.None,
    /** Token lifecycle events (D-016); defaults to "no owner", which emits nothing. */
    private val tokenEvents: TokenLifecycleEvents = TokenLifecycleEvents.None,
    /** Seat role (D-017). Defaults to the front seat so a caller that does not care is unaffected. */
    private val seatRoleProvider: SeatRoleProvider = SeatRoleProvider { SeatRole.FRONT },
    /** Central error code catalogue (D-015). */
    private val errorCatalog: ActivationErrorCatalog = DefaultActivationErrorCatalog,
) {

    private val _state = MutableStateFlow(ActivationState())
    val state: StateFlow<ActivationState> = _state.asStateFlow()

    private val intents = Channel<Intent>(Channel.UNLIMITED)
    private var pollJob: Job? = null
    private var loginSubmitted = false

    /** Single flight guard shared by the poll loop and "query now" (D-014). */
    private val queryInFlight = AtomicBoolean(false)

    private val lastRevocationAt = mutableMapOf<TokenInvalidationReason, Long>()

    /** Set when the cloud asked for a catalogue reload before a privacy report is re-sent. */
    private var catalogReloadPending = false
    private var latestCatalogVersion: String? = null

    private val handlerJob: Job = scope.launch(dispatcher) {
        for (intent in intents) {
            handle(intent)
        }
    }

    private val tokenEventJob: Job = scope.launch(dispatcher) {
        tokenEvents.events().collect { event -> intents.trySend(Intent.TokenEvent(event)) }
    }

    init {
        // A retryable report failure may require a catalogue reload before the record is re-sent.
        recordSync.onRetryableFailure = { record, category, businessCode ->
            onReportRetryRequired(record, category, businessCode)
        }
    }

    fun start() {
        intents.trySend(Intent.Start)
    }

    fun reloadAgreements() {
        intents.trySend(Intent.ReloadDocuments)
    }

    fun acceptAgreement(type: AgreementType) {
        // Compatibility alias (DSN-CR-006): selecting is not consenting any more. Callers that mean
        // "提交同意" must call confirmAgreements() once both checkboxes are selected.
        intents.trySend(Intent.Accept(type))
    }

    /** Pre-submit checkbox toggle; reversible and never reaches the cloud (DSN-CR-006). */
    fun selectAgreement(type: AgreementType, selected: Boolean) {
        intents.trySend(Intent.Select(type, selected))
    }

    /** Submits both agreements, which allocates the activation session (DSN-CR-006). */
    fun confirmAgreements() {
        intents.trySend(Intent.ConfirmAgreements)
    }

    /** User or expiry driven refresh: a new flow generation and therefore a new idempotency key. */
    fun refreshSession() {
        intents.trySend(Intent.Refresh)
    }

    /**
     * Transport retry of the same session request intent: keeps the flow generation and therefore the
     * same `Idempotency-Key`, so the cloud replays the same session (BLK-7).
     */
    fun retrySessionRequest() {
        intents.trySend(Intent.RetrySession)
    }

    /** On demand query; ignored while another query is in flight (single flight, D-014). */
    fun querySessionNow() {
        intents.trySend(Intent.QueryNow)
    }

    /** Long press skip on the QR screen. Only one local submission is allowed. */
    fun skipActivation() {
        intents.trySend(Intent.Skip)
    }

    fun retrySkip() {
        intents.trySend(Intent.RetrySkip)
    }

    fun retryLogin() {
        intents.trySend(Intent.RetryLogin)
    }

    fun decidePrivacy(choice: PrivacyChoice) {
        intents.trySend(Intent.Privacy(choice))
    }

    fun retryPrivacyPrompt() {
        intents.trySend(Intent.RetryPrivacy)
    }

    /** Retry entry of the privacy catalogue when the items could not be loaded (D-013). */
    fun retryPrivacyCatalog() {
        intents.trySend(Intent.RetryPrivacy)
    }

    /** Re-evaluates the seat gate, e.g. after the user switched screens (D-017). */
    fun onSeatChanged() {
        intents.trySend(Intent.SeatChanged)
    }

    /** User accepted continuing with the account after a late binding result during local entry. */
    fun acceptAccountContinuation() {
        intents.trySend(Intent.Continuation)
    }

    fun onForeground() {
        intents.trySend(Intent.Foreground)
    }

    fun onBackground() {
        intents.trySend(Intent.Background)
    }

    /** Clears local guide state only; the cloud relationship and activation facts are untouched. */
    fun resetLocalStateForFactoryReset() {
        intents.trySend(Intent.Reset)
    }

    fun close() {
        pollJob?.cancel()
        tokenEventJob.cancel()
        intents.close()
        handlerJob.cancel()
    }

    private sealed interface Intent {
        data object Start : Intent
        data object ReloadDocuments : Intent
        data class Accept(val type: AgreementType) : Intent
        data class Select(val type: AgreementType, val selected: Boolean) : Intent
        data object ConfirmAgreements : Intent
        data object Refresh : Intent
        data object RetrySession : Intent
        data object QueryNow : Intent
        data object Skip : Intent
        data object RetrySkip : Intent
        data object RetryLogin : Intent
        data object Continuation : Intent
        data object SeatChanged : Intent
        data class Privacy(val choice: PrivacyChoice) : Intent
        data object RetryPrivacy : Intent
        data object Foreground : Intent
        data object Background : Intent
        data object Reset : Intent
        data class TokenEvent(val event: TokenLifecycleEvent) : Intent

        data class Observed(
            val generation: Long,
            val sessionId: String,
            val observation: SessionObservation,
        ) : Intent

        data class QueryFailed(
            val generation: Long,
            val sessionId: String,
            val category: ErrorCategory,
        ) : Intent
    }

    private suspend fun handle(intent: Intent) {
        when (intent) {
            Intent.Start -> onStart()
            Intent.ReloadDocuments -> loadDocuments()
            is Intent.Accept -> onSelect(intent.type, selected = true)
            is Intent.Select -> onSelect(intent.type, intent.selected)
            Intent.ConfirmAgreements -> onConfirmAgreements()
            Intent.Refresh -> prepareSession(newGeneration = true)
            Intent.RetrySession -> prepareSession(newGeneration = false)
            Intent.QueryNow -> queryNow()
            Intent.Skip -> submitSkip()
            Intent.RetrySkip -> handleRetrySkip()
            Intent.RetryLogin -> startLogin()
            Intent.Continuation -> {
                dispatch(ActivationEvent.AccountContinuationAccepted)
                startLogin()
            }

            Intent.SeatChanged -> dispatch(ActivationEvent.SeatGateEvaluated(currentSeatBlock()))
            is Intent.Privacy -> commitPrivacyIfSignedIn(intent.choice)
            Intent.RetryPrivacy -> ensurePrivacyCatalogAndPrompt()
            Intent.Foreground -> handleForeground()
            Intent.Background -> handleBackground()
            Intent.Reset -> onLocalReset()
            is Intent.TokenEvent -> onTokenEvent(intent.event)
            is Intent.Observed -> onObserved(intent)
            is Intent.QueryFailed -> onQueryFailed(intent)
        }
    }

    // ------------------------------------------------------------------ start / restore

    private suspend fun onStart() {
        val context = flowStore.loadContext()
        val restored = context?.let { restoredFlow(it) }
        dispatch(ActivationEvent.FlowStarted(restored))
        // D-017: the seat gate is evaluated before anything can reach the cloud.
        dispatch(ActivationEvent.SeatGateEvaluated(currentSeatBlock()))
        logger.event(
            "flow.started",
            "restored" to (restored != null).toString(),
            "guide" to _state.value.guide.name,
            "seat" to (currentSeatBlock()?.name ?: "FRONT"),
        )
        loadDocuments()
        val current = _state.value
        when {
            current.guide == GuideState.LOGIN_PENDING && current.sessionBound -> reconcileAndLogin()
            current.entertainmentEntryAllowed -> flushRecords()
            else -> Unit
        }
    }

    private suspend fun restoredFlow(context: FlowContext): RestoredFlow {
        val vinScope = ScopeKeys.vehicle(vehicle)
        val stored = acceptanceStore.loadAcceptances(vinScope)
        val acceptances = stored.map {
            AgreementAcceptanceSnapshot(
                type = it.type,
                version = it.version,
                revisionId = it.revisionId,
                contentHash = it.contentHash,
                language = it.language,
                agreedAtEpochMillis = it.agreedAtEpochMillis,
            )
        }
        val agreementSync = when {
            stored.isEmpty() -> AgreementSyncState.NOT_ACCEPTED
            stored.all { it.syncState == AgreementSyncState.ACKNOWLEDGED } ->
                AgreementSyncState.ACKNOWLEDGED

            else -> AgreementSyncState.PENDING
        }
        val accountScope = context.accountId?.let { ScopeKeys.accountVehicle(it, vehicle.vin) }
        val privacy = accountScope?.let { privacyStore.loadDecision(it) }
        return RestoredFlow(
            flowGeneration = context.flowGeneration,
            guide = context.guide,
            sessionId = context.sessionId,
            sessionExpiresAtEpochMillis = context.sessionExpiresAtEpochMillis,
            bindingOperationId = context.bindingOperationId,
            bindingConfirmed = context.bindingConfirmed,
            login = context.login,
            accountId = context.accountId,
            acceptances = acceptances,
            agreementSync = agreementSync,
            privacyChoice = privacy?.choice ?: PrivacyChoice.NOT_DECIDED,
            privacySync = privacy?.syncState ?: PrivacySyncState.NOT_SYNCED,
            privacyFirstInteractionCompleted = privacy?.firstInteractionCompleted ?: false,
        )
    }

    // ------------------------------------------------------------------ seat gate (D-017)

    private fun currentSeatBlock(): SeatBlockReason? = SeatGate.evaluate(seatRoleProvider.currentSeat())

    /** Returns true when the action must be refused on this screen. */
    private fun blockRearSeat(action: String): Boolean {
        val blocked = currentSeatBlock() ?: return false
        dispatch(ActivationEvent.SeatBlocked(blocked))
        logger.event("seat.blocked", "action" to action, "reason" to blocked.name)
        return true
    }

    // ------------------------------------------------------------------ agreements

    private suspend fun loadDocuments() {
        dispatch(ActivationEvent.DocumentsLoadStarted)
        when (val outcome = agreements.loadPublishedDocuments()) {
            is AgreementLoadOutcome.Loaded -> {
                if (!outcome.bundle.isComplete) {
                    // Incomplete content or unidentifiable version: never let the flow pass.
                    dispatch(ActivationEvent.DocumentsLoadFailed(ErrorCategory.CONTRACT_REJECTED))
                    logger.event("agreement.incomplete")
                    return
                }
                dispatch(ActivationEvent.DocumentsLoaded(outcome.bundle.documents))
                if (_state.value.agreementVersionDrift) {
                    logger.event("agreement.version_drift", "guide" to _state.value.guide.name)
                }
                persistFlowContext()
            }

            is AgreementLoadOutcome.Failed -> {
                dispatch(ActivationEvent.DocumentsLoadFailed(outcome.category))
                logger.event("agreement.load_failed", "category" to outcome.category.name)
            }
        }
    }

    private suspend fun onSelect(type: AgreementType, selected: Boolean) {
        val current = _state.value
        if (current.entertainmentEntryAllowed) return
        // D-017 gate 1: the rear seat may read the documents but must not select or confirm them.
        if (blockRearSeat("select_agreement")) return
        // Submitted consent is frozen (DSN-CR-006): the checkbox is read only from then on.
        if (current.acceptances.isNotEmpty()) return
        val document = current.document(type)
        if (document == null || !document.isUsable) {
            dispatch(
                ActivationEvent.ErrorRaised(
                    ActivationOperation.LOAD_AGREEMENTS,
                    ErrorCategory.CONTRACT_REJECTED,
                ),
            )
            return
        }
        dispatch(ActivationEvent.AgreementSelected(type = type, selected = selected))
        logger.event("agreement.selected", "type" to type.name, "selected" to selected.toString())
    }

    /**
     * Submits both agreements and starts the session (DSN-CR-006).
     *
     * Preconditions are re-validated here: a screen that calls it with an incomplete selection must
     * not reach the cloud, and a rear seat must not submit anything.
     */
    private suspend fun onConfirmAgreements() {
        val current = _state.value
        if (current.entertainmentEntryAllowed) return
        if (blockRearSeat("confirm_agreements")) return
        if (current.acceptances.isNotEmpty()) return
        if (!current.documentsComplete) {
            dispatch(
                ActivationEvent.ErrorRaised(
                    ActivationOperation.LOAD_AGREEMENTS,
                    ErrorCategory.CONTRACT_REJECTED,
                ),
            )
            return
        }
        val missing = AgreementType.entries.filterNot { it in current.agreementSelection }
        if (missing.isNotEmpty()) {
            // No cloud request and no consent fact: the screen keeps the "please select" prompt.
            logger.event(
                "agreement.confirm_rejected",
                "missing" to missing.joinToString(",") { it.name },
            )
            return
        }
        val agreedAt = wallClock.nowEpochMillis()
        for (type in AgreementType.entries) {
            val document = current.document(type)
            if (document == null || !document.isUsable) {
                dispatch(
                    ActivationEvent.ErrorRaised(
                        ActivationOperation.LOAD_AGREEMENTS,
                        ErrorCategory.CONTRACT_REJECTED,
                    ),
                )
                return
            }
            dispatch(
                ActivationEvent.AgreementAccepted(
                    type = type,
                    version = document.version,
                    revisionId = document.revisionId,
                    contentHash = document.contentHash,
                    language = document.language,
                    agreedAtEpochMillis = agreedAt,
                ),
            )
        }
        persistAcceptances()
        persistFlowContext()
        logger.event(
            "agreement.confirmed",
            "count" to _state.value.acceptances.size.toString(),
        )
        if (_state.value.agreementsAccepted) prepareSession(newGeneration = true)
    }

    private suspend fun persistAcceptances() {
        val scopeKey = ScopeKeys.vehicle(vehicle)
        val records = _state.value.acceptances.map { snapshot ->
            AgreementAcceptance(
                scopeKey = scopeKey,
                type = snapshot.type,
                version = snapshot.version,
                revisionId = snapshot.revisionId,
                contentHash = snapshot.contentHash,
                language = snapshot.language,
                agreedAtEpochMillis = snapshot.agreedAtEpochMillis,
                dedupeId = ids.stable(
                    "agr",
                    scopeKey,
                    snapshot.type.name,
                    snapshot.version,
                    snapshot.revisionId ?: "-",
                ),
                syncState = AgreementSyncState.PENDING,
            )
        }
        if (records.isNotEmpty()) acceptanceStore.saveAll(records)
    }

    // ------------------------------------------------------------------ session

    /**
     * Creates (or re-creates) the activation session.
     *
     * [newGeneration] distinguishes the two idempotency semantics of BLK-7:
     * - `true` (user refresh / expiry refresh): new flow generation -> new key -> new session;
     * - `false` (transport retry of the same intent): same generation -> same key -> replay.
     */
    private suspend fun prepareSession(newGeneration: Boolean) {
        val current = _state.value
        if (current.entertainmentEntryAllowed) return
        if (!current.documentsComplete) {
            dispatch(
                ActivationEvent.ErrorRaised(
                    ActivationOperation.LOAD_AGREEMENTS,
                    ErrorCategory.CONTRACT_REJECTED,
                ),
            )
            return
        }
        if (!current.agreementsAccepted) {
            dispatch(
                ActivationEvent.ErrorRaised(
                    ActivationOperation.REQUEST_SESSION,
                    ErrorCategory.CONTRACT_REJECTED,
                ),
            )
            return
        }
        if (newGeneration) {
            val generation = current.flowGeneration + 1
            pollJob?.cancel()
            dispatch(ActivationEvent.SessionPreparationStarted(generation))
            persistFlowContext()
        }
        val state = _state.value
        val references = state.acceptances.map { it.toReference() }
        val requestId = sessionIdempotencyKey(state.flowGeneration, references)
        requestSession(requestId, references, state.flowGeneration)
    }

    private suspend fun requestSession(
        requestId: String,
        references: List<AgreementReference>,
        generation: Long,
    ) {
        val input = SessionRequestInput(vehicle, references, generation, requestId)
        when (val outcome = cloud.requestSession(input)) {
            is SessionRequestOutcome.Created -> {
                dispatch(ActivationEvent.SessionCreated(generation, outcome.session))
                persistFlowContext()
                logger.event(
                    "session.created",
                    "generation" to generation.toString(),
                    "expires_at" to outcome.session.expiresAtEpochMillis.toString(),
                )
                startPolling(generation, outcome.session)
            }

            is SessionRequestOutcome.Failed -> {
                dispatch(ActivationEvent.SessionRequestFailed(generation, outcome.category))
                persistFlowContext()
                logger.event(
                    "session.request_failed",
                    "category" to outcome.category.name,
                    "retryable" to outcome.category.isRetryableCategory().toString(),
                )
            }
        }
    }

    /**
     * BLK-7 idempotency key. Derived from the intent (generation + agreement digest), so a transport
     * retry derives the same key while a refresh (new generation) derives a different one.
     */
    private fun sessionIdempotencyKey(generation: Long, references: List<AgreementReference>): String =
        ids.stable(
            "session",
            vehicle.vin,
            generation.toString(),
            agreementsDigest(references),
        )

    private fun agreementsDigest(references: List<AgreementReference>): String =
        PendingRecord.sha256(
            references
                .sortedBy { it.type.name }
                .joinToString("|") { reference ->
                    "${reference.type}:${reference.version}:${reference.revisionId ?: "-"}:" +
                        "${reference.contentHash ?: "-"}:${reference.language ?: "-"}"
                },
        )

    /**
     * Polls until the session `expiresAt` (D-014 / R-13), with a per request margin.
     *
     * There is no local attempt cap and no hard cap: as long as the session is valid, a late phone
     * side binding must still be observed. The backoff ladder only advances on undecided polls.
     */
    private fun startPolling(generation: Long, session: SessionRef) {
        pollJob?.cancel()
        pollJob = scope.launch(dispatcher) {
            val policy = config.sessionPoll
            val startedAt = monotonicClock.elapsedMillis()
            var step = 0
            var decided = false
            var reachedDeadline = false
            if (policy.shortWindow(wallClock.nowEpochMillis(), session.expiresAtEpochMillis)) {
                logger.event(
                    "session.poll_short_window",
                    "remaining" to policy
                        .remainingBudgetMillis(wallClock.nowEpochMillis(), session.expiresAtEpochMillis)
                        .toString(),
                )
            }
            while (!decided) {
                val now = wallClock.nowEpochMillis()
                val current = _state.value
                if (!pollingStillValid(current, generation, session)) return@launch
                val delayMillis = policy.attemptDelayMillis(step, now, session.expiresAtEpochMillis)
                if (delayMillis == null) {
                    reachedDeadline = true
                    break
                }
                delay(delayMillis)
                val resumed = _state.value
                if (!pollingStillValid(resumed, generation, session)) return@launch
                if (!resumed.foreground) {
                    // Background: no request, but the deadline is not abandoned.
                    step++
                    continue
                }
                step++
                val outcome = querySingleFlight(
                    generation = generation,
                    session = session,
                ) ?: continue
                when (outcome) {
                    is SessionQueryOutcome.Observed -> {
                        intents.trySend(
                            Intent.Observed(generation, session.sessionId, outcome.observation),
                        )
                        decided = outcome.observation !is SessionObservation.Active &&
                            outcome.observation != SessionObservation.ResultUnknown
                    }

                    is SessionQueryOutcome.Failed -> {
                        if (outcome.category == ErrorCategory.RESULT_UNKNOWN) {
                            intents.trySend(
                                Intent.Observed(
                                    generation,
                                    session.sessionId,
                                    SessionObservation.ResultUnknown,
                                ),
                            )
                        } else {
                            intents.trySend(
                                Intent.QueryFailed(generation, session.sessionId, outcome.category),
                            )
                            decided = !outcome.category.isRetryableCategory()
                        }
                    }
                }
            }
            val elapsed = monotonicClock.elapsedMillis() - startedAt
            logger.event("session.poll_stopped", "elapsed_ms" to elapsed.toString(), "decided" to decided.toString())
            if (decided) return@launch
            val last = _state.value
            if (reachedDeadline && pollingStillValid(last, generation, session) &&
                last.guide == GuideState.QR_VISIBLE
            ) {
                // The session deadline is reached: the result stays unknown, never a failure.
                logger.event("session.poll_deadline", "generation" to generation.toString())
                intents.trySend(
                    Intent.Observed(generation, session.sessionId, SessionObservation.ResultUnknown),
                )
            }
        }
    }

    private fun pollingStillValid(
        state: ActivationState,
        generation: Long,
        session: SessionRef,
    ): Boolean {
        if (state.flowGeneration != generation) return false
        if (state.sessionRef?.sessionId != session.sessionId) return false
        return state.guide == GuideState.QR_VISIBLE || state.guide == GuideState.SESSION_PREPARING
    }

    /**
     * One query at a time across the poll loop and the manual entry point (D-014).
     *
     * Returns null when another query is already in flight: the caller drops the click instead of
     * stacking requests, and the backoff ladder is not reset.
     */
    private suspend fun querySingleFlight(
        generation: Long,
        session: SessionRef,
    ): SessionQueryOutcome? {
        if (!queryInFlight.compareAndSet(false, true)) {
            logger.event("session.query_dropped", "reason" to "single_flight")
            return null
        }
        return try {
            cloud.querySession(SessionQueryInput(vehicle, session, generation))
        } finally {
            queryInFlight.set(false)
        }
    }

    private suspend fun queryNow() {
        val current = _state.value
        val session = current.sessionRef ?: return
        if (!current.foreground) return
        val outcome = querySingleFlight(current.flowGeneration, session) ?: return
        when (outcome) {
            is SessionQueryOutcome.Observed -> onObserved(
                Intent.Observed(current.flowGeneration, session.sessionId, outcome.observation),
            )

            is SessionQueryOutcome.Failed -> onQueryFailed(
                Intent.QueryFailed(current.flowGeneration, session.sessionId, outcome.category),
            )
        }
    }

    private suspend fun onObserved(intent: Intent.Observed) {
        dispatch(
            ActivationEvent.SessionObserved(intent.generation, intent.sessionId, intent.observation),
        )
        persistFlowContext()
        val current = _state.value
        when {
            current.entertainmentEntryAllowed -> pollJob?.cancel()
            current.sessionBound && current.guide == GuideState.LOGIN_PENDING -> {
                if (current.skipInProgress || current.accountContinuationSuggested) {
                    reconcileAndLogin()
                } else {
                    startLogin()
                }
            }

            current.guide == GuideState.RECOVERABLE_ERROR -> pollJob?.cancel()
        }
    }

    private fun onQueryFailed(intent: Intent.QueryFailed) {
        dispatch(
            ActivationEvent.SessionObserved(
                intent.generation,
                intent.sessionId,
                SessionObservation.ResultUnknown,
            ),
        )
        dispatch(ActivationEvent.ErrorRaised(ActivationOperation.QUERY_SESSION, intent.category))
        logger.event("session.query_failed", "category" to intent.category.name)
    }

    /** Re-check the authoritative session state before continuing after a conflict or restart. */
    private suspend fun reconcileAndLogin() {
        val current = _state.value
        val session = current.sessionRef ?: return
        if (!current.sessionBound) return
        when (val outcome = cloud.querySession(
            SessionQueryInput(vehicle, session, current.flowGeneration),
        )) {
            is SessionQueryOutcome.Observed -> {
                dispatch(
                    ActivationEvent.SessionObserved(
                        current.flowGeneration,
                        session.sessionId,
                        outcome.observation,
                    ),
                )
                logger.event("session.reconciled", "observation" to outcome.observation.javaClass.simpleName)
                if (outcome.observation is SessionObservation.BoundConfirmed) startLogin()
            }

            is SessionQueryOutcome.Failed -> {
                dispatch(
                    ActivationEvent.ErrorRaised(ActivationOperation.QUERY_SESSION, outcome.category),
                )
            }
        }
    }

    // ------------------------------------------------------------------ skip

    private suspend fun submitSkip() {
        val current = _state.value
        if (!current.qrVisible) return
        if (current.skip != SkipState.IDLE) return
        // D-017 gate 3: a rear seat long press must not produce a pending record.
        if (blockRearSeat("skip_activation")) return
        val sessionId = current.sessionRef?.sessionId
        val requestId = ids.stable("skip", vehicle.vin, sessionId ?: "-")
        val references = current.acceptances.map { it.toReference() }
        dispatch(ActivationEvent.SkipRequested)
        val now = wallClock.nowEpochMillis()
        recordStore.enqueue(
            PendingRecord.create(
                id = requestId,
                type = RecordType.SKIP_ACTIVATION,
                scopeKey = ScopeKeys.vehicle(vehicle),
                payload = RecordPayload.SkipActivation(vehicle.vin, references, sessionId, now),
                createdAtEpochMillis = now,
                userActionAtEpochMillis = now,
            ),
        )
        persistFlowContext()
        submitSkipRequest(requestId, references, sessionId, current.flowGeneration)
    }

    private suspend fun handleRetrySkip() {
        val current = _state.value
        if (current.skip != SkipState.RESULT_UNKNOWN) return
        val sessionId = current.sessionRef?.sessionId
        // Reuse the same correlation id: the cloud can deduplicate the retried skip.
        val requestId = ids.stable("skip", vehicle.vin, sessionId ?: "-")
        submitSkipRequest(
            requestId = requestId,
            references = current.acceptances.map { it.toReference() },
            sessionId = sessionId,
            generation = current.flowGeneration,
        )
    }

    private suspend fun submitSkipRequest(
        requestId: String,
        references: List<AgreementReference>,
        sessionId: String?,
        generation: Long,
    ) {
        val input = SkipRequestInput(vehicle, requestId, references, sessionId)
        when (val outcome = cloud.submitSkip(input)) {
            SkipSubmitOutcome.Acknowledged -> {
                recordStore.markAcknowledged(requestId)
                acceptanceStore.markSync(
                    ScopeKeys.vehicle(vehicle),
                    AgreementSyncState.ACKNOWLEDGED,
                )
                dispatch(ActivationEvent.SkipAcknowledged(generation, sessionId))
                dispatch(ActivationEvent.AgreementSyncUpdated(AgreementSyncState.ACKNOWLEDGED))
                persistFlowContext()
                logger.event("skip.acknowledged", "guide" to _state.value.guide.name)
                flushRecords()
            }

            is SkipSubmitOutcome.Failed -> {
                dispatch(ActivationEvent.SkipFailed(generation, sessionId, outcome.category))
                persistFlowContext()
                logger.event("skip.submit_failed", "category" to outcome.category.name)
            }
        }
    }

    // ------------------------------------------------------------------ login

    private suspend fun startLogin() {
        val current = _state.value
        if (!current.sessionBound) return
        if (current.login == LoginState.IN_PROGRESS || current.login == LoginState.SIGNED_IN) return
        if (loginSubmitted) {
            logger.event("login.duplicate_callback_ignored")
            return
        }
        val session = current.sessionRef ?: return
        val generation = current.flowGeneration
        loginSubmitted = true
        dispatch(ActivationEvent.LoginStarted(generation))
        persistFlowContext()
        val bindingOperationId = (current.session as? SessionObservation.BoundConfirmed)
            ?.bindingOperationId
        val ticketOutcome = cloud.issueLoginTicket(
            LoginTicketRequestInput(vehicle, session, bindingOperationId),
        )
        val ticket = when (ticketOutcome) {
            is LoginTicketOutcome.Ticket -> ticketOutcome.ticket
            is LoginTicketOutcome.Failed -> {
                loginSubmitted = false
                dispatch(ActivationEvent.LoginFailed(generation, ticketOutcome.category))
                persistFlowContext()
                enqueueLoginFailure(
                    session = session,
                    bindingOperationId = bindingOperationId,
                    category = ticketOutcome.category,
                    reason = IviLoginFailureReason.TOKEN_EXCHANGE_FAILED,
                )
                logger.event(
                    "login.ticket_failed",
                    "category" to ticketOutcome.category.name,
                )
                return
            }
        }
        if (ticket.isExpired(wallClock.nowEpochMillis())) {
            // A consumed or expired ticket is never reused; a retry requests a fresh one.
            loginSubmitted = false
            dispatch(ActivationEvent.LoginFailed(generation, ErrorCategory.EXPIRED))
            persistFlowContext()
            enqueueLoginFailure(
                session = session,
                bindingOperationId = bindingOperationId,
                category = ErrorCategory.EXPIRED,
                reason = IviLoginFailureReason.TOKEN_EXCHANGE_FAILED,
            )
            logger.event("login.ticket_expired")
            return
        }
        when (val outcome = loginGateway.login(ticket)) {
            is LoginOutcome.Success -> {
                val account = outcome.account
                val firstInteractionCompleted = privacyStore.isFirstInteractionCompleted(
                    ScopeKeys.accountVehicle(account, vehicle),
                )
                dispatch(
                    ActivationEvent.LoginSucceeded(
                        generation = generation,
                        account = account,
                        firstInteractionCompleted = firstInteractionCompleted,
                    ),
                )
                persistFlowContext()
                persistAccountScopeRecords(account, bindingOperationId)
                logger.event(
                    "login.succeeded",
                    "guide" to _state.value.guide.name,
                    "first_interaction" to firstInteractionCompleted.toString(),
                )
                flushRecords()
                if (!firstInteractionCompleted) ensurePrivacyCatalogAndPrompt()
            }

            is LoginOutcome.Failed -> {
                loginSubmitted = false
                // A local login failure must not clear the confirmed cloud binding.
                dispatch(ActivationEvent.LoginFailed(generation, outcome.category))
                persistFlowContext()
                enqueueLoginFailure(
                    session = session,
                    bindingOperationId = bindingOperationId,
                    category = outcome.category,
                    reason = outcome.category.toLoginFailureReason(),
                )
                logger.event("login.failed", "category" to outcome.category.name)
            }
        }
    }

    /**
     * Reports the failed login attempt (DSN-CR-002 D-003 step 5, D-005 revision).
     *
     * BLK-11: the record is generated for the **session** scope, not for an account. A failed login
     * has no account by definition, and a missing `bindingOperationId` must not suppress the receipt
     * either: the cloud resolves the owner through the session VIN. R-15: `failureReason` is
     * mandatory.
     */
    private suspend fun enqueueLoginFailure(
        session: SessionRef,
        bindingOperationId: String?,
        category: ErrorCategory,
        reason: IviLoginFailureReason,
    ) {
        val now = wallClock.nowEpochMillis()
        val scopeKey = ScopeKeys.vehicleSession(vehicle, session.sessionId)
        val accountId = _state.value.account?.accountId
        val recordId = ids.stable("login-result", vehicle.vin, session.sessionId, FAILED_STATUS)
        recordStore.enqueue(
            PendingRecord.create(
                id = recordId,
                type = RecordType.LOGIN_RESULT,
                scopeKey = scopeKey,
                payload = RecordPayload.LoginResult(
                    vin = vehicle.vin,
                    accountId = accountId,
                    sessionId = session.sessionId,
                    bindingOperationId = bindingOperationId,
                    loggedInAtEpochMillis = now,
                    loginStatus = FAILED_STATUS,
                    agreements = _state.value.acceptances.map { it.toReference() },
                    failureReason = reason,
                ),
                createdAtEpochMillis = now,
                userActionAtEpochMillis = now,
            ),
        )
        logger.event(
            "login.failure_reported",
            "reason" to reason.name,
            "category" to category.name,
            "binding_operation" to (bindingOperationId != null).toString(),
        )
    }

    private suspend fun persistAccountScopeRecords(
        account: AccountRef,
        bindingOperationId: String?,
    ) {
        val current = _state.value
        val scopeKey = ScopeKeys.accountVehicle(account, vehicle)
        val now = wallClock.nowEpochMillis()
        val sessionId = current.sessionRef?.sessionId
        val references = current.acceptances.map { it.toReference() }
        acceptanceStore.saveAll(
            references.map { reference ->
                AgreementAcceptance(
                    scopeKey = scopeKey,
                    type = reference.type,
                    version = reference.version,
                    revisionId = reference.revisionId,
                    contentHash = reference.contentHash,
                    language = reference.language,
                    agreedAtEpochMillis = current.acceptance(reference.type)?.agreedAtEpochMillis ?: now,
                    dedupeId = ids.stable(
                        "agr",
                        scopeKey,
                        reference.type.name,
                        reference.version,
                        reference.revisionId ?: "-",
                    ),
                    syncState = AgreementSyncState.PENDING,
                )
            },
        )
        if (bindingOperationId == null) {
            // A SUCCEEDED receipt without the binding operation would be rejected by the cloud:
            // report nothing and ask the user to re-query the authoritative result instead.
            dispatch(ActivationEvent.ReceiptBlocked(ErrorCategory.RETRYABLE))
            logger.event("login.receipt_blocked", "reason" to "missing_binding_operation")
        } else {
            recordStore.enqueue(
                PendingRecord.create(
                    id = ids.stable("login", scopeKey, sessionId ?: "-"),
                    type = RecordType.LOGIN_RESULT,
                    scopeKey = scopeKey,
                    payload = RecordPayload.LoginResult(
                        vin = vehicle.vin,
                        accountId = account.accountId,
                        sessionId = sessionId ?: "-",
                        bindingOperationId = bindingOperationId,
                        loggedInAtEpochMillis = now,
                        loginStatus = SUCCEEDED_STATUS,
                        agreements = references,
                    ),
                    createdAtEpochMillis = now,
                    userActionAtEpochMillis = now,
                ),
            )
        }
        recordStore.enqueue(
            PendingRecord.create(
                id = ids.stable("agc", scopeKey, sessionId ?: "-"),
                type = RecordType.AGREEMENT_CONSENT,
                scopeKey = scopeKey,
                payload = RecordPayload.AgreementConsent(
                    vin = vehicle.vin,
                    accountId = account.accountId,
                    agreements = references,
                    sessionId = sessionId,
                    bindingOperationId = bindingOperationId,
                    agreedAtEpochMillis = current.acceptances.maxOfOrNull { it.agreedAtEpochMillis } ?: now,
                ),
                createdAtEpochMillis = now,
                userActionAtEpochMillis = now,
            ),
        )
    }

    // ------------------------------------------------------------------ privacy (D-013)

    private suspend fun commitPrivacyIfSignedIn(choice: PrivacyChoice) {
        if (choice == PrivacyChoice.NOT_DECIDED) return
        val current = _state.value
        if (current.login != LoginState.SIGNED_IN) return
        val account = current.account ?: return
        val catalogVersion = current.privacyCatalogVersion ?: return
        if (current.privacyCatalogItems.isEmpty()) return
        commitPrivacy(
            account = account,
            catalogVersion = catalogVersion,
            items = current.privacyCatalogItems,
            choice = choice,
            itemStates = emptyMap(),
        )
    }

    /**
     * Loads the cloud catalogue and, when it is usable, asks the desktop for the decision.
     *
     * Q-001 / BLK-3: `Failed`, `Loaded(empty)` and the feature switch all converge on
     * `PrivacyCatalogUnavailable`, which only blocks the report (guide becomes ENTERED_ACCOUNT).
     */
    private suspend fun ensurePrivacyCatalogAndPrompt() {
        val current = _state.value
        if (current.login != LoginState.SIGNED_IN) return
        if (current.privacyFirstInteractionCompleted) return
        val account = current.account ?: return
        if (!config.privacyCatalogEnabled) {
            dispatch(
                ActivationEvent.PrivacyCatalogUnavailable(
                    PrivacyCatalogUnavailableReason.FEATURE_DISABLED,
                ),
            )
            logger.event("privacy.catalog.unavailable", "reason" to "FEATURE_DISABLED")
            return
        }
        if (current.privacyCatalog == PrivacyCatalogState.LOADED &&
            current.privacyCatalogItems.isNotEmpty()
        ) {
            promptPrivacy(
                account = account,
                catalogVersion = current.privacyCatalogVersion.orEmpty(),
                items = current.privacyCatalogItems,
            )
            return
        }
        dispatch(ActivationEvent.PrivacyCatalogLoading)
        when (val outcome = privacyCatalog.catalog(config.privacyCatalogLanguage)) {
            is PrivacyCatalogOutcome.Loaded -> {
                if (outcome.items.any { it.code.isBlank() || it.displayName.isBlank() }) {
                    // A malformed catalogue item is a contract violation: no report, no entry block.
                    dispatch(
                        ActivationEvent.PrivacyCatalogUnavailable(
                            PrivacyCatalogUnavailableReason.CATALOG_FAILED,
                        ),
                    )
                    logger.event("privacy.catalog.unavailable", "reason" to "CONTRACT_REJECTED")
                    return
                }
                dispatch(
                    ActivationEvent.PrivacyCatalogLoaded(outcome.catalogVersion, outcome.items),
                )
                val loaded = _state.value
                if (loaded.privacyCatalog != PrivacyCatalogState.LOADED) {
                    logger.event(
                        "privacy.catalog.unavailable",
                        "reason" to loaded.privacyCatalogUnavailableReason?.name.orEmpty(),
                    )
                    return
                }
                logger.event(
                    "privacy.catalog.loaded",
                    "items" to loaded.privacyCatalogItems.size.toString(),
                    "version" to mask(loaded.privacyCatalogVersion),
                )
                promptPrivacy(
                    account = account,
                    catalogVersion = loaded.privacyCatalogVersion.orEmpty(),
                    items = loaded.privacyCatalogItems,
                )
            }

            is PrivacyCatalogOutcome.Failed -> {
                dispatch(
                    ActivationEvent.PrivacyCatalogUnavailable(
                        PrivacyCatalogUnavailableReason.CATALOG_FAILED,
                    ),
                )
                logger.event(
                    "privacy.catalog.unavailable",
                    "category" to outcome.category.name,
                    "code" to (outcome.businessCode ?: "-"),
                )
            }
        }
    }

    private suspend fun promptPrivacy(
        account: AccountRef,
        catalogVersion: String,
        items: List<PrivacyAuthorizationItem>,
    ) {
        when (val result = privacyGateway.requestFirstConsent(PrivacyScope(vehicle, account), items)) {
            is PrivacyPromptResult.Decided -> commitPrivacy(
                account = account,
                catalogVersion = catalogVersion,
                items = items,
                choice = result.choice,
                itemStates = result.itemStates,
            )

            is PrivacyPromptResult.Unavailable -> {
                dispatch(
                    ActivationEvent.ErrorRaised(ActivationOperation.PRIVACY_PROMPT, result.category),
                )
                logger.event("privacy.prompt_unavailable", "category" to result.category.name)
            }

            PrivacyPromptResult.FrontSeatRequired -> {
                dispatch(ActivationEvent.PrivacyPromptRequiresFrontSeat(true))
                dispatch(ActivationEvent.SeatGateEvaluated(SeatBlockReason.FRONT_SEAT_REQUIRED))
                dispatch(
                    ActivationEvent.ErrorRaised(
                        ActivationOperation.PRIVACY_PROMPT,
                        ErrorCategory.CONTRACT_REJECTED,
                    ),
                )
                logger.event("privacy.front_seat_required")
            }
        }
    }

    private suspend fun commitPrivacy(
        account: AccountRef,
        catalogVersion: String,
        items: List<PrivacyAuthorizationItem>,
        choice: PrivacyChoice,
        itemStates: Map<String, PrivacyItemState>,
    ) {
        if (catalogVersion.isBlank() || items.isEmpty()) return
        val scopeKey = ScopeKeys.accountVehicle(account, vehicle)
        val now = wallClock.nowEpochMillis()
        val previous = privacyStore.loadDecision(scopeKey)
        val decisions = items.map { item ->
            val state = itemStates[item.code] ?: choice.toItemState()
            val previousVersion = previous?.items
                ?.firstOrNull { it.authorizationItem == item.code }
                ?.version
                ?: 0
            // Local decision sequence: monotonically increasing, never smaller than the stored one.
            val nextVersion = maxOf(previousVersion, item.decisionVersion) + 1
            PrivacyItemDecision(
                authorizationItem = item.code,
                state = state,
                version = nextVersion,
            )
        }
        val maxVersion = decisions.maxOf { it.version }
        val reportId = ids.stable("privacy-report", scopeKey, catalogVersion, maxVersion.toString())
        val decision = PrivacyDecision(
            scopeKey = scopeKey,
            accountId = account.accountId,
            vin = vehicle.vin,
            catalogVersion = catalogVersion,
            choice = choice,
            items = decisions,
            decidedAtEpochMillis = now,
            dedupeId = reportId,
            syncState = PrivacySyncState.PENDING,
            firstInteractionCompleted = true,
        )
        val record = PendingRecord.create(
            id = reportId,
            type = RecordType.PRIVACY_DECISION,
            scopeKey = scopeKey,
            payload = RecordPayload.PrivacyDecisionRecord(
                vin = vehicle.vin,
                accountId = account.accountId,
                catalogVersion = catalogVersion,
                choice = choice,
                items = decisions,
                decidedAtEpochMillis = now,
            ),
            createdAtEpochMillis = now,
            userActionAtEpochMillis = now,
        )
        // Selection, pending record and first interaction flag are persisted atomically.
        if (!privacyStore.commitDecision(decision, record)) {
            dispatch(ActivationEvent.PrivacyDecisionPersistFailed)
            logger.event("privacy.persist_failed")
            return
        }
        dispatch(ActivationEvent.PrivacyDecisionCommitted(choice, true))
        persistFlowContext()
        logger.event(
            "privacy.decided",
            "choice" to choice.name,
            "items" to decisions.size.toString(),
            "guide" to _state.value.guide.name,
        )
        flushRecords()
    }

    private fun PrivacyChoice.toItemState(): PrivacyItemState = when (this) {
        PrivacyChoice.GRANTED -> PrivacyItemState.GRANTED
        // Closing the dialog or refusing maps to NOT_GRANTED, never to a default grant.
        PrivacyChoice.NOT_GRANTED, PrivacyChoice.NOT_DECIDED -> PrivacyItemState.NOT_GRANTED
    }

    // ------------------------------------------------------------------ token lifecycle (D-016)

    private suspend fun onTokenEvent(event: TokenLifecycleEvent) {
        when (event) {
            is TokenLifecycleEvent.CredentialsInvalidated -> handleRevocation(event.reason)
            is TokenLifecycleEvent.SignedOut -> handleRevocation(event.reason)
            is TokenLifecycleEvent.RenewalFailed -> {
                // Credentials are kept: only a retry hint, never a credential cleanup.
                dispatch(ActivationEvent.TokenRenewalFailed(event.category))
                logger.event("token.renewal_failed", "category" to event.category.name)
            }
        }
    }

    private suspend fun handleRevocation(reason: TokenInvalidationReason) {
        val now = wallClock.nowEpochMillis()
        val previous = lastRevocationAt[reason]
        if (previous != null && now - previous < REVOCATION_DEDUPE_MILLIS) {
            logger.event("token.event_deduplicated", "reason" to reason.name)
            return
        }
        lastRevocationAt[reason] = now
        pollJob?.cancel()
        loginSubmitted = false
        dispatch(ActivationEvent.CredentialsRevoked(reason))
        dropAccountScopeRecordsFromRetryQueue()
        persistFlowContext()
        logger.event("token.credentials_invalidated", "reason" to reason.name)
    }

    /**
     * Account + VIN scoped records can no longer be delivered without a credential.
     *
     * They are kept as an audit copy (terminal local state) but removed from the retry queue, in line
     * with D-016; VIN scoped skip records and the cloud binding fact stay untouched.
     */
    private suspend fun dropAccountScopeRecordsFromRetryQueue() {
        val candidates = recordStore.pending(Int.MAX_VALUE)
            .filter { it.scopeKey.startsWith(ACCOUNT_SCOPE_PREFIX) }
        candidates.forEach { recordStore.markFailed(it.id, it.attempts) }
        if (candidates.isNotEmpty()) {
            logger.event("token.records_dropped", "count" to candidates.size.toString())
        }
    }

    // ------------------------------------------------------------------ records / lifecycle

    private suspend fun flushRecords() {
        val acknowledged = recordSync.flush(config.recordQueueLimit)
        if (acknowledged > 0) logger.event("record.flushed", "acknowledged" to acknowledged.toString())
        rebuildStalePrivacyReport()
        refreshSyncStates()
    }

    /**
     * 401118 / 401119 recovery: after a catalogue reload the stored decision is re-sent with the new
     * `catalogVersion` and a new report id (the old record must not be replayed with a new payload,
     * which the cloud would answer with 401113).
     */
    private suspend fun onReportRetryRequired(
        record: PendingRecord,
        category: ErrorCategory,
        businessCode: String?,
    ) {
        if (record.type != RecordType.PRIVACY_DECISION) return
        val classification = errorCatalog.classify(ErrorScope.TSP_ACTIVATION, businessCode)
        if (!classification.requiresCatalogReload) return
        logger.event(
            "privacy.report.retry_after",
            "code" to (businessCode ?: "-"),
            "category" to category.name,
        )
        // One reload per retry cycle: the engine retries several times inside one flush and a second
        // reload would race the first one (and could downgrade the version that is being rebuilt).
        if (catalogReloadPending) return
        catalogReloadPending = true
        when (val outcome = privacyCatalog.catalog(config.privacyCatalogLanguage)) {
            is PrivacyCatalogOutcome.Loaded -> {
                if (outcome.catalogVersion.isNotBlank()) {
                    latestCatalogVersion = outcome.catalogVersion
                    dispatch(
                        ActivationEvent.PrivacyCatalogLoaded(outcome.catalogVersion, outcome.items),
                    )
                }
            }

            is PrivacyCatalogOutcome.Failed -> logger.event(
                "privacy.catalog.unavailable",
                "category" to outcome.category.name,
            )
        }
    }

    private suspend fun rebuildStalePrivacyReport() {
        if (!catalogReloadPending) return
        val latest = latestCatalogVersion ?: return
        val account = _state.value.account ?: return
        val scopeKey = ScopeKeys.accountVehicle(account, vehicle)
        val decision = privacyStore.loadDecision(scopeKey) ?: return
        if (decision.syncState == PrivacySyncState.ACKNOWLEDGED || decision.catalogVersion == latest) {
            catalogReloadPending = false
            return
        }
        val maxVersion = decision.items.maxOfOrNull { it.version } ?: 1
        val reportId = ids.stable("privacy-report", scopeKey, latest, maxVersion.toString())
        val rebuilt = PendingRecord.create(
            id = reportId,
            type = RecordType.PRIVACY_DECISION,
            scopeKey = scopeKey,
            payload = RecordPayload.PrivacyDecisionRecord(
                vin = vehicle.vin,
                accountId = account.accountId,
                catalogVersion = latest,
                choice = decision.choice,
                items = decision.items,
                decidedAtEpochMillis = decision.decidedAtEpochMillis,
            ),
            createdAtEpochMillis = wallClock.nowEpochMillis(),
            userActionAtEpochMillis = decision.decidedAtEpochMillis,
        )
        val recommitted = privacyStore.commitDecision(
            decision.copy(
                catalogVersion = latest,
                dedupeId = reportId,
                syncState = PrivacySyncState.PENDING,
            ),
            rebuilt,
        )
        if (!recommitted) {
            logger.event("privacy.rebuild_failed")
            return
        }
        recordStore.pending(Int.MAX_VALUE)
            .filter { it.type == RecordType.PRIVACY_DECISION && it.scopeKey == scopeKey && it.id != reportId }
            .forEach { recordStore.markFailed(it.id, it.attempts) }
        catalogReloadPending = false
        logger.event("privacy.report.rebuilt", "version" to mask(latest))
    }

    private suspend fun refreshSyncStates() {
        val current = _state.value
        val vinScope = ScopeKeys.vehicle(vehicle)
        val storedAcceptances = acceptanceStore.loadAcceptances(vinScope)
        if (storedAcceptances.isNotEmpty()) {
            val sync = if (storedAcceptances.all { it.syncState == AgreementSyncState.ACKNOWLEDGED }) {
                AgreementSyncState.ACKNOWLEDGED
            } else {
                AgreementSyncState.PENDING
            }
            dispatch(ActivationEvent.AgreementSyncUpdated(sync))
        }
        val account = current.account ?: return
        val decision = privacyStore.loadDecision(ScopeKeys.accountVehicle(account, vehicle)) ?: return
        dispatch(
            ActivationEvent.PrivacySyncUpdated(
                if (decision.syncState == PrivacySyncState.ACKNOWLEDGED) {
                    PrivacySyncState.ACKNOWLEDGED
                } else {
                    PrivacySyncState.PENDING
                },
            ),
        )
    }

    private suspend fun handleForeground() {
        dispatch(ActivationEvent.ForegroundChanged(true))
        val current = _state.value
        when {
            current.guide == GuideState.QR_VISIBLE || current.guide == GuideState.SESSION_PREPARING -> {
                current.sessionRef?.let { session ->
                    startPolling(current.flowGeneration, session)
                    queryNow()
                }
            }

            current.sessionBound && current.guide == GuideState.LOGIN_PENDING -> reconcileAndLogin()
            current.entertainmentEntryAllowed -> flushRecords()
        }
    }

    private fun handleBackground() {
        dispatch(ActivationEvent.ForegroundChanged(false))
        // Stop useless polling in background; resume from an authoritative query on return.
        pollJob?.cancel()
    }

    private suspend fun onLocalReset() {
        pollJob?.cancel()
        loginSubmitted = false
        flowStore.clear()
        recordStore.clearAll()
        secureMaterials.clearAll()
        catalogReloadPending = false
        latestCatalogVersion = null
        dispatch(ActivationEvent.FlowStarted(null))
        logger.event("flow.local_reset")
    }

    // ------------------------------------------------------------------ helpers

    private fun dispatch(event: ActivationEvent) {
        val previous = _state.value
        val next = _state.updateAndGet { ActivationReducer.reduce(it, event) }
        val eventName = event::class.simpleName ?: "Event"
        if (next == previous) {
            logger.event(
                "state.ignored",
                "event" to eventName,
                "guide" to previous.guide.name,
            )
            return
        }
        logger.event(
            "state.transition",
            "event" to eventName,
            "guide" to next.guide.name,
            "login" to next.login.name,
            "skip" to next.skip.name,
            "error" to (next.error?.category?.name ?: "-"),
        )
    }

    private suspend fun persistFlowContext() {
        val current = _state.value
        flowStore.save(
            FlowContext(
                flowGeneration = current.flowGeneration,
                guide = current.guide,
                login = current.login,
                sessionId = current.sessionRef?.sessionId,
                sessionExpiresAtEpochMillis = current.sessionRef?.expiresAtEpochMillis,
                bindingOperationId = current.session.bindingOperation(),
                bindingConfirmed = current.sessionBound,
                accountId = current.account?.accountId,
                updatedAtEpochMillis = wallClock.nowEpochMillis(),
            ),
        )
    }

    private fun SessionObservation.bindingOperation(): String? =
        (this as? SessionObservation.BoundConfirmed)?.bindingOperationId

    private fun ErrorCategory.isRetryableCategory(): Boolean =
        this == ErrorCategory.RETRYABLE || this == ErrorCategory.RESULT_UNKNOWN

    private fun ErrorCategory.toLoginFailureReason(): IviLoginFailureReason = when (this) {
        ErrorCategory.RESULT_UNKNOWN -> IviLoginFailureReason.TIMEOUT
        ErrorCategory.RETRYABLE -> IviLoginFailureReason.NETWORK_ERROR
        ErrorCategory.EXPIRED,
        ErrorCategory.AUTH_REJECTED,
        ErrorCategory.CONTRACT_REJECTED,
        -> IviLoginFailureReason.TOKEN_EXCHANGE_FAILED
    }

    private companion object {
        const val SUCCEEDED_STATUS = "SUCCEEDED"
        const val FAILED_STATUS = "FAILED"
        const val ACCOUNT_SCOPE_PREFIX = "AV:"
        const val REVOCATION_DEDUPE_MILLIS = 5_000L
    }
}
