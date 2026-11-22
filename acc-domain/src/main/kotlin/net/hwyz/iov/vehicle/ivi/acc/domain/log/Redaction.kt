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
