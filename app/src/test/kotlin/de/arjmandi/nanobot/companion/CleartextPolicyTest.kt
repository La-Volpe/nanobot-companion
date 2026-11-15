package de.arjmandi.nanobot.companion

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cleartext is a dev-gateway affordance, not a property of the app (T27). The design's own hint is
 * a gateway on the home network over `http`, and Android refuses that by default since targetSdk
 * 28 — so the debug build needs the exception. A release build must not: an `http://` address a
 * user types, or one a redirect leads to, would then be sent in the clear.
 *
 * This reads the two files a build actually merges rather than the merged output of the build
 * running the test, because the merged output is always the debug one.
 */
class CleartextPolicyTest {

    private val module = File("src/main/res/xml/network_security_config.xml")
    private val debug = File("src/debug/res/xml/network_security_config.xml")

    private fun policy(file: File): String {
        assertTrue(file.isFile, "expected the network policy at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun aReleaseBuildCannotSpeakCleartextToAnyHost() {
        val text = policy(module)

        assertTrue(
            Regex("""<base-config[^>]*cleartextTrafficPermitted="false"""").containsMatchIn(text),
            "the release policy denies cleartext at the base config: $text",
        )
        assertFalse(
            Regex("""cleartextTrafficPermitted="true"""").containsMatchIn(text),
            "and nothing in the release policy hands it back, by host or by base: $text",
        )
    }

    @Test
    fun aDebugBuildKeepsTheDevGatewayReachable() {
        val text = policy(debug)

        assertTrue(
            Regex("""<base-config[^>]*cleartextTrafficPermitted="true"""").containsMatchIn(text),
            "a debug build talks to the emulator's host and to a LAN gateway over http: $text",
        )
    }
}
