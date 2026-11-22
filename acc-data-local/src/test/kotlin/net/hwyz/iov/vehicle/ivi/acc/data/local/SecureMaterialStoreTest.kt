package net.hwyz.iov.vehicle.ivi.acc.data.local

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.data.local.security.InMemorySecretBox
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginMaterial
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureMaterialStoreTest {

    private val keyValueStore = InMemoryKeyValueStore()
    private var now = 1_000L
    private val store = SecureMaterialStoreImpl(
        store = keyValueStore,
        box = InMemorySecretBox(),
        nowEpochMillis = { now },
    )

    @Test
    fun `material is stored sealed and can be read back`() = runTest {
        store.put(LoginMaterial("handle-1", 2_000L), "short-lived-secret")
        assertEquals("short-lived-secret", store.get("handle-1"))
        // The plain value never reaches the underlying store.
        assertTrue(keyValueStore.get("material.handle-1")?.contains("short-lived-secret") == false)
    }

    @Test
    fun `expired material is removed and never returned`() = runTest {
        store.put(LoginMaterial("handle-2", 1_500L), "secret")
        now = 2_000L
        assertNull(store.get("handle-2"))
        assertNull(keyValueStore.get("material.handle-2"))
    }

    @Test
    fun `clear removes a single handle and clearAll removes everything`() = runTest {
        store.put(LoginMaterial("handle-3", 5_000L), "a")
        store.put(LoginMaterial("handle-4", 5_000L), "b")
        store.clear("handle-3")
        assertNull(store.get("handle-3"))
        assertEquals("b", store.get("handle-4"))

        store.clearAll()
        assertNull(store.get("handle-4"))
    }

    @Test
    fun `unknown or malformed entries are dropped`() = runTest {
        keyValueStore.put("material.handle-5", "not-a-number:sealed")
        assertNull(store.get("handle-5"))
        keyValueStore.put("material.handle-6", "garbage")
        assertNull(store.get("handle-6"))
    }
}
