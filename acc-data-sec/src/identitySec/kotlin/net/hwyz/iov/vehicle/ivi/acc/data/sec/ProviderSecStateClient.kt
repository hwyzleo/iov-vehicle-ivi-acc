package net.hwyz.iov.vehicle.ivi.acc.data.sec

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.hwyz.iov.vehicle.ivi.sec.api.SecContract

/**
 * Reads the security state from the read only provider of the unified service (DSN-CR-004 D-010).
 *
 * The provider exists so a caller can render the state without binding the service; it never
 * exposes tokens, signatures or key material.
 */
class ProviderSecStateClient(
    private val context: Context,
    private val timeoutMillis: Long = DEFAULT_QUERY_TIMEOUT_MILLIS,
) : SecStateClient {

    override suspend fun state(): Int = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://${SecContract.STATE_AUTHORITY}/${SecContract.COLUMN_STATE}")
        try {
            val cursor = withTimeout(timeoutMillis) {
                context.contentResolver.query(uri, SecContract.STATE_COLUMNS, null, null, null)
            } ?: throw SecRemoteException(SecFailureKind.SERVICE_MISSING)
            cursor.use {
                if (!it.moveToFirst()) throw SecRemoteException(SecFailureKind.UNKNOWN)
                val index = it.getColumnIndexOrThrow(SecContract.COLUMN_STATE)
                if (it.isNull(index)) throw SecRemoteException(SecFailureKind.UNKNOWN)
                it.getInt(index)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw SecRemoteException(SecFailureKind.TIMEOUT, cause = timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: SecurityException) {
            throw SecRemoteException(SecFailureKind.CALLER_REJECTED, cause = denied)
        } catch (failure: SecRemoteException) {
            throw failure
        } catch (error: Exception) {
            throw SecRemoteException(SecFailureKind.UNKNOWN, cause = error)
        }
    }

    companion object {
        const val DEFAULT_QUERY_TIMEOUT_MILLIS: Long = 1_000L
    }
}
