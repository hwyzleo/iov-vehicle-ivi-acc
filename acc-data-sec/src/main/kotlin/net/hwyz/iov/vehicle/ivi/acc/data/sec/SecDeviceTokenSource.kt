package net.hwyz.iov.vehicle.ivi.acc.data.sec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenRef
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenSource
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory

/**
 * Device token source backed by the unified security service (DSN-CR-004 D-008 / D-010).
 *
 * The service signs the proof and calls the device identity grant, so this class never sees the
 * device key and never talks to CIAM. It only keeps the returned token for the current request chain
 * and maps failures onto the local categories.
 */
class SecDeviceTokenSource(
    private val client: SecServiceClient,
    private val scopes: Array<String> = DEFAULT_SCOPES,
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
        try {
            val issued = client.deviceToken(scopes)
            val ref = DeviceTokenRef(
                accessToken = issued.accessToken,
                expiresAtEpochMillis = issued.expiresAtEpochMillis,
            )
            token = ref
            logger?.event(
                "device_token.issued.remote",
                "expires_at" to issued.expiresAtEpochMillis.toString(),
            )
            DeviceTokenOutcome.Available(ref)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecRemoteException) {
            unavailable(SecErrorMapper.categoryOf(failure), reasonOf(failure))
        } catch (error: Exception) {
            // Never let an unexpected client defect look like a missing device identity.
            unavailable(ErrorCategory.RETRYABLE, "sec-call-failed:${error.javaClass.simpleName}")
        }
    }

    override suspend fun invalidate() {
        mutex.withLock { token = null }
        // Best effort: the local cache is already dropped, so a failure here only delays the
        // service side invalidation until the next issuance.
        try {
            client.invalidateDeviceToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecRemoteException) {
            logger?.event("device_token.invalidate_failed", "reason" to reasonOf(failure))
        } catch (error: Exception) {
            logger?.event("device_token.invalidate_failed", "reason" to error.javaClass.simpleName)
        }
    }

    private fun unavailable(category: ErrorCategory, reason: String): DeviceTokenOutcome {
        // Fail closed: a rejected or unreachable service is never cached.
        logger?.event("device_token.failed.remote", "category" to category.name, "reason" to reason)
        return DeviceTokenOutcome.Unavailable(category, reason)
    }

    private fun reasonOf(failure: SecRemoteException): String =
        failure.code?.let { "sec:$it" } ?: "sec:${failure.kind.name}"

    companion object {
        /** Device level scopes published by the security service for the activation flow. */
        val DEFAULT_SCOPES: Array<String> = arrayOf("vehicle.activation", "vehicle.privacy.report")

        /** Device token TTL is 15 minutes; ask again when less than this remains. */
        const val DEFAULT_RENEW_WINDOW_MILLIS: Long = 5 * 60_000L
    }
}
