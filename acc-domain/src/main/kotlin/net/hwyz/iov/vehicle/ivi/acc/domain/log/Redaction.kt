package net.hwyz.iov.vehicle.ivi.acc.domain.log

/** Masks a secret while keeping a stable, non reversible shape for diagnostics. */
fun mask(value: String?): String {
    if (value.isNullOrEmpty()) return "***"
    return "***${value.length}"
}

/** Last four characters only, for identifiers that must stay correlatable in logs. */
fun maskTail(value: String?): String {
    if (value.isNullOrEmpty()) return "***"
    if (value.length <= 4) return "***"
    return "***${value.takeLast(4)}"
}

/**
 * Normalises a cloud supplied, publicly safe message so it can be logged as is (DSN-CR-007).
 *
 * Only the business text is kept: control characters are removed, whitespace runs collapse to one
 * space, and the result is truncated so a rogue server can never flood the local log. Nothing here
 * makes a secret safe — tokens, tickets and QR payloads must never be passed in.
 */
fun sanitizeDiagnosticMessage(value: String?, maxLength: Int = DIAGNOSTIC_MESSAGE_MAX_LENGTH): String? {
    if (value.isNullOrBlank()) return null
    val collapsed = value
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(WHITESPACE_RUN, " ")
        .trim()
    if (collapsed.isEmpty()) return null
    return if (collapsed.length <= maxLength) collapsed else collapsed.take(maxLength) + "…"
}

const val DIAGNOSTIC_MESSAGE_MAX_LENGTH = 80

private val WHITESPACE_RUN = Regex("\\s+")
