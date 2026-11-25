package net.hwyz.iov.vehicle.ivi.acc.domain

import net.hwyz.iov.vehicle.ivi.acc.domain.event.ActivationEvent
import net.hwyz.iov.vehicle.ivi.acc.domain.event.RestoredFlow
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogUnavailableReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenInvalidationReason
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptanceSnapshot
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementDocument
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationError
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.SkipState

/**
 * Pure activation state machine.
 *
 * Every guard that protects a user story lives here so it can be unit tested without Android,
 * network or storage: agreement gate, flow generation isolation, single skip submission,
 * login gating on a trusted binding result, and the "entered" states being terminal for late
 * session results.
 */
object ActivationReducer {

    fun reduce(state: ActivationState, event: ActivationEvent): ActivationState = when (event) {
        is ActivationEvent.FlowStarted -> flowStarted(state, event.restored)
        ActivationEvent.DocumentsLoadStarted -> documentsLoadStarted(state)
        is ActivationEvent.DocumentsLoaded -> documentsLoaded(state, event.documents)
        is ActivationEvent.DocumentsLoadFailed -> documentsLoadFailed(state, event.category)
        is ActivationEvent.AgreementAccepted -> agreementAccepted(state, event)
        is ActivationEvent.AgreementSelected -> agreementSelected(state, event)
        is ActivationEvent.SessionPreparationStarted -> sessionPreparationStarted(state, event.generation)
        is ActivationEvent.SessionCreated -> sessionCreated(state, event.generation, event.session)
        is ActivationEvent.SessionRequestFailed ->
            sessionRequestFailed(state, event.generation, event.category)

        is ActivationEvent.SessionObserved -> sessionObserved(
            state = state,
            generation = event.generation,
            sessionId = event.sessionId,
            observation = event.observation,
        )

        ActivationEvent.SkipRequested -> skipRequested(state)
        is ActivationEvent.SkipAcknowledged -> skipAcknowledged(state, event.generation)
        is ActivationEvent.SkipFailed -> skipFailed(state, event.generation, event.category)
        ActivationEvent.AccountContinuationAccepted -> accountContinuationAccepted(state)
        is ActivationEvent.LoginStarted -> loginStarted(state, event.generation)
        is ActivationEvent.LoginSucceeded -> loginSucceeded(
            state = state,
            generation = event.generation,
            account = event.account,
            firstInteractionCompleted = event.firstInteractionCompleted,
        )

        is ActivationEvent.LoginFailed -> loginFailed(state, event.generation, event.category)
        is ActivationEvent.PrivacyDecisionCommitted -> privacyDecisionCommitted(
            state = state,
            choice = event.choice,
            firstInteractionCompleted = event.firstInteractionCompleted,
        )

        ActivationEvent.PrivacyDecisionPersistFailed -> state.copy(
            error = ActivationError(ActivationOperation.PRIVACY_PERSIST, ErrorCategory.RETRYABLE),
        )

        is ActivationEvent.PrivacyPromptRequiresFrontSeat ->
            state.copy(privacyFrontSeatRequired = event.required)

        ActivationEvent.PrivacyCatalogLoading -> state.copy(
            privacyCatalog = PrivacyCatalogState.LOADING,
            privacyCatalogUnavailableReason = null,
        )

        is ActivationEvent.PrivacyCatalogLoaded -> privacyCatalogLoaded(
            state = state,
            catalogVersion = event.catalogVersion,
            items = event.items,
        )

        is ActivationEvent.PrivacyCatalogUnavailable -> privacyCatalogUnavailable(state, event.reason)

        is ActivationEvent.CredentialsRevoked -> credentialsRevoked(state, event.reason)

        is ActivationEvent.TokenRenewalFailed -> state.copy(
            login = if (state.login == LoginState.SIGNED_IN || state.login == LoginState.IN_PROGRESS) {
                LoginState.RETRY_REQUIRED
            } else {
                state.login
            },
            error = ActivationError(ActivationOperation.REPORT_RECORD, event.category),
        )

        is ActivationEvent.SeatGateEvaluated -> state.copy(
            seatBlocked = event.reason,
            privacyFrontSeatRequired = event.reason != null,
            skip = if (event.reason != null && state.skip == SkipState.SUBMITTING) {
                SkipState.IDLE
            } else {
                state.skip
            },
        )

        is ActivationEvent.SeatBlocked -> state.copy(
            seatBlocked = event.reason,
            privacyFrontSeatRequired = true,
        )

        is ActivationEvent.ReceiptBlocked -> state.copy(
            login = if (state.login == LoginState.SIGNED_IN) {
                LoginState.RETRY_REQUIRED
            } else {
                state.login
            },
            error = ActivationError(ActivationOperation.REPORT_RECORD, event.category),
        )

        is ActivationEvent.PrivacySyncUpdated -> state.copy(privacySync = event.sync)
        is ActivationEvent.AgreementSyncUpdated -> state.copy(agreementSync = event.sync)
        is ActivationEvent.ForegroundChanged -> state.copy(foreground = event.foreground)
        is ActivationEvent.ErrorRaised -> state.copy(error = ActivationError(event.operation, event.category))
        ActivationEvent.ErrorCleared -> state.copy(error = null)
    }

