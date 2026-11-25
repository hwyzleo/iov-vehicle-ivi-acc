package net.hwyz.iov.vehicle.ivi.acc.demo

import android.content.Context
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.CiamRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam.DeviceProofProvider
import net.hwyz.iov.vehicle.ivi.acc.data.sec.CiamDeviceIdentityGrantClient
import net.hwyz.iov.vehicle.ivi.acc.data.sec.KeystoreDeviceProofProvider
import net.hwyz.iov.vehicle.ivi.acc.data.sec.LocalDeviceTokenSource
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityState
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatus
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSecurityStatusQuery
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOrigin
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialProvider
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceTokenSource
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.platform.AndroidDeviceSerialProvider

/**
 * `identityLocal` assembly (DSN-CR-004 D-010 stage one / 阶段三 删除).
 *
 * Migration window only: the app holds the device key alias and calls the CIAM device identity
 * grant itself. It exists for benches and emulators that cannot run the unified security service;
 * the `identitySec` flavour is the default and the target.
 */
private class LocalDeviceIdentityAssembly(
    logger: DiagnosticsLogger,
) : DeviceIdentityAssembly {

    override val source = DeviceIdentitySource.LOCAL

    /**
     * D-011 / BLK-4: platform serial only, and a non blank `DEVICE_SN_OVERRIDE` short circuits it for
     * benches that cannot grant `ro.serialno`. An empty override restores the platform reader.
     */
    override val deviceSerialProvider: DeviceSerialProvider = DeviceSerialProvider {
        val override = BuildConfig.DEVICE_SN_OVERRIDE.trim()
        if (override.isEmpty()) {
            AndroidDeviceSerialProvider().serial()
        } else {
            DeviceSerialOutcome.Available(override, DeviceSerialOrigin.RO_SERIALNO)
        }
    }

    private val deviceSerial: DeviceSerialOutcome = deviceSerialProvider.serial()

    private val proofProvider: DeviceProofProvider = when (deviceSerial) {
        is DeviceSerialOutcome.Available -> KeystoreDeviceProofProvider(
            keyAlias = BuildConfig.DEVICE_KEY_ALIAS,
            deviceId = deviceSerial.serial,
        )

        is DeviceSerialOutcome.Unavailable -> DeviceProofProvider {
            // Never reached: LocalDeviceTokenSource checks the serial first and fails closed.
            throw IllegalStateException("Device serial unavailable: ${deviceSerial.reason}")
        }
    }

    override val deviceTokenSource: DeviceTokenSource = LocalDeviceTokenSource(
        proofProvider = proofProvider,
        grantClient = CiamDeviceIdentityGrantClient(
            routes = CiamRouteConfig(baseUrl = BuildConfig.SGW_BASE_URL),
            clientId = BuildConfig.CIAM_CLIENT_ID,
        ),
        deviceSerial = deviceSerialProvider,
        logger = logger,
    )

    /**
     * `identityLocal` has no security service, so the state is derived from the serial it can read:
     * a readable serial behaves like an ACTIVE identity, a missing one like an unprovisioned device.
     */
    override suspend fun refreshStatus(): DeviceSecurityStatus = localStatus()

    override fun cachedStatus(): DeviceSecurityStatus = localStatus()

    override val statusQuery: DeviceSecurityStatusQuery = DeviceSecurityStatusQuery { localStatus() }

    private fun localStatus(): DeviceSecurityStatus = when (val serial = deviceSerialProvider.serial()) {
        is DeviceSerialOutcome.Available -> DeviceSecurityStatus(
            state = DeviceSecurityState.ACTIVE,
            deviceId = serial.serial,
        )

        is DeviceSerialOutcome.Unavailable -> DeviceSecurityStatus(
            state = DeviceSecurityState.NOT_PROVISIONED,
        )
    }
}

/** Creates the `identityLocal` assembly; the `identitySec` flavour provides the same symbol. */
object DemoIdentityAssemblyFactory {
    fun create(context: Context, logger: DiagnosticsLogger): DeviceIdentityAssembly =
        LocalDeviceIdentityAssembly(logger)

    /** Diagnostic marker used by the boot log: the identity source of this build. */
    val source: DeviceIdentitySource = DeviceIdentitySource.LOCAL
}
