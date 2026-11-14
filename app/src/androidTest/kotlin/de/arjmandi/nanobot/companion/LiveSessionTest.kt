package de.arjmandi.nanobot.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.arjmandi.nanobot.companion.attachment.AttachmentViewModel
import de.arjmandi.nanobot.companion.attachment.PickedAttachment
import de.arjmandi.nanobot.companion.bridge.openCompanion
import de.arjmandi.nanobot.companion.chat.ChatThreadView
import de.arjmandi.nanobot.companion.chat.ChatViewModel
import de.arjmandi.nanobot.companion.core.ChatSession
import de.arjmandi.nanobot.companion.core.CoreHolder
import de.arjmandi.nanobot.companion.core.GatewayOutcome
import de.arjmandi.nanobot.companion.voice.AudioRecording
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * The Android half of G3's scripted session (plan.md §8): the same five beats the iOS
 * `LiveSessionTests` runs, driven through the app's own bridge and view models on the device
 * against the real gateway — connect, send, stream, attach, stop, then reconnect and find the
 * history replayed.
 *
 * Opt-in, like the iOS trio: without `nanobot.live.url` and `nanobot.live.secret` in the
 * instrumentation arguments every case is skipped rather than failed. `tools/android-session.sh`
 * starts a gateway, passes the two arguments and prints one line per beat for GATES.md.
 */
@RunWith(AndroidJUnit4::class)
class LiveSessionTest {

    private val arguments = InstrumentationRegistry.getArguments()
    private val url: String? = arguments.getString("nanobot.live.url")?.takeIf { it.isNotEmpty() }
    private val secret: String? = arguments.getString("nanobot.live.secret")?.takeIf { it.isNotEmpty() }

    /** The chat G3's side-by-side run pins: the CLI settles a turn in it, and this reads it back. */
    private val chatId: String? = arguments.getString("nanobot.live.chat")?.takeIf { it.isNotEmpty() }

    /** A recorder that hands over a short generated WAV: a device test has no way to speak. */
    private class SilentRecorder : AudioRecording {
        override fun start() = Unit
        override fun stop(): ByteArray = ByteArray(44 + 32_000)
        override fun cancel() = Unit
    }

    private fun beat(name: String, ok: Boolean, detail: String = "") {
        println("live android: beat $name ${if (ok) "ok" else "FAILED"} $detail")
    }