    private fun flowStarted(state: ActivationState, restored: RestoredFlow?): ActivationState {
        if (restored == null) return ActivationState(foreground = state.foreground)
        val base = ActivationState(
            flowGeneration = restored.flowGeneration,
            acceptances = restored.acceptances,
            agreementSync = restored.agreementSync,
            login = restored.login,
            account = restored.accountId?.let(::AccountRef),
            privacyChoice = restored.privacyChoice,
            privacySync = restored.privacySync,
            privacyFirstInteractionCompleted = restored.privacyFirstInteractionCompleted,
            foreground = state.foreground,
        )
        return when (restored.guide) {
            GuideState.ENTERED_LOCAL -> base.copy(guide = GuideState.ENTERED_LOCAL)
            GuideState.ENTERED_ACCOUNT -> base.copy(guide = GuideState.ENTERED_ACCOUNT)
            GuideState.PRIVACY_PENDING -> base.copy(guide = GuideState.PRIVACY_PENDING)
            else -> when {
                restored.bindingConfirmed -> base.copy(
                    guide = GuideState.LOGIN_PENDING,
                    session = SessionObservation.BoundConfirmed(restored.bindingOperationId),
                    sessionRef = restored.sessionId?.let { id ->
                        SessionRef(
                            sessionId = id,
                            qrPayload = "",
                            issuedAtEpochMillis = 0L,
                            expiresAtEpochMillis = restored.sessionExpiresAtEpochMillis ?: 0L,
                        )
                    },
                )

                // A cached session is not proof that the session is still valid: ask the cloud again.
                restored.acceptances.isNotEmpty() -> base.copy(
                    guide = GuideState.RECOVERABLE_ERROR,
                    error = ActivationError(
                        ActivationOperation.REQUEST_SESSION,
                        ErrorCategory.RESULT_UNKNOWN,
                    ),
                )

                else -> base.copy(guide = GuideState.AGREEMENT_REQUIRED)
            }
        }
    }

    private fun documentsLoadStarted(state: ActivationState): ActivationState {
        val recoveringFromLoadError = !state.entertainmentEntryAllowed &&
            !state.sessionBound &&
            state.guide == GuideState.RECOVERABLE_ERROR &&
            state.error?.operation == ActivationOperation.LOAD_AGREEMENTS
        return state.copy(
            documentsLoading = true,
            documentsFailure = null,
            // A retry shows the loading state instead of the stale error page.
            guide = if (recoveringFromLoadError) GuideState.AGREEMENT_REQUIRED else state.guide,
            // Only the agreement display is being refreshed; other recoverable problems stay visible.
            error = if (state.error?.operation == ActivationOperation.LOAD_AGREEMENTS) {
                null
            } else {
                state.error
            },
        )
    }

