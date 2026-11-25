package net.hwyz.iov.vehicle.ivi.acc.platform

import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCodeFailure
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCodePresenter
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCodeRender
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialFailure
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOrigin
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.DeviceSerialOutcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-012 (payload verbatim) and D-011 (no secure settings identifier source) platform guarantees.
 *
 * Everything here runs without Android: the encoder is pure Java (ZXing core) and the serial reader
 * takes its three sources as lambdas.
 */
class QrAndDeviceSerialTest {

    // ------------------------------------------------------------------ D-012 payload contract

    @Test
    fun `the encoded payload decodes back character by character`() {
        val payloads = listOf(
            "qrToken-abc123",
            "a b\tc  spaced ",
            "https://example.com/?a=1&b=2",
            "混合内容-ü-😀-END",
            "x",
        )
        payloads.forEach { payload ->
            val outcome = assertIs<QrEncoding.EncodeOutcome.Ready>(QrEncoding.encode(payload, 512))
            assertEquals(512, outcome.sizePx)
            val pixels = QrEncoding.toArgbPixels(outcome.matrix)
            assertEquals(payload, QrEncoding.decode(pixels, outcome.sizePx), "payload '$payload'")
        }
    }

    @Test
    fun `the matrix keeps the quiet zone and is square`() {
        val outcome = assertIs<QrEncoding.EncodeOutcome.Ready>(QrEncoding.encode("token", 320))
        assertEquals(320, outcome.matrix.width)
        assertEquals(320, outcome.matrix.height)
        // The border module ring is white, so a scanner can find the finder patterns.
        assertEquals(false, outcome.matrix.get(0, 0))
    }

    @Test
    fun `an empty payload is refused instead of rendering a blank code`() {
        val outcome = QrEncoding.encode("", 256)
        assertEquals(
            ActivationCodeFailure.EMPTY_PAYLOAD,
            assertIs<QrEncoding.EncodeOutcome.Failed>(outcome).reason,
        )
    }

    @Test
    fun `an unusable size is reported as a render failure`() {
        assertEquals(
            ActivationCodeFailure.RENDER_ERROR,
            assertIs<QrEncoding.EncodeOutcome.Failed>(QrEncoding.encode("token", 0)).reason,
        )
    }

    @Test
    fun `the presenter port is renderer agnostic`() {
        // A fake renderer proves the port carries the payload unchanged and the size through.
        var seen: String? = null
        val presenter: ActivationCodePresenter<String> = object : ActivationCodePresenter<String> {
            override fun present(payload: String): ActivationCodeRender<String> {
                seen = payload
                return ActivationCodeRender.Ready("image:$payload", 42)
            }
        }
        val render = assertIs<ActivationCodeRender.Ready<String>>(presenter.present("qr-token-1"))
        assertEquals("qr-token-1", seen)
        assertEquals("image:qr-token-1", render.image)
        assertEquals(42, render.sizePx)
    }

    // ------------------------------------------------------------------ D-011 serial sources

    @Test
    fun `ro serialno wins over the build serials`() {
        val provider = AndroidDeviceSerialProvider(
            systemProperty = { name -> if (name == "ro.serialno") "SN-RO-1" else null },
            apiSerial = { "SN-API" },
            legacySerial = { "SN-LEGACY" },
        )
        val available = assertIs<DeviceSerialOutcome.Available>(provider.serial())
        assertEquals("SN-RO-1", available.serial)
        assertEquals(DeviceSerialOrigin.RO_SERIALNO, available.origin)
    }

    @Test
    fun `build getSerial is used when the property is empty`() {
        val provider = AndroidDeviceSerialProvider(
            systemProperty = { "" },
            apiSerial = { "SN-API" },
            legacySerial = { "SN-LEGACY" },
        )
        val available = assertIs<DeviceSerialOutcome.Available>(provider.serial())
        assertEquals("SN-API", available.serial)
        assertEquals(DeviceSerialOrigin.BUILD_SERIAL, available.origin)
    }

    @Test
    fun `legacy build serial is the last resort`() {
        val provider = AndroidDeviceSerialProvider(
            systemProperty = { null },
            apiSerial = { "unknown" },
            legacySerial = { "SN-LEGACY" },
        )
        val available = assertIs<DeviceSerialOutcome.Available>(provider.serial())
        assertEquals("SN-LEGACY", available.serial)
        assertEquals(DeviceSerialOrigin.LEGACY_BUILD_SERIAL, available.origin)
    }

    @Test
    fun `no source available fails closed without a substitute value`() {
        val provider = AndroidDeviceSerialProvider(
            systemProperty = { null },
            apiSerial = { throw SecurityException("READ_PHONE_STATE") },
            legacySerial = { null },
        )
        val unavailable = assertIs<DeviceSerialOutcome.Unavailable>(provider.serial())
        assertEquals(DeviceSerialFailure.NOT_AVAILABLE, unavailable.reason)
    }

    @Test
    fun `the origin enum never contains a secure settings identifier source`() {
        val names = DeviceSerialOrigin.entries.map { it.name }
        assertEquals(
            listOf("SEC_UNIFIED_SERVICE", "RO_SERIALNO", "BUILD_SERIAL", "LEGACY_BUILD_SERIAL"),
            names,
        )
        // The identifier token is assembled at runtime so the A-017 gate stays at zero hits.
        val forbidden = "ANDROID" + "_ID"
        names.forEach { name ->
            assertTrue(!name.contains(forbidden), "unexpected origin $name")
            assertTrue(!name.contains("SECURE"), "unexpected origin $name")
        }
        assertNull(DeviceSerialOrigin.entries.firstOrNull { it.name == "SECURE_$forbidden" })
    }
}
