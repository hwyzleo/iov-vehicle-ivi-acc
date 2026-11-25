package net.hwyz.iov.vehicle.ivi.acc.domain.gateway

/**
 * Seat gate (IVI-ACC-DSN-CR-005 D-017 / A-009).
 *
 * Seat awareness used to live in the platform layer only, so the domain could not refuse a rear
 * seat action before it reached the cloud. The port is promoted to the domain and the platform keeps
 * the Android implementations.
 */
enum class SeatRole {
    FRONT,
    REAR,
}

fun interface SeatRoleProvider {
    fun currentSeat(): SeatRole
}

/** Why an action is blocked on this screen. */
enum class SeatBlockReason {
    FRONT_SEAT_REQUIRED,
}

/**
 * Pure seat policy. Any rear seat screen may read the flow but must not drive it: agreement consent,
 * QR display and long press skip are all front seat actions.
 */
object SeatGate {
    fun evaluate(seat: SeatRole): SeatBlockReason? =
        if (seat == SeatRole.REAR) SeatBlockReason.FRONT_SEAT_REQUIRED else null
}