    private fun documentsLoaded(
        state: ActivationState,
        documents: List<AgreementDocument>,
    ): ActivationState {
        val next = state.copy(
            documents = documents,
            documentsLoading = false,
            documentsFailure = null,
            error = null,
        )
        if (state.entertainmentEntryAllowed) return next
        if (next.agreementVersionDrift) {
            // A new published version never silently replaces the agreed one.
            return next.copy(
                guide = GuideState.AGREEMENT_REQUIRED,
                session = SessionObservation.None,
                sessionRef = null,
                skip = SkipState.IDLE,
                accountContinuationSuggested = false,
                // The pre-submit selection belonged to the superseded version.
                agreementSelection = emptySet(),
            )
        }
        // A recovered document load returns to the guide instead of staying on the error page.
        val previousOperation = state.error?.operation
        if ((previousOperation == ActivationOperation.LOAD_AGREEMENTS ||
                previousOperation == ActivationOperation.REQUEST_SESSION) &&
            state.guide == GuideState.RECOVERABLE_ERROR &&
            !state.sessionBound
        ) {
            return next.copy(guide = GuideState.AGREEMENT_REQUIRED)
        }
        return next
    }

    private fun documentsLoadFailed(
        state: ActivationState,
        category: ErrorCategory,
    ): ActivationState {
        val next = state.copy(
            documentsLoading = false,
            documentsFailure = category,
            error = ActivationError(ActivationOperation.LOAD_AGREEMENTS, category),
        )
        if (state.entertainmentEntryAllowed) return next
        return when (state.guide) {
            GuideState.AGREEMENT_REQUIRED,
            GuideState.SESSION_PREPARING,
            GuideState.QR_VISIBLE,
            GuideState.RECOVERABLE_ERROR,
            -> next.copy(guide = GuideState.RECOVERABLE_ERROR)

            else -> next
        }
    }

    private fun agreementAccepted(
        state: ActivationState,
        event: ActivationEvent.AgreementAccepted,
    ): ActivationState {
        if (state.entertainmentEntryAllowed) return state
        val document = state.document(event.type) ?: return state
        // The acceptance is bound to the displayed version, never to "whatever is latest".
        if (document.version != event.version) return state
        val snapshot = AgreementAcceptanceSnapshot(
            type = event.type,
            version = event.version,
            revisionId = event.revisionId,
            contentHash = event.contentHash,
            language = event.language,
            agreedAtEpochMillis = event.agreedAtEpochMillis,
        )
        val acceptances = state.acceptances.filterNot { it.type == event.type } + snapshot
        return state.copy(
            acceptances = acceptances,
            // The committed type no longer needs a pre-submit selection.
            agreementSelection = state.agreementSelection - event.type,
            agreementSync = AgreementSyncState.PENDING,
            error = null,
        )
    }

    /**
     * Pre-submit checkbox toggle (DSN-CR-006): reversible, no consent fact and no cloud request.
     * Refused once the consent was submitted, on a rear seat, or before both documents are usable.
     */
    private fun agreementSelected(
        state: ActivationState,
        event: ActivationEvent.AgreementSelected,
    ): ActivationState {
        if (state.entertainmentEntryAllowed) return state
        if (state.acceptances.isNotEmpty()) return state
        if (!state.documentsComplete) return state
        if (state.seatBlocked != null) return state
        val selection = if (event.selected) {
            state.agreementSelection + event.type
        } else {
            state.agreementSelection - event.type
        }
        return state.copy(agreementSelection = selection)
    }

    private fun sessionPreparationStarted(state: ActivationState, generation: Long): ActivationState =
        state.copy(
            flowGeneration = generation,
            guide = GuideState.SESSION_PREPARING,
            session = SessionObservation.None,
            sessionRef = null,
            skip = SkipState.IDLE,
            accountContinuationSuggested = false,
            error = null,
        )

    private fun sessionCreated(
        state: ActivationState,
        generation: Long,
        session: SessionRef,
    ): ActivationState {
        if (generation != state.flowGeneration) return state
        if (!state.agreementsAccepted) return state
        return state.copy(
            sessionRef = session,
            session = SessionObservation.Active,
            guide = GuideState.QR_VISIBLE,
            skip = SkipState.IDLE,
            error = null,
        )
    }

