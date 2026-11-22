package net.hwyz.iov.vehicle.ivi.acc.data.local

import android.content.SharedPreferences
import net.hwyz.iov.vehicle.ivi.acc.data.local.security.SecretBox
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginMaterial
import net.hwyz.iov.vehicle.ivi.acc.domain.store.SecureMaterialStore

/** Minimal key value abstraction so the secure material store is testable on the host. */
interface KeyValueStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)

    fun clear()
}

class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences,
) : KeyValueStore {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}

class InMemoryKeyValueStore : KeyValueStore {
    private val entries = linkedMapOf<String, String>()

    override fun get(key: String): String? = entries[key]

    override fun put(key: String, value: String) {
        entries[key] = value
    }

    override fun remove(key: String) {
        entries.remove(key)
    }

    override fun clear() {
        entries.clear()
    }
}

/** Encrypted short lived login material; the plain value never reaches the disk. */
class SecureMaterialStoreImpl(
    private val store: KeyValueStore,
    private val box: SecretBox,
    private val nowEpochMillis: () -> Long,
) : SecureMaterialStore {

    override suspend fun put(material: LoginMaterial, secret: String) {
        store.put(
            key(material.handle),
            "${material.expiresAtEpochMillis}:${box.encrypt(secret)}",
        )
    }

    override suspend fun get(handle: String): String? {
        val raw = store.get(key(handle)) ?: return null
        val separator = raw.indexOf(':')
        if (separator <= 0) {
            clear(handle)
            return null
        }
        val expiresAt = raw.substring(0, separator).toLongOrNull() ?: return null
        if (expiresAt <= nowEpochMillis()) {
            // Expired material is never reused.
            clear(handle)
            return null
        }
        return runCatching { box.decrypt(raw.substring(separator + 1)) }.getOrNull()
    }

    override suspend fun clear(handle: String) {
        store.remove(key(handle))
    }

    override suspend fun clearAll() {
        store.clear()
    }

    private fun key(handle: String): String = "material.$handle"
}
