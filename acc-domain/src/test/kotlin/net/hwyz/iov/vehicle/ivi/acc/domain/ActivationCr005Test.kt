package net.hwyz.iov.vehicle.ivi.acc.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogUnavailableReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatBlockReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatRole
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenInvalidationReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.TokenLifecycleEvent
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.IviLoginFailureReason
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordPayload
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.support.ActivationTestHarness
import net.hwyz.iov.vehicle.ivi.acc.domain.support.harness
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IVI-ACC-DSN-CR-005 defect-face matrix (D-018) at the coordinator level:
 * D-011 device identity, D-012 idempotency semantics, D-013 catalogue three states, D-014 polling
 * deadline and single flight, D-005 failed receipts, D-016 revocation, D-017 seat gate.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivationCr005Test {

    private fun ActivationTestHarness.advanceToBind(bindingOperationId: String? = "binding-1") {
        cloud.defaultQueryOutcome = SessionQueryOutcome.Observed(
            SessionObservation.BoundConfirmed(bindingOperationId),
        )
        advance(150L)
    }

    // ------------------------------------------------------------------ D-013 three states

    @Test
    fun `A-019 a loaded catalogue prompts with the cloud items and reports against its version`() =
        runTest {
            val h = harness(this.backgroundScope, testScheduler)
            h.startAndAcceptBoth()
            h.advanceToBind()

            val state = h.coordinator.state.value
            assertEquals(GuideState.ENTERED_ACCOUNT, state.guide)
            assertEquals(PrivacyCatalogState.LOADED, state.privacyCatalog)
            assertEquals("v1", state.privacyCatalogVersion)
            assertEquals(1, h.privacyGateway.calls)
            // The dialog items are the catalogue items, displayName included.
            assertEquals(listOf("PERSONALIZED_CONTENT"), h.privacyGateway.lastItems.map { it.code })
            assertEquals("个性化内容", h.privacyGateway.lastItems.single().displayName)
            assertEquals("zh-CN", h.catalogGateway.lastLanguage)

            val record = h.recordStore.records.values.single { it.type == RecordType.PRIVACY_DECISION }
            val payload = record.payload as RecordPayload.PrivacyDecisionRecord
            assertEquals("v1", payload.catalogVersion)
            assertEquals(1, payload.items.single().version)
            assertEquals(
                net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyItemState.GRANTED,
                payload.items.single().state,
            )
        }

    @Test
    fun `A-019 an empty catalogue never blocks entry and never reports`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.catalogGateway.outcome = PrivacyCatalogOutcome.Loaded("v1", emptyList())
        h.startAndAcceptBoth()
        h.advanceToBind()

        val state = h.coordinator.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, state.guide)
        assertTrue(state.entertainmentEntryAllowed)
        assertEquals(PrivacyCatalogState.UNAVAILABLE, state.privacyCatalog)
        assertEquals(PrivacyCatalogUnavailableReason.CATALOG_EMPTY, state.privacyCatalogUnavailableReason)
        assertEquals(PrivacySyncState.NOT_SYNCED, state.privacySync)
        assertFalse(state.privacyFirstInteractionCompleted)
        assertNull(state.error)
        // No dialog for an empty catalogue and no report at all.
        assertEquals(0, h.privacyGateway.calls)
        assertTrue(h.recordStore.records.values.none { it.type == RecordType.PRIVACY_DECISION })
        assertTrue(h.cloud.reportedRecordIds.none { it.startsWith("privacy-report") })
    }

    @Test
    fun `A-019 a failed catalogue never blocks entry and keeps a retry entry`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.catalogGateway.outcome = PrivacyCatalogOutcome.Failed(ErrorCategory.RETRYABLE, "401108")
        h.startAndAcceptBoth()
        h.advanceToBind()

        val state = h.coordinator.state.value
        assertEquals(GuideState.ENTERED_ACCOUNT, state.guide)
        assertEquals(PrivacyCatalogState.UNAVAILABLE, state.privacyCatalog)
        assertEquals(PrivacyCatalogUnavailableReason.CATALOG_FAILED, state.privacyCatalogUnavailableReason)
        assertEquals(PrivacySyncState.NOT_SYNCED, state.privacySync)
        assertFalse(state.privacyFirstInteractionCompleted)
        assertEquals(0, h.privacyGateway.calls)
        assertTrue(h.recordStore.records.values.none { it.type == RecordType.PRIVACY_DECISION })

        // Retry entry: the catalogue is loaded again and the prompt happens on success.
        h.catalogGateway.outcome = PrivacyCatalogOutcome.Loaded(
            "v1",
            listOf(
                net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem(
                    "PERSONALIZED_CONTENT",
                    "个性化内容",
                    false,
                ),
            ),
        )
        h.coordinator.retryPrivacyCatalog()
        h.runCurrent()
        assertEquals(2, h.catalogGateway.loadCount)
        assertEquals(1, h.privacyGateway.calls)
        assertEquals(GuideState.ENTERED_ACCOUNT, h.coordinator.state.value.guide)
        assertTrue(h.coordinator.state.value.privacyFirstInteractionCompleted)
    }

    @Test
    fun `A-019 a disabled catalogue feature behaves like an unavailable catalogue`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.config = h.config.copy(privacyCatalogEnabled = false)
        val disabled = h.restart()
        disabled.start()
        h.runCurrent()
        h.acceptBothAgreements()
        h.advanceToBind()

        assertEquals(GuideState.ENTERED_ACCOUNT, disabled.state.value.guide)
        assertEquals(
            PrivacyCatalogUnavailableReason.FEATURE_DISABLED,
            disabled.state.value.privacyCatalogUnavailableReason,
        )
        assertEquals(0, h.catalogGateway.loadCount)
        assertEquals(0, h.privacyGateway.calls)
        assertTrue(h.recordStore.records.values.none { it.type == RecordType.PRIVACY_DECISION })
        disabled.close()
    }

    // ------------------------------------------------------------------ 401118 / 401119

    @Test
    fun `A-022 a 401119 privacy report stays pending and reloads the catalogue`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.cloud.recordOutcome = RecordReportOutcome.Failed(ErrorCategory.RETRYABLE, "401119")
        h.startAndAcceptBoth()
        h.advanceToBind()

        val record = h.recordStore.records.values.single { it.type == RecordType.PRIVACY_DECISION }
        assertEquals(SyncState.PENDING, record.syncState)
        assertTrue(record.attempts > 0)
        // The catalogue is reloaded before the report is re-sent (BLK-8 recovery branch).
        assertTrue(h.catalogGateway.loadCount >= 2)
        assertEquals(GuideState.ENTERED_ACCOUNT, h.coordinator.state.value.guide)
    }

    @Test
    fun `A-022 a 401118 report is rebuilt with the newest catalogue version and a new report id`() =
        runTest {
            val h = harness(this.backgroundScope, testScheduler)
            // The first report goes out against v1; the reload triggered by 401118 returns v2.
            h.catalogGateway.reloadOutcome = PrivacyCatalogOutcome.Loaded(
                "v2",
                listOf(
                    net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem(
                        "PERSONALIZED_CONTENT",
                        "个性化内容",
                        false,
                    ),
                ),
            )
            h.cloud.recordOutcome = RecordReportOutcome.Failed(ErrorCategory.RETRYABLE, "401118")
            h.startAndAcceptBoth()
            h.advanceToBind()

            val privacyRecords = h.recordStore.records.values.filter {
                it.type == RecordType.PRIVACY_DECISION
            }
            val rebuilt = privacyRecords.firstOrNull { it.syncState == SyncState.PENDING }
            assertNotNull(rebuilt)
            val payload = rebuilt.payload as RecordPayload.PrivacyDecisionRecord
            // The first report went out against v1; the rebuild uses the reloaded catalogue version.
            assertEquals("v2", payload.catalogVersion)
            assertTrue(privacyRecords.any { it.syncState == SyncState.FAILED_TERMINAL })
            assertEquals(GuideState.ENTERED_ACCOUNT, h.coordinator.state.value.guide)
        }

    // ------------------------------------------------------------------ BLK-7 idempotency

    @Test
    fun `BLK-7 a transport retry reuses the key and a refresh allocates a new one`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        assertEquals(1, h.cloud.sessionRequests.size)
        val first = h.cloud.sessionRequests[0]

        // Same intent retried after a transport problem: same generation, same key.
        h.coordinator.retrySessionRequest()
        h.runCurrent()
        assertEquals(2, h.cloud.sessionRequests.size)
        assertEquals(first.requestId, h.cloud.sessionRequests[1].requestId)
        assertEquals(first.flowGeneration, h.cloud.sessionRequests[1].flowGeneration)

        // User/expiry refresh: new generation, new key.
        h.coordinator.refreshSession()
        h.runCurrent()
        assertEquals(3, h.cloud.sessionRequests.size)
        assertNotEquals(first.requestId, h.cloud.sessionRequests[2].requestId)
        assertEquals(first.flowGeneration + 1, h.cloud.sessionRequests[2].flowGeneration)
    }

    @Test
    fun `BLK-7 the last response owns the rendered payload`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.cloud.sessionOutcome = Sessions.first("qr-token-1")
        h.startAndAcceptBoth()
        assertEquals("qr-token-1", h.coordinator.state.value.sessionRef?.qrPayload)

        // A transport replay re-signs the code for the same session: the car must render the new one.
        h.cloud.sessionOutcome = Sessions.first("qr-token-2")
        h.coordinator.retrySessionRequest()
        h.runCurrent()
        assertEquals("qr-token-2", h.coordinator.state.value.sessionRef?.qrPayload)
        assertEquals("session-1", h.coordinator.state.value.sessionRef?.sessionId)
    }

    // ------------------------------------------------------------------ D-014 polling

    @Test
    fun `A-021 polling continues until the session expires and then stops`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        val expiresAt = h.clock.nowEpochMillis() + 5_000L
        h.cloud.sessionOutcome =
            net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestOutcome.Created(
                SessionRef("session-a", "qr-a", h.clock.nowEpochMillis(), expiresAt),
            )
        h.startAndAcceptBoth()

        h.advance(1_000L)
        assertTrue(h.cloud.queryCount >= 3, "polling must keep running inside the session lifetime")

        // Push the wall clock past the session deadline: the loop stops and reports "unknown".
        h.clock.wallEpochMillis = expiresAt + 1
        h.advance(200L)
        val queriesAtDeadline = h.cloud.queryCount
        h.advance(5_000L)
        assertEquals(queriesAtDeadline, h.cloud.queryCount, "no request may cross the deadline")
        assertEquals(GuideState.QR_VISIBLE, h.coordinator.state.value.guide)
        assertEquals(ErrorCategory.RESULT_UNKNOWN, h.coordinator.state.value.error?.category)
    }

    @Test
    fun `A-021 query now is dropped while another query is in flight`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        val gate = CompletableDeferred<Unit>()
        h.cloud.queryGate = gate
        h.advance(150L)
        val inFlight = h.cloud.queryCount
        assertTrue(inFlight >= 1)

        h.coordinator.querySessionNow()
        h.runCurrent()
        assertEquals(inFlight, h.cloud.queryCount, "a second query must be dropped")
        gate.complete(Unit)
        h.runCurrent()

        h.cloud.queryGate = null
        h.coordinator.querySessionNow()
        h.runCurrent()
        assertTrue(h.cloud.queryCount > inFlight)
    }

    // ------------------------------------------------------------------ D-005 receipts

    @Test
    fun `A-020 a failed login without an account still produces a reachable FAILED receipt`() =
        runTest {
            val h = harness(this.backgroundScope, testScheduler)
            h.cloud.defaultQueryOutcome = SessionQueryOutcome.Observed(
                SessionObservation.BoundConfirmed(null),
            )
            h.loginGateway.outcome = LoginOutcome.Failed(ErrorCategory.RETRYABLE)
            h.startAndAcceptBoth()
            h.advance(150L)

            val state = h.coordinator.state.value
            assertEquals(GuideState.LOGIN_PENDING, state.guide)
            assertEquals(LoginState.RETRY_REQUIRED, state.login)
            assertNull(state.account)

            val receipt = h.recordStore.records.values.single { it.type == RecordType.LOGIN_RESULT }
            val payload = receipt.payload as RecordPayload.LoginResult
            assertEquals("FAILED", payload.loginStatus)
            assertEquals(IviLoginFailureReason.NETWORK_ERROR, payload.failureReason)
            // BLK-11: a missing bindingOperationId does not suppress the receipt.
            assertNull(payload.bindingOperationId)
            assertNull(payload.accountId)
            // The receipt is scoped to the session, not to an account that does not exist yet.
            assertTrue(receipt.scopeKey.startsWith("VS:"))

            // It really leaves the car once a flush happens.
            h.loginGateway.outcome = LoginOutcome.Success(AccountRef("account-001"))
            h.coordinator.retryLogin()
            h.runCurrent()
            assertTrue(h.cloud.reportedRecordIds.contains(receipt.id))
        }

    @Test
    fun `A-020 a successful login without a binding operation is blocked locally`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.cloud.defaultQueryOutcome = SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed(null))
        h.startAndAcceptBoth()
        h.advance(150L)

        val state = h.coordinator.state.value
        assertEquals(LoginState.RETRY_REQUIRED, state.login)
        assertEquals(ActivationOperation.REPORT_RECORD, state.error?.operation)
        // No SUCCEEDED receipt was created: the cloud would reject it without the operation id.
        assertTrue(
            h.recordStore.records.values.none {
                it.type == RecordType.LOGIN_RESULT &&
                    (it.payload as? RecordPayload.LoginResult)?.loginStatus == "SUCCEEDED"
            },
        )
    }

    // ------------------------------------------------------------------ D-016 revocation

    @Test
    fun `A-023 a credential revocation signs out, keeps the binding and drops account records`() =
        runTest {
            val h = harness(this.backgroundScope, testScheduler)
            h.startAndAcceptBoth()
            h.advanceToBind()
            h.runCurrent()
            assertEquals(LoginState.SIGNED_IN, h.coordinator.state.value.login)
            assertTrue(h.recordStore.records.keys.any { it.startsWith("login-") })

            h.tokenEvents.emit(
                TokenLifecycleEvent.CredentialsInvalidated(TokenInvalidationReason.REVOKED),
            )
            h.runCurrent()

            val state = h.coordinator.state.value
            assertEquals(LoginState.SIGNED_OUT, state.login)
            assertNull(state.account)
            assertEquals(GuideState.LOGIN_PENDING, state.guide)
            // Cloud binding facts survive a local credential loss.
            assertTrue(state.sessionBound)
            assertNotNull(state.sessionRef)
            assertTrue(
                h.recordStore.records.values.none {
                    it.scopeKey.startsWith("AV:") && it.syncState == SyncState.PENDING
                },
            )
        }

    @Test
    fun `A-023 a renewal failure only asks for a retry and keeps the credentials`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        h.advanceToBind()
        h.runCurrent()

        h.tokenEvents.emit(TokenLifecycleEvent.RenewalFailed(ErrorCategory.RETRYABLE))
        h.runCurrent()

        val state = h.coordinator.state.value
        assertEquals(LoginState.RETRY_REQUIRED, state.login)
        assertEquals(ActivationOperation.REPORT_RECORD, state.error?.operation)
        assertNotNull(state.account)
        assertTrue(state.sessionBound)
    }

    // ------------------------------------------------------------------ D-017 seat gate

    @Test
    fun `A-009 a rear seat cannot accept an agreement or create a session`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.seatRole = SeatRole.REAR
        h.coordinator.start()
        h.runCurrent()
        h.coordinator.acceptAgreement(AgreementType.PRIVACY_POLICY)
        h.runCurrent()

        val state = h.coordinator.state.value
        assertEquals(SeatBlockReason.FRONT_SEAT_REQUIRED, state.seatBlocked)
        assertNull(state.acceptance(AgreementType.PRIVACY_POLICY))
        assertTrue(h.cloud.sessionRequests.isEmpty())
        assertEquals(GuideState.AGREEMENT_REQUIRED, state.guide)
    }

    @Test
    fun `A-009 a rear seat never shows a scannable code and never creates a skip record`() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.startAndAcceptBoth()
        assertTrue(h.coordinator.state.value.qrVisible)

        h.seatRole = SeatRole.REAR
        h.coordinator.onSeatChanged()
        h.runCurrent()
        assertFalse(h.coordinator.state.value.qrVisible)
        assertEquals(SeatBlockReason.FRONT_SEAT_REQUIRED, h.coordinator.state.value.seatBlocked)

        h.coordinator.skipActivation()
        h.runCurrent()
        assertTrue(h.cloud.skipRequests.isEmpty())
        assertTrue(h.recordStore.records.values.none { it.type == RecordType.SKIP_ACTIVATION })
        assertEquals(GuideState.QR_VISIBLE, h.coordinator.state.value.guide)

        // Back on the front seat the flow is usable again.
        h.seatRole = SeatRole.FRONT
        h.coordinator.onSeatChanged()
        h.runCurrent()
        assertTrue(h.coordinator.state.value.qrVisible)
    }

    private object Sessions {
        fun first(qrToken: String) =
            net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestOutcome.Created(
                SessionRef(
                    sessionId = "session-1",
                    qrPayload = qrToken,
                    issuedAtEpochMillis = 0L,
                    expiresAtEpochMillis = Long.MAX_VALUE,
                ),
            )
    }
}