    private fun sessionRequestFailed(
        state: ActivationState,
        generation: Long,
        category: ErrorCategory,
    ): ActivationState {
        if (generation != state.flowGeneration) return state
        return state.copy(
            session = SessionObservation.None,
            sessionRef = null,
            guide = GuideState.RECOVERABLE_ERROR,
            error = ActivationError(ActivationOperation.REQUEST_SESSION, category),
        )
    }

    private fun sessionObserved(
        state: ActivationState,
        generation: Long,
        sessionId: String,
        observation: SessionObservation,
    ): ActivationState {
        val currentSession = state.sessionRef ?: return state
        if (generation != state.flowGeneration || currentSession.sessionId != sessionId) {
            // Late callback from an old generation or an old session must not drive this flow.
            return state
        }
        if (state.entertainmentEntryAllowed) {
            return if (observation is SessionObservation.BoundConfirmed &&
                state.guide == GuideState.ENTERED_LOCAL
            ) {
                // Never switch accounts silently: only suggest the continuation.
                state.copy(
                    session = observation,
                    accountContinuationSuggested = true,
                )
            } else {
                state
            }
        }
        return when (observation) {
            SessionObservation.Active -> state.copy(
                session = observation,
                guide = if (state.guide == GuideState.SESSION_PREPARING) {
                    GuideState.QR_VISIBLE
                } else {
                    state.guide
                },
                error = null,
            )

            SessionObservation.Expired -> state.copy(
                session = observation,
                guide = GuideState.RECOVERABLE_ERROR,
                error = ActivationError(ActivationOperation.QUERY_SESSION, ErrorCategory.EXPIRED),
            )

            SessionObservation.ResultUnknown -> state.copy(
                session = observation,
                error = ActivationError(ActivationOperation.QUERY_SESSION, ErrorCategory.RESULT_UNKNOWN),
            )

            is SessionObservation.BoundConfirmed -> state.copy(
                session = observation,
                guide = GuideState.LOGIN_PENDING,
                error = null,
            )

            SessionObservation.None -> state
        }
    }

    private fun skipRequested(state: ActivationState): ActivationState {
        if (!state.qrVisible) return state
        if (state.skip != SkipState.IDLE) return state
        return state.copy(skip = SkipState.SUBMITTING, error = null)
    }

    private fun skipAcknowledged(state: ActivationState, generation: Long): ActivationState {
        if (generation != state.flowGeneration) return state
        if (state.skip != SkipState.SUBMITTING && state.skip != SkipState.RESULT_UNKNOWN) return state
        // A confirmed cloud binding always wins over the local skip branch.
        val nextGuide = if (state.sessionBound) GuideState.LOGIN_PENDING else GuideState.ENTERED_LOCAL
        return state.copy(
            skip = SkipState.ACKNOWLEDGED,
            agreementSync = AgreementSyncState.ACKNOWLEDGED,
            guide = nextGuide,
            error = null,
        )
    }

    private fun skipFailed(
        state: ActivationState,
        generation: Long,
        category: ErrorCategory,
    ): ActivationState {
        if (generation != state.flowGeneration) return state
        return if (category.isRetryableCategory()) {
            state.copy(
                skip = SkipState.RESULT_UNKNOWN,
                error = ActivationError(ActivationOperation.SUBMIT_SKIP, category),
            )
        } else {
            state.copy(
                skip = SkipState.IDLE,
                error = ActivationError(ActivationOperation.SUBMIT_SKIP, category),
            )
        }
    }

    private fun accountContinuationAccepted(state: ActivationState): ActivationState {
        if (state.guide != GuideState.ENTERED_LOCAL) return state
        if (!state.accountContinuationSuggested) return state
        return state.copy(
            guide = GuideState.LOGIN_PENDING,
            accountContinuationSuggested = false,
            error = null,
        )
    }

    private fun loginStarted(state: ActivationState, generation: Long): ActivationState {
        if (generation != state.flowGeneration) return state
        if (!state.sessionBound) return state
        if (state.login == LoginState.IN_PROGRESS || state.login == LoginState.SIGNED_IN) return state
        return state.copy(
            login = LoginState.IN_PROGRESS,
            guide = GuideState.LOGIN_PENDING,
            error = null,
        )
    }

