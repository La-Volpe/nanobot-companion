package de.arjmandi.nanobot.companion.architecture

import java.io.File

/**
 * One source file as the layering guard sees it: which source set it belongs to, which
 * package it declares, and which imports it carries.
 */
internal data class SourceFile(
    val path: String,
    val sourceSet: String,
    val packageName: String,
    val imports: List<String>,
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
     */
    fun layerViolations(file: SourceFile): List<String> {
        if (!file.sourceSet.isMainSourceSet()) return emptyList()
        val layer = layeringOf(file.packageName) ?: return emptyList()
        val allowed = ALLOWED_IMPORTS.getValue(layer)
        return file.imports.mapNotNull { name ->
            val imported = layeringOf(name) ?: return@mapNotNull null
            if (imported in allowed || imported == layer) null else "$layer -> $imported"
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
