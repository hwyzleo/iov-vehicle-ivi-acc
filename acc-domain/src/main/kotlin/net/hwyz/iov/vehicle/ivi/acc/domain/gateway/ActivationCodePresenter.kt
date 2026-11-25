package net.hwyz.iov.vehicle.ivi.acc.domain.gateway

/**
 * Activation code presentation port (IVI-ACC-DSN-CR-005 D-012).
 *
 * The domain never depends on an Android type: the rendered artifact is a type parameter, so the QR
 * encoder lives in the platform layer while the payload contract stays testable in pure Kotlin.
 *
 * Payload contract: the payload is exactly the cloud `data.qrToken` string — no URL scheme, no
 * Base64 re-encoding, no trimming, no case folding.
 */
enum class ActivationCodeFailure {
    EMPTY_PAYLOAD,
    ENCODING_UNSUPPORTED,
    RENDER_ERROR,
}

sealed interface ActivationCodeRender<out T> {
    data class Ready<T>(val image: T, val sizePx: Int) : ActivationCodeRender<T>

    data class Failed(val reason: ActivationCodeFailure) : ActivationCodeRender<Nothing>
}

interface ActivationCodePresenter<T> {
    fun present(payload: String): ActivationCodeRender<T>
}
