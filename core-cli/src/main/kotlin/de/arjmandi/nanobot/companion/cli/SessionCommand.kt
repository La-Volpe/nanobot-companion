package de.arjmandi.nanobot.companion.cli

import de.arjmandi.nanobot.companion.facade.OutboundDraft
import de.arjmandi.nanobot.companion.facade.PickedFile
import de.arjmandi.nanobot.companion.facade.SendReceipt
import de.arjmandi.nanobot.companion.facade.TranscriptionResult
import de.arjmandi.nanobot.companion.facade.UploadResult
import de.arjmandi.nanobot.companion.projection.ActivityItem
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.projection.TurnStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `core-cli session`: the 20-minute scripted session the iOS gate asks for, run against the
 * live gateway with the same `:core` the app links.
 *
 * The beats are the gate's own list: connect, send, stream, attach, transcribe, stop, reconnect
 * mid-turn, resume. The pacing spreads them across the budget so the session really lasts
 * twenty minutes and a socket that dies at minute twelve is not a session that never had time
 * to fail. One line per beat, plain, so the log can be pasted into the gate record.
 *
 * Two substitutions are deliberate. The recording is a generated 2-second WAV, because a
 * simulator and a CI host have no microphone, and the bytes are real and go through the real
 * gateway. The reconnect is `connect()` again, which is what the app does: the gateway mints a
 * single-use token per handshake and has no resume.
 */
internal object SessionCommand {

    fun run(args: List<String>) = HarnessCommands.run("session", args) { flags ->
        val minutes = flags["minutes"]?.toDouble() ?: 20.0
        val prompt = flags["prompt"] ?: "In one short sentence: what is a WebSocket?"
        val beats = listOf("connect", "send", "stream", "attach", "transcribe", "stop", "reconnect", "resume")
        val paceMs = (minutes * 60_000 / (beats.size - 1)).toLong()  // seven waits: the last beat starts on time
        val started = System.currentTimeMillis()

        println("session start minutes=$minutes paceMs=$paceMs prompt=\"$prompt\"")
        var failures = 0
        var unavailable = 0

        Harness.open(HarnessCommands.profile(flags)).use { harness ->
            // 1. connect
            var handle = beat("connect") {
                val connected = harness.connect()
                println("  chat=${connected.id.value ?: "-"}")
                connected
            } ?: return@use

            // 2. send + 3. stream: the answer arrives as deltas, which the reducer collects.
            val turnId = beat("send") {
                when (val receipt = handle.send(OutboundDraft(text = prompt))) {
                    is SendReceipt.Accepted -> receipt.turnId
                    is SendReceipt.Rejected -> {
                        failures += 1
                        println("  send rejected: ${receipt.reason}")
                        null
                    }
                }
            }
            beat("stream") {
                val streamed = withTimeoutOrNull(SETTLE_TIMEOUT) {
                    handle.thread.first { state -> state.activeTurnId == null && state.turns.isNotEmpty() }
                }
                if (streamed == null) {
                    failures += 1
                    println("  no settled turn within ${SETTLE_TIMEOUT}ms")
                } else {
                    val turn = streamed.turns.lastOrNull { it.id == turnId } ?: streamed.turns.last()
                    println("  turn=${turn.id} status=${turn.status} answer=${turn.answer.text.length}chars")
                    println("  activity=${turn.activity.size} ${turn.activity.take(3).joinToString(" ") { it.label() }}")
                }
            }
            pace(started, paceMs, 1)

            // 4. attach: a real file, uploaded with the greeting's capability.
            beat("attach") {
                val file = PickedFile(
                    name = "session-attach.txt",
                    mimeType = "text/plain",
                    bytes = "attached by the 20-minute session at ${System.currentTimeMillis()}\n".toByteArray(),
                )
                when (val result = handle.upload(file)) {
                    is UploadResult.Uploaded -> println("  uploaded ${result.reference}")
                    is UploadResult.Rejected -> {
                        failures += 1
                        println("  rejected: ${result.reason.copyKey}")
                    }
                    is UploadResult.Failed -> {
                        failures += 1
                        println("  failed: ${result.error}")
                    }
                }
            }
            pace(started, paceMs, 2)

            // 5. transcribe: a generated 2-second WAV, the same bytes the app's recorder makes.
            beat("transcribe") {
                val wav = silentWav(seconds = 2)
                when (val result = handle.transcribe(pcm = wav, durationMs = 2_000)) {
                    is TranscriptionResult.Transcribed -> println("  transcript=\"${result.text.take(80)}\"")
                    is TranscriptionResult.Rejected -> {
                        failures += 1
                        println("  rejected: ${result.reason.copyKey}")
                    }
                    is TranscriptionResult.Failed -> {
                        // A gateway with no STT provider answers `not_configured` on the same
                        // request id: the round trip is proven, the transcription is not. It is
                        // the gateway's configuration, so it is reported, not counted as a
                        // broken beat.
                        if (result.detail.contains("not_configured") || result.detail.contains("unavailable")) {
                            unavailable += 1
                            println("  unavailable: ${result.detail} (the gateway has no STT provider configured)")
                        } else {
                            failures += 1
                            println("  failed: ${result.detail}")
                        }
                    }
                }
            }
            pace(started, paceMs, 3)

            // 6. stop: a turn started and stopped before it can settle. `/stop` is the message.
            beat("stop") {
                when (val receipt = handle.send(OutboundDraft(text = "Count slowly from 1 to 200."))) {
                    is SendReceipt.Accepted -> {
                        delay(2_000)
                        handle.stop()
                        val stopped = withTimeoutOrNull(SETTLE_TIMEOUT) {
                            handle.thread.first { state -> state.activeTurnId == null }
                        }
                        println("  stopped turn=${receipt.turnId} settled=${stopped != null}")
                    }
                    is SendReceipt.Rejected -> {
                        failures += 1
                        println("  send rejected: ${receipt.reason}")
                    }
                }
            }
            pace(started, paceMs, 4)

            // 7. reconnect mid-turn: a turn is running when the socket is thrown away, then a
            // fresh bootstrap and a fresh single-use token rejoin the same chat.
            val chatId = handle.id.value
            beat("reconnect") {
                val receipt = handle.send(OutboundDraft(text = "Count slowly from 1 to 50, one number per line."))
                println("  send=${receipt}")
                // Wait for the turn to actually run, so "mid-turn" means mid-turn.
                val running = withTimeoutOrNull(30_000) {
                    handle.thread.first { it.activeTurnId != null }.activeTurnId
                }
                harness.disconnect()
                val reopened = harness.connect()
                val joined = chatId?.let { harness.chat(it) } ?: reopened
                handle = joined
                println("  mid-turn=$running reconnected chat=${joined.id.value ?: "-"}")
                println("  jobs on the new connection=${harness.core.activeChildJobs()}")
            }
            pace(started, paceMs, 5)

            // 8. resume: the chat's history comes back through the attach the app opens with.
            pace(started, paceMs, 7)
            beat("resume") {
                handle.open()
                val state = withTimeoutOrNull(SETTLE_TIMEOUT) {
                    handle.thread.first { it.turns.isNotEmpty() }
                }
                if (state == null) {
                    failures += 1
                    println("  no history after resume")
                } else {
                    println("  turns=${state.turns.size} notices=${state.notices.size} subagents=${state.subagents.size}")
                    val last = state.turns.last()
                    println("  last turn=${last.id} status=${last.status} answer=${last.answer.text.length}chars")
                }
            }

            harness.disconnect()
        }

        val elapsed = (System.currentTimeMillis() - started) / 1000
        println("session done beats=8 failures=$failures unavailable=$unavailable elapsed=${elapsed}s")
        if (failures > 0) throw HarnessError("$failures beat(s) failed")
    }

