package net.hwyz.iov.vehicle.ivi.acc.domain

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatBlockReason
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatRole
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.support.harness
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * DSN-CR-006 / A-001: the agreement checkboxes are a reversible pre-selection, and only the
 * explicit confirmation submits consent and allocates the session.
 */
class ActivationCr006Test {

    @Test
    @DisplayName("A-001 只勾选一份：不产生同意事实、不显示二维码、零会话请求")
    fun onlyOneSelectionStaysOffline() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.coordinator.start()
        h.runCurrent()

        h.coordinator.selectAgreement(AgreementType.PRIVACY_POLICY, true)
        h.runCurrent()

        val state = h.coordinator.state.value
        assertTrue(AgreementType.PRIVACY_POLICY in state.agreementSelection)
        assertFalse(state.agreementSelectionComplete)
        assertNull(state.acceptance(AgreementType.PRIVACY_POLICY))
        assertFalse(state.qrVisible)
        assertEquals(GuideState.AGREEMENT_REQUIRED, state.guide)
        assertTrue(h.cloud.sessionRequests.isEmpty())
    }

    @Test
    @DisplayName("A-001 勾选可取消：取消后回到提示态且仍零会话请求")
    fun selectionIsReversibleBeforeConfirmation() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.coordinator.start()
        h.runCurrent()

        h.coordinator.selectAgreement(AgreementType.PRIVACY_POLICY, true)
        h.coordinator.selectAgreement(AgreementType.USER_AGREEMENT, true)
        h.runCurrent()
        assertTrue(h.coordinator.state.value.agreementSelectionComplete)

        h.coordinator.selectAgreement(AgreementType.USER_AGREEMENT, false)
        h.runCurrent()

        val state = h.coordinator.state.value
        assertFalse(state.agreementSelectionComplete)
        assertFalse(state.qrVisible)
        assertTrue(state.acceptances.isEmpty())
        assertTrue(h.cloud.sessionRequests.isEmpty())
    }

    @Test
    @DisplayName("A-001 缺项确认：不下发任何云端请求，也不写同意记录")
    fun confirmationWithMissingSelectionIsRefusedLocally() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.coordinator.start()
        h.runCurrent()

        h.coordinator.selectAgreement(AgreementType.PRIVACY_POLICY, true)
        h.runCurrent()
        h.coordinator.confirmAgreements()
        h.runCurrent()

        val state = h.coordinator.state.value
        assertTrue(state.acceptances.isEmpty())
        assertFalse(state.qrVisible)
        assertEquals(GuideState.AGREEMENT_REQUIRED, state.guide)
        assertTrue(h.cloud.sessionRequests.isEmpty())
        assertTrue(h.agreementStore.saveAllCalls == 0)
    }

    @Test
    @DisplayName("A-001 确认后才提交两份同意、恰好一次会话请求并显示二维码")
    fun confirmationSubmitsOnceAndStartsSession() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.coordinator.start()
        h.runCurrent()

        h.coordinator.selectAgreement(AgreementType.PRIVACY_POLICY, true)
        h.coordinator.selectAgreement(AgreementType.USER_AGREEMENT, true)
        h.runCurrent()
        assertTrue(h.cloud.sessionRequests.isEmpty())

        h.coordinator.confirmAgreements()
        h.runCurrent()

        val state = h.coordinator.state.value
        assertEquals(GuideState.QR_VISIBLE, state.guide)
        assertTrue(state.qrVisible)
        assertNotNull(state.acceptance(AgreementType.PRIVACY_POLICY))
        assertNotNull(state.acceptance(AgreementType.USER_AGREEMENT))
        // The consent keeps the version that was displayed when the user confirmed.
        assertEquals("1.0", state.acceptance(AgreementType.PRIVACY_POLICY)?.version)
        assertEquals(1, h.cloud.sessionRequests.size)
        // Exactly one persisted snapshot set (both agreements at once).
        assertEquals(1, h.agreementStore.saveAllCalls)
    }

    @Test
    @DisplayName("A-001 提交后勾选锁定：再次切换不改变已提交的同意")
    fun selectionIsLockedAfterConfirmation() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.coordinator.start()
        h.runCurrent()
        h.coordinator.selectAgreement(AgreementType.PRIVACY_POLICY, true)
        h.coordinator.selectAgreement(AgreementType.USER_AGREEMENT, true)
        h.runCurrent()
        h.coordinator.confirmAgreements()
        h.runCurrent()

        h.coordinator.selectAgreement(AgreementType.PRIVACY_POLICY, false)
        h.runCurrent()

        val state = h.coordinator.state.value
        assertTrue(state.agreementSelectionLocked)
        assertNotNull(state.acceptance(AgreementType.PRIVACY_POLICY))
        assertTrue(state.isAgreementSelected(AgreementType.PRIVACY_POLICY))
        assertEquals(1, h.cloud.sessionRequests.size)
    }

    @Test
    @DisplayName("A-009 后排既不能预选也不能确认，且零云端写动作")
    fun rearSeatCannotSelectOrConfirm() = runTest {
        val h = harness(this.backgroundScope, testScheduler)
        h.seatRole = SeatRole.REAR
        h.coordinator.start()
        h.runCurrent()

        h.coordinator.selectAgreement(AgreementType.PRIVACY_POLICY, true)
        h.coordinator.selectAgreement(AgreementType.USER_AGREEMENT, true)
        h.runCurrent()
        h.coordinator.confirmAgreements()
        h.runCurrent()

        val state = h.coordinator.state.value
        assertEquals(SeatBlockReason.FRONT_SEAT_REQUIRED, state.seatBlocked)
        assertTrue(state.agreementSelection.isEmpty())
        assertTrue(state.acceptances.isEmpty())
        assertTrue(h.cloud.sessionRequests.isEmpty())
        assertEquals(0, h.agreementStore.saveAllCalls)
    }
}
