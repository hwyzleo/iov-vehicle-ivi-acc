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
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamAccountLoginGateway
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamAuthClient
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.IviTokenProvider
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.KeystoreDeviceProofProvider
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.OkHttpSgwTransport
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.data.local.ActivationLocalStores
import net.hwyz.iov.vehicle.ivi.acc.domain.ActivationCoordinator
import net.hwyz.iov.vehicle.ivi.acc.domain.config.ActivationConfig
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyScope
import net.hwyz.iov.vehicle.ivi.acc.domain.id.DefaultIdGenerator
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.RetryPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.SessionPollPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.sync.RecordSyncEngine
import net.hwyz.iov.vehicle.ivi.acc.domain.time.SystemMonotonicClock
import net.hwyz.iov.vehicle.ivi.acc.domain.time.SystemWallClock
import net.hwyz.iov.vehicle.ivi.acc.platform.DesktopPrivacyConsentAdapter
import net.hwyz.iov.vehicle.ivi.acc.platform.PrivacyPrompt
import net.hwyz.iov.vehicle.ivi.acc.platform.PrivacyPromptOutcome
import net.hwyz.iov.vehicle.ivi.acc.platform.SeatRole
import net.hwyz.iov.vehicle.ivi.acc.platform.StaticSeatRoleProvider
import java.util.Locale

/**
 * Production wiring of the activation flow.
 *
 * Every cloud interaction goes to the real gateway ([BuildConfig.SGW_BASE_URL]): agreements,
 * sessions, skip, login tickets and receipts are IOV-TSP vehicle side routes, while device and
 * user tokens are issued by SEC-CIAM. No scripted fake is wired here; the flow shows the real
 * failure of a vehicle that has no published agreement or no provisioned device identity.
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

    private val deviceProof = KeystoreDeviceProofProvider(
        keyAlias = BuildConfig.DEVICE_KEY_ALIAS,
    )
    private val authClient = CiamAuthClient(
        routes = CiamRouteConfig(baseUrl = BuildConfig.SGW_BASE_URL),
        clientId = BuildConfig.CIAM_CLIENT_ID,
    )
    private val localStore = ActivationLocalStores.create(context)
    private val materialStore = ActivationLocalStores.secureMaterialStore(context)

    val tokens = IviTokenProvider(
        authClient = authClient,
        proofProvider = deviceProof,
        secureStore = materialStore,
    )

    val agreementGateway = SgwAgreementGateway(
        transport = transport,
        tokens = tokens,
        languageProvider = { Locale.getDefault().toLanguageTag().ifBlank { DEFAULT_LANGUAGE } },
    )
    val cloud = SgwActivationCloudPort(transport = transport, tokens = tokens)
    val loginGateway = CiamAccountLoginGateway(tokens)

    val privacyPrompt = DemoPrivacyPrompt()
    val privacyGateway = DesktopPrivacyConsentAdapter(seatRoleProvider, privacyPrompt)

    val logger: DiagnosticsLogger = object : DiagnosticsLogger {
        override fun event(stage: String, vararg attributes: Pair<String, String>) {
            Log.d(LOG_TAG, (listOf(stage) + attributes.map { "${it.first}=${it.second}" }).joinToString(" "))
        }
    }

    private val config = ActivationConfig(
        sessionPoll = SessionPollPolicy(intervalMillis = 2_000L, timeoutMillis = 120_000L, maxAttempts = 60),
        recordRetry = RetryPolicy(),
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
    )

    fun setRearSeat(rear: Boolean) {
        seatRoleProvider.seatRole = if (rear) SeatRole.REAR else SeatRole.FRONT
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

    /** Drops the local activation state, the sealed credentials and the in memory tokens. */
    suspend fun resetLocal() {
        coordinator.resetLocalStateForFactoryReset()
        tokens.signOut()
    }

    fun shutdown() {
        coordinator.close()
        scope.cancel()
    }

    companion object {
        private const val LOG_TAG = "IVI-ACC"
        private const val DEFAULT_LANGUAGE = "zh-CN"
    }
}

/** Bridges the desktop prompt to an Activity dialog. */
class DemoPrivacyPrompt : PrivacyPrompt {
    private var pending: CompletableDeferred<PrivacyPromptOutcome>? = null

    var onShow: (() -> Unit)? = null

    override suspend fun present(
        scope: PrivacyScope,
        items: List<String>,
    ): PrivacyPromptOutcome {
        val deferred = CompletableDeferred<PrivacyPromptOutcome>()
        pending = deferred
        onShow?.invoke()
        return deferred.await()
    }

    fun answer(outcome: PrivacyPromptOutcome) {
        pending?.complete(outcome)
        pending = null
    }
}
