package net.hwyz.iov.vehicle.ivi.acc.domain.log

/**
 * Structured diagnostics sink.
 *
 * Only stage names, result categories and retry counters may be logged. Tokens, QR payloads,
 * consent proofs, VIN and account identifiers must be masked by the caller (see [mask]).
 */
interface DiagnosticsLogger {
    fun event(stage: String, vararg attributes: Pair<String, String>)
}

object NoOpDiagnosticsLogger : DiagnosticsLogger {
    override fun event(stage: String, vararg attributes: Pair<String, String>) = Unit
}

/**
 * In-memory sink for tests and the demo console.
 *
 * [sensitiveKeys] are dropped even if a caller forgets to mask them; the check is key based so it
 * also protects against future call sites that pass raw secrets.
 */
class RecordingDiagnosticsLogger(
    private val sink: (String) -> Unit = {},
    private val sensitiveKeys: Set<String> = DEFAULT_SENSITIVE_KEYS,
) : DiagnosticsLogger {

    private val _entries = mutableListOf<String>()
    val entries: List<String> get() = _entries.toList()

    override fun event(stage: String, vararg attributes: Pair<String, String>) {
        val rendered = attributes
            .filterNot { (key, _) -> key.lowercase() in sensitiveKeys }
            .joinToString(separator = " ") { (key, value) -> "$key=$value" }
        val line = if (rendered.isEmpty()) stage else "$stage $rendered"
        _entries += line
        sink(line)
    }

    companion object {
        val DEFAULT_SENSITIVE_KEYS: Set<String> = setOf(
            "token",
            "accesstoken",
            "refreshtoken",
            "payload",
            "qrwpayload",
            "qr",
            "qrpayload",
            "secret",
            "material",
            "credential",
            "authorization",
            "vin",
            "accountid",
        )
    }
}
