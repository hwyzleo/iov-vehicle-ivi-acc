package net.hwyz.iov.vehicle.ivi.acc.platform

import android.graphics.Bitmap
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCodeFailure
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCodePresenter
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCodeRender

/**
 * ZXing implementation of the activation code presenter (D-012).
 *
 * Only the last payload is cached: a new `qrToken` (transport replay re-signs the code, refresh
 * creates a new session) invalidates the previous bitmap immediately, so a superseded code is never
 * displayed again. A render failure is reported as [ActivationCodeFailure]; it is never replaced by a
 * text fallback, which would leak the secret and invite manual typing.
 */
class ZxingActivationCodePresenter(
    private val sizePx: Int = DEFAULT_SIZE_PX,
) : ActivationCodePresenter<Bitmap> {

    private var cachedPayload: String? = null
    private var cachedBitmap: Bitmap? = null
    private var cachedSizePx: Int = 0

    override fun present(payload: String): ActivationCodeRender<Bitmap> {
        if (payload.isEmpty()) {
            clearCache()
            return ActivationCodeRender.Failed(ActivationCodeFailure.EMPTY_PAYLOAD)
        }
        cachedBitmap?.let { bitmap ->
            if (cachedPayload == payload && cachedSizePx == sizePx) {
                return ActivationCodeRender.Ready(bitmap, sizePx)
            }
        }
        return when (val outcome = QrEncoding.encode(payload, sizePx)) {
            is QrEncoding.EncodeOutcome.Failed -> {
                clearCache()
                ActivationCodeRender.Failed(outcome.reason)
            }

            is QrEncoding.EncodeOutcome.Ready -> {
                val pixels = QrEncoding.toArgbPixels(outcome.matrix)
                val bitmap = runCatching { render(pixels, outcome.sizePx) }.getOrNull()
                if (bitmap == null) {
                    clearCache()
                    ActivationCodeRender.Failed(ActivationCodeFailure.RENDER_ERROR)
                } else {
                    cachedPayload = payload
                    cachedBitmap = bitmap
                    cachedSizePx = outcome.sizePx
                    ActivationCodeRender.Ready(bitmap, outcome.sizePx)
                }
            }
        }
    }

    /** Releases the bitmap when the session ends; QR content is a short lived secret. */
    fun clearCache() {
        cachedPayload = null
        cachedBitmap = null
        cachedSizePx = 0
    }

    private fun render(pixels: IntArray, size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }

    companion object {
        /** Default edge in pixels; callers align it to the short edge of the ImageView. */
        const val DEFAULT_SIZE_PX = 640
    }
}
