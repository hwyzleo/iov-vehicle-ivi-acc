package net.hwyz.iov.vehicle.ivi.acc.platform

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyPromptResult
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyScope
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginMaterial
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptersTest {

    private val scope = PrivacyScope(VehicleRef("VIN1"), AccountRef("account-1"))

    private class RecordingPrompt(
        var outcome: PrivacyPromptOutcome = PrivacyPromptOutcome.Answered(PrivacyChoice.GRANTED),
    ) : PrivacyPrompt {
        var calls = 0
            private set

        override suspend fun present(
            scope: PrivacyScope,
            items: List<String>,
        ): PrivacyPromptOutcome {
            calls++
            return outcome
        }
    }

    @Test
    fun `a rear seat never shows its own dialog`() = runTest {
        val prompt = RecordingPrompt()
        val adapter = DesktopPrivacyConsentAdapter(StaticSeatRoleProvider(SeatRole.REAR), prompt)
        assertEquals(PrivacyPromptResult.FrontSeatRequired, adapter.requestFirstConsent(scope, emptyList()))
        assertEquals(0, prompt.calls)
    }

    @Test
    fun `a front seat prompt maps user answers`() = runTest {
        val prompt = RecordingPrompt(PrivacyPromptOutcome.Answered(PrivacyChoice.GRANTED))
        val adapter = DesktopPrivacyConsentAdapter(StaticSeatRoleProvider(SeatRole.FRONT), prompt)
        assertEquals(
            PrivacyPromptResult.Decided(PrivacyChoice.GRANTED),
            adapter.requestFirstConsent(scope, listOf("PERSONALIZED_CONTENT")),
        )
        assertEquals(1, prompt.calls)
    }

    @Test
    fun `closing the dialog is not a grant`() = runTest {
        val prompt = RecordingPrompt(PrivacyPromptOutcome.Dismissed)
        val adapter = DesktopPrivacyConsentAdapter(StaticSeatRoleProvider(SeatRole.FRONT), prompt)
        assertEquals(
            PrivacyPromptResult.Decided(PrivacyChoice.NOT_GRANTED),
            adapter.requestFirstConsent(scope, emptyList()),
        )
    }

    @Test
    fun `a failing prompt is reported as unavailable`() = runTest {
        val prompt = RecordingPrompt(PrivacyPromptOutcome.Failed(ErrorCategory.RETRYABLE))
        val adapter = DesktopPrivacyConsentAdapter(StaticSeatRoleProvider(SeatRole.FRONT), prompt)
        assertEquals(
            PrivacyPromptResult.Unavailable(ErrorCategory.RETRYABLE),
            adapter.requestFirstConsent(scope, emptyList()),
        )
    }

    @Test
    fun `expired login material is never accepted`() = runTest {
        var now = 1_000L
        val adapter = FakeAccountLoginAdapter(nowEpochMillis = { now })
        val expired = LoginMaterial("handle", expiresAtEpochMillis = 900L)
        assertEquals(LoginOutcome.Failed(ErrorCategory.EXPIRED), adapter.login(expired))
        assertTrue(adapter.receivedMaterials.size == 1)

        now = 2_000L
        val valid = LoginMaterial("handle", expiresAtEpochMillis = 5_000L)
        assertTrue(adapter.login(valid) is LoginOutcome.Success)
    }

    @Test
    fun `seat role provider is switchable for the demo`() {
        val provider = StaticSeatRoleProvider()
        assertEquals(SeatRole.FRONT, provider.currentSeat())
        provider.seatRole = SeatRole.REAR
        assertEquals(SeatRole.REAR, provider.currentSeat())
    }
}
