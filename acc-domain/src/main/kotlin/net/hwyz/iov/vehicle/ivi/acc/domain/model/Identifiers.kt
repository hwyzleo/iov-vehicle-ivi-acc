package net.hwyz.iov.vehicle.ivi.acc.domain.model

/**
 * Vehicle reference used by the activation flow.
 *
 * The VIN is never proof of vehicle identity for the cloud side; it is only a local correlation
 * key. [toString] is masked so the value can be logged safely.
 */
@JvmInline
value class VehicleRef(val vin: String) {
    val masked: String
        get() = when {
            vin.length <= 6 -> "***"
            else -> "${vin.take(3)}***${vin.takeLast(3)}"
        }

    override fun toString(): String = "VehicleRef($masked)"
}

/** Account reference. Never carries a token; [toString] is masked. */
@JvmInline
value class AccountRef(val accountId: String) {
    val masked: String
        get() = when {
            accountId.length <= 4 -> "***"
            else -> "${accountId.take(2)}***${accountId.takeLast(2)}"
        }

    override fun toString(): String = "AccountRef($masked)"
}

/** Storage scopes: skip activation records are VIN scoped, post-login records are account + VIN scoped. */
object ScopeKeys {
    fun vehicle(vin: String): String = "VIN:$vin"

    fun vehicle(vehicle: VehicleRef): String = vehicle(vehicle.vin)

    fun accountVehicle(accountId: String, vin: String): String = "AV:$accountId|$vin"

    fun accountVehicle(account: AccountRef, vehicle: VehicleRef): String =
        accountVehicle(account.accountId, vehicle.vin)

    /**
     * Session scope used by receipts that must be reachable before an account exists (D-005 / BLK-11).
     *
     * A failed login has no account (`account` is empty by definition), so the receipt is scoped to
     * the vehicle plus the session; the cloud resolves the owner through the session VIN.
     */
    fun vehicleSession(vin: String, sessionId: String): String = "VS:$vin|$sessionId"

    fun vehicleSession(vehicle: VehicleRef, sessionId: String): String =
        vehicleSession(vehicle.vin, sessionId)
}
