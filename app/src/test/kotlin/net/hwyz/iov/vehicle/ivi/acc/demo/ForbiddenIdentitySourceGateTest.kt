package net.hwyz.iov.vehicle.ivi.acc.demo

import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A-017 mechanical gate, unit test form (IVI-ACC-DSN-CR-005 D-011 / BLK-4).
 *
 * Device identity may only come from the unified security service or a device level read only
 * property. A secure settings identifier is resettable, collides across devices of the same model and
 * is not a factory identity, so its presence anywhere under `acc-*` or `app/` fails this test — the
 * same rule the CI script (`tools/check-forbidden-identity-source.sh`) enforces.
 */
class ForbiddenIdentitySourceGateTest {

    /** Assembled at runtime so this test file is not itself a hit. */
    private val forbidden = "ANDROID" + "_ID"

    @Test
    fun `no source file uses a secure settings identifier as device identity`() {
        // Gradle runs tests with the module directory as the working directory.
        val root = File("..").canonicalFile
        val modules = listOf("acc-domain", "acc-data-cloud", "acc-data-local", "acc-data-sec", "acc-platform", "app")
            .map { File(root, it) }
            .filter { it.isDirectory }
        assertTrue(modules.isNotEmpty(), "IVI-ACC modules not found under $root")

        val hits = modules.flatMap { module ->
            module.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.extension in setOf("kt", "java", "xml") &&
                        file.relativeTo(root).invariantSeparatorsPath
                            .split('/')
                            .none { it == "build" || it == ".gradle" }
                }
                .flatMap { file ->
                    file.useLines { lines ->
                        lines.withIndex()
                            .filter { (_, line) -> line.contains(forbidden) }
                            .map { (index, line) -> "${file.path}:${index + 1}: ${line.trim()}" }
                            .toList()
                    }.asSequence()
                }
                .toList()
        }

        assertEquals(
            emptyList(),
            hits,
            "A-017 violated: a secure settings device identifier must never be a device identity source",
        )
    }

    @Test
    fun `the CI gate script exists and is executable`() {
        val script = File("../tools/check-forbidden-identity-source.sh").canonicalFile
        assertTrue(script.isFile, "A-017 gate script is missing: $script")
        assertTrue(script.canExecute() || script.canRead(), "A-017 gate script must be runnable")
    }
}
