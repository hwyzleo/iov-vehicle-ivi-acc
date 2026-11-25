package net.hwyz.iov.vehicle.ivi.acc.demo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.SgwActivationCloudPort
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.SgwAgreementGateway
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.SgwPrivacyCatalogGateway
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamAccountLoginGateway
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamAuthClient
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.IviTokenProvider
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.OkHttpSgwTransport
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.data.local.ActivationLocalStores
import net.hwyz.iov.vehicle.ivi.acc.domain.ActivationCoordinator
import net.hwyz.iov.vehicle.ivi.acc.domain.config.ActivationConfig
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenSource
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyAuthorizationItem
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyItemState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyScope
import net.hwyz.iov.vehicle.ivi.acc.domain.id.DefaultIdGenerator
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.RetryPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.sync.RecordSyncEngine
import net.hwyz.iov.vehicle.ivi.acc.domain.time.SystemMonotonicClock
import net.hwyz.iov.vehicle.ivi.acc.domain.time.SystemWallClock
import net.hwyz.iov.vehicle.ivi.acc.platform.DesktopPrivacyConsentAdapter
import net.hwyz.iov.vehicle.ivi.acc.platform.PrivacyPrompt
import net.hwyz.iov.vehicle.ivi.acc.platform.PrivacyPromptOutcome
import net.hwyz.iov.vehicle.ivi.acc.platform.SeatRole
import net.hwyz.iov.vehicle.ivi.acc.platform.StaticSeatRoleProvider
import net.hwyz.iov.vehicle.ivi.acc.platform.ZxingActivationCodePresenter
import java.util.Locale

/**
 * Production wiring of the activation flow.
 *
 * Every cloud interaction goes to the real gateway ([BuildConfig.SGW_BASE_URL]): agreements,
 * sessions, skip, login tickets, receipts and the privacy catalogue are IOV-TSP vehicle side routes,
 * while device and user tokens are issued by SEC-CIAM. No scripted fake is wired here; the flow shows
 * the real failure of a vehicle that has no published agreement or no provisioned device identity.
 *
 * D-011: the device serial is resolved through the domain port and the assembly is fail-closed. If it
 * is unavailable the device proof provider is never constructed, so no CIAM call can be made with a
 * fabricated identifier.
 */
class DemoEnvironment(context: Context) {

    val vehicle = VehicleRef(BuildConfig.DEMO_VIN)
    val seatRoleProvider = StaticSeatRoleProvider()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val routes = SgwRouteConfig(
        baseUrl = BuildConfig.SGW_BASE_URL,
        paths = SgwRouteConfig.activationPaths,
    )
    private val transport = OkHttpSgwTransport(routes)

    val logger: DiagnosticsLogger = object : DiagnosticsLogger {
        override fun event(stage: String, vararg attributes: Pair<String, String>) {
            Log.d(LOG_TAG, (listOf(stage) + attributes.map { "${it.first}=${it.second}" }).joinToString(" "))
        }
    }

    private val localStore = ActivationLocalStores.create(context)
    private val materialStore = ActivationLocalStores.secureMaterialStore(context)

    /**
     * D-010「构建变体二选一」: the device identity seam is supplied by the compiled flavour, so the
     * app never runs two identity sources side by side. `identitySec` (default) consumes the token of
     * the car security service and holds no device key; `identityLocal` is the migration window.
     */
    val identity: DeviceIdentityAssembly = DemoIdentityAssemblyFactory.create(context, logger)

    val deviceSerialProvider get() = identity.deviceSerialProvider

    /** Last device security status reported by the identity source, for the state driven UI. */
    @Volatile
    var deviceStatus: DeviceSecurityStatus? = identity.cachedStatus()
        private set

    /**
     * Device token source (DSN-CR-004 D-008 / D-010): the rest of the app only sees the port, so it
     * cannot tell — and must not care — whether the token came from the security service or from the
     * migration period local implementation.
     */
    private val deviceTokens: DeviceTokenSource = identity.deviceTokenSource

    private val authClient = CiamAuthClient(
        routes = CiamRouteConfig(baseUrl = BuildConfig.SGW_BASE_URL),
        clientId = BuildConfig.CIAM_CLIENT_ID,
    )

    val tokens = IviTokenProvider(
        authClient = authClient,
        deviceTokens = deviceTokens,
        secureStore = materialStore,
        logger = logger,
    )

    val agreementGateway = SgwAgreementGateway(
        transport = transport,
        tokens = tokens,
        languageProvider = { currentLanguage() },
        logger = logger,
    )
    val cloud = SgwActivationCloudPort(transport = transport, tokens = tokens)
    val loginGateway = CiamAccountLoginGateway(tokens)

    /** D-013: cloud authoritative privacy catalogue (no local fallback list). */
    val privacyCatalog = SgwPrivacyCatalogGateway(
        transport = transport,
        tokens = tokens,
        languageProvider = { currentLanguage() },
        logger = logger,
    )

    val privacyPrompt = DemoPrivacyPrompt()
    val privacyGateway = DesktopPrivacyConsentAdapter(seatRoleProvider, privacyPrompt)

    /** D-012: ZXing renderer; only the presenting layer knows about Bitmap. */
    val activationCodePresenter = ZxingActivationCodePresenter()

