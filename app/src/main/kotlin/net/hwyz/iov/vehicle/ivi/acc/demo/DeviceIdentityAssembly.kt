package net.hwyz.iov.vehicle.ivi.acc.demo

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatusQuery
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialProvider
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenSource

/**
 * Where the device identity comes from (DSN-CR-004 D-010「构建变体二选一」).
 *
 * The two implementations are supplied by the flavour source sets, so exactly one of them exists in
 * a build and no runtime switch can mix them. `UNIFIED` is the target architecture: the device key
 * and the token issuance live in the car security service, IVI-ACC only consumes the token.
 */
enum class DeviceIdentitySource {
    /** `identitySec`: 车端统一密码学服务（IIviSecService）签发设备令牌。 */
    UNIFIED,

    /** `identityLocal`: 迁移期本地设备证明 + CIAM 设备身份 grant。 */
    LOCAL,
}

/**
 * Device identity wiring of one build.
 *
 * [deviceSerialProvider] is diagnostic only for `UNIFIED` (the token is issued by the service); for
 * `LOCAL` it is the CIAM `device_id` source and must fail closed when unavailable (D-011).
 */
interface DeviceIdentityAssembly {
    val source: DeviceIdentitySource

    val deviceTokenSource: DeviceTokenSource

    val statusQuery: DeviceSecurityStatusQuery

    val deviceSerialProvider: DeviceSerialProvider

    /** Refreshes the cached security status; returns the fresh snapshot. */
    suspend fun refreshStatus(): net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus

    /** Last known status, or null when the assembly never reached the service. */
    fun cachedStatus(): net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus?
}

/**
 * True when retrying (or re-authenticating) can actually change the outcome (CR-004 §3 映射表).
 *
 * It drives the UI: an unprovisioned or blocked device must not offer a "重新认证" affordance that can
 * only fail again.
 */
fun isDeviceStateRetryable(state: DeviceSecurityState): Boolean = when (state) {
    DeviceSecurityState.ACTIVE,
    DeviceSecurityState.RENEW_DUE,
    DeviceSecurityState.ISSUED_PENDING_CLOUD,
    -> true

    else -> false
}

/**
 * The flavour source sets (`identitySec` / `identityLocal`) each declare
 * `object DemoIdentityAssemblyFactory { fun create(context, logger): DeviceIdentityAssembly }`.
 *
 * It is intentionally *not* declared here: an Android build must not be able to fall back to the
 * other identity source at runtime (D-010 禁止运行期并存).
 */