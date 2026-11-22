package net.hwyz.iov.vehicle.ivi.acc.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationState

/**
 * Screen state holder: it forwards user intents to the coordinator and exposes a read only state,
 * it does not implement any activation rule itself.
 */
class ActivationViewModel(application: Application) : AndroidViewModel(application) {

    private val environment = DemoEnvironment(application)

    val state: StateFlow<ActivationState> = environment.coordinator.state
    val privacyPrompt: DemoPrivacyPrompt = environment.privacyPrompt

    fun start() = environment.coordinator.start()

    fun onForeground() = environment.coordinator.onForeground()

    fun onBackground() = environment.coordinator.onBackground()

    fun acceptPrivacyAgreement() = environment.coordinator.acceptAgreement(AgreementType.PRIVACY_POLICY)

    fun acceptUserAgreement() = environment.coordinator.acceptAgreement(AgreementType.USER_AGREEMENT)

    fun reloadAgreements() = environment.coordinator.reloadAgreements()

    fun refreshSession() = environment.coordinator.refreshSession()

    fun skipActivation() = environment.coordinator.skipActivation()

    fun retrySkip() = environment.coordinator.retrySkip()

    fun retryLogin() = environment.coordinator.retryLogin()

    fun retryPrivacyPrompt() = environment.coordinator.retryPrivacyPrompt()

    fun acceptContinuation() = environment.coordinator.acceptAccountContinuation()

    fun grantPrivacy() = environment.grantPrivacy()

    fun closePrivacy() = environment.closePrivacy()

    fun setRearSeat(rear: Boolean) = environment.setRearSeat(rear)

    fun applyScenario(scenario: DemoScenario) = environment.applyScenario(scenario)

    fun resetLocalState() = environment.coordinator.resetLocalStateForFactoryReset()

    /** Dispatches the retry action that matches the current recoverable error. */
    fun retryCurrentError(state: ActivationState) {
        val error = state.error ?: return
        when (error.operation) {
            ActivationOperation.LOAD_AGREEMENTS -> reloadAgreements()
            ActivationOperation.REQUEST_SESSION,
            ActivationOperation.QUERY_SESSION,
            -> if (state.skip == net.hwyz.iov.vehicle.ivi.acc.domain.state.SkipState.RESULT_UNKNOWN) {
                retrySkip()
            } else {
                refreshSession()
            }

            ActivationOperation.SUBMIT_SKIP -> retrySkip()
            ActivationOperation.REQUEST_LOGIN_MATERIAL, ActivationOperation.LOGIN -> retryLogin()
            ActivationOperation.PRIVACY_PROMPT,
            ActivationOperation.PRIVACY_PERSIST,
            -> retryPrivacyPrompt()

            ActivationOperation.REPORT_RECORD -> refreshSession()
        }
    }

    override fun onCleared() {
        environment.shutdown()
    }
}
