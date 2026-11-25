package net.hwyz.iov.vehicle.ivi.acc.data.sec

/**
 * Device token returned by the unified security service (DSN-CR-004 D-008).
 *
 * A local value type on purpose: the cross process parcelable of `sec-api` never leaks into this
 * module's flavour agnostic code, so the `identityLocal` build stays free of the AIDL contract.
 */
data class IssuedDeviceToken(
    val accessToken: String,
    val scopes: List<String>,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

/**
 * Why a cross-process call to the unified security service failed when the service published no
 * error code (IVI-ACC-SPEC设计 D-010).
 */
enum class SecFailureKind {
    /** The service is not installed or not resolvable for this caller. */
    SERVICE_MISSING,

    /** The caller does not hold the signature permission, or is not in the caller whitelist. */
    CALLER_REJECTED,

    /** The service process died or the binder object is gone. */
    PROCESS_DIED,

    /** Bind or call deadline exceeded. */
    TIMEOUT,

    /** Any other binder level failure; never treated as "no device identity required". */
    UNKNOWN,
}

/** Failure of a call to the unified security service; [code] is a `SecError` value when published. */
class SecRemoteException(
    val kind: SecFailureKind,
    val code: Int? = null,
    cause: Throwable? = null,
) : Exception("sec:${kind.name}${code?.let { ":$it" } ?: ""}", cause)

/**
 * The calls IVI-ACC makes on the unified service (DSN-CR-004 D-008 / D-010).
 *
 * Declared as an interface so the token lifecycle can be tested without a binder, and so the
 * migration period local implementation can be kept behind the same seam.
 */
interface SecServiceClient {
    suspend fun deviceToken(scopes: Array<String>): IssuedDeviceToken

    suspend fun invalidateDeviceToken()

    /** Device identifier owned by the service (D-011 `SEC_UNIFIED_SERVICE`). */
    suspend fun deviceId(): String?

    /** `notAfter` of the device certificate, when the service reports one. */
    suspend fun certificateNotAfterEpochMillis(): Long?
}

/** Read only device security state; cheap enough to consult on every guide transition. */
fun interface SecStateClient {
    suspend fun state(): Int
}

/**
 * Device security status surface: the state plus the two values the UI and the device identity port
 * need (D-010「状态驱动界面」, D-011 `SEC_UNIFIED_SERVICE`).
 */
interface SecStatusClient {
    suspend fun state(): Int

    suspend fun deviceId(): String?

    suspend fun certificateNotAfterEpochMillis(): Long?
}