    private fun loginSucceeded(
        state: ActivationState,
        generation: Long,
        account: AccountRef,
        firstInteractionCompleted: Boolean,
    ): ActivationState {
        if (generation != state.flowGeneration) return state
        return state.copy(
            login = LoginState.SIGNED_IN,
            account = account,
            privacyFirstInteractionCompleted = firstInteractionCompleted,
            // A new account scope always starts from an unknown catalogue.
            privacyCatalog = if (firstInteractionCompleted) {
                PrivacyCatalogState.NOT_LOADED
            } else {
                state.privacyCatalog
            },
            agreementSync = AgreementSyncState.PENDING,
            guide = if (firstInteractionCompleted) {
                GuideState.ENTERED_ACCOUNT
            } else {
                GuideState.PRIVACY_PENDING
            },
            error = null,
        )
    }

    private fun loginFailed(
        state: ActivationState,
        generation: Long,
        category: ErrorCategory,
    ): ActivationState {
        if (generation != state.flowGeneration) return state
        return state.copy(
            login = LoginState.RETRY_REQUIRED,
            guide = GuideState.LOGIN_PENDING,
            // The cloud binding stays confirmed: a local login failure never unbinds.
            session = state.session,
            error = ActivationError(ActivationOperation.LOGIN, category),
        )
    }

    private fun privacyDecisionCommitted(
        state: ActivationState,
        choice: net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice,
        firstInteractionCompleted: Boolean,
    ): ActivationState {
        if (state.login != LoginState.SIGNED_IN) return state
        return state.copy(
            privacyChoice = choice,
            privacyFirstInteractionCompleted = firstInteractionCompleted,
            privacySync = net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState.PENDING,
            privacyFrontSeatRequired = false,
            guide = GuideState.ENTERED_ACCOUNT,
            error = null,
        )
    }

    private fun ErrorCategory.isRetryableCategory(): Boolean =
        this == ErrorCategory.RETRYABLE || this == ErrorCategory.RESULT_UNKNOWN

    private fun privacyCatalogLoaded(
        state: ActivationState,
        catalogVersion: String,
        items: List<PrivacyAuthorizationItem>,
    ): ActivationState {
        if (catalogVersion.isBlank()) {
            // A catalogue without a version cannot be reported against: treated as unavailable.
            return privacyCatalogUnavailable(state, PrivacyCatalogUnavailableReason.CATALOG_FAILED)
        }
        if (items.isEmpty()) {
            // BLK-3: an empty catalogue never blocks the entertainment system.
            return privacyCatalogUnavailable(state, PrivacyCatalogUnavailableReason.CATALOG_EMPTY)
        }
        return state.copy(
            privacyCatalog = PrivacyCatalogState.LOADED,
            privacyCatalogVersion = catalogVersion,
            privacyCatalogItems = items,
            privacyCatalogUnavailableReason = null,
        )
    }

    /**
     * Q-001 / BLK-3 transition: the catalogue is unusable, so the report is blocked, but the user
     * enters the account. It must not become RECOVERABLE_ERROR and must not clear cloud facts.
     */
    private fun privacyCatalogUnavailable(
        state: ActivationState,
        reason: PrivacyCatalogUnavailableReason,
    ): ActivationState {
        if (state.login != LoginState.SIGNED_IN) return state
        return state.copy(
            guide = GuideState.ENTERED_ACCOUNT,
            privacyCatalog = PrivacyCatalogState.UNAVAILABLE,
            privacyCatalogUnavailableReason = reason,
            privacyCatalogItems = emptyList(),
            privacySync = net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState.NOT_SYNCED,
            privacyFirstInteractionCompleted = false,
            error = null,
        )
    }

    /** D-016: revoked credentials drop the local account, never the cloud binding fact. */
    private fun credentialsRevoked(
        state: ActivationState,
        reason: TokenInvalidationReason,
    ): ActivationState {
        val nextGuide = when (state.guide) {
            GuideState.ENTERED_ACCOUNT -> GuideState.LOGIN_PENDING
            else -> state.guide
        }
        return state.copy(
            login = LoginState.SIGNED_OUT,
            account = null,
            guide = nextGuide,
            // Binding facts and VIN scoped skip records are preserved on purpose.
            error = null,
        )
    }
}