    private val config = ActivationConfig(
        recordRetry = RetryPolicy(),
        privacyCatalogLanguage = currentLanguage(),
    )

    val coordinator = ActivationCoordinator(
        vehicle = vehicle,
        agreements = agreementGateway,
        cloud = cloud,
        loginGateway = loginGateway,
        privacyGateway = privacyGateway,
        flowStore = localStore,
        acceptanceStore = localStore,
        privacyStore = localStore,
        recordStore = localStore,
        recordSync = RecordSyncEngine(
            recordStore = localStore,
            cloud = cloud,
            agreementStore = localStore,
            privacyStore = localStore,
            policy = config.recordRetry,
            wallClock = SystemWallClock,
            logger = logger,
        ),
        secureMaterials = materialStore,
        config = config,
        monotonicClock = SystemMonotonicClock,
        wallClock = SystemWallClock,
        logger = logger,
        ids = DefaultIdGenerator(),
        scope = scope,
        privacyCatalog = privacyCatalog,
        tokenEvents = tokens,
        seatRoleProvider = seatRoleProvider,
    )

    fun setRearSeat(rear: Boolean) {
        seatRoleProvider.seatRole = if (rear) SeatRole.REAR else SeatRole.FRONT
        // D-017: the domain must know immediately, so a rear seat cannot accept, scan or skip.
        coordinator.onSeatChanged()
    }

    fun answerPrivacy(outcome: PrivacyPromptOutcome) {
        privacyPrompt.answer(outcome)
    }

    fun grantPrivacy() = answerPrivacy(PrivacyPromptOutcome.Answered(PrivacyChoice.GRANTED))

    fun closePrivacy() = answerPrivacy(PrivacyPromptOutcome.Dismissed)

    /**
     * Re-runs the device identity proof and reloads the agreements.
     *
     * Used when the gateway rejected the previous token (expired device certificate, revoked
     * device session): the retry must re-prove, not repeat the same rejected call.
     */
    suspend fun reauthenticateAndReload() {
        tokens.invalidateDeviceToken()
        coordinator.reloadAgreements()
    }

    /**
     * D-010「状态驱动界面」: refreshes the device security status and logs it (state enum, reason code
     * and device id presence only — the identifier itself never reaches the log).
     */
    suspend fun refreshDeviceStatus(): DeviceSecurityStatus {
        val status = identity.refreshStatus()
        deviceStatus = status
        logger.event(
            "device.status",
            "source" to identity.source.name,
            "state" to status.state.name,
            "reason" to status.reasonCode.toString(),
            "deviceId" to if (status.deviceId.isNullOrBlank()) "absent" else "present",
            "certNotAfter" to (status.certNotAfterEpochMillis?.toString() ?: "-"),
        )
        return status
    }

    /** True when retrying or re-authenticating can change the outcome of the current device state. */
    fun isDeviceRetryable(): Boolean {
        val state = deviceStatus?.state ?: DeviceSecurityState.UNKNOWN
        return isDeviceStateRetryable(state)
    }

    /** Drops the local activation state, the sealed credentials and the in memory tokens. */
    suspend fun resetLocal() {
        coordinator.resetLocalStateForFactoryReset()
        tokens.signOut()
    }

    fun shutdown() {
        coordinator.close()
        scope.cancel()
    }

    private fun currentLanguage(): String =
        Locale.getDefault().toLanguageTag().ifBlank { DEFAULT_LANGUAGE }

    companion object {
        private const val LOG_TAG = "IVI-ACC"
        private const val DEFAULT_LANGUAGE = "zh-CN"
    }
}

/**
 * Bridges the desktop prompt to an Activity dialog.
 *
 * The item list comes from the cloud catalogue (G-1): `displayName` is the row title and `required`
 * only drives the "must choose" hint. The dialog has one grant/close answer, which is expanded to the
 * per item states the report needs.
 */
class DemoPrivacyPrompt : PrivacyPrompt {
    private var pending: CompletableDeferred<PrivacyPromptOutcome>? = null
    private var items: List<PrivacyAuthorizationItem> = emptyList()

    var onShow: ((List<PrivacyAuthorizationItem>) -> Unit)? = null

    val currentItems: List<PrivacyAuthorizationItem> get() = items

    override suspend fun present(
        scope: PrivacyScope,
        items: List<PrivacyAuthorizationItem>,
    ): PrivacyPromptOutcome {
        val deferred = CompletableDeferred<PrivacyPromptOutcome>()
        this.items = items
        pending = deferred
        onShow?.invoke(items)
        return deferred.await()
    }

    fun answer(outcome: PrivacyPromptOutcome) {
        val effective = if (outcome is PrivacyPromptOutcome.Answered && outcome.itemStates.isEmpty()) {
            // Expand the aggregate answer to every catalogue item.
            PrivacyPromptOutcome.Answered(
                choice = outcome.choice,
                itemStates = items.associate { item ->
                    item.code to when (outcome.choice) {
                        PrivacyChoice.GRANTED -> PrivacyItemState.GRANTED
                        PrivacyChoice.NOT_GRANTED, PrivacyChoice.NOT_DECIDED ->
                            PrivacyItemState.NOT_GRANTED
                    }
                },
            )
        } else {
            outcome
        }
        pending?.complete(effective)
        pending = null
    }
}
