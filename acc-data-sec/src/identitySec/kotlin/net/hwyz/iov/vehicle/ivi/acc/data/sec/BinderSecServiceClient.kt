package net.hwyz.iov.vehicle.ivi.acc.data.sec

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.hwyz.iov.vehicle.ivi.sec.api.IIviSecService
import net.hwyz.iov.vehicle.ivi.sec.api.SecContract
import net.hwyz.iov.vehicle.ivi.sec.api.SecError

/**
 * Binder client of the unified security service (DSN-CR-004 D-010).
 *
 * The connection is a lazily bound singleton. Every call runs off the main thread with a deadline,
 * and every failure is turned into a [SecRemoteException] carrying either the published service code
 * (`IVI_SEC:<code>:<NAME>` in the exception message) or the binder level cause.
 */
class BinderSecServiceClient(
    private val context: Context,
    private val bindTimeoutMillis: Long = DEFAULT_BIND_TIMEOUT_MILLIS,
    private val callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
) : SecServiceClient, SecStatusClient {

    private val bindMutex = Mutex()
    private var service: IIviSecService? = null
    private var pending: CompletableDeferred<IIviSecService?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val resolved = IIviSecService.Stub.asInterface(binder)
            service = resolved
            pending?.complete(resolved)
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
            pending?.complete(null)
            pending = null
        }
    }

    override suspend fun deviceToken(scopes: Array<String>): IssuedDeviceToken {
        val token = call { it.getDeviceToken(scopes) }
        return IssuedDeviceToken(
            accessToken = token.accessToken,
            scopes = token.scopes?.toList().orEmpty(),
            issuedAtEpochMillis = token.issuedAtEpochMillis,
            expiresAtEpochMillis = token.expiresAtEpochMillis,
        )
    }

    override suspend fun invalidateDeviceToken() {
        call { it.invalidateDeviceToken() }
    }

    override suspend fun state(): Int = call { it.state }

    /**
     * Device identifier reported by the service.
     *
     * A blank answer is *not* an error: the service may hold a certificate without exposing an
     * identifier (or it may be unable to read the platform serial). Reporting it as a status failure
     * used to mask the real device state as `KEY_UNAVAILABLE`.
     */
    override suspend fun deviceId(): String? = call { it.deviceId }?.takeIf { it.isNotBlank() }

    override suspend fun certificateNotAfterEpochMillis(): Long? =
        call { it.certificateInfo }
            ?.notAfterEpochMillis
            ?.takeIf { it > 0L }

    private suspend fun <T> call(block: (IIviSecService) -> T): T {
        val bound = serviceOrNull() ?: throw SecRemoteException(SecFailureKind.SERVICE_MISSING)
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(callTimeoutMillis) { block(bound) }
            }
        } catch (timeout: TimeoutCancellationException) {
            // A slow call is retryable; it must not look like a rejected identity.
            throw SecRemoteException(SecFailureKind.TIMEOUT, cause = timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: SecurityException) {
            throw SecRemoteException(
                SecFailureKind.CALLER_REJECTED,
                SecErrorMapper.codeOfWireMessage(denied.message),
                denied,
            )
        } catch (dead: DeadObjectException) {
            service = null
            throw SecRemoteException(SecFailureKind.PROCESS_DIED, cause = dead)
        } catch (remote: RemoteException) {
            service = null
            throw SecRemoteException(SecFailureKind.PROCESS_DIED, cause = remote)
        } catch (failed: IllegalStateException) {
            // The service publishes stable codes in the exception message; unknown ones stay unknown.
            throw SecRemoteException(
                SecFailureKind.UNKNOWN,
                SecErrorMapper.codeOfWireMessage(failed.message),
                failed,
            )
        } catch (error: Exception) {
            throw SecRemoteException(SecFailureKind.UNKNOWN, cause = error)
        }
    }

    private suspend fun serviceOrNull(): IIviSecService? = bindMutex.withLock {
        service?.let { return@withLock it }
        val deferred = CompletableDeferred<IIviSecService?>()
        pending = deferred
        val started = try {
            context.bindService(SecContract.serviceIntent(), connection, Context.BIND_AUTO_CREATE)
        } catch (denied: SecurityException) {
            pending = null
            throw SecRemoteException(SecFailureKind.CALLER_REJECTED, cause = denied)
        } catch (error: Exception) {
            pending = null
            throw SecRemoteException(SecFailureKind.UNKNOWN, cause = error)
        }
        if (!started) {
            // Package missing or the binding was refused: nothing to unbind.
            pending = null
            return@withLock null
        }
        val resolved = try {
            withTimeoutOrNull(bindTimeoutMillis) { deferred.await() }
        } finally {
            pending = null
        }
        if (resolved == null) {
            // Timed out: drop the connection so the next call starts from a clean state.
            runCatching { context.unbindService(connection) }
        }
        resolved
    }

    companion object {
        const val DEFAULT_BIND_TIMEOUT_MILLIS: Long = 5_000L
        const val DEFAULT_CALL_TIMEOUT_MILLIS: Long = 20_000L
    }
}
