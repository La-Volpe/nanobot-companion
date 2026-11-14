package de.arjmandi.nanobot.companion.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The layering in plan.md §2 is worth only what a test enforces: arrows point one way,
 * and `commonMain` never mentions a UI toolkit or a platform API. The scan reads the
 * source tree, so a new file is covered the moment it is added.
 */
class ArchitectureTest {

    @Test
    fun scannerSeesTheSourceTree() {
        val sources = Architecture.sources()

        assertTrue(sources.isNotEmpty(), "the scan found no Kotlin sources; the guard would pass vacuously")
        assertTrue(sources.any { it.sourceSet == "commonMain" }, "commonMain sources must be scanned")
        // A file whose package the scan could not read would be exempt from the rules.
        assertTrue(
            sources.all { it.packageName.startsWith("de.arjmandi.nanobot.companion") },
            "every core source declares the core package; unparsed: " +
                sources.filterNot { it.packageName.startsWith("de.arjmandi.nanobot.companion") }.map { it.path },
        )
    }

    @Test
    fun commonMainDoesNotImportUi() {
        val offenders = Architecture.sources()
            .filter { it.sourceSet == "commonMain" }
            .flatMap { file -> Architecture.forbiddenPlatformImports(file).map { "${file.path}: $it" } }

        assertTrue(offenders.isEmpty(), "commonMain must stay UI- and platform-free (plan.md D2):\n${offenders.joinToString("\n")}")
    }

    @Test
    fun lowerLayerDoesNotImportHigher() {
        val offenders = Architecture.sources()
            .flatMap { file -> Architecture.layerViolations(file).map { "${file.path}: $it" } }

        assertTrue(offenders.isEmpty(), "layering is one-way (plan.md §2):\n${offenders.joinToString("\n")}")
    }

    @Test
    fun ruleTableRejectsAnOffendingImport() {
        val wireImportingFacade = SourceFile(
            path = "core/src/commonMain/.../wire/Bad.kt",
            sourceSet = "commonMain",
            packageName = "de.arjmandi.nanobot.companion.wire",
            imports = listOf("de.arjmandi.nanobot.companion.facade.NanobotCore"),
        )
        val commonMainUsingCompose = wireImportingFacade.copy(
            imports = listOf("androidx.compose.runtime.Composable"),
        )
        val platformImportingEngine = wireImportingFacade.copy(
            packageName = "de.arjmandi.nanobot.companion.platform",
            imports = listOf("de.arjmandi.nanobot.companion.engine.TurnEngine"),
        )

        assertEquals(listOf("wire -> facade"), Architecture.layerViolations(wireImportingFacade))
        assertEquals(listOf("androidx.compose.runtime.Composable"), Architecture.forbiddenPlatformImports(commonMainUsingCompose))
        assertEquals(listOf("platform -> engine"), Architecture.layerViolations(platformImportingEngine))

        val allowed = wireImportingFacade.copy(
            imports = listOf("de.arjmandi.nanobot.companion.wire.Frame", "kotlinx.serialization.json.Json"),
        )
        assertTrue(Architecture.layerViolations(allowed).isEmpty(), "a legal import is not a violation")
    }
}
