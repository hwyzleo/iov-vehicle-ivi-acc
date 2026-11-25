package net.hwyz.iov.vehicle.ivi.acc.data.sec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamTokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.DeviceProofProvider
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialProvider
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenRef
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenSource
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Migration period `identityLocal` device token source (DSN-CR-004 D-010, stage one).
 *
 * Keeps today's behaviour — in process device proof, CIAM device identity grant, 15 minute token
 * renewed inside the last 5 minutes, single flight — but behind [DeviceTokenSource], so the rest of
 * the app no longer depends on the key material or on the grant contract.
 *
 * Stage three (D-010) deletes this class and the `identityLocal` flavour once the unified security
 * service is validated on real hardware; it must not gain new features in the meantime.
 */
class LocalDeviceTokenSource(
    private val proofProvider: DeviceProofProvider,
    private val grantClient: DeviceIdentityGrantClient,
    /**
     * Device identity source (DSN-CR-005 D-011 / BLK-4).
     *
     * When present and unavailable, no proof is created and no CIAM call is made: the flow fails
     * closed with `CONTRACT_REJECTED` / `device-id-unavailable` instead of degrading to a fabricated
     * identifier.
     */
    private val deviceSerial: DeviceSerialProvider? = null,
    private val renewWindowMillis: Long = DEFAULT_RENEW_WINDOW_MILLIS,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val logger: DiagnosticsLogger? = null,
) : DeviceTokenSource {

    private val mutex = Mutex()
    private var token: DeviceTokenRef? = null

    override suspend fun deviceToken(): DeviceTokenOutcome = mutex.withLock {
        token?.let { current ->
            if (current.expiresAtEpochMillis - nowEpochMillis() > renewWindowMillis) {
                return@withLock DeviceTokenOutcome.Available(current)
            }
        }
        deviceSerial?.let { provider ->
            when (provider.serial()) {
                is DeviceSerialOutcome.Available -> Unit
                is DeviceSerialOutcome.Unavailable ->
                    return@withLock unavailable(ErrorCategory.CONTRACT_REJECTED, DEVICE_ID_UNAVAILABLE)
            }
        }
        val proof = try {
            proofProvider.deviceProof()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Fail closed: no proof, no call. The cause is logged without any secret.
            return@withLock unavailable(
                ErrorCategory.AUTH_REJECTED,
                "device-proof-unavailable:${error.javaClass.simpleName}",
            )
        }
        when (val outcome = grantClient.requestDeviceToken(proof)) {
            is CiamTokenOutcome.Failed -> unavailable(
                outcome.category,
                "device-identity:${outcome.code ?: "-"}",
            )

            is CiamTokenOutcome.Issued -> {
                val issued = DeviceTokenRef(
                    accessToken = outcome.accessToken,
                    expiresAtEpochMillis = nowEpochMillis() + outcome.expiresInSeconds * 1_000L,
                )
                token = issued
                logger?.event("device_token.issued", "expires_in" to outcome.expiresInSeconds.toString())
                DeviceTokenOutcome.Available(issued)
            }
        }
    }

    override suspend fun invalidate() {
        mutex.withLock { token = null }
    }

    private fun unavailable(category: ErrorCategory, reason: String): DeviceTokenOutcome {
        // A rejected or unreachable identity is never cached: the next call proves again.
        logger?.event("device_token.failed", "category" to category.name, "reason" to reason)
        return DeviceTokenOutcome.Unavailable(category, reason)
    }

    private companion object {
        /** Device token TTL is 15 minutes; ask again when less than this remains. */
        const val DEFAULT_RENEW_WINDOW_MILLIS = 5 * 60_000L

        /** Diagnostic reason of the fail closed branch; never a credential and never a device id. */
        const val DEVICE_ID_UNAVAILABLE = "device-id-unavailable"
    }
}
