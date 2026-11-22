package net.hwyz.iov.vehicle.ivi.acc.domain.id

import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import java.util.UUID

interface IdGenerator {
    /** New unique identifier, for example a session request id. */
    fun next(prefix: String): String

    /**
     * Deterministic identifier for a business fact. Retries and restarts derive the same value,
     * which is what makes repeated uploads idempotent on the cloud side.
     */
    fun stable(prefix: String, vararg parts: String): String
}

class DefaultIdGenerator : IdGenerator {
    override fun next(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    override fun stable(prefix: String, vararg parts: String): String =
        "$prefix-${PendingRecord.sha256(parts.joinToString("|")).take(32)}"
}