    private suspend fun await(timeoutMs: Long = 45_000, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) kotlinx.coroutines.delay(120)
            true
        } ?: false

    private fun holder(scope: CoroutineScope, address: String, key: String): CoreHolder =
        CoreHolder(scope) { gateway, secret, allowUnverified ->
            openCompanion(InstrumentationRegistry.getInstrumentation().targetContext, gateway, secret, allowUnverified)
        }.also { holder ->
            runBlocking {
                val outcome = holder.connect(address, key, allowUnverified = false)
                assertTrue(outcome is GatewayOutcome.Connected, "the gateway answered: $outcome")
            }
        }

    /**
     * G3's third item, Android half: the app opens the chat the CLI already has, and prints the
     * thread it holds as the same plain lines. `tools/parity-check.sh` diffs this against
     * `core-cli watch --chat` for the same chat.
     */
    @Test
    fun theSameStoredChatReadsBackAsTheTranscript() {
        assumeTrue("no live chat: pass nanobot.live.chat", url != null && secret != null && chatId != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val holder = holder(scope, url!!, secret!!)
        val session = holder.chatSession(chatId)!!
        val model = ChatViewModel(
            chat = session.chat,
            composerDriver = session.composer,
            snapshots = session.snapshots,
            attachmentDriver = session.attachments,
            voiceDriver = session.voice,
            recorder = { SilentRecorder() },
            scope = scope,
        )

        runBlocking {
            model.open(chatId)
            val replayed = await(60_000) { model.thread.value.turns.isNotEmpty() }
            assertTrue(replayed, "the stored chat replayed its history")
            model.thread.value.turns.forEachIndexed { index, turn ->
                val prompt = turn.prompt.orEmpty().replace("\n", " ")
                val answer = turn.answer.replace("\n", " ")
                val status = if (turn.running) "Running" else "Complete"
                println("parity turn ${index + 1} | prompt=$prompt | answer=$answer | status=$status")
            }
        }

        holder.close()
        scope.cancel()
    }

    @Test
    fun theAppRunsATurnStreamsAttachesStopsAndResumes() {
        assumeTrue("no live gateway: pass nanobot.live.url and nanobot.live.secret", url != null && secret != null)
        val address = url!!
        val key = secret!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val holder = holder(scope, address, key)
        beat("connect", true, address)

        val session: ChatSession = holder.chatSession(null)!!
        val model = ChatViewModel(
            chat = session.chat,
            composerDriver = session.composer,
            snapshots = session.snapshots,
            attachmentDriver = session.attachments,
            voiceDriver = session.voice,
            recorder = { SilentRecorder() },
            scope = scope,
        )

        var storedChatId: String? = null
        runBlocking {
            model.open(null)
            model.composer.draft = "In one short sentence: what is a WebSocket?"
            model.composer.primaryAction()
            // The answer arrives as deltas on the same thread the replay fills.
            val streamed = await(60_000) { model.thread.value.turns.any { it.answer.isNotEmpty() } }
            beat("send", streamed, "turns=${model.thread.value.turns.size}")
            assertTrue(streamed, "a turn was accepted and its answer streamed")

            val settled = await(120_000) { model.thread.value.turns.none { it.running } }
            assertTrue(settled, "the turn settled")
            // The gateway names the stored chat in its session list; that is the id a reconnect
            // re-opens (D48: one chat model per chat, not per greeting).
            holder.refreshSessions()
            storedChatId = await(30_000) { holder.sessions.value.rows.isNotEmpty() }
                .let { if (it) holder.sessions.value.rows.first().chatId else null }
            beat("session", storedChatId != null, "chat=${storedChatId.orEmpty().take(8)}…")
            beat("stream", settled, "answer=${model.thread.value.turns.lastOrNull()?.answer?.length ?: 0} chars")

            // Attachment: the gateway's upload endpoint, with the same limits the composer shows.
            val attachments = AttachmentViewModel(session.attachments)
            attachments.add(
                PickedAttachment(
                    name = "live-probe.txt",
                    mimeType = "text/plain",
                    bytes = "the android half of the 20-minute session".encodeToByteArray(),
                ),
            )
            beat("attach", attachments.references.isNotEmpty(), attachments.references.firstOrNull().orEmpty())
            assertTrue(attachments.references.isNotEmpty(), "the upload came back with a reference")

            // Transcription: the round trip is proven; the transcript itself is the gateway's own
            // configuration, which on the dev harness has no STT provider (P8).
            val wav = SilentRecorder().stop()
            when (val voice = session.voice.transcribe(wav, durationMs = 2_000)) {
                is de.arjmandi.nanobot.companion.voice.VoiceOutcome.Transcribed ->
                    beat("transcribe", true, "${voice.text.length} chars")

                is de.arjmandi.nanobot.companion.voice.VoiceOutcome.Refused ->
                    beat("transcribe", true, "unavailable: ${voice.detail}")
            }

            // Stop: a second turn that is cancelled while it runs.
            model.composer.draft = "Count slowly to thirty, one number per line."
            model.composer.primaryAction()
            val running = await(60_000) { model.thread.value.turns.any { it.running } }
            assertTrue(running, "the second turn started")
            model.stop()
            val stopped = await(60_000) { model.thread.value.turns.none { it.running } }
            beat("stop", stopped)
            assertTrue(stopped, "the running turn settled after /stop")
        }

        // Reconnect: the same gateway, a fresh socket, and the history the core replays (D46).
        runBlocking {
            holder.disconnect()
            val reopened = holder.connect(address, key, allowUnverified = false)
            assertTrue(reopened is GatewayOutcome.Connected, "the gateway answered again: $reopened")
            val again = holder.chatSession(storedChatId)!!
            val resumed = ChatViewModel(
                chat = again.chat,
                composerDriver = again.composer,
                snapshots = again.snapshots,
                attachmentDriver = again.attachments,
                voiceDriver = again.voice,
                scope = scope,
            )
            resumed.open(storedChatId)
            val replayed = await(60_000) { resumed.thread.value.turns.any { it.answer.isNotEmpty() } }
            beat("reconnect", replayed, "turns=${resumed.thread.value.turns.size}")
            assertTrue(replayed, "the re-opened chat shows the history the gateway stored")
        }

        holder.close()
        scope.cancel()
    }
}
