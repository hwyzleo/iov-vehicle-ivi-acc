package net.hwyz.iov.vehicle.ivi.acc.demo

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.hwyz.iov.vehicle.ivi.acc.demo.databinding.ActivityActivationBinding
import net.hwyz.iov.vehicle.ivi.acc.demo.databinding.DialogDocumentBinding
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCodeRender
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyCatalogState
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationError
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationOperation
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ActivationState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.SkipState

/**
 * Customer facing activation screen (landscape head unit).
 *
 * Layout: agreements on the left, a large QR stage on the right. The QR code is only rendered once
 * the state reports [ActivationState.qrVisible] (front seat, both agreements accepted for the
 * displayed versions, session allocated); before that the stage explains *why* it is empty.
 *
 * Rendering is driven only by [ActivationState]. Diagnostics that only a developer or a field
 * engineer needs (flow state, device identity source, error operation/category) live in the
 * collapsible debug panel instead of the main visual.
 */
class ActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationBinding
    private val viewModel: ActivationViewModel by viewModels()
    private var privacyDialog: AlertDialog? = null
    private var documentDialog: AlertDialog? = null

    private val countdownHandler = Handler(Looper.getMainLooper())
    private var renderedPayload: String? = null
    private var sessionExpiresAtEpochMillis: Long? = null

    /**
     * Restored consent (both agreements already accepted in an earlier run) has no session yet: the
     * domain only allocates one on an accept/refresh intent, so the screen resumes it exactly once
     * instead of leaving the user on a checked-but-dead page.
     */
    private var restoredConsentResumed = false

    private val countdownTick = object : Runnable {
        override fun run() {
            updateCountdown()
            countdownHandler.postDelayed(this, COUNTDOWN_TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyUiMode()
        super.onCreate(savedInstanceState)
        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cbPrivacy.setOnClickListener {
            val selected = viewModel.state.value.isAgreementSelected(AgreementType.PRIVACY_POLICY)
            viewModel.selectAgreement(AgreementType.PRIVACY_POLICY, !selected)
        }
        binding.cbUserAgreement.setOnClickListener {
            val selected = viewModel.state.value.isAgreementSelected(AgreementType.USER_AGREEMENT)
            viewModel.selectAgreement(AgreementType.USER_AGREEMENT, !selected)
        }
        binding.btnConfirmAgreements.setOnClickListener {
            // The user submitted explicitly: the coordinator allocates the session itself, so the
            // restored-consent resume must not fire on the intermediate render (double session).
            restoredConsentResumed = true
            viewModel.confirmAgreements()
        }
        binding.btnReloadAgreements.setOnClickListener { viewModel.retryCurrentError(viewModel.state.value) }
        binding.btnRetryAgreements.setOnClickListener { viewModel.retryCurrentError(viewModel.state.value) }
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
        binding.btnRetryCatalog.setOnClickListener { viewModel.retryPrivacyCatalog() }
        binding.btnQueryNow.setOnClickListener { viewModel.querySessionNow() }
        binding.btnReset.setOnClickListener {
            viewModel.resetLocalState()
            // A local reset must leave the screen usable: the agreements are re-fetched (never from
            // a cache) so a fresh activation can start without restarting the app.
            viewModel.reloadAgreements()
        }
        binding.cbRearSeat.setOnCheckedChangeListener { _, checked -> viewModel.setRearSeat(checked) }
        binding.cbDaylightMode.isChecked = isDaylightPreferred()
        binding.cbDaylightMode.setOnCheckedChangeListener { _, checked ->
            if (checked == isDaylightPreferred()) return@setOnCheckedChangeListener
            prefs().edit().putBoolean(PREF_DAYLIGHT, checked).apply()
            // AppCompat recreates the activity; the token palettes switch with the UI mode.
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES,
            )
        }

        binding.btnDebugToggle.setOnClickListener { toggleDebugPanel() }
        binding.btnDebugClose.setOnClickListener { binding.groupDebug.visibility = View.GONE }
        binding.btnDebugSkip.setOnClickListener { viewModel.skipActivation() }

        viewModel.privacyPrompt.onShow = { items -> runOnUiThread { showPrivacyPrompt(items) } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deviceStatus.collect { status -> renderDeviceStatus(status) }
            }
        }
        viewModel.start()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
        // D-010: an activation done elsewhere must show up when the screen comes back.
        viewModel.refreshDeviceStatus()
        countdownHandler.post(countdownTick)
    }

    override fun onStop() {
        countdownHandler.removeCallbacks(countdownTick)
        viewModel.onBackground()
        privacyDialog?.dismiss()
        privacyDialog = null
        documentDialog?.dismiss()
        documentDialog = null
        super.onStop()
    }

    /**
     * 车机配色按规范「夜间为默认基线，日间抗眩为强光态」。
     *
     * 当前车机尚未把光感/日夜信号接入车端（没有系统级 day/night 输入），因此默认夜间模式；
     * 调试面板的「日间抗眩模式」可切换并持久化，便于两端真机核验。
     * 接入车机光感信号后改为 MODE_NIGHT_FOLLOW_SYSTEM 即可，业务路径不变。
     */
    private fun applyUiMode() {
        val mode = if (isDaylightPreferred()) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun isDaylightPreferred(): Boolean = prefs().getBoolean(PREF_DAYLIGHT, false)

    private fun prefs() = getSharedPreferences(PREF_UI, MODE_PRIVATE)

    private fun toggleDebugPanel() {
        binding.groupDebug.visibility =
            if (binding.groupDebug.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun render(state: ActivationState) {
        binding.tvStatus.text = statusText(state)
        maybeResumeRestoredConsent(state)

        val entered = state.entertainmentEntryAllowed
        binding.groupContent.visibility = if (entered) View.GONE else View.VISIBLE
        binding.groupEntered.visibility = if (entered) View.VISIBLE else View.GONE
        binding.tvEntered.text = when (state.guide) {
            GuideState.ENTERED_LOCAL -> getString(R.string.entered_local)
            GuideState.ENTERED_ACCOUNT -> getString(R.string.entered_account)
            else -> ""
        }

        val loading = state.documentsLoading
        val hasDocuments = state.documentsComplete

        binding.tvAgreementLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.groupAgreementRows.visibility =
            if (!loading && hasDocuments) View.VISIBLE else View.GONE
        binding.groupAgreementFailure.visibility =
            if (!loading && !hasDocuments) View.VISIBLE else View.GONE

        // User facing agreement failure text; the category stays in the debug panel only.
        binding.tvAgreementError.text = when (state.documentsFailure) {
            ErrorCategory.AUTH_REJECTED -> getString(R.string.agreement_unavailable_auth)
            ErrorCategory.RETRYABLE, ErrorCategory.RESULT_UNKNOWN ->
                getString(R.string.agreement_unavailable_retry)

            else -> getString(R.string.agreement_unavailable_hint)
        }

        binding.cbPrivacy.isEnabled = hasDocuments
        binding.cbUserAgreement.isEnabled = hasDocuments
        binding.btnViewPrivacy.isEnabled = hasDocuments
        binding.btnViewUserAgreement.isEnabled = hasDocuments
        binding.cbPrivacy.isChecked = state.isAgreementSelected(AgreementType.PRIVACY_POLICY)
        binding.cbUserAgreement.isChecked = state.isAgreementSelected(AgreementType.USER_AGREEMENT)
        // DSN-CR-006: the checkboxes are a reversible pre-selection, never the consent itself.
        // After「开始激活」the consent is frozen, so the boxes become read only (and D-017 blocks a
        // rear seat from selecting at all).
        val canSelect = hasDocuments && state.seatBlocked == null && !state.agreementSelectionLocked
        binding.cbPrivacy.isClickable = canSelect
        binding.cbUserAgreement.isClickable = canSelect
        binding.btnConfirmAgreements.visibility =
            if (state.agreementSelectionLocked) View.GONE else View.VISIBLE
        binding.btnConfirmAgreements.isEnabled = hasDocuments &&
            state.seatBlocked == null &&
            state.agreementSelectionComplete
        binding.tvAgreementNote.text = if (state.agreementSelectionLocked) {
            getString(R.string.agreements_submitted_hint)
        } else {
            getString(R.string.agreements_consent_note)
        }
        binding.tvPrivacyAgreement.text = documentLabel(state, AgreementType.PRIVACY_POLICY, "隐私政策")
        binding.tvUserAgreement.text = documentLabel(state, AgreementType.USER_AGREEMENT, "用户协议")

        // The QR stage stays on screen during the whole pre-entry flow so the user always sees the
        // reason it is empty, and the code itself only when the domain allows it.
        val stageVisible = !entered && when (state.guide) {
            GuideState.AGREEMENT_REQUIRED,
            GuideState.SESSION_PREPARING,
            GuideState.QR_VISIBLE,
            GuideState.RECOVERABLE_ERROR,
            -> true

            else -> false
        }
        binding.groupQr.visibility = if (stageVisible) View.VISIBLE else View.GONE
        if (stageVisible) renderQrStage(state)
        binding.tvSeatBlocked.visibility =
            if (stageVisible && state.seatBlocked != null) View.VISIBLE else View.GONE

        val agreementErrorShown = !loading && !hasDocuments
        val genericError = state.error?.takeIf {
            !(agreementErrorShown && it.operation == ActivationOperation.LOAD_AGREEMENTS)
        }
        // DSN-CR-007: a login failure is explained inside the login area; the generic bar must not
        // repeat the same problem twice on one screen.
        val loginReasonInline = state.guide == GuideState.LOGIN_PENDING &&
            LoginPendingHint.isInlineOperation(genericError)
        binding.groupError.visibility =
            if (!entered && genericError != null && !loginReasonInline) View.VISIBLE else View.GONE
        binding.tvError.text = genericError?.let { genericErrorText(it) }.orEmpty()
        binding.tvErrorDebug.text =
            state.error?.let { "${it.operation.name} / ${it.category.name}" }.orEmpty()
        binding.tvErrorDebug.visibility = if (state.error != null) View.VISIBLE else View.GONE

        binding.groupLogin.visibility =
            if (state.guide == GuideState.LOGIN_PENDING) View.VISIBLE else View.GONE
        binding.groupPrivacy.visibility =
            if (state.guide == GuideState.PRIVACY_PENDING) View.VISIBLE else View.GONE
        binding.tvPrivacyItems.text = privacyItemsLabel(state)
        // D-013: the catalogue is unusable, entry is allowed and a retry entry is offered.
        binding.groupPrivacyCatalog.visibility =
            if (state.privacyCatalog == PrivacyCatalogState.UNAVAILABLE) View.VISIBLE else View.GONE
        binding.tvPrivacyCatalogUnavailable.text = getString(R.string.privacy_catalog_unavailable)

        // D-010 状态驱动界面：未开通/被阻断时隐藏「重新认证」类入口，避免重试死循环。
        val deviceRetryable = viewModel.isDeviceRetryable()
        // DSN-CR-007: the login area follows the login dimension (in flight / failed / waiting) and
        // never offers a duplicate entry while the automatic login is still running.
        val loginPresentation = LoginPendingHint.of(
            login = state.login,
            error = state.error,
            deviceRetryable = deviceRetryable,
        )
        binding.tvLoginHint.text = getString(loginPresentation.hintRes)
        binding.tvLoginReason.text = loginPresentation.reasonRes?.let { getString(it) }.orEmpty()
        binding.tvLoginReason.visibility =
            if (loginPresentation.reasonRes != null) View.VISIBLE else View.GONE
        binding.btnRetryLogin.visibility =
            if (state.guide == GuideState.LOGIN_PENDING && loginPresentation.retryVisible) {
                View.VISIBLE
            } else {
                View.GONE
            }
        // 鉴权类失败只有在设备身份可修复时才给重试入口；其他失败（网关/契约）保留重试，
        // 因为它可能由云端侧修复（例如网关路由上线）而无需重启应用。
        val authFailureBlocked = state.documentsFailure == ErrorCategory.AUTH_REJECTED && !deviceRetryable
        binding.btnRetryAgreements.visibility = if (authFailureBlocked) View.GONE else View.VISIBLE
        binding.btnReloadAgreements.visibility = if (authFailureBlocked) View.GONE else View.VISIBLE

        binding.tvContinuation.visibility =
            if (state.accountContinuationSuggested) View.VISIBLE else View.GONE
        binding.btnContinuation.visibility =
            if (state.accountContinuationSuggested) View.VISIBLE else View.GONE
    }

    /**
     * Resumes a flow whose agreements were accepted in a previous run.
     *
     * Without this the restored consent only re-renders checked boxes while `GuideState` stays
     * `AGREEMENT_REQUIRED` and no session is ever requested (the user would have to click a checked
     * box again). Guarded so a failing request cannot turn into a retry loop: failures move the
     * guide to `RECOVERABLE_ERROR`, which offers the explicit retry instead.
     */
    private fun maybeResumeRestoredConsent(state: ActivationState) {
        if (restoredConsentResumed) return
        if (state.entertainmentEntryAllowed) return
        if (state.guide != GuideState.AGREEMENT_REQUIRED) return
        if (!state.documentsComplete || !state.agreementsAccepted) return
        if (state.sessionRef != null) return
        restoredConsentResumed = true
        viewModel.refreshSession()
    }

    /**
     * The QR area is state driven:
     * - no code yet -> explain the blocking reason (agreements, loading, seat, retry);
     * - code available -> render the bitmap and the server driven countdown.
     */
    private fun renderQrStage(state: ActivationState) {
        val ready = state.qrVisible && state.sessionRef != null
        binding.tvQrCountdown.visibility = if (ready) View.VISIBLE else View.GONE
        binding.btnQueryNow.isEnabled = ready
        binding.btnRefreshSession.isEnabled = !state.documentsLoading

        if (ready) {
            renderActivationCode(state)
            return
        }

        sessionExpiresAtEpochMillis = state.sessionRef?.expiresAtEpochMillis
        binding.ivQrCode.setImageDrawable(null)
        binding.ivQrCode.visibility = View.GONE
        binding.tvQrPlaceholder.text = when {
            state.seatBlocked != null -> getString(R.string.activation_front_seat_required)
            state.documentsLoading -> getString(R.string.qr_loading_documents)
            !state.documentsComplete -> getString(R.string.qr_documents_failed)
            !state.agreementsAccepted -> getString(R.string.qr_wait_agreements)
            state.guide == GuideState.RECOVERABLE_ERROR -> getString(R.string.qr_retry_hint)
            // Accepted agreements without a session yet: either the request is in flight or the
            // restored consent is being resumed, never a silent dead end.
            else -> getString(R.string.qr_preparing)
        }
        binding.tvQrPlaceholder.visibility = View.VISIBLE
        // The last payload is kept on purpose: when the server session runs out the countdown tick
        // still triggers the automatic refresh (D-012 / D-014).
        updateCountdown()
    }

    /**
     * D-010「状态驱动界面」: a deterministic hint for a device identity that retrying cannot fix.
     *
     * The banner is only raised when the identity is actually unusable; the full state line always
     * goes to the debug panel, where an engineer can read source and reason code.
     */
    private fun renderDeviceStatus(status: DeviceSecurityStatus?) {
        if (status == null) {
            binding.tvDeviceStatus.visibility = View.GONE
            binding.tvDeviceStatusDebug.visibility = View.GONE
            return
        }
        val hint = when (deviceStatusHint(status)) {
            DeviceStatusHint.RETRYABLE -> getString(R.string.device_retry_hint)
            DeviceStatusHint.NOT_PROVISIONED -> getString(R.string.device_not_provisioned)
            DeviceStatusHint.CERT_REQUIRED -> getString(R.string.device_cert_required)
            DeviceStatusHint.BLOCKED -> getString(R.string.device_blocked)
            DeviceStatusHint.CALLER_REJECTED -> getString(R.string.device_caller_rejected)
            DeviceStatusHint.TOKEN_UNAVAILABLE -> getString(R.string.device_token_unavailable)
            DeviceStatusHint.SERVICE_UNAVAILABLE -> getString(R.string.device_service_unavailable)
        }
        binding.tvDeviceStatusDebug.text = buildString {
            append(
                getString(
                    R.string.device_status_line,
                    status.state.name,
                    viewModel.identitySource(),
                    status.reasonCode,
                ),
            )
            append('\n').append(hint)
        }
        binding.tvDeviceStatusDebug.visibility = View.VISIBLE

        val usable = deviceStatusHint(status) == DeviceStatusHint.RETRYABLE
        binding.tvDeviceStatus.text = if (usable) "" else hint
        binding.tvDeviceStatus.visibility = if (usable) View.GONE else View.VISIBLE
        // The hint decides the affordances, and a late status update must re-render them.
        render(viewModel.state.value)
    }

    private fun genericErrorText(error: ActivationError): String = when (error.operation) {
        ActivationOperation.LOAD_AGREEMENTS -> getString(R.string.error_agreements_retry)
        ActivationOperation.REQUEST_SESSION,
        ActivationOperation.QUERY_SESSION,
        -> getString(R.string.error_session)

        ActivationOperation.SUBMIT_SKIP -> getString(R.string.error_skip)
        ActivationOperation.ISSUE_LOGIN_TICKET, ActivationOperation.LOGIN ->
            getString(R.string.error_login)

        ActivationOperation.PRIVACY_PROMPT,
        ActivationOperation.PRIVACY_PERSIST,
        ActivationOperation.PRIVACY_CATALOG,
        -> getString(R.string.error_privacy)

        ActivationOperation.REPORT_RECORD -> getString(R.string.error_report)
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
        append("\ncatalog=").append(state.privacyCatalog.name)
        state.privacyCatalogVersion?.let { append(" v=").append(it) }
        state.seatBlocked?.let { append("  seatBlocked=").append(it.name) }
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

    /**
     * Agreement full text: the cloud ships HTML (`content`), so it is rendered by a WebView with a
     * readable base style instead of being shown as raw markup. Plain text payloads are still
     * supported (escaped, newlines preserved).
     */
    private fun showDocument(type: AgreementType) {
        val document = viewModel.state.value.document(type) ?: return
        val title = when (type) {
            AgreementType.PRIVACY_POLICY -> getString(R.string.agreement_privacy)
            AgreementType.USER_AGREEMENT -> getString(R.string.agreement_user)
        }
        documentDialog?.dismiss()

        val dialogBinding = DialogDocumentBinding.inflate(layoutInflater)
        val web = dialogBinding.webDocument
        web.setBackgroundColor(Color.WHITE)
        web.settings.javaScriptEnabled = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.loadDataWithBaseURL(null, documentHtml(document.body), "text/html", "utf-8", null)

        documentDialog = AlertDialog.Builder(this, R.style.Theme_IviAcc_Dialog)
            .setTitle("$title · ${document.version}")
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.document_dialog_ok, null)
            .setOnDismissListener { destroyDocumentWebView(web) }
            .create()
            .apply {
                setOnShowListener {
                    val metrics = resources.displayMetrics
                    window?.setLayout(
                        (metrics.widthPixels * DOCUMENT_DIALOG_WIDTH_RATIO).toInt(),
                        (metrics.heightPixels * DOCUMENT_DIALOG_HEIGHT_RATIO).toInt(),
                    )
                }
                show()
            }
    }

    private fun destroyDocumentWebView(web: WebView) {
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading()
        web.destroy()
    }

    private fun documentHtml(raw: String?): String {
        val body = raw?.trim().orEmpty()
        if (body.isEmpty()) {
            return wrapDocumentHtml(TextUtils.htmlEncode(getString(R.string.document_body_missing)))
        }
        val looksLikeHtml = HTML_TAG.containsMatchIn(body)
        val inner = if (looksLikeHtml) {
            body
        } else {
            TextUtils.htmlEncode(body).replace("\n", "<br/>")
        }
        return wrapDocumentHtml(inner)
    }

    /** Keeps a full HTML document intact and only injects the reading style. */
    private fun wrapDocumentHtml(inner: String): String {
        if (FULL_DOCUMENT.containsMatchIn(inner)) {
            return if (HEAD_END.containsMatchIn(inner)) {
                inner.replaceFirst(HEAD_END, "<style>$DOCUMENT_STYLE</style></head>")
            } else {
                inner.replaceFirst(BODY_OPEN, "<head><style>$DOCUMENT_STYLE</style></head><body>")
            }
        }
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1"/>
            <style>$DOCUMENT_STYLE</style></head><body>$inner</body></html>
        """.trimIndent()
    }

    /**
     * D-012: renders the `qrToken` of the last response only.
     *
     * The presenter caches a single payload, so a replayed (re-signed) token or a refreshed session
     * immediately invalidates the previous bitmap; a render failure shows a retry hint and never a
     * text fallback.
     */
    private fun renderActivationCode(state: ActivationState) {
        val session = state.sessionRef
        sessionExpiresAtEpochMillis = session?.expiresAtEpochMillis
        if (!state.qrVisible || session == null) {
            clearRenderedCode()
            return
        }
        if (renderedPayload == session.qrPayload) {
            // The placeholder may have replaced the bitmap while the same payload stayed current
            // (for example a seat change), so restore the already rendered image.
            if (binding.ivQrCode.drawable != null) {
                binding.ivQrCode.visibility = View.VISIBLE
                binding.tvQrPlaceholder.visibility = View.GONE
            }
            updateCountdown()
            return
        }
        renderedPayload = session.qrPayload
        when (val render = viewModel.presentActivationCode(session.qrPayload)) {
            is ActivationCodeRender.Ready -> {
                binding.ivQrCode.setImageBitmap(render.image)
                binding.ivQrCode.visibility = View.VISIBLE
                binding.tvQrPlaceholder.visibility = View.GONE
            }

            is ActivationCodeRender.Failed -> {
                binding.ivQrCode.setImageDrawable(null)
                binding.ivQrCode.visibility = View.GONE
                binding.tvQrPlaceholder.text = getString(R.string.qr_unavailable) +
                    " (" + render.reason.name + ")"
                binding.tvQrPlaceholder.visibility = View.VISIBLE
            }
        }
        updateCountdown()
    }

    private fun clearRenderedCode() {
        renderedPayload = null
        binding.ivQrCode.setImageDrawable(null)
        binding.ivQrCode.visibility = View.GONE
        binding.tvQrPlaceholder.visibility = View.GONE
    }

    /**
     * Countdown uses the server `expiresAt` (never a local creation time) and refreshes the session
     * when it runs out, which allocates a new flow generation and therefore a new QR code.
     */
    private fun updateCountdown() {
        val expiresAt = sessionExpiresAtEpochMillis ?: run {
            binding.tvQrCountdown.text = getString(R.string.qr_countdown_placeholder)
            return
        }
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0L) {
            binding.tvQrCountdown.text = getString(R.string.qr_countdown_expired)
            if (renderedPayload != null) {
                // Auto refresh on expiry (D-012 / D-014).
                renderedPayload = null
                viewModel.refreshSession()
            }
            return
        }
        val seconds = remaining / 1_000L
        binding.tvQrCountdown.text = getString(
            R.string.qr_countdown_format,
            seconds / 60,
            seconds % 60,
        )
    }

    private fun privacyItemsLabel(state: ActivationState): String {
        if (state.privacyCatalog != PrivacyCatalogState.LOADED) {
            return getString(R.string.privacy_hint)
        }
        return state.privacyCatalogItems.joinToString("\n") { item ->
            val suffix = if (item.required) " *" else ""
            "• ${item.displayName}$suffix"
        }
    }

    private fun showPrivacyPrompt(items: List<PrivacyAuthorizationItem>) {
        if (privacyDialog?.isShowing == true) return
        val message = if (items.isEmpty()) {
            getString(R.string.privacy_message)
        } else {
            items.joinToString("\n") { item -> "• ${item.displayName}" } + "\n\n" +
                getString(R.string.privacy_message)
        }
        privacyDialog = AlertDialog.Builder(this, R.style.Theme_IviAcc_Dialog)
            .setTitle(R.string.privacy_title)
            .setMessage(message)
            .setPositiveButton(R.string.privacy_grant) { _, _ -> viewModel.grantPrivacy() }
            .setNegativeButton(R.string.privacy_close) { _, _ -> viewModel.closePrivacy() }
            .setOnCancelListener { viewModel.closePrivacy() }
            .show()
    }

    private companion object {
        const val COUNTDOWN_TICK_MILLIS = 1_000L
        const val PREF_UI = "acc-ui"
        const val PREF_DAYLIGHT = "daylight-mode"
        const val DOCUMENT_DIALOG_WIDTH_RATIO = 0.78f
        const val DOCUMENT_DIALOG_HEIGHT_RATIO = 0.82f

        val HTML_TAG = Regex("<[a-zA-Z!/][^>]*>")
        val FULL_DOCUMENT = Regex("<html", RegexOption.IGNORE_CASE)
        val HEAD_END = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
        val BODY_OPEN = Regex("<body[^>]*>", RegexOption.IGNORE_CASE)

        val DOCUMENT_STYLE = """
            :root { color-scheme: light; }
            body {
              margin: 0;
              padding: 16px 22px 32px;
              font-family: -apple-system, "Noto Sans CJK SC", "Source Han Sans SC", sans-serif;
              font-size: 16px;
              line-height: 1.75;
              color: #1F2937;
              -webkit-text-size-adjust: 100%;
              word-break: break-word;
            }
            h1 { font-size: 22px; line-height: 1.4; }
            h2 { font-size: 19px; line-height: 1.4; }
            h3 { font-size: 17px; line-height: 1.4; }
            p { margin: 0 0 12px; }
            img { max-width: 100%; height: auto; }
            table { border-collapse: collapse; width: 100%; margin: 12px 0; }
            td, th { border: 1px solid #DDE3EC; padding: 6px 8px; text-align: left; }
            pre { white-space: pre-wrap; word-break: break-word; }
            a { color: #0F62FE; }
        """.trimIndent()
    }
}
