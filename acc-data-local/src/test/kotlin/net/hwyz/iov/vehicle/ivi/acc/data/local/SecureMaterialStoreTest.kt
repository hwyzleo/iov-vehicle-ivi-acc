package net.hwyz.iov.vehicle.ivi.acc.data.local

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.data.local.security.InMemorySecretBox
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
        store.putSealed("handle-1", "short-lived-secret", 2_000L)
        assertEquals("short-lived-secret", store.sealed("handle-1"))
        // The plain value never reaches the underlying store.
        assertTrue(keyValueStore.get("material.handle-1")?.contains("short-lived-secret") == false)
    }

    @Test
    fun `expired material is removed and never returned`() = runTest {
        store.putSealed("handle-2", "secret", 1_500L)
        now = 2_000L
        assertNull(store.sealed("handle-2"))
        assertNull(keyValueStore.get("material.handle-2"))
    }

    @Test
    fun `clear removes a single handle and clearAll removes everything`() = runTest {
        store.putSealed("handle-3", "a", 5_000L)
        store.putSealed("handle-4", "b", 5_000L)
        store.clear("handle-3")
        assertNull(store.sealed("handle-3"))
        assertEquals("b", store.sealed("handle-4"))

        store.clearAll()
        assertNull(store.sealed("handle-4"))
    }

    @Test
    fun `unknown or malformed entries are dropped`() = runTest {
        keyValueStore.put("material.handle-5", "not-a-number:sealed")
        assertNull(store.sealed("handle-5"))
        keyValueStore.put("material.handle-6", "garbage")
        assertNull(store.sealed("handle-6"))
    }
}
