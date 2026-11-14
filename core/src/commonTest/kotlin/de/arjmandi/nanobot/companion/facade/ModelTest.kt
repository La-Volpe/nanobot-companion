package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.platform.CoreDispatchers
import de.arjmandi.nanobot.companion.platform.FakeAppInfo
import de.arjmandi.nanobot.companion.platform.FakeClock
import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.InMemorySecureStore
import de.arjmandi.nanobot.companion.projection.Notice
import de.arjmandi.nanobot.companion.transport.BootstrapOutcome
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.FakeSocket
import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Presets are a chat command, not a frame (plan.md C6.2, §0): `/model <preset>` goes out as a
 * message and `turn_model_updated` is the answer. The composer therefore learns the model
 * from the gateway, and a fallback has to reach the user as a notice, not silence.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelTest {

    private val chat = "websocket:a"
    private suspend fun TestScope.core(socket: FakeSocket): NanobotCore {
        val text = """
            {"token":"tok","ws_url":"ws://127.0.0.1:18765/","ws_path":"/","expires_in":300,
             "model_name":"deepseek-v4.1-flash:cloud",
             "terminal":{"webui":{"min_protocol":1,"max_protocol":1,"capabilities":["webui.core.v1"]}}}
        """.trimIndent()
        val payload = assertIs<BootstrapResult.Parsed>(BootstrapParser.parse(text)).payload
        val deps = CoreDeps(
            clock = FakeClock(startMs = 1_000),
            secureStore = InMemorySecureStore(),
            files = InMemoryFileStore(),
            appInfo = FakeAppInfo(),
            dispatchers = CoreDispatchers(
                compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                main = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            ),
        )
        val core = NanobotCore(
            profile = GatewayProfile(url = "http://127.0.0.1:18765", secret = "s3cr3t"),
            deps = deps,
            attempt = ConnectionAttempt({ BootstrapOutcome.Ready(payload) }, { socket }),
            http = null,
        )
        socket.push("""{"event":"ready","chat_id":"$chat","client_id":"companion"}""")
        assertIs<ConnectResult.Connected>(core.connect())
        runCurrent()
        socket.push("""{"event":"attached","chat_id":"$chat"}""")
        runCurrent()
        socket.sent.clear()
        return core
    }

    @Test
    fun setPresetSendsSlashModel() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        handle.setModelPreset("deepseek-v4.1-flash:cloud")
        runCurrent()

        val sent = socket.sent.single()
        assertEquals("message", (Json.parseToJsonElement(sent) as JsonObject)["type"]!!.jsonPrimitive.content)
        assertTrue(
            sent.contains("\"content\":\"/model deepseek-v4.1-flash:cloud\""),
            "a preset switch is the /model command (builtin.py), not a frame of its own",
        )
    }

    @Test
    fun turnModelUpdatedReflectsInComposer() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        socket.push(
            """{"event":"turn_model_updated","chat_id":"$chat","model_name":"deepseek-v4.1-flash",
                "model_preset":"deepseek","context_window_tokens":65536}""".trimIndent(),
        )
        advanceUntilIdle()

        val composer = handle.composer.value
        assertEquals("deepseek", composer.modelPreset, "the chip shows the preset the gateway says answered")
        assertEquals("deepseek-v4.1-flash", composer.modelName)
        assertEquals(65_536, composer.contextWindowTokens, "the usage ring's denominator comes from here (C6.7)")
    }

    @Test
    fun fallbackFlagSurfacesNotice() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        socket.push(
            """{"event":"turn_model_updated","chat_id":"$chat","model_name":"cheap-fallback",
                "model_preset":"cheap","fallback":true}""".trimIndent(),
        )
        advanceUntilIdle()

        val notice = assertIs<Notice.PresetFallback>(handle.thread.value.notices.single())
        assertEquals("cheap-fallback", notice.modelName, "the user is told which model actually answered")
    }

    @Test
    fun theRuntimeModelIsNotAPresetSwitch() = runTest {
        val socket = FakeSocket()
        val core = core(socket)
        val handle = core.chat(chat)

        socket.push("""{"event":"runtime_model_updated","chat_id":"$chat","model_name":"other","model_preset":"other"}""")
        advanceUntilIdle()

        assertEquals(null, handle.composer.value.modelPreset, "a changed default is not this chat's preset")
    }
}
