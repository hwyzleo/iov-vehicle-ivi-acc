package net.hwyz.iov.vehicle.ivi.acc.demo

import android.content.Context
import net.hwyz.iov.vehicle.ivi.acc.data.sec.BinderSecServiceClient
import net.hwyz.iov.vehicle.ivi.acc.data.sec.SecDeviceSecurityStatusQuery
import net.hwyz.iov.vehicle.ivi.acc.data.sec.SecDeviceSerialProvider
import net.hwyz.iov.vehicle.ivi.acc.data.sec.SecDeviceStatusCache
import net.hwyz.iov.vehicle.ivi.acc.data.sec.SecDeviceTokenSource
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatusQuery
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialProvider
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenSource
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger

/**
 * `identitySec` assembly (DSN-CR-004 D-008 / D-010): the device token comes from the car security
 * service over its AIDL contract.
 *
 * IVI-ACC never creates, reads or exports a device key here — it holds no key alias at all — and it
 * never calls the CIAM device identity grant. A binding, permission or provisioning failure is
 * classified per the CR-004 mapping table and fails closed.
 */
private class SecDeviceIdentityAssembly(
    context: Context,
    logger: DiagnosticsLogger,
) : DeviceIdentityAssembly {

    override val source = DeviceIdentitySource.UNIFIED

    private val client = BinderSecServiceClient(context)

    override val deviceTokenSource: DeviceTokenSource =
        SecDeviceTokenSource(client = client, logger = logger)

    private val cache = SecDeviceStatusCache(SecDeviceSecurityStatusQuery(client))

    override val statusQuery: DeviceSecurityStatusQuery = DeviceSecurityStatusQuery { cache.refresh() }

    override val deviceSerialProvider: DeviceSerialProvider = SecDeviceSerialProvider { cache.status() }

    override suspend fun refreshStatus(): DeviceSecurityStatus = cache.refresh()

    override fun cachedStatus(): DeviceSecurityStatus? = cache.status()
}

/** Creates the `identitySec` assembly; the `identityLocal` flavour provides the same symbol. */
object DemoIdentityAssemblyFactory {
    fun create(context: Context, logger: DiagnosticsLogger): DeviceIdentityAssembly =
        SecDeviceIdentityAssembly(context, logger)

    /** Diagnostic marker used by the boot log: the identity source of this build. */
    val source: DeviceIdentitySource = DeviceIdentitySource.UNIFIED
}