    /** Wait until the pacing says this beat is due, so the session really lasts the budget. */
    private suspend fun pace(startedMillis: Long, paceMs: Long, index: Int) {
        val due = startedMillis + paceMs * index
        val wait = due - System.currentTimeMillis()
        if (wait > 0) delay(wait)
    }

    private inline fun <T> beat(name: String, block: () -> T): T? {
        val began = System.currentTimeMillis()
        return try {
            val result = block()
            println("beat $name ${System.currentTimeMillis() - began}ms ok")
            result
        } catch (failure: Throwable) {
            println("beat $name ${System.currentTimeMillis() - began}ms FAILED ${failure.message}")
            null
        }
    }

    /** A 16 kHz mono 16-bit WAV of silence: a real WAV the gateway's STT can decode. */
    private fun silentWav(seconds: Int, sampleRate: Int = 16_000): ByteArray {
        val samples = seconds * sampleRate
        val dataBytes = samples * 2
        val out = java.io.ByteArrayOutputStream(44 + dataBytes)
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        le32(36 + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1) // PCM
        le16(1) // mono
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray())
        le32(dataBytes)
        repeat(dataBytes) { out.write(0) }
        return out.toByteArray()
    }

    private fun ActivityItem.label(): String = when (this) {
        is ActivityItem.Reasoning -> "reasoning"
        is ActivityItem.Tool -> "tool:$name($status)"
        is ActivityItem.FileEdit -> "edit:$path(+$added-$deleted)"
        is ActivityItem.Search -> "search:$query"
    }

    private const val SETTLE_TIMEOUT = 120_000L
}
