package net.hwyz.iov.vehicle.ivi.acc.demo

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCodeRender
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.SkipState

/**
 * Screen state holder: it forwards user intents to the coordinator and exposes a read only state,
 * it does not implement any activation rule itself.
 */
class ActivationViewModel(application: Application) : AndroidViewModel(application) {

    private val environment = DemoEnvironment(application)

    val state: StateFlow<ActivationState> = environment.coordinator.state

    /**
     * D-010「状态驱动界面」: the security state of the device identity source.
     *
     * A device that is not provisioned or is blocked cannot be fixed by retrying, so the screen must
     * hide the re-authentication affordance instead of looping the user through it.
     */
    private val _deviceStatus = MutableStateFlow(environment.deviceStatus)
    val deviceStatus: StateFlow<DeviceSecurityStatus?> = _deviceStatus.asStateFlow()
    val privacyPrompt: DemoPrivacyPrompt = environment.privacyPrompt

    fun start() {
        environment.coordinator.start()
        viewModelScope.launch { _deviceStatus.value = environment.refreshDeviceStatus() }
    }

    /** D-010: `UNIFIED` (car security service) or `LOCAL` (migration window), for diagnostics only. */
    fun identitySource(): String = environment.identity.source.name

    /** True when retrying or re-authenticating can change the device identity outcome. */
    fun isDeviceRetryable(): Boolean = environment.isDeviceRetryable()

    fun refreshDeviceStatus() {
        viewModelScope.launch { _deviceStatus.value = environment.refreshDeviceStatus() }
    }

    fun onForeground() = environment.coordinator.onForeground()

    fun onBackground() = environment.coordinator.onBackground()

    fun acceptPrivacyAgreement() = selectAgreement(AgreementType.PRIVACY_POLICY, true)

    fun acceptUserAgreement() = selectAgreement(AgreementType.USER_AGREEMENT, true)

    /** DSN-CR-006: checkbox toggle before submission; reversible and offline. */
    fun selectAgreement(type: AgreementType, selected: Boolean) =
        environment.coordinator.selectAgreement(type, selected)

    /** DSN-CR-006: the only action that submits consent and allocates the QR session. */
    fun confirmAgreements() = environment.coordinator.confirmAgreements()

    fun reloadAgreements() = environment.coordinator.reloadAgreements()

    fun refreshSession() = environment.coordinator.refreshSession()

    /** D-014: the manual "query now" entry point; dropped while another query is in flight. */
    fun querySessionNow() = environment.coordinator.querySessionNow()

    fun skipActivation() = environment.coordinator.skipActivation()

    fun retrySkip() = environment.coordinator.retrySkip()

    fun retryLogin() = environment.coordinator.retryLogin()

    fun retryPrivacyPrompt() = environment.coordinator.retryPrivacyPrompt()

    fun retryPrivacyCatalog() = environment.coordinator.retryPrivacyCatalog()

    fun acceptContinuation() = environment.coordinator.acceptAccountContinuation()

    fun grantPrivacy() = environment.grantPrivacy()

    fun closePrivacy() = environment.closePrivacy()

    fun setRearSeat(rear: Boolean) = environment.setRearSeat(rear)

    fun resetLocalState() = environment.coordinator.resetLocalStateForFactoryReset()

    /** D-012: renders the *current* payload; the presenter caches only the latest one. */
    fun presentActivationCode(payload: String): ActivationCodeRender<Bitmap> =
        environment.activationCodePresenter.present(payload)

    /** Items of the catalogue snapshot the dialog is currently showing. */
    fun privacyItems(): List<PrivacyAuthorizationItem> = environment.privacyPrompt.currentItems

    /** Re-proves the device identity and reloads the agreements after an authentication failure. */
    fun reauthenticateAndReload() {
        viewModelScope.launch {
            _deviceStatus.value = environment.refreshDeviceStatus()
            environment.reauthenticateAndReload()
        }
    }

    /**
     * Dispatches the retry action that matches the current recoverable error.
     *
     * The device status is re-queried first: an activation performed elsewhere (admin console,
     * provisioning tool) must become visible without restarting the app.
     */
    fun retryCurrentError(state: ActivationState) {
        refreshDeviceStatus()
        val error = state.error ?: return
        when (error.operation) {
            ActivationOperation.LOAD_AGREEMENTS ->
                if (error.category == ErrorCategory.AUTH_REJECTED) {
                    reauthenticateAndReload()
                } else {
                    reloadAgreements()
                }

            ActivationOperation.REQUEST_SESSION,
            ActivationOperation.QUERY_SESSION,
            -> when {
                state.skip == SkipState.RESULT_UNKNOWN -> retrySkip()
                // BLK-7: a transient failure is a transport retry of the same intent (same key).
                error.retryable -> environment.coordinator.retrySessionRequest()
                else -> refreshSession()
            }

            ActivationOperation.SUBMIT_SKIP -> retrySkip()
            ActivationOperation.ISSUE_LOGIN_TICKET, ActivationOperation.LOGIN -> retryLogin()
            ActivationOperation.PRIVACY_PROMPT,
            ActivationOperation.PRIVACY_PERSIST,
            -> retryPrivacyPrompt()

            // D-013: an unavailable or empty catalogue is retried through its own entry point.
            ActivationOperation.PRIVACY_CATALOG -> retryPrivacyCatalog()
            ActivationOperation.REPORT_RECORD -> refreshSession()
        }
    }

    override fun onCleared() {
        environment.shutdown()
    }
}
