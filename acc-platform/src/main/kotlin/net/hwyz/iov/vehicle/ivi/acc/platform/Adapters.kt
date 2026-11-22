package net.hwyz.iov.vehicle.ivi.acc.platform

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AccountLoginGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyConsentGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyPromptResult
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyScope
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginMaterial
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice

/** Which screen hosts the current interaction. */
enum class SeatRole {
    FRONT,
    REAR,
}

fun interface SeatRoleProvider {
    fun currentSeat(): SeatRole
}

/** First privacy authorization prompt rendered by the vehicle desktop. */
interface PrivacyPrompt {
    suspend fun present(scope: PrivacyScope, items: List<String>): PrivacyPromptOutcome
}

sealed interface PrivacyPromptOutcome {
    data class Answered(val choice: PrivacyChoice) : PrivacyPromptOutcome

    /** The user closed the dialog: mapped to NOT_GRANTED, never to a default grant. */
    data object Dismissed : PrivacyPromptOutcome

    data class Failed(val category: ErrorCategory) : PrivacyPromptOutcome
}

/**
 * Desktop privacy adapter.
 *
 * A rear seat screen never drives its own activation: it only returns an instruction to operate
 * on the front screen (acceptance A-009).
 */
class DesktopPrivacyConsentAdapter(
    private val seatRoleProvider: SeatRoleProvider,
    private val prompt: PrivacyPrompt,
) : PrivacyConsentGateway {

    override suspend fun requestFirstConsent(
        scope: PrivacyScope,
        items: List<String>,
    ): PrivacyPromptResult {
        if (seatRoleProvider.currentSeat() == SeatRole.REAR) {
            return PrivacyPromptResult.FrontSeatRequired
        }
        return when (val outcome = prompt.present(scope, items)) {
            is PrivacyPromptOutcome.Answered -> PrivacyPromptResult.Decided(outcome.choice)
            PrivacyPromptOutcome.Dismissed -> PrivacyPromptResult.Decided(PrivacyChoice.NOT_GRANTED)
            is PrivacyPromptOutcome.Failed -> PrivacyPromptResult.Unavailable(outcome.category)
        }
    }
}

/**
 * Scriptable local login capability.
 *
 * The adapter never generates user tokens itself; it consumes the short lived material that the
 * cloud adapter stored in the platform secure store.
 */
class FakeAccountLoginAdapter(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : AccountLoginGateway {

    var outcomeFactory: (LoginMaterial) -> LoginOutcome = {
        LoginOutcome.Success(AccountRef("account-demo"))
    }
    val receivedMaterials = mutableListOf<LoginMaterial>()
    var signOutCount = 0
        private set

    override suspend fun login(material: LoginMaterial): LoginOutcome {
        receivedMaterials += material
        if (material.isExpired(nowEpochMillis())) {
            // Expired material is never reused.
            return LoginOutcome.Failed(ErrorCategory.EXPIRED)
        }
        return outcomeFactory(material)
    }

    override suspend fun signOutLocally() {
        signOutCount++
    }
}

/** Fixed seat role, used by the demo front/rear switch and by unit tests. */
class StaticSeatRoleProvider(var seatRole: SeatRole = SeatRole.FRONT) : SeatRoleProvider {
    override fun currentSeat(): SeatRole = seatRole
}
