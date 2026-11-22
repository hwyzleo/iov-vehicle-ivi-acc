package net.hwyz.iov.vehicle.ivi.acc.domain

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
import net.hwyz.iov.vehicle.ivi.acc.domain.event.ActivationEvent
import net.hwyz.iov.vehicle.ivi.acc.domain.event.RestoredFlow
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
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.id.IdGenerator
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptance
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptanceSnapshot
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
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
) {

    private val _state = MutableStateFlow(ActivationState())
    val state: StateFlow<ActivationState> = _state.asStateFlow()

    private val intents = Channel<Intent>(Channel.UNLIMITED)
    private var pollJob: Job? = null
    private var loginSubmitted = false

    private val handlerJob: Job = scope.launch(dispatcher) {
        for (intent in intents) {
            handle(intent)
        }
    }

    fun start() {
        intents.trySend(Intent.Start)
    }

    fun reloadAgreements() {
        intents.trySend(Intent.ReloadDocuments)
    }

    fun acceptAgreement(type: AgreementType) {
        intents.trySend(Intent.Accept(type))
    }

    /** Refresh the QR session: a new flow generation is allocated, old results are dropped. */
    fun refreshSession() {
        intents.trySend(Intent.Refresh)
    }

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
        intents.close()
        handlerJob.cancel()
    }

    private sealed interface Intent {
        data object Start : Intent
        data object ReloadDocuments : Intent
        data class Accept(val type: AgreementType) : Intent
        data object Refresh : Intent
        data object QueryNow : Intent
        data object Skip : Intent
        data object RetrySkip : Intent
        data object RetryLogin : Intent
        data object Continuation : Intent
        data class Privacy(val choice: PrivacyChoice) : Intent
        data object RetryPrivacy : Intent
        data object Foreground : Intent
        data object Background : Intent
        data object Reset : Intent
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
            is Intent.Accept -> onAccept(intent.type)
            Intent.Refresh -> prepareSession()
            Intent.QueryNow -> queryNow()
            Intent.Skip -> submitSkip()
            Intent.RetrySkip -> handleRetrySkip()
            Intent.RetryLogin -> startLogin()
            Intent.Continuation -> {
                dispatch(ActivationEvent.AccountContinuationAccepted)
                startLogin()
            }

            is Intent.Privacy -> commitPrivacyIfSignedIn(intent.choice)
            Intent.RetryPrivacy -> requestPrivacyPrompt()
            Intent.Foreground -> handleForeground()
            Intent.Background -> handleBackground()
            Intent.Reset -> onLocalReset()
            is Intent.Observed -> onObserved(intent)
            is Intent.QueryFailed -> onQueryFailed(intent)
        }
    }

    // ------------------------------------------------------------------ start / restore

    private suspend fun onStart() {
        val context = flowStore.loadContext()
        val restored = context?.let { restoredFlow(it) }
        dispatch(ActivationEvent.FlowStarted(restored))
        logger.event(
            "flow.started",
            "restored" to (restored != null).toString(),
            "guide" to _state.value.guide.name,
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

    private suspend fun onAccept(type: AgreementType) {
        val current = _state.value
        if (current.entertainmentEntryAllowed) return
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
                agreedAtEpochMillis = wallClock.nowEpochMillis(),
            ),
        )
        persistAcceptances()
        persistFlowContext()
        logger.event("agreement.accepted", "type" to type.name, "version" to document.version)
        if (_state.value.agreementsAccepted) prepareSession()
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

    private suspend fun prepareSession() {
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
        val generation = current.flowGeneration + 1
        pollJob?.cancel()
        dispatch(ActivationEvent.SessionPreparationStarted(generation))
        persistFlowContext()
        val references = _state.value.acceptances.map { it.toReference() }
        val requestId = ids.next("session")
        when (val outcome = cloud.requestSession(
            SessionRequestInput(vehicle, references, generation, requestId),
        )) {
            is SessionRequestOutcome.Created -> {
                dispatch(ActivationEvent.SessionCreated(generation, outcome.session))
                persistFlowContext()
                logger.event("session.created", "generation" to generation.toString())
                startPolling(generation, outcome.session)
            }

            is SessionRequestOutcome.Failed -> {
                dispatch(ActivationEvent.SessionRequestFailed(generation, outcome.category))
                persistFlowContext()
                logger.event("session.request_failed", "category" to outcome.category.name)
            }
        }
    }

    private fun startPolling(generation: Long, session: SessionRef) {
        pollJob?.cancel()
        pollJob = scope.launch(dispatcher) {
            val startedAt = monotonicClock.elapsedMillis()
            var attempts = 0
            var decided = false
            while (!decided &&
                attempts < config.sessionPoll.maxAttempts &&
                monotonicClock.elapsedMillis() - startedAt < config.sessionPoll.timeoutMillis
            ) {
                delay(config.sessionPoll.intervalMillis)
                val current = _state.value
                if (current.flowGeneration != generation) return@launch
                if (current.sessionRef?.sessionId != session.sessionId) return@launch
                if (current.guide != GuideState.QR_VISIBLE &&
                    current.guide != GuideState.SESSION_PREPARING
                ) {
                    return@launch
                }
                if (!current.foreground) continue
                attempts++
                when (val outcome = cloud.querySession(
                    SessionQueryInput(vehicle, session, generation),
                )) {
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
                            decided = true
                        }
                    }
                }
            }
            if (decided) return@launch
            val current = _state.value
            if (current.flowGeneration == generation &&
                current.sessionRef?.sessionId == session.sessionId &&
                current.guide == GuideState.QR_VISIBLE
            ) {
                // Timeout means the result is unknown, never a business failure.
                intents.trySend(
                    Intent.Observed(generation, session.sessionId, SessionObservation.ResultUnknown),
                )
            }
        }
    }

    private suspend fun queryNow() {
        val current = _state.value
        val session = current.sessionRef ?: return
        if (!current.foreground) return
        when (val outcome = cloud.querySession(
            SessionQueryInput(vehicle, session, current.flowGeneration),
        )) {
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
                persistAccountScopeRecords(account)
                logger.event(
                    "login.succeeded",
                    "guide" to _state.value.guide.name,
                    "first_interaction" to firstInteractionCompleted.toString(),
                )
                flushRecords()
                if (!firstInteractionCompleted) requestPrivacyPrompt()
            }

            is LoginOutcome.Failed -> {
                loginSubmitted = false
                // A local login failure must not clear the confirmed cloud binding.
                dispatch(ActivationEvent.LoginFailed(generation, outcome.category))
                persistFlowContext()
                enqueueLoginFailure(session, bindingOperationId)
                logger.event("login.failed", "category" to outcome.category.name)
            }
        }
    }

    /**
     * Reports the failed login attempt (DSN-CR-002 D-003 step 5).
     *
     * The record is best effort: it never changes the confirmed binding and reuses a stable id so a
     * retry cannot create a second fact.
     */
    private suspend fun enqueueLoginFailure(session: SessionRef, bindingOperationId: String?) {
        val account = _state.value.account ?: return
        val now = wallClock.nowEpochMillis()
        val scopeKey = ScopeKeys.accountVehicle(account, vehicle)
        recordStore.enqueue(
            PendingRecord.create(
                id = ids.stable("login-failed", scopeKey, session.sessionId),
                type = RecordType.LOGIN_RESULT,
                scopeKey = scopeKey,
                payload = RecordPayload.LoginResult(
                    vin = vehicle.vin,
                    accountId = account.accountId,
                    sessionId = session.sessionId,
                    bindingOperationId = bindingOperationId,
                    loggedInAtEpochMillis = now,
                    loginStatus = "FAILED",
                    agreements = _state.value.acceptances.map { it.toReference() },
                ),
                createdAtEpochMillis = now,
                userActionAtEpochMillis = now,
            ),
        )
    }

    private suspend fun persistAccountScopeRecords(account: AccountRef) {
        val current = _state.value
        val scopeKey = ScopeKeys.accountVehicle(account, vehicle)
        val now = wallClock.nowEpochMillis()
        val sessionId = current.sessionRef?.sessionId
        val bindingOperationId = current.session.bindingOperation()
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
                    loginStatus = "SUCCEEDED",
                    agreements = references,
                ),
                createdAtEpochMillis = now,
                userActionAtEpochMillis = now,
            ),
        )
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

    // ------------------------------------------------------------------ privacy

    private suspend fun commitPrivacyIfSignedIn(choice: PrivacyChoice) {
        if (choice == PrivacyChoice.NOT_DECIDED) return
        val current = _state.value
        if (current.login != LoginState.SIGNED_IN) return
        val account = current.account ?: return
        commitPrivacy(account, choice)
    }

    private suspend fun requestPrivacyPrompt() {
        val current = _state.value
        if (current.guide != GuideState.PRIVACY_PENDING) return
        val account = current.account ?: return
        when (val result = privacyGateway.requestFirstConsent(
            PrivacyScope(vehicle, account),
            config.privacyPromptItems,
        )) {
            is PrivacyPromptResult.Decided -> commitPrivacy(account, result.choice)
            is PrivacyPromptResult.Unavailable -> {
                dispatch(
                    ActivationEvent.ErrorRaised(ActivationOperation.PRIVACY_PROMPT, result.category),
                )
                logger.event("privacy.prompt_unavailable", "category" to result.category.name)
            }

            PrivacyPromptResult.FrontSeatRequired -> {
                dispatch(ActivationEvent.PrivacyPromptRequiresFrontSeat(true))
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

    private suspend fun commitPrivacy(account: AccountRef, choice: PrivacyChoice) {
        val scopeKey = ScopeKeys.accountVehicle(account, vehicle)
        val now = wallClock.nowEpochMillis()
        val dedupeId = ids.stable("pvc", scopeKey, choice.name, now.toString())
        val decision = PrivacyDecision(
            scopeKey = scopeKey,
            accountId = account.accountId,
            vin = vehicle.vin,
            choice = choice,
            items = config.privacyPromptItems,
            decidedAtEpochMillis = now,
            dedupeId = dedupeId,
            syncState = PrivacySyncState.PENDING,
            firstInteractionCompleted = true,
        )
        val record = PendingRecord.create(
            id = dedupeId,
            type = RecordType.PRIVACY_DECISION,
            scopeKey = scopeKey,
            payload = RecordPayload.PrivacyDecisionRecord(
                vin = vehicle.vin,
                accountId = account.accountId,
                choice = choice,
                items = config.privacyPromptItems,
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
        logger.event("privacy.decided", "choice" to choice.name, "guide" to _state.value.guide.name)
        flushRecords()
    }

    // ------------------------------------------------------------------ records / lifecycle

    private suspend fun flushRecords() {
        val acknowledged = recordSync.flush(config.recordQueueLimit)
        if (acknowledged > 0) logger.event("record.flushed", "acknowledged" to acknowledged.toString())
        refreshSyncStates()
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
}
