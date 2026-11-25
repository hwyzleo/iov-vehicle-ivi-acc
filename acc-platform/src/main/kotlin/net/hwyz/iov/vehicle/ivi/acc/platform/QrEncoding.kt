package net.hwyz.iov.vehicle.ivi.acc.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCodeFailure

/**
 * Pure QR encoding/decoding over ZXing core (no Android type, no camera).
 *
 * Payload contract (D-012): the encoded content is exactly the payload string — no URL scheme, no
 * Base64, no trimming. [decode] exists so a unit test can prove a round trip character by character
 * without a device camera.
 */
object QrEncoding {

    /** Quiet zone required by the QR specification, in modules. */
    const val QUIET_ZONE_MODULES = 1

    sealed interface EncodeOutcome {
        data class Ready(val matrix: com.google.zxing.common.BitMatrix, val sizePx: Int) : EncodeOutcome

        data class Failed(val reason: ActivationCodeFailure) : EncodeOutcome
    }

    fun encode(payload: String, sizePx: Int): EncodeOutcome {
        if (payload.isEmpty()) return EncodeOutcome.Failed(ActivationCodeFailure.EMPTY_PAYLOAD)
        if (sizePx <= 0) return EncodeOutcome.Failed(ActivationCodeFailure.RENDER_ERROR)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
        )
        return runCatching {
            val matrix = MultiFormatWriter()
                .encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            EncodeOutcome.Ready(matrix, matrix.width)
        }.getOrElse { error ->
            EncodeOutcome.Failed(
                if (error is IllegalArgumentException) {
                    ActivationCodeFailure.ENCODING_UNSUPPORTED
                } else {
                    ActivationCodeFailure.RENDER_ERROR
                },
            )
        }
    }

    /** ARGB_8888 pixel buffer of a matrix; a black module is fully opaque black. */
    fun toArgbPixels(matrix: com.google.zxing.common.BitMatrix): IntArray =
        IntArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix.get(x, y)) BLACK else WHITE
        }

    /** Decodes a square pixel buffer back to text; null when the image is not readable. */
    fun decode(pixels: IntArray, sizePx: Int): String? {
        if (sizePx <= 0 || pixels.size < sizePx * sizePx) return null
        return runCatching {
            val source = RGBLuminanceSource(sizePx, sizePx, pixels)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader()
                .decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true))
                .text
        }.getOrNull()
    }

    private const val BLACK = -0x1000000 // 0xFF000000
    private const val WHITE = -0x1 // 0xFFFFFFFF
}
