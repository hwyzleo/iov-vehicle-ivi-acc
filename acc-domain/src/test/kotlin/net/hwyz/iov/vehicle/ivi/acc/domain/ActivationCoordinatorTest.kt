package net.hwyz.iov.vehicle.ivi.acc.domain

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AgreementLoadOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyPromptResult
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementBundle
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.SkipState
import net.hwyz.iov.vehicle.ivi.acc.domain.support.ActivationTestHarness
import net.hwyz.iov.vehicle.ivi.acc.domain.support.FakeAgreementGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.support.harness
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivationCoordinatorTest {

    private fun ActivationTestHarness.advanceToBind() {
        cloud.defaultQueryOutcome = SessionQueryOutcome.Observed(
            SessionObservation.BoundConfirmed("binding-1"),
        )
        advance(150L)
    }

    // ------------------------------------------------------------------ happy path

    @Test
    fun `agreements gate the qr and a trusted binding completes login and privacy`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.coordinator.start()
        h.runCurrent()
        assertEquals(GuideState.AGREEMENT_REQUIRED, h.coordinator.state.value.guide)
        assertTrue(h.coordinator.state.value.documentsComplete)

        h.coordinator.acceptAgreement(AgreementType.PRIVACY_POLICY)
        h.runCurrent()
        assertTrue(h.cloud.sessionRequests.isEmpty())

        h.coordinator.acceptAgreement(AgreementType.USER_AGREEMENT)
        h.runCurrent()
        val qr = h.coordinator.state.value
        assertEquals(GuideState.QR_VISIBLE, qr.guide)
        assertTrue(qr.qrVisible)
        assertEquals(1, h.cloud.sessionRequests.size)
        assertEquals(1L, h.cloud.sessionRequests.single().flowGeneration)

        h.cloud.defaultQueryOutcome = SessionQueryOutcome.Observed(
            SessionObservation.BoundConfirmed("binding-1"),
        )
        h.advance(150L)

        val state = h.coordinator.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, state.guide)
        assertEquals(LoginState.SIGNED_IN, state.login)
        assertEquals(AccountRef("account-001"), state.account)
        assertEquals(PrivacyChoice.GRANTED, state.privacyChoice)
        assertEquals(PrivacySyncState.ACKNOWLEDGED, state.privacySync)
        assertTrue(h.loginGateway.receivedTickets.isNotEmpty())
        assertTrue(state.sessionBound)
    }

    @Test
    fun `agreement loading is visible before the documents arrive`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.agreementGateway.delayMillis = 500L
        h.coordinator.start()
        h.runCurrent()

        val loading = h.coordinator.state.value
        assertTrue(loading.documentsLoading)
        assertTrue(loading.documents.isEmpty())
        assertNull(loading.documentsFailure)

        h.advance(600L)
        val loaded = h.coordinator.state.value
        assertFalse(loaded.documentsLoading)
        assertTrue(loaded.documentsComplete)
        assertEquals(GuideState.AGREEMENT_REQUIRED, loaded.guide)
    }

    @Test
    fun `incomplete agreement documents never open the flow`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.agreementGateway.outcome = AgreementLoadOutcome.Loaded(
            AgreementBundle(listOf(FakeAgreementGateway.document(AgreementType.PRIVACY_POLICY))),
        )
        h.coordinator.start()
        h.runCurrent()
        val state = h.coordinator.state.value
        assertEquals(GuideState.RECOVERABLE_ERROR, state.guide)
        assertEquals(ActivationOperation.LOAD_AGREEMENTS, state.error?.operation)
        assertTrue(h.cloud.sessionRequests.isEmpty())
    }

    @Test
    fun `agreement load failure can be retried`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.agreementGateway.outcome = AgreementLoadOutcome.Failed(ErrorCategory.RETRYABLE)
        h.coordinator.start()
        h.runCurrent()
        assertEquals(GuideState.RECOVERABLE_ERROR, h.coordinator.state.value.guide)

        h.agreementGateway.outcome = AgreementLoadOutcome.Loaded(FakeAgreementGateway.defaultBundle())
        h.coordinator.reloadAgreements()
        h.runCurrent()
        assertEquals(GuideState.AGREEMENT_REQUIRED, h.coordinator.state.value.guide)
        assertTrue(h.coordinator.state.value.documentsComplete)
    }

    @Test
    fun `a published version change forces re consent`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        assertEquals(GuideState.QR_VISIBLE, h.coordinator.state.value.guide)

        h.agreementGateway.outcome = AgreementLoadOutcome.Loaded(
            AgreementBundle(
                listOf(
                    FakeAgreementGateway.document(AgreementType.PRIVACY_POLICY, "2.0"),
                    FakeAgreementGateway.document(AgreementType.USER_AGREEMENT, "2.0"),
                ),
            ),
        )
        h.coordinator.reloadAgreements()
        h.runCurrent()
        val state = h.coordinator.state.value
        assertEquals(GuideState.AGREEMENT_REQUIRED, state.guide)
        assertNull(state.sessionRef)
        assertFalse(state.agreementsAccepted)
    }

    // ------------------------------------------------------------------ refresh / isolation

    @Test
    fun `refresh allocates a new generation and session`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        val first = h.coordinator.state.value
        val firstSession = first.sessionRef?.sessionId

        h.coordinator.refreshSession()
        h.runCurrent()
        val refreshed = h.coordinator.state.value
        assertEquals(first.flowGeneration + 1, refreshed.flowGeneration)
        assertTrue(refreshed.sessionRef?.sessionId != firstSession)
        assertEquals(2, h.cloud.sessionRequests.size)
    }

    @Test
    fun `background stops polling and foreground resumes with a query`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.coordinator.onBackground()
        h.runCurrent()
        val queriesWhileBackground = h.cloud.queryCount
        h.advance(500L)
        assertEquals(queriesWhileBackground, h.cloud.queryCount)

        h.coordinator.onForeground()
        h.runCurrent()
        assertTrue(h.cloud.queryCount > queriesWhileBackground)
    }

    @Test
    fun `session timeout keeps an unknown result instead of claiming failure`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.cloud.defaultQueryOutcome = SessionQueryOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)
        h.advance(2_000L)
        val state = h.coordinator.state.value
        assertEquals(GuideState.QR_VISIBLE, state.guide)
        assertEquals(ErrorCategory.RESULT_UNKNOWN, state.error?.category)
        assertFalse(state.sessionBound)
        assertFalse(state.login == LoginState.SIGNED_IN)
    }

    @Test
    fun `an expired session asks for a refresh instead of failing`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.cloud.defaultQueryOutcome = SessionQueryOutcome.Observed(SessionObservation.Expired)
        h.advance(150L)
        val state = h.coordinator.state.value
        assertEquals(GuideState.RECOVERABLE_ERROR, state.guide)
        assertEquals(ErrorCategory.EXPIRED, state.error?.category)

        h.cloud.defaultQueryOutcome = SessionQueryOutcome.Observed(SessionObservation.Active)
        h.coordinator.refreshSession()
        h.runCurrent()
        assertEquals(GuideState.QR_VISIBLE, h.coordinator.state.value.guide)
    }

    // ------------------------------------------------------------------ skip (A-003 / A-008)

    @Test
    fun `skip acknowledges and enters the local account without login`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.coordinator.skipActivation()
        h.runCurrent()

        val state = h.coordinator.state.value
        assertEquals(GuideState.ENTERED_LOCAL, state.guide)
        assertEquals(SkipState.ACKNOWLEDGED, state.skip)
        assertEquals(LoginState.SIGNED_OUT, state.login)
        assertEquals(AgreementSyncState.ACKNOWLEDGED, state.agreementSync)
        assertTrue(h.loginGateway.receivedTickets.isEmpty())
        assertTrue(state.entertainmentEntryAllowed)
        val skipRequestId = h.cloud.skipRequests.single().requestId
        assertEquals(SyncState.ACKNOWLEDGED, h.recordStore.records[skipRequestId]?.syncState)
    }

    @Test
    fun `skip is submitted once even when the user presses twice`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.coordinator.skipActivation()
        h.coordinator.skipActivation()
        h.runCurrent()
        assertEquals(1, h.cloud.skipRequests.size)
    }

    @Test
    fun `a timed out skip keeps an unknown result and retries with the same correlation id`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.cloud.skipOutcome = SkipSubmitOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)
        h.coordinator.skipActivation()
        h.runCurrent()

        val unknown = h.coordinator.state.value
        assertEquals(SkipState.RESULT_UNKNOWN, unknown.skip)
        assertEquals(GuideState.QR_VISIBLE, unknown.guide)
        assertFalse(unknown.entertainmentEntryAllowed)

        h.cloud.skipOutcome = SkipSubmitOutcome.Acknowledged
        h.coordinator.retrySkip()
        h.runCurrent()
        assertEquals(2, h.cloud.skipRequests.size)
        assertEquals(
            h.cloud.skipRequests[0].requestId,
            h.cloud.skipRequests[1].requestId,
        )
        assertEquals(GuideState.ENTERED_LOCAL, h.coordinator.state.value.guide)
    }

    @Test
    fun `a binding that arrives after the local entry only suggests continuation`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.coordinator.skipActivation()
        h.runCurrent()
        assertEquals(GuideState.ENTERED_LOCAL, h.coordinator.state.value.guide)

        h.cloud.defaultQueryOutcome = SessionQueryOutcome.Observed(
            SessionObservation.BoundConfirmed("binding-late"),
        )
        h.coordinator.querySessionNow()
        h.runCurrent()
        val suggested = h.coordinator.state.value
        assertEquals(GuideState.ENTERED_LOCAL, suggested.guide)
        assertTrue(suggested.accountContinuationSuggested)
        assertTrue(h.loginGateway.receivedTickets.isEmpty())

        h.coordinator.acceptAccountContinuation()
        h.runCurrent()
        val continued = h.coordinator.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, continued.guide)
        assertEquals(LoginState.SIGNED_IN, continued.login)
        assertTrue(h.loginGateway.receivedTickets.isNotEmpty())
    }

    // ------------------------------------------------------------------ login recovery (A-004)

    @Test
    fun `login failure keeps the binding and can be retried`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.loginGateway.outcome = LoginOutcome.Failed(ErrorCategory.RETRYABLE)
        h.advanceToBind()

        val failed = h.coordinator.state.value
        assertEquals(GuideState.LOGIN_PENDING, failed.guide)
        assertEquals(LoginState.RETRY_REQUIRED, failed.login)
        assertTrue(failed.sessionBound)
        assertEquals(ActivationOperation.LOGIN, failed.error?.operation)

        h.loginGateway.outcome = LoginOutcome.Success(AccountRef("account-001"))
        h.coordinator.retryLogin()
        h.runCurrent()
        val recovered = h.coordinator.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, recovered.guide)
        assertEquals(LoginState.SIGNED_IN, recovered.login)
        assertEquals(2, h.loginGateway.receivedTickets.size)
    }

    @Test
    fun `expired login ticket is not reused`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.cloud.ticketOutcome = net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginTicketOutcome.Ticket(
            net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginTicket(
                ticket = "stale-ticket",
                expiresAtEpochMillis = h.clock.nowEpochMillis() - 1,
            ),
        )
        h.advanceToBind()
        val state = h.coordinator.state.value
        assertEquals(LoginState.RETRY_REQUIRED, state.login)
        assertTrue(h.loginGateway.receivedTickets.isEmpty())
        assertEquals(ErrorCategory.EXPIRED, state.error?.category)
    }

    @Test
    fun `a restart resumes the login without unbinding`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.loginGateway.outcome = LoginOutcome.Failed(ErrorCategory.RETRYABLE)
        h.advanceToBind()
        assertEquals(GuideState.LOGIN_PENDING, h.coordinator.state.value.guide)
        h.coordinator.close()

        h.loginGateway.outcome = LoginOutcome.Success(AccountRef("account-001"))
        val restarted = h.newCoordinator()
        restarted.start()
        h.runCurrent()
        val state = restarted.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, state.guide)
        assertEquals(LoginState.SIGNED_IN, state.login)
        assertTrue(h.loginGateway.receivedTickets.size >= 2)
        // No unbind / rebind request was made: the skip path was never used.
        assertTrue(h.cloud.skipRequests.isEmpty())
        restarted.close()
    }

    // ------------------------------------------------------------------ privacy (A-006)

    @Test
    fun `closing the privacy dialog is not a grant but still allows entry`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.privacyGateway.result = PrivacyPromptResult.Decided(PrivacyChoice.NOT_GRANTED)
        h.startAndAcceptBoth()
        h.advanceToBind()

        val state = h.coordinator.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, state.guide)
        assertEquals(PrivacyChoice.NOT_GRANTED, state.privacyChoice)
        assertEquals(PrivacySyncState.ACKNOWLEDGED, state.privacySync)
        assertTrue(state.privacyFirstInteractionCompleted)
    }

    @Test
    fun `privacy persistence failure keeps the flow in privacy pending`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.privacyStore.failCommit = true
        h.startAndAcceptBoth()
        h.advanceToBind()
        val failed = h.coordinator.state.value
        assertEquals(GuideState.PRIVACY_PENDING, failed.guide)
        assertEquals(PrivacyChoice.NOT_DECIDED, failed.privacyChoice)
        assertFalse(failed.privacyFirstInteractionCompleted)

        h.privacyStore.failCommit = false
        h.coordinator.retryPrivacyPrompt()
        h.runCurrent()
        assertEquals(GuideState.ENTERED_ACCOUNT, h.coordinator.state.value.guide)
    }

    @Test
    fun `the first interaction dialog is not repeated after a restart`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.advanceToBind()
        assertEquals(1, h.privacyGateway.calls)
        h.coordinator.close()

        val restarted = h.newCoordinator()
        restarted.start()
        h.runCurrent()
        assertEquals(1, h.privacyGateway.calls)
        assertEquals(GuideState.ENTERED_ACCOUNT, restarted.state.value.guide)
        restarted.close()
    }

    @Test
    fun `a rear seat request only reports that the front seat must operate`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.privacyGateway.result = net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyPromptResult.FrontSeatRequired
        h.startAndAcceptBoth()
        h.advanceToBind()
        val state = h.coordinator.state.value
        assertEquals(GuideState.PRIVACY_PENDING, state.guide)
        assertTrue(state.privacyFrontSeatRequired)
        assertEquals(ActivationOperation.PRIVACY_PROMPT, state.error?.operation)
    }

    // ------------------------------------------------------------------ records / diagnostics

    @Test
    fun `record upload failure keeps a retryable pending record`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.cloud.recordOutcome = net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome.Failed(
            ErrorCategory.RETRYABLE,
        )
        h.startAndAcceptBoth()
        h.advanceToBind()
        val pending = h.recordStore.records.values.filter { it.syncState == SyncState.PENDING }
        assertTrue(pending.isNotEmpty())
        // Only the reportable records are uploaded; the consent evidence rides with the login result.
        assertTrue(pending.filter { it.type == RecordType.LOGIN_RESULT }.all { it.attempts > 0 })
        // The cloud binding and the local login stay valid even when the upload is pending.
        assertEquals(LoginState.SIGNED_IN, h.coordinator.state.value.login)
        assertEquals(AgreementSyncState.PENDING, h.coordinator.state.value.agreementSync)
    }

    @Test
    fun `logs never contain secrets or vehicle identifiers`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.advanceToBind()
        h.coordinator.onBackground()
        h.runCurrent()

        val logs = h.logger.entries.joinToString("\n")
        assertFalse(logs.contains("qr-secret"))
        assertFalse(logs.contains(h.vehicle.vin))
        assertFalse(logs.contains("material-handle"))
        assertFalse(logs.contains("account-001"))
        assertTrue(logs.contains("state.transition"))
    }

    @Test
    fun `factory reset clears local state only`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        val requestsBefore = h.cloud.sessionRequests.size
        h.materialStore.putSealed("handle", "secret", Long.MAX_VALUE)
        h.coordinator.resetLocalStateForFactoryReset()
        h.runCurrent()

        assertEquals(GuideState.AGREEMENT_REQUIRED, h.coordinator.state.value.guide)
        assertNull(h.flowStore.context)
        assertTrue(h.materialStore.entries.isEmpty())
        assertTrue(h.recordStore.records.isEmpty())
        // No cloud interaction was triggered by the local reset.
        assertEquals(requestsBefore, h.cloud.sessionRequests.size)
    }

    @Test
    fun `privacy consent is scoped to account and vehicle`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.advanceToBind()
        val decision = h.privacyStore.loadDecision("AV:account-001|${h.vehicle.vin}")
        assertNotNull(decision)
        assertEquals(PrivacyChoice.GRANTED, decision.choice)
    }
}
