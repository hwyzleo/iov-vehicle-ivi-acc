package net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam

/**
 * Supplies the IAM authorization token used for gateway calls (DSN-CR-002 D-003 / D-008).
 *
 * The vehicle side never invents credentials: it presents the CIAM device token before login and
 * the user access token afterwards, and both are produced by [IviTokenProvider].
 */
fun interface TokenProvider {
    suspend fun authorizationToken(): String?

    companion object {
        /** Used by tests and by scripted demos that do not run the IAM handshake. */
        val None: TokenProvider = TokenProvider { null }
    }
}
