package net.hwyz.iov.vehicle.ivi.acc.data.local.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Symmetric sealing for short lived login material.
 *
 * The platform implementation keeps the AES key inside the Android keystore; only ciphertext is
 * written to disk. An in-memory implementation is used by unit tests and the demo.
 */
interface SecretBox {
    fun encrypt(plain: String): String

    fun decrypt(sealed: String): String
}

/**
 * Android keystore backed AES/GCM box.
 *
 * Requires a real Android keystore provider, so it is verified on a device or emulator only and
 * not covered by host side unit tests.
 */
class AndroidKeystoreSecretBox(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretBox {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "${encode(cipher.iv)}:${encode(encrypted)}"
    }

    override fun decrypt(sealed: String): String {
        val parts = sealed.split(':')
        require(parts.size == 2) { "Malformed sealed value" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), javax.crypto.spec.GCMParameterSpec(128, decode(parts[0])))
        return String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_KEY_ALIAS = "iov_acc_material_key"
    }
}

/** Non persistent box for tests and the demo build. */
class InMemorySecretBox : SecretBox {
    private val entries = mutableMapOf<String, String>()
    private var counter = 0

    override fun encrypt(plain: String): String {
        val token = "sealed-${counter++}"
        entries[token] = plain
        return token
    }

    override fun decrypt(sealed: String): String = entries[sealed] ?: error("Unknown sealed value")
}
