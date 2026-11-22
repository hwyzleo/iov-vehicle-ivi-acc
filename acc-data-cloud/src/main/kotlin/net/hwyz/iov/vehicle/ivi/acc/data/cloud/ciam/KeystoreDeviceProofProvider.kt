package net.hwyz.iov.vehicle.ivi.acc.data.cloud.ciam

import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64

/**
 * Device identity proof backed by the platform keystore (DSN-CR-002 D-008).
 *
 * The private key never leaves the secure store; every request carries a fresh nonce and a
 * signature over that nonce, so a captured proof cannot be replayed (VIAM consumes the nonce once).
 *
 * The device certificate / key must be provisioned and registered in VIAM before activation can
 * pass the gateway; without it the provider fails closed.
 */
class KeystoreDeviceProofProvider(
    private val keyAlias: String,
    private val deviceId: String? = null,
    private val proofType: String = PROOF_KEY_SIGNATURE,
    private val nonceSizeBytes: Int = 32,
    private val androidKeyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) },
) : DeviceProofProvider {

    private val random = SecureRandom()

    override suspend fun deviceProof(): DeviceProof {
        val nonceBytes = ByteArray(nonceSizeBytes).also(random::nextBytes)
        val nonce = Base64.getEncoder().encodeToString(nonceBytes)
        val signature = sign(nonceBytes)
        return DeviceProof(
            deviceId = deviceId,
            proofType = proofType,
            proof = Base64.getEncoder().encodeToString(signature),
            nonce = nonce,
        )
    }

    /** Exposes the public key so the provisioning flow can register it in VIAM. */
    fun publicKeyEncoded(): ByteArray? {
        val certificate = androidKeyStore.getCertificate(keyAlias) ?: return null
        return certificate.publicKey.encoded
    }

    private fun sign(payload: ByteArray): ByteArray {
        val entry = androidKeyStore.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Device key alias '$keyAlias' is not provisioned")
        val privateKey: PrivateKey = entry.privateKey
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(payload)
        return signature.sign()
    }

    companion object {
        const val PROOF_KEY_SIGNATURE = "KEY_SIGNATURE"
        const val PROOF_MTLS_CERT = "MTLS_CERT"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        /**
         * Generates a software backed key in the Android keystore.
         *
         * Real vehicles receive a provisioned certificate; this helper only exists for a device
         * under test and must never be used to bypass the VIAM registration step.
         */
        fun provisionTestKey(keyAlias: String): Boolean {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) return true
            val generator = java.security.KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE,
            )
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN,
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
            return true
        }
    }
}
