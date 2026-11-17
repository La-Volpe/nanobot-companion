package de.arjmandi.nanobot.companion.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The layering is worth only what a test enforces: arrows point one way,
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
    fun ruleTableRejectsAnOffendingImport() {        val wireImportingFacade = SourceFile(
            path = "core/src/commonMain/.../wire/Bad.kt",
            sourceSet = "commonMain",
            packageName = "de.arjmandi.nanobot.companion.wire",
            imports = listOf("de.arjmandi.nanobot.companion.facade.NanobotCore"),
            body = "",
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

    @Test
    fun fullyQualifiedReferenceWithoutAnImportIsAViolation() {
        // The hole this closes: writing the type out in full needs no import line, so
        // an import-only scan let `wire` reach `projection` unnoticed.
        val wireUsingProjection = SourceFile(
            path = "core/src/commonMain/.../wire/Qualified.kt",
            sourceSet = "commonMain",
            packageName = "de.arjmandi.nanobot.companion.wire",
            imports = listOf("kotlinx.serialization.json.JsonObject"),
            body = """
                internal fun state(): Any = de.arjmandi.nanobot.companion.projection.ThreadState.EMPTY
            """.trimIndent(),
        )
        val wireUsingItself = wireUsingProjection.copy(body = "de.arjmandi.nanobot.companion.wire.Frame")

        assertEquals(
            listOf("wire -> projection (fully qualified)"),
            Architecture.layerViolations(wireUsingProjection),
        )
        assertTrue(
            Architecture.layerViolations(wireUsingItself).isEmpty(),
            "naming its own layer in full is not a violation",
        )
    }

    @Test
    fun qualifiedReferenceInACommentOrStringIsNotAViolation() {
        val quotes = "\"\"\""
        val documented = SourceFile(
            path = "core/src/commonMain/.../wire/Documented.kt",
            sourceSet = "commonMain",
            packageName = "de.arjmandi.nanobot.companion.wire",
            imports = emptyList(),
            body = listOf(
                "// plan.md: de.arjmandi.nanobot.companion.facade.NanobotCore owns this.",
                "/* and the engine, de.arjmandi.nanobot.companion.engine.TurnEngine, runs it */",
                "internal val note: String = \"see de.arjmandi.nanobot.companion.engine.TurnEngine\"",
                "internal val raw: String = " + quotes +
                    "de.arjmandi.nanobot.companion.projection.ThreadState" + quotes,
            ).joinToString("\n"),
        )

        assertTrue(
            Architecture.layerViolations(documented).isEmpty(),
            "prose about another layer is not a dependency on it: ${Architecture.layerViolations(documented)}",
        )
    }
}
