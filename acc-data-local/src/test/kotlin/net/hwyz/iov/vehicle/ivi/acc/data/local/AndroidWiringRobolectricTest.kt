package net.hwyz.iov.vehicle.ivi.acc.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.hwyz.iov.vehicle.ivi.acc.data.local.security.InMemorySecretBox
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginMaterial
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.store.FlowContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Android wiring smoke test: DataStore factory and SharedPreferences adapter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidWiringRobolectricTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `local store factory persists and reloads the flow context`() = runBlocking {
        val store = ActivationLocalStores.create(context)
        store.save(
            FlowContext(
                flowGeneration = 2L,
                guide = GuideState.QR_VISIBLE,
                login = LoginState.SIGNED_OUT,
                sessionId = "session-robolectric",
                sessionExpiresAtEpochMillis = 123L,
                bindingOperationId = null,
                bindingConfirmed = false,
                accountId = null,
                updatedAtEpochMillis = 5L,
            ),
        )
        val loaded = store.loadContext()
        assertEquals("session-robolectric", loaded?.sessionId)
        assertEquals(GuideState.QR_VISIBLE, loaded?.guide)
        store.clear()
    }

    @Test
    fun `secure material store works with shared preferences`() = runBlocking {
        val materialStore = ActivationLocalStores.secureMaterialStore(context, InMemorySecretBox())
        materialStore.put(LoginMaterial("handle-robolectric", Long.MAX_VALUE), "secret")
        assertEquals("secret", materialStore.get("handle-robolectric"))
        materialStore.clearAll()
    }
}
