package de.arjmandi.nanobot.companion.architecture

import java.io.File

/**
 * One source file as the layering guard sees it: which source set it belongs to, which
 * package it declares, which imports it carries, and the code between them. The body
 * matters because an import is not the only way to name another layer: a fully
 * qualified reference reaches it without an import line at all.
 */
internal data class SourceFile(
    val path: String,
    val sourceSet: String,
    val packageName: String,
    val imports: List<String>,
    val body: String,
)

/**
 * plan.md §2 read as data. `facade → engine → projection|transport → wire`, with
 * `platform` depended on only by `transport` and `engine`. A new layer is one line in
 * [ALLOWED_IMPORTS]; anything else falls foul of the test, including a file with no
 * package.
 */
internal object Architecture {

    private const val ROOT = "de.arjmandi.nanobot.companion"

    private val ALLOWED_IMPORTS: Map<String, Set<String>> = mapOf(
        "wire" to setOf("wire"),
        "projection" to setOf("wire", "projection"),
        "transport" to setOf("wire", "transport", "platform"),
        "engine" to setOf("wire", "projection", "transport", "engine", "platform"),
        "facade" to setOf("wire", "projection", "transport", "engine", "facade", "platform"),
        "platform" to setOf("platform"),
    )

    /**
     * Imports that must never appear in shared code: the two UI toolkits, Compose, the
     * Android platform and JVM-only APIs. `platform.*` cinterop is how the native side
     * is written, and it belongs in the platform source sets, not in `commonMain`.
     */
    private val PLATFORM_AND_UI_PREFIXES: List<String> = listOf(
        "androidx.", "android.", "com.google.android.", "platform.UIKit", "platform.SwiftUI",
        "platform.AppKit", "platform.Foundation", "platform.Security", "platform.posix",
        "platform.darwin", "kotlinx.cinterop", "java.", "javax.",
    )

    fun sources(): List<SourceFile> = sourceRoots()
        .flatMap { (sourceSet, dir) ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { file -> parse(file, sourceSet) }
        }
        .sortedBy { it.path }
        .toList()

    fun layeringOf(packageName: String): String? {
        val relative = packageName.removePrefix("$ROOT.")
        if (!packageName.startsWith(ROOT)) return null
        return relative.substringBefore('.').takeIf { it in ALLOWED_IMPORTS }
    }

    /**
     * Tests are exempt: a test is allowed to compose two layers to prove they fit, and it
     * ships in no artifact. Production source sets are the ones the rule protects.
     *
     * Two ways to name another layer are checked: the import line, and the fully qualified
     * reference in the code. The second is the one an import-only scan misses.
     */
    fun layerViolations(file: SourceFile): List<String> {
        if (!file.sourceSet.isMainSourceSet()) return emptyList()
        val layer = layeringOf(file.packageName) ?: return emptyList()
        val allowed = ALLOWED_IMPORTS.getValue(layer)
        val imported = file.imports.mapNotNull { name ->
            val target = layeringOf(name) ?: return@mapNotNull null
            if (target in allowed || target == layer) null else "$layer -> $target"
        }
        val qualified = qualifiedLayersOf(file.body)
            .filterNot { it in allowed || it == layer }
            .map { "$layer -> $it (fully qualified)" }
        return imported + qualified
    }

    /**
     * The layers a file reaches by naming them in full, e.g.
     * `de.arjmandi.nanobot.companion.projection.ThreadState::class`. Comments and string
     * literals are removed first: prose about a layer is not a dependency on it, and a
     * guard that fires on a doc comment would be turned off.
     */
    fun qualifiedLayersOf(body: String): List<String> {
        val pattern = Regex(Regex.escape(ROOT) + "\\.([A-Za-z_][A-Za-z0-9_]*)")
        return pattern.findAll(codeOnly(body))
            .mapNotNull { match -> layeringOf("$ROOT.${match.groupValues[1]}") }
            .distinct()
            .toList()
    }

    /** The file with comments and string literals blanked, so only code is scanned. */
    private fun codeOnly(body: String): String = buildString {
        var index = 0
        while (index < body.length) {
            val char = body[index]
            when {
                body.startsWith("//", index) -> {
                    index = body.indexOf('\n', index).takeIf { it >= 0 } ?: body.length
                }
                body.startsWith("/*", index) -> {
                    index = body.indexOf("*/", index).takeIf { it >= 0 }?.plus(2) ?: body.length
                }
                body.startsWith("\"\"\"", index) -> {
                    index = body.indexOf("\"\"\"", index + 3).takeIf { it >= 0 }?.plus(3) ?: body.length
                }
                char == '"' -> {
                    index += 1
                    while (index < body.length && body[index] != '"' && body[index] != '\n') {
                        index += if (body[index] == '\\') 2 else 1
                    }
                    index += 1
                }
                else -> {
                    append(char)
                    index += 1
                }
            }
        }
    }

    private fun String.isMainSourceSet(): Boolean = !endsWith("Test")

    fun forbiddenPlatformImports(file: SourceFile): List<String> =
        file.imports.filter { name -> PLATFORM_AND_UI_PREFIXES.any(name::startsWith) }

    private fun parse(file: File, sourceSet: String): SourceFile {
        val lines = file.readLines()
        val packageName = lines.firstOrNull { it.startsWith("package ") }
            ?.removePrefix("package ")
            ?.trim()
            ?: ""
        val imports = lines.filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim().removePrefix("static ").substringBefore(" as ") }
        return SourceFile(
            path = file.path.substringAfter("/core/src/"),
            sourceSet = sourceSet,
            packageName = packageName,
            imports = imports,
            // Declarations are already covered by [imports]; the body is what is left.
            body = lines.filterNot { it.startsWith("import ") || it.startsWith("package ") }
                .joinToString("\n"),
        )
    }

    /** Source-set name → directory, discovered by walking up from the test's working directory. */
    private fun sourceRoots(): List<Pair<String, File>> {
        val src = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "core/src") }
            .firstOrNull { it.isDirectory }
            ?: error("core/src not found above ${System.getProperty("user.dir")}")
        return src.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { sourceSet -> File(sourceSet, "kotlin").takeIf { it.isDirectory }?.let { sourceSet.name to it } }
            .sortedBy { it.first }
    }
}
