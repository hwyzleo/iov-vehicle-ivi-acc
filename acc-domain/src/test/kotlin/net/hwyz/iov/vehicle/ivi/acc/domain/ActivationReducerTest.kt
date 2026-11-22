package net.hwyz.iov.vehicle.ivi.acc.domain

import net.hwyz.iov.vehicle.ivi.acc.domain.event.ActivationEvent
import net.hwyz.iov.vehicle.ivi.acc.domain.event.RestoredFlow
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptanceSnapshot
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementDocument
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.SkipState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivationReducerTest {

    private val session = SessionRef(
        sessionId = "session-1",
        qrPayload = "qr-secret",
        issuedAtEpochMillis = 0L,
        expiresAtEpochMillis = Long.MAX_VALUE,
    )

    private fun documents(version: String = "1.0"): List<AgreementDocument> = listOf(
        AgreementDocument(AgreementType.PRIVACY_POLICY, version, "rev-$version", "zh-CN", "hash-$version"),
        AgreementDocument(AgreementType.USER_AGREEMENT, version, "rev-$version", "zh-CN", "hash-$version"),
    )

    private fun accepted(
        type: AgreementType,
        version: String = "1.0",
    ): ActivationEvent.AgreementAccepted = ActivationEvent.AgreementAccepted(
        type = type,
        version = version,
        revisionId = "rev-$version",
        contentHash = "hash-$version",
        language = "zh-CN",
        agreedAtEpochMillis = 100L,
    )

    private fun reduce(state: ActivationState, vararg events: ActivationEvent): ActivationState =
        events.fold(state) { current, event -> ActivationReducer.reduce(current, event) }

    private fun documentsState(): ActivationState =
        reduce(ActivationState(), ActivationEvent.FlowStarted(null), ActivationEvent.DocumentsLoaded(documents()))

    private fun qrState(): ActivationState = reduce(
        documentsState(),
        accepted(AgreementType.PRIVACY_POLICY),
        accepted(AgreementType.USER_AGREEMENT),
        ActivationEvent.SessionPreparationStarted(1L),
        ActivationEvent.SessionCreated(1L, session),
    )

    private fun boundState(): ActivationState = reduce(
        qrState(),
        ActivationEvent.SessionObserved(1L, session.sessionId, SessionObservation.BoundConfirmed("bind-1")),
    )

    private fun enteredLocalState(): ActivationState = reduce(
        qrState(),
        ActivationEvent.SkipRequested,
        ActivationEvent.SkipAcknowledged(1L, session.sessionId),
    )

    // ------------------------------------------------------------------ agreement gate (US-001 / A-001)

    @Test
    fun `qr stays hidden until both agreements are accepted`() {
        val state = reduce(
            documentsState(),
            accepted(AgreementType.PRIVACY_POLICY),
            ActivationEvent.SessionPreparationStarted(1L),
            ActivationEvent.SessionCreated(1L, session),
        )
        assertEquals(GuideState.SESSION_PREPARING, state.guide)
        assertNull(state.sessionRef)
        assertFalse(state.qrVisible)
    }

    @Test
    fun `acceptance is bound to the displayed version`() {
        val state = reduce(documentsState(), accepted(AgreementType.PRIVACY_POLICY, version = "9.9"))
        assertNull(state.acceptance(AgreementType.PRIVACY_POLICY))
    }

    @Test
    fun `a newly published version requires a new consent`() {
        val state = reduce(qrState(), ActivationEvent.DocumentsLoaded(documents("2.0")))
        assertEquals(GuideState.AGREEMENT_REQUIRED, state.guide)
        assertNull(state.sessionRef)
        assertFalse(state.agreementsAccepted)
        assertTrue(state.agreementVersionDrift)
        // The historical acceptance is kept, it is not silently rewritten to 2.0.
        assertEquals("1.0", state.acceptance(AgreementType.PRIVACY_POLICY)?.version)
    }

    @Test
    fun `agreement loading is an explicit state before any content is shown`() {
        val loading = reduce(ActivationState(), ActivationEvent.FlowStarted(null), ActivationEvent.DocumentsLoadStarted)
        assertTrue(loading.documentsLoading)
        assertTrue(loading.documents.isEmpty())
        assertNull(loading.documentsFailure)
        assertFalse(loading.documentsComplete)

        val loaded = ActivationReducer.reduce(loading, ActivationEvent.DocumentsLoaded(documents()))
        assertFalse(loaded.documentsLoading)
        assertTrue(loaded.documentsComplete)

        val failed = ActivationReducer.reduce(
            loading,
            ActivationEvent.DocumentsLoadFailed(ErrorCategory.RETRYABLE),
        )
        assertFalse(failed.documentsLoading)
        assertEquals(ErrorCategory.RETRYABLE, failed.documentsFailure)
        assertFalse(failed.documentsComplete)
    }

    @Test
    fun `a retry clears the previous failure and shows loading again`() {
        val failed = reduce(
            ActivationState(),
            ActivationEvent.FlowStarted(null),
            ActivationEvent.DocumentsLoadFailed(ErrorCategory.RETRYABLE),
        )
        val retrying = ActivationReducer.reduce(failed, ActivationEvent.DocumentsLoadStarted)
        assertTrue(retrying.documentsLoading)
        assertNull(retrying.documentsFailure)
        assertNull(retrying.error)
    }

    @Test
    fun `incomplete documents block the flow`() {
        val state = reduce(
            ActivationState(),
            ActivationEvent.FlowStarted(null),
            ActivationEvent.DocumentsLoadFailed(ErrorCategory.CONTRACT_REJECTED),
        )
        assertEquals(GuideState.RECOVERABLE_ERROR, state.guide)
        assertEquals(ActivationOperation.LOAD_AGREEMENTS, state.error?.operation)
        assertFalse(state.documentsComplete)
    }

    // ------------------------------------------------------------------ session isolation (US-002 / A-002)

    @Test
    fun `observations from another generation or session are ignored`() {
        val qr = qrState()
        assertEquals(
            qr,
            ActivationReducer.reduce(
                qr,
                ActivationEvent.SessionObserved(0L, session.sessionId, SessionObservation.BoundConfirmed()),
            ),
        )
        assertEquals(
            qr,
            ActivationReducer.reduce(
                qr,
                ActivationEvent.SessionObserved(1L, "old-session", SessionObservation.BoundConfirmed()),
            ),
        )
    }

    @Test
    fun `expired session blocks the qr and asks for a refresh`() {
        val state = reduce(
            qrState(),
            ActivationEvent.SessionObserved(1L, session.sessionId, SessionObservation.Expired),
        )
        assertEquals(GuideState.RECOVERABLE_ERROR, state.guide)
        assertEquals(ErrorCategory.EXPIRED, state.error?.category)
        assertFalse(state.qrVisible)
    }

    @Test
    fun `unknown session result never claims success`() {
        val state = reduce(
            qrState(),
            ActivationEvent.SessionObserved(1L, session.sessionId, SessionObservation.ResultUnknown),
        )
        assertEquals(GuideState.QR_VISIBLE, state.guide)
        assertEquals(SessionObservation.ResultUnknown, state.session)
        assertEquals(ErrorCategory.RESULT_UNKNOWN, state.error?.category)
        assertFalse(state.sessionBound)
    }

    // ------------------------------------------------------------------ skip (US-003 / A-003)

    @Test
    fun `skip can only be submitted once`() {
        val submitting = reduce(qrState(), ActivationEvent.SkipRequested)
        assertEquals(SkipState.SUBMITTING, submitting.skip)
        assertEquals(submitting, ActivationReducer.reduce(submitting, ActivationEvent.SkipRequested))
    }

    @Test
    fun `acknowledged skip enters local account without login`() {
        val state = enteredLocalState()
        assertEquals(GuideState.ENTERED_LOCAL, state.guide)
        assertEquals(LoginState.SIGNED_OUT, state.login)
        assertEquals(AgreementSyncState.ACKNOWLEDGED, state.agreementSync)
        assertTrue(state.entertainmentEntryAllowed)
        assertFalse(state.sessionBound)
    }

    @Test
    fun `a confirmed binding wins over the skip branch`() {
        val state = reduce(
            reduce(
                reduce(qrState(), ActivationEvent.SkipRequested),
                ActivationEvent.SessionObserved(
                    1L,
                    session.sessionId,
                    SessionObservation.BoundConfirmed("bind-1"),
                ),
            ),
            ActivationEvent.SkipAcknowledged(1L, session.sessionId),
        )
        assertEquals(GuideState.LOGIN_PENDING, state.guide)
        assertEquals(SkipState.ACKNOWLEDGED, state.skip)
        assertTrue(state.sessionBound)
    }

    @Test
    fun `retryable skip failure keeps an unknown result`() {
        val state = reduce(
            reduce(qrState(), ActivationEvent.SkipRequested),
            ActivationEvent.SkipFailed(1L, session.sessionId, ErrorCategory.RESULT_UNKNOWN),
        )
        assertEquals(SkipState.RESULT_UNKNOWN, state.skip)
        assertEquals(GuideState.QR_VISIBLE, state.guide)
        assertTrue(state.error!!.retryable)
        assertFalse(state.entertainmentEntryAllowed)
    }

    @Test
    fun `terminal skip failure allows a new attempt`() {
        val state = reduce(
            reduce(qrState(), ActivationEvent.SkipRequested),
            ActivationEvent.SkipFailed(1L, session.sessionId, ErrorCategory.CONTRACT_REJECTED),
        )
        assertEquals(SkipState.IDLE, state.skip)
        assertEquals(GuideState.QR_VISIBLE, state.guide)
        assertFalse(state.entertainmentEntryAllowed)
    }

    @Test
    fun `late binding during local entry only suggests the account continuation`() {
        val suggested = reduce(
            enteredLocalState(),
            ActivationEvent.SessionObserved(1L, session.sessionId, SessionObservation.BoundConfirmed("bind-9")),
        )
        assertEquals(GuideState.ENTERED_LOCAL, suggested.guide)
        assertTrue(suggested.accountContinuationSuggested)
        assertTrue(suggested.sessionBound)

        val continued = ActivationReducer.reduce(suggested, ActivationEvent.AccountContinuationAccepted)
        assertEquals(GuideState.LOGIN_PENDING, continued.guide)
        assertFalse(continued.accountContinuationSuggested)
        assertEquals(LoginState.SIGNED_OUT, continued.login)
    }

    // ------------------------------------------------------------------ login and recovery (US-004 / A-004)

    @Test
    fun `login is only possible with a confirmed binding`() {
        val state = ActivationReducer.reduce(qrState(), ActivationEvent.LoginStarted(1L))
        assertEquals(LoginState.SIGNED_OUT, state.login)
        assertEquals(GuideState.QR_VISIBLE, state.guide)
    }

    @Test
    fun `login failure keeps the cloud binding`() {
        val state = reduce(
            boundState(),
            ActivationEvent.LoginStarted(1L),
            ActivationEvent.LoginFailed(1L, ErrorCategory.RETRYABLE),
        )
        assertEquals(LoginState.RETRY_REQUIRED, state.login)
        assertEquals(GuideState.LOGIN_PENDING, state.guide)
        assertTrue(state.sessionBound)
        assertEquals(ActivationOperation.LOGIN, state.error?.operation)
    }

    @Test
    fun `duplicate login start is ignored while in progress`() {
        val inProgress = reduce(boundState(), ActivationEvent.LoginStarted(1L))
        assertEquals(LoginState.IN_PROGRESS, inProgress.login)
        assertEquals(inProgress, ActivationReducer.reduce(inProgress, ActivationEvent.LoginStarted(1L)))
    }

    @Test
    fun `login success goes through the first privacy interaction`() {
        val pending = reduce(
            boundState(),
            ActivationEvent.LoginStarted(1L),
            ActivationEvent.LoginSucceeded(1L, AccountRef("account-1"), firstInteractionCompleted = false),
        )
        assertEquals(GuideState.PRIVACY_PENDING, pending.guide)
        assertEquals(LoginState.SIGNED_IN, pending.login)

        val entered = ActivationReducer.reduce(
            pending,
            ActivationEvent.PrivacyDecisionCommitted(PrivacyChoice.NOT_GRANTED, true),
        )
        assertEquals(GuideState.ENTERED_ACCOUNT, entered.guide)
        assertEquals(PrivacyChoice.NOT_GRANTED, entered.privacyChoice)
        assertEquals(PrivacySyncState.PENDING, entered.privacySync)
        assertTrue(entered.privacyFirstInteractionCompleted)
    }

    @Test
    fun `already completed first interaction skips the dialog`() {
        val state = reduce(
            boundState(),
            ActivationEvent.LoginStarted(1L),
            ActivationEvent.LoginSucceeded(1L, AccountRef("account-1"), firstInteractionCompleted = true),
        )
        assertEquals(GuideState.ENTERED_ACCOUNT, state.guide)
    }

    @Test
    fun `privacy persistence failure does not fake completion`() {
        val pending = reduce(
            boundState(),
            ActivationEvent.LoginStarted(1L),
            ActivationEvent.LoginSucceeded(1L, AccountRef("account-1"), firstInteractionCompleted = false),
            ActivationEvent.PrivacyDecisionPersistFailed,
        )
        assertEquals(GuideState.PRIVACY_PENDING, pending.guide)
        assertEquals(PrivacyChoice.NOT_DECIDED, pending.privacyChoice)
        assertFalse(pending.privacyFirstInteractionCompleted)
        assertEquals(ActivationOperation.PRIVACY_PERSIST, pending.error?.operation)
        assertTrue(pending.error!!.retryable)
    }

    @Test
    fun `privacy sync state is independent from the selection`() {
        val granted = reduce(
            boundState(),
            ActivationEvent.LoginStarted(1L),
            ActivationEvent.LoginSucceeded(1L, AccountRef("account-1"), firstInteractionCompleted = false),
            ActivationEvent.PrivacyDecisionCommitted(PrivacyChoice.GRANTED, true),
            ActivationEvent.PrivacySyncUpdated(PrivacySyncState.ACKNOWLEDGED),
        )
        assertEquals(PrivacyChoice.GRANTED, granted.privacyChoice)
        assertEquals(PrivacySyncState.ACKNOWLEDGED, granted.privacySync)
    }

    @Test
    fun `entered account is terminal for late session observations`() {
        val entered = reduce(
            boundState(),
            ActivationEvent.LoginStarted(1L),
            ActivationEvent.LoginSucceeded(1L, AccountRef("account-1"), firstInteractionCompleted = true),
        )
        assertEquals(GuideState.ENTERED_ACCOUNT, entered.guide)
        assertEquals(
            entered,
            ActivationReducer.reduce(
                entered,
                ActivationEvent.SessionObserved(1L, session.sessionId, SessionObservation.Expired),
            ),
        )
        assertEquals(
            entered,
            ActivationReducer.reduce(
                entered,
                ActivationEvent.SessionObserved(1L, session.sessionId, SessionObservation.ResultUnknown),
            ),
        )
    }

    // ------------------------------------------------------------------ restore (US-004 / A-004)

    private fun restored(
        guide: GuideState,
        bindingConfirmed: Boolean,
        acceptances: List<AgreementAcceptanceSnapshot> = emptyless(),
    ): RestoredFlow = RestoredFlow(
        flowGeneration = 3L,
        guide = guide,
        sessionId = "session-restored",
        sessionExpiresAtEpochMillis = Long.MAX_VALUE,
        bindingOperationId = "bind-restored",
        bindingConfirmed = bindingConfirmed,
        login = LoginState.RETRY_REQUIRED,
        accountId = "account-1",
        acceptances = acceptances,
        agreementSync = AgreementSyncState.PENDING,
        privacyChoice = PrivacyChoice.GRANTED,
        privacySync = PrivacySyncState.ACKNOWLEDGED,
        privacyFirstInteractionCompleted = true,
    )

    private fun emptyless(): List<AgreementAcceptanceSnapshot> = listOf(
        AgreementAcceptanceSnapshot(AgreementType.PRIVACY_POLICY, "1.0", "rev-1.0", "hash-1.0", "zh-CN", 1L),
        AgreementAcceptanceSnapshot(AgreementType.USER_AGREEMENT, "1.0", "rev-1.0", "hash-1.0", "zh-CN", 1L),
    )

    @Test
    fun `a cached qr session is never trusted after restart`() {
        val state = ActivationReducer.reduce(
            ActivationState(),
            ActivationEvent.FlowStarted(restored(GuideState.QR_VISIBLE, bindingConfirmed = false)),
        )
        assertEquals(GuideState.RECOVERABLE_ERROR, state.guide)
        assertEquals(ActivationOperation.REQUEST_SESSION, state.error?.operation)
        assertNull(state.sessionRef)
        assertFalse(state.qrVisible)
    }

    @Test
    fun `a restored binding goes back to login pending`() {
        val state = ActivationReducer.reduce(
            ActivationState(),
            ActivationEvent.FlowStarted(restored(GuideState.LOGIN_PENDING, bindingConfirmed = true)),
        )
        assertEquals(GuideState.LOGIN_PENDING, state.guide)
        assertTrue(state.sessionBound)
        assertEquals("session-restored", state.sessionRef?.sessionId)
    }

    @Test
    fun `entered states survive a restart`() {
        val local = ActivationReducer.reduce(
            ActivationState(),
            ActivationEvent.FlowStarted(restored(GuideState.ENTERED_LOCAL, bindingConfirmed = false)),
        )
        assertEquals(GuideState.ENTERED_LOCAL, local.guide)
        assertTrue(local.entertainmentEntryAllowed)

        val account = ActivationReducer.reduce(
            ActivationState(),
            ActivationEvent.FlowStarted(restored(GuideState.ENTERED_ACCOUNT, bindingConfirmed = true)),
        )
        assertEquals(GuideState.ENTERED_ACCOUNT, account.guide)
        assertEquals(PrivacyChoice.GRANTED, account.privacyChoice)
    }

    @Test
    fun `a fresh start has no acceptances and no binding`() {
        val state = ActivationReducer.reduce(
            ActivationState(),
            ActivationEvent.FlowStarted(null),
        )
        assertEquals(GuideState.AGREEMENT_REQUIRED, state.guide)
        assertTrue(state.acceptances.isEmpty())
        assertNull(state.sessionRef)
    }

    @Test
    fun `foreground flag is tracked for polling control`() {
        val background = ActivationReducer.reduce(
            qrState(),
            ActivationEvent.ForegroundChanged(false),
        )
        assertFalse(background.foreground)
        val resumed = ActivationReducer.reduce(background, ActivationEvent.ForegroundChanged(true))
        assertTrue(resumed.foreground)
        assertNotNull(resumed.sessionRef)
    }
}
