package net.hwyz.iov.vehicle.ivi.acc.platform

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.AccountLoginGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyConsentGateway
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyItemState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyPromptResult
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyScope
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginTicket
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice

/**
 * Seat types now live in the domain (D-017 / D-001): the domain must be able to refuse a rear seat
 * action before it reaches the cloud. The platform keeps implementations and a one cycle alias so
 * existing call sites and tests keep compiling.
 */
typealias SeatRole = net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatRole
typealias SeatRoleProvider = net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SeatRoleProvider

/** First privacy authorization prompt rendered by the vehicle desktop. */
interface PrivacyPrompt {
    suspend fun present(
        scope: PrivacyScope,
        items: List<PrivacyAuthorizationItem>,
    ): PrivacyPromptOutcome
}

sealed interface PrivacyPromptOutcome {
    /**
     * [itemStates] carries the per item answer (`authorizationItem` -> state); an empty map means the
     * aggregate [choice] applies to every catalogue item.
     */
    data class Answered(
        val choice: PrivacyChoice,
        val itemStates: Map<String, PrivacyItemState> = emptyMap(),
    ) : PrivacyPromptOutcome

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
        items: List<PrivacyAuthorizationItem>,
    ): PrivacyPromptResult {
        if (seatRoleProvider.currentSeat() == SeatRole.REAR) {
            return PrivacyPromptResult.FrontSeatRequired
        }
        return when (val outcome = prompt.present(scope, items)) {
            is PrivacyPromptOutcome.Answered ->
                PrivacyPromptResult.Decided(outcome.choice, outcome.itemStates)
            PrivacyPromptOutcome.Dismissed -> PrivacyPromptResult.Decided(PrivacyChoice.NOT_GRANTED)
            is PrivacyPromptOutcome.Failed -> PrivacyPromptResult.Unavailable(outcome.category)
        }
    }
}

/**
 * Scriptable login capability for tests and scripted demos.
 *
 * The adapter never generates user tokens itself: it only exchanges the one-time ticket in the
 * simulated IAM (the production adapter lives in the cloud data layer).
 */
class FakeAccountLoginAdapter(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : AccountLoginGateway {

    var outcomeFactory: (LoginTicket) -> LoginOutcome = {
        LoginOutcome.Success(AccountRef("account-demo"))
    }
    val receivedTickets = mutableListOf<LoginTicket>()
    var signOutCount = 0
        private set

    override suspend fun login(ticket: LoginTicket): LoginOutcome {
        receivedTickets += ticket
        if (ticket.isExpired(nowEpochMillis())) {
            // Expired tickets are never reused.
            return LoginOutcome.Failed(ErrorCategory.EXPIRED)
        }
        return outcomeFactory(ticket)
    }

    override suspend fun signOutLocally() {
        signOutCount++
    }
}

/** Fixed seat role, used by the demo front/rear switch and by unit tests. */
class StaticSeatRoleProvider(var seatRole: SeatRole = SeatRole.FRONT) : SeatRoleProvider {
    override fun currentSeat(): SeatRole = seatRole
}
