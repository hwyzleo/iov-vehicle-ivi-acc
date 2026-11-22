package net.hwyz.iov.vehicle.ivi.acc.demo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.FakeActivationCloudPort
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.FakeAgreementGateway
import net.hwyz.iov.vehicle.ivi.acc.data.local.ActivationLocalStores
import net.hwyz.iov.vehicle.ivi.acc.data.local.security.InMemorySecretBox
import net.hwyz.iov.vehicle.ivi.acc.domain.ActivationCoordinator
import net.hwyz.iov.vehicle.ivi.acc.domain.config.ActivationConfig
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyScope
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.id.DefaultIdGenerator
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.RetryPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.SessionPollPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.sync.RecordSyncEngine
import net.hwyz.iov.vehicle.ivi.acc.domain.time.SystemMonotonicClock
import net.hwyz.iov.vehicle.ivi.acc.domain.time.SystemWallClock
import net.hwyz.iov.vehicle.ivi.acc.platform.DesktopPrivacyConsentAdapter
import net.hwyz.iov.vehicle.ivi.acc.platform.FakeAccountLoginAdapter
import net.hwyz.iov.vehicle.ivi.acc.platform.PrivacyPrompt
import net.hwyz.iov.vehicle.ivi.acc.platform.PrivacyPromptOutcome
import net.hwyz.iov.vehicle.ivi.acc.platform.SeatRole
import net.hwyz.iov.vehicle.ivi.acc.platform.StaticSeatRoleProvider

/**
 * Demo wiring.
 *
 * The cloud, login and desktop capabilities are replaced by scriptable fakes so every acceptance
 * scenario (A-001..A-009) can be walked through without a cloud environment. Production wiring
 * would use [net.hwyz.iov.vehicle.ivi.acc.data.cloud.SgwActivationCloudPort] with a confirmed
 * payload codec.
 */
class DemoEnvironment(context: Context) {

    val vehicle = VehicleRef("LVINACC0000000001")
    val seatRoleProvider = StaticSeatRoleProvider()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val agreementGateway = FakeAgreementGateway()
    val cloud = FakeActivationCloudPort()
    val loginGateway = FakeAccountLoginAdapter()
    val privacyPrompt = DemoPrivacyPrompt()
    val privacyGateway = DesktopPrivacyConsentAdapter(seatRoleProvider, privacyPrompt)

    val localStore = ActivationLocalStores.create(context)
    val materialStore = ActivationLocalStores.secureMaterialStore(context, InMemorySecretBox())

    val logger: DiagnosticsLogger = object : DiagnosticsLogger {
        override fun event(stage: String, vararg attributes: Pair<String, String>) {
            Log.d(LOG_TAG, (listOf(stage) + attributes.map { "${it.first}=${it.second}" }).joinToString(" "))
        }
    }

    init {
        // Default demo state matches the current cloud reality: no agreement has been published yet,
        // so the first screen must fail explicitly instead of pretending the documents exist.
        agreementGateway.failure = ErrorCategory.RETRYABLE
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

    /** Applies one of the scripted scenarios of the debug panel and re-runs the agreement load. */
    fun applyScenario(scenario: DemoScenario) {
        agreementGateway.failure =
            if (scenario == DemoScenario.AGREEMENT_UNAVAILABLE) ErrorCategory.RETRYABLE else null
        if (scenario != DemoScenario.AGREEMENT_UNAVAILABLE &&
            scenario != DemoScenario.AGREEMENT_AVAILABLE
        ) {
            applyCloudScenario(scenario)
        }
        coordinator.reloadAgreements()
    }

    private fun applyCloudScenario(scenario: DemoScenario) {
        loginGateway.outcomeFactory = {
            LoginOutcome.Success(net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef(DEMO_ACCOUNT))
        }
        cloud.skipOutcome = SkipSubmitOutcome.Acknowledged
        cloud.defaultQueryOutcome = SessionQueryOutcome.Observed(SessionObservation.Active)
        when (scenario) {
            DemoScenario.BOUND -> cloud.defaultQueryOutcome =
                SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-demo"))

            DemoScenario.WAITING -> cloud.defaultQueryOutcome =
                SessionQueryOutcome.Observed(SessionObservation.Active)

            DemoScenario.UNKNOWN -> cloud.defaultQueryOutcome =
                SessionQueryOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)

            DemoScenario.EXPIRED -> cloud.defaultQueryOutcome =
                SessionQueryOutcome.Observed(SessionObservation.Expired)

            DemoScenario.LOGIN_FAILURE -> {
                cloud.defaultQueryOutcome =
                    SessionQueryOutcome.Observed(SessionObservation.BoundConfirmed("binding-demo"))
                loginGateway.outcomeFactory = { LoginOutcome.Failed(ErrorCategory.RETRYABLE) }
            }

            DemoScenario.SKIP_TIMEOUT -> cloud.skipOutcome =
                SkipSubmitOutcome.Failed(ErrorCategory.RESULT_UNKNOWN)

            DemoScenario.AGREEMENT_UNAVAILABLE, DemoScenario.AGREEMENT_AVAILABLE -> Unit
        }
    }

    fun answerPrivacy(outcome: PrivacyPromptOutcome) {
        privacyPrompt.answer(outcome)
    }

    fun grantPrivacy() = answerPrivacy(PrivacyPromptOutcome.Answered(PrivacyChoice.GRANTED))

    fun closePrivacy() = answerPrivacy(PrivacyPromptOutcome.Dismissed)

    fun shutdown() {
        coordinator.close()
        scope.cancel()
    }

    companion object {
        private const val LOG_TAG = "IVI-ACC"
        private const val DEMO_ACCOUNT = "account-demo"
    }
}

enum class DemoScenario {
    AGREEMENT_UNAVAILABLE,
    AGREEMENT_AVAILABLE,
    BOUND,
    WAITING,
    UNKNOWN,
    EXPIRED,
    LOGIN_FAILURE,
    SKIP_TIMEOUT,
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
