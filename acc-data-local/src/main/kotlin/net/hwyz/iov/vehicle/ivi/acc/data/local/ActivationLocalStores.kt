package net.hwyz.iov.vehicle.ivi.acc.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import net.hwyz.iov.vehicle.ivi.acc.data.local.security.AndroidKeystoreSecretBox
import net.hwyz.iov.vehicle.ivi.acc.data.local.security.SecretBox
import net.hwyz.iov.vehicle.ivi.acc.domain.store.SecureMaterialStore

/** Wiring for the local persistence of the activation flow. */
object ActivationLocalStores {

    fun dataStore(context: Context): DataStore<Preferences> = context.activationDataStore

    fun create(context: Context): ActivationLocalStore = ActivationLocalStore(dataStore(context))

    fun secureMaterialStore(
        context: Context,
        box: SecretBox = AndroidKeystoreSecretBox(),
    ): SecureMaterialStore = SecureMaterialStoreImpl(
        store = SharedPreferencesKeyValueStore(
            context.getSharedPreferences(MATERIAL_PREFERENCES, Context.MODE_PRIVATE),
        ),
        box = box,
        nowEpochMillis = System::currentTimeMillis,
    )

    private const val MATERIAL_PREFERENCES = "acc_secure_material"
}

private val Context.activationDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "activation_local")
