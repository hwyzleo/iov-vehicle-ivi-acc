package net.hwyz.iov.vehicle.ivi.acc.demo

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.acc.demo.databinding.ActivityActivationBinding
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.SkipState

/**
 * Demo screen for the activation flow.
 *
 * Rendering is driven only by [ActivationState]: while agreements are being fetched the screen
 * shows a loading hint, and a fetch failure shows an explicit error with a retry action instead of
 * any consent control. Every interaction is an intent forwarded to the coordinator.
 */
class ActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationBinding
    private val viewModel: ActivationViewModel by viewModels()
    private var privacyDialog: AlertDialog? = null
    private var scenarioInitialised = false
    private var documentDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cbPrivacy.setOnClickListener { viewModel.acceptPrivacyAgreement() }
        binding.cbUserAgreement.setOnClickListener { viewModel.acceptUserAgreement() }
        binding.btnReloadAgreements.setOnClickListener { viewModel.reloadAgreements() }
        binding.btnRetryAgreements.setOnClickListener { viewModel.reloadAgreements() }
        binding.btnViewPrivacy.setOnClickListener { showDocument(AgreementType.PRIVACY_POLICY) }
        binding.btnViewUserAgreement.setOnClickListener { showDocument(AgreementType.USER_AGREEMENT) }
        binding.btnRefreshSession.setOnClickListener { viewModel.refreshSession() }
        binding.qrBox.setOnLongClickListener {
            viewModel.skipActivation()
            true
        }
        binding.btnRetry.setOnClickListener { viewModel.retryCurrentError(viewModel.state.value) }
        binding.btnRetryLogin.setOnClickListener { viewModel.retryLogin() }
        binding.btnContinuation.setOnClickListener { viewModel.acceptContinuation() }
        binding.btnRetryPrivacy.setOnClickListener { viewModel.retryPrivacyPrompt() }
        binding.btnReset.setOnClickListener { viewModel.resetLocalState() }
        binding.cbRearSeat.setOnCheckedChangeListener { _, checked -> viewModel.setRearSeat(checked) }

        binding.spinnerScenario.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.demo_scenarios),
        )
        binding.spinnerScenario.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    // The initial selection only reflects the default demo state; it must not
                    // trigger a second agreement load right after start().
                    if (!scenarioInitialised) {
                        scenarioInitialised = true
                        return
                    }
                    viewModel.applyScenario(DemoScenario.entries[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        viewModel.privacyPrompt.onShow = { runOnUiThread { showPrivacyPrompt() } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
        viewModel.start()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        privacyDialog?.dismiss()
        privacyDialog = null
        documentDialog?.dismiss()
        documentDialog = null
        super.onStop()
    }

    private fun render(state: ActivationState) {
        binding.tvStatus.text = statusText(state)
        val entered = state.entertainmentEntryAllowed
        val loading = state.documentsLoading
        val hasDocuments = state.documentsComplete

        binding.groupAgreements.visibility = if (entered) View.GONE else View.VISIBLE
        binding.tvAgreementLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.groupAgreementRows.visibility =
            if (!loading && hasDocuments) View.VISIBLE else View.GONE
        binding.groupAgreementFailure.visibility =
            if (!loading && !hasDocuments) View.VISIBLE else View.GONE

        binding.tvAgreementError.text = buildString {
            append(getString(R.string.agreement_unavailable_hint))
            state.documentsFailure?.let { append("\ncategory=").append(it.name) }
        }

        binding.cbPrivacy.isEnabled = hasDocuments
        binding.cbUserAgreement.isEnabled = hasDocuments
        binding.btnViewPrivacy.isEnabled = hasDocuments
        binding.btnViewUserAgreement.isEnabled = hasDocuments
        binding.cbPrivacy.isChecked = state.acceptance(AgreementType.PRIVACY_POLICY) != null
        binding.cbUserAgreement.isChecked = state.acceptance(AgreementType.USER_AGREEMENT) != null
        binding.tvPrivacyAgreement.text = documentLabel(state, AgreementType.PRIVACY_POLICY, "隐私政策")
        binding.tvUserAgreement.text = documentLabel(state, AgreementType.USER_AGREEMENT, "用户协议")

        binding.groupQr.visibility = if (!entered && state.qrVisible) View.VISIBLE else View.GONE
        val agreementErrorShown = !loading && !hasDocuments
        val genericError = state.error?.takeIf {
            !(agreementErrorShown && it.operation == ActivationOperation.LOAD_AGREEMENTS)
        }
        binding.groupError.visibility =
            if (!entered && genericError != null) View.VISIBLE else View.GONE
        binding.groupLogin.visibility =
            if (state.guide == GuideState.LOGIN_PENDING) View.VISIBLE else View.GONE
        binding.groupPrivacy.visibility =
            if (state.guide == GuideState.PRIVACY_PENDING) View.VISIBLE else View.GONE
        binding.tvEntered.visibility = if (entered) View.VISIBLE else View.GONE

        binding.tvError.text = genericError?.let { "${it.operation.name} / ${it.category.name}" } ?: ""

        binding.tvContinuation.visibility =
            if (state.accountContinuationSuggested) View.VISIBLE else View.GONE
        binding.btnContinuation.visibility =
            if (state.accountContinuationSuggested) View.VISIBLE else View.GONE

        binding.tvEntered.text = when (state.guide) {
            GuideState.ENTERED_LOCAL -> getString(R.string.entered_local)
            GuideState.ENTERED_ACCOUNT -> getString(R.string.entered_account)
            else -> ""
        }
    }

    private fun statusText(state: ActivationState): String = buildString {
        append("guide=").append(state.guide.name)
        append("  gen=").append(state.flowGeneration)
        append("\nagreements=").append(
            when {
                state.documentsLoading -> "LOADING"
                state.documentsFailure != null -> "FAILED(${state.documentsFailure})"
                state.documentsComplete -> "READY v${state.document(AgreementType.PRIVACY_POLICY)?.version}"
                else -> "EMPTY"
            },
        )
        append("  accepted=").append(state.acceptances.size).append("/2")
        append("\nsession=").append(state.session.javaClass.simpleName)
        append("  login=").append(state.login.name)
        append("\nagreementSync=").append(state.agreementSync.name)
        append("  privacySync=").append(state.privacySync.name)
        append("  skip=").append(state.skip.name)
        if (state.skip == SkipState.RESULT_UNKNOWN) append(" (retry with same request id)")
        if (state.login == LoginState.RETRY_REQUIRED) append("  login retry required")
        state.sessionRef?.let { append("\nsessionId=").append(it.sessionId) }
        state.error?.let {
            append("\nerror=").append(it.operation.name).append('/').append(it.category.name)
        }
        if (state.privacyChoice != PrivacyChoice.NOT_DECIDED) {
            append("  privacy=").append(state.privacyChoice.name)
        }
        if (state.privacyFrontSeatRequired) append("  (front seat required)")
    }

    private fun documentLabel(
        state: ActivationState,
        type: AgreementType,
        title: String,
    ): String {
        val document = state.document(type)
        return if (document == null) {
            "$title (未获取)"
        } else {
            "$title · ${document.version}${document.revisionId?.let { " ($it)" } ?: ""}"
        }
    }

    private fun showDocument(type: AgreementType) {
        val document = viewModel.state.value.document(type) ?: return
        val title = when (type) {
            AgreementType.PRIVACY_POLICY -> getString(R.string.agreement_privacy)
            AgreementType.USER_AGREEMENT -> getString(R.string.agreement_user)
        }
        documentDialog?.dismiss()
        documentDialog = AlertDialog.Builder(this)
            .setTitle("$title · ${document.version}")
            .setMessage(document.body?.takeIf { it.isNotBlank() } ?: getString(R.string.document_body_missing))
            .setPositiveButton(R.string.document_dialog_ok, null)
            .show()
    }

    private fun showPrivacyPrompt() {
        if (privacyDialog?.isShowing == true) return
        privacyDialog = AlertDialog.Builder(this)
            .setTitle(R.string.privacy_title)
            .setMessage(R.string.privacy_message)
            .setPositiveButton(R.string.privacy_grant) { _, _ -> viewModel.grantPrivacy() }
            .setNegativeButton(R.string.privacy_close) { _, _ -> viewModel.closePrivacy() }
            .setOnCancelListener { viewModel.closePrivacy() }
            .show()
    }
}
