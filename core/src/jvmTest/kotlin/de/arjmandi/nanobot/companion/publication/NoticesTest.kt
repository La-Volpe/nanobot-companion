package de.arjmandi.nanobot.companion.publication

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The repository ships a licence and a consolidated notices file, and both app bundles carry a
 * copy of the notices so the text travels with the binary. Three copies of one file drift the
 * moment one of them is edited alone, so they are compared here instead of by eye.
 *
 * `LICENSE` and `THIRD_PARTY_NOTICES.txt` are deliberately not `.md`: `.gitignore` ignores
 * `*.md`, and a filename nobody tracks is a licence nobody ships.
 */
class NoticesTest {

    private val root: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "THIRD_PARTY_NOTICES.txt").isFile && File(it, "LICENSE").isFile }
            ?: error("no LICENSE/THIRD_PARTY_NOTICES.txt above ${System.getProperty("user.dir")}")
    }

    private fun file(path: String): File = File(root, path)

    private val notices: String by lazy { file("THIRD_PARTY_NOTICES.txt").readText() }

    @Test
    fun licenceIsMitAndNamesTheCopyrightHolder() {
        val licence = file("LICENSE").readText()

        assertTrue(licence.startsWith("MIT License"), "the root licence is MIT as decided (T51)")
        assertTrue(licence.contains("Copyright (c) 2026 Javád Arjmandi"), "the holder is the owner, no email address")
        assertTrue(licence.contains("Permission is hereby granted, free of charge"), "the MIT grant is intact")
        assertTrue(licence.contains("THE SOFTWARE IS PROVIDED \"AS IS\""), "the MIT warranty disclaimer is intact")
    }

    @Test
    fun noticesCarryUpstreamNanoBot() {
        assertTrue(notices.contains("2025-present Xubin Ren and the nanobot contributors"), "nanobot's MIT line, verbatim")
        assertTrue(notices.contains("day/night"), "the launcher mark is nanobot's, and the notices say so")
        assertTrue(notices.contains("Permission is hereby granted, free of charge"), "nanobot's MIT grant is reproduced")
    }

    /** The 17 WebUI glyphs are Lucide 0.469.0; pele's bundled icon is a different Lucide version. */
    @Test
    fun noticesCarryLucideAndFeatherForTheGeneratedGlyphs() {
        assertTrue(notices.contains("Lucide"), "the glyph set is Lucide's")
        assertTrue(notices.contains("Cole Bemis 2013-2022 as part of Feather"), "the ISC notice carries Feather's portion")
        assertTrue(notices.contains("Lucide Contributors 2022"), "and Lucide's own")
        assertTrue(notices.contains("0.469.0"), "the version the generator actually read")
        assertTrue(notices.contains("1.14.0"), "pele's bundled icon is a *different* version, said plainly")
        assertTrue(notices.contains("ISC"), "Lucide is ISC-licensed")
    }

    @Test
    fun noticesCarryTheFontsAndPele() {
        assertTrue(notices.contains("SIL OPEN FONT LICENSE"), "Open Sans' OFL text is reproduced")
        assertTrue(notices.contains("The Open Sans Project Authors"), "…under its real copyright line")
        assertTrue(notices.contains("Copyright (c) 2026 kepano"), "pele's MIT text")
    }

    @Test
    fun noticesCarryTheApacheRuntimeSetAndSlf4j() {
        assertTrue(notices.contains("Apache License"), "Apache-2.0 text for the Kotlin/Ktor/OkHttp/AndroidX set")
        assertTrue(notices.contains("END OF TERMS AND CONDITIONS"), "the full Apache-2.0 terms, not a pointer")
        for (family in listOf("Kotlin", "kotlinx", "Ktor", "OkHttp", "Okio", "AndroidX")) {
            assertTrue(notices.contains(family), "$family is on the resolved runtime classpath")
        }
        assertTrue(notices.contains("QOS.ch"), "SLF4J's MIT copyright line")
    }

    @Test
    fun readmeLinksTheLicenceAndTheNotices() {
        val readme = file("README.md").readText()

        assertTrue(readme.contains("(LICENSE)"), "README links the root licence")
        assertTrue(
            readme.contains("(THIRD_PARTY_NOTICES.txt)"),
            "README links the consolidated notices, which is where a reader looks for them",
        )
    }

    /**
     * The notices have to travel with the binary, and that claim is only checkable if the copies
     * are the root files. `LICENSE` rides along so the notices' reference to it resolves inside
     * the app.
     */
    @Test
    fun bothBundlesCarryTheSameLicenceAndNotices() {
        val bundles = listOf("app/src/main/assets", "iosApp/iosApp/Resources")
        for (bundle in bundles) {
            for (name in listOf("LICENSE", "THIRD_PARTY_NOTICES.txt")) {
                val shipped = file("$bundle/$name")
                assertTrue(shipped.isFile, "$bundle/$name ships in the app (T51: in both distributions)")
                assertEquals(
                    file(name).readText(),
                    shipped.readText(),
                    "$bundle/$name must be the root file, byte for byte",
                )
            }
        }
    }
}
