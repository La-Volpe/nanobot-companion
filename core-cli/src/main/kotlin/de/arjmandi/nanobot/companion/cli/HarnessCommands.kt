package de.arjmandi.nanobot.companion.cli

import de.arjmandi.nanobot.companion.facade.ConnectionState
import de.arjmandi.nanobot.companion.facade.OutboundDraft
import de.arjmandi.nanobot.companion.facade.SendReceipt
import de.arjmandi.nanobot.companion.projection.HistoryReplay
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.projection.TurnStatus
import de.arjmandi.nanobot.companion.wire.SessionRow
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * `core-cli connect | sessions | send | watch | soak` (plan.md C8.3). Every command prints
 * plain lines, because two of them are gate instruments: `watch`'s transcript is compared
 * against the WebUI's, and `soak`'s summary is pasted into `GATES.md`.
 */
internal object HarnessCommands {

    fun connect(args: List<String>) = run("connect", args) { flags ->
        Harness.open(profile(flags)).use { harness ->
            val handle = harness.connect()
            val state = harness.core.connection.value
            println("connected: ${harness.profile.url}")
            println("state: ${state.line()}")
            println("chat: ${handle.id.value ?: "-"}")
            harness.core.refreshSessions()
            println("sessions: ${harness.core.sessions.value.rows.size}")
            harness.disconnect()
        }
    }

    fun sessions(args: List<String>) = run("sessions", args) { flags ->
        Harness.open(profile(flags)).use { harness ->
            harness.connect()
            harness.core.refreshSessions()
            for (row in harness.core.sessions.value.rows) println(row.line())
        }
    }

    fun send(args: List<String>) = run("send", args) { flags ->
        val prompt = flags["prompt"] ?: "Reply with exactly the word: pong"
        Harness.open(profile(flags)).use { harness ->
            val handle = if (flags["chat"] == null) harness.connect() else {
                harness.connect()
                harness.chat(flags["chat"]).also { named ->
                    // A named chat has to be opened, not just bound: `chat(id)` only registers a
                    // handle, and the stored turns arrive from the thread page that `open()` reads
                    // (D46). Without this the transcript of an existing chat is empty.
                    named.open()
                }
            }
            // `--media PATH` uploads a real file first and sends its reference, which is the
            // composer's own path (C6.6) and the only way to get real bytes into a chat from
            // this harness. The row the gateway signs back is what a media row draws.
            val media = flags["media"]?.let { path -> upload(handle, File(path)) }
            val draft = OutboundDraft(text = prompt, media = listOfNotNull(media))
            when (val receipt = handle.send(draft)) {
                is SendReceipt.Rejected -> throw HarnessError("send rejected: ${receipt.reason}")
                is SendReceipt.Accepted -> println("sent: turn ${receipt.turnId}")
            }
            val settled = handle.settled(SETTLE_TIMEOUT_MS)
            print(Transcript.header(settled))
            print(Transcript.render(settled))
            for (turn in settled.turns) {
                for (row in turn.prompt?.media.orEmpty() + turn.answer.media) {
                    println("media | ${row.kind} | ${row.name} | ${row.url}")
                }
            }
        }
    }

    /**
     * The parity instrument. `watch` follows one chat and prints the transcript the live
     * socket produced; `--verify` then fetches the WebUI's own thread page for the same chat,
     * reduces it through the same reducer, and diffs the two. The comparison is mechanical:
     * two renderings of one thread, not two scrollbacks.
     */
    fun watch(args: List<String>) = run("watch", args) { flags ->
        val prompts = promptsToSend(flags)
        val verify = flags.containsKey("verify")
        Harness.open(profile(flags)).use { harness ->
            val handle = if (flags["chat"] == null) harness.connect() else {
                harness.connect()
                harness.chat(flags["chat"]).also { named ->
                    // A named chat has to be opened, not just bound: `chat(id)` only registers a
                    // handle, and the stored turns arrive from the thread page that `open()` reads
                    // (D46). Without this the transcript of an existing chat is empty.
                    named.open()
                }
            }
            for (prompt in prompts) {
                when (val receipt = handle.send(OutboundDraft(text = prompt))) {
                    is SendReceipt.Rejected -> throw HarnessError("send rejected: ${receipt.reason}")
                    is SendReceipt.Accepted -> println("# sent turn ${receipt.turnId}")
                }
                handle.settled(SETTLE_TIMEOUT_MS)
            }
            println(Transcript.header(handle.thread.value).trimEnd())
            val live = Transcript.render(handle.thread.value)
            print(live)

            flags["out"]?.let { path -> File(path).writeText(live) }

            if (verify) {
                val chatId = handle.id.value ?: throw HarnessError("no chat to verify")
                val history = harness.thread("websocket:$chatId")
                val replayed = replay(history, chatId)
                val recorded = Transcript.render(replayed)
                flags["history-out"]?.let { path -> File(path).writeText(recorded) }
                if (recorded == live) {
                    println("parity: PASS (${live.lines().size} lines, chat $chatId)")
                } else {
                    println("parity: FAIL (chat $chatId)")
                    diff(live, recorded)
                    exitProcess(1)
                }
            }
            harness.disconnect()
        }
    }

    /**
     * The soak rig (plan.md C8.4): reconnect, send, settle, repeat, for a wall-clock budget.
     * Everything it counts is a gate item: uncaught errors, leaked jobs, sends, reconnects.
     */
    fun soak(args: List<String>) = run("soak", args) { flags ->
        val sends = flags["sends"]?.toInt() ?: 200
        val reconnects = flags["reconnects"]?.toInt() ?: 50
        val minutes = flags["minutes"]?.toDouble() ?: 30.0
        val prompt = flags["prompt"] ?: "Reply with exactly the word: pong"
        val budgetMs = (minutes * 60_000).toLong()
        // The budget is spent across the sends, so 200 sends is a 30-minute run rather than a
        // ten-minute one that stopped early: a soak measures duration as much as throughput.
        val paceMs = if (sends > 0) budgetMs / sends else 0
        val everyNth = if (reconnects > 0) maxOf(1, sends / reconnects) else Int.MAX_VALUE
        val started = System.currentTimeMillis()
        val heapStart = usedHeapMb()

        val uncaught = mutableListOf<String>()
        var sent = 0
        var reconnected = 0
        var failures = 0
        var leaked = 0
        var slowest = 0L
        println(
            "soak start sends=$sends reconnects=$reconnects minutes=$minutes " +
                "paceMs=$paceMs reconnectEvery=$everyNth",
        )

        Harness.open(profile(flags)).use { harness ->
            var chatId: String? = null
            var handle = try {
                harness.connect()
            } catch (failure: Throwable) {
                uncaught += "connect: ${failure.message}"
                null
            }
            chatId = handle?.id?.value

            while (sent < sends && System.currentTimeMillis() - started < budgetMs) {
                val roundStarted = System.currentTimeMillis()

                // A reconnect is a fresh bootstrap and a fresh single-use token, and it rejoins
                // the same chat afterwards: the gateway has no resume (plan.md §0, C3.4).
                if (sent > 0 && sent % everyNth == 0 && reconnected < reconnects) {
                    harness.disconnect()
                    val active = harness.core.activeChildJobs()
                    if (active != 0) {
                        leaked += active
                        println("leak $active jobs after teardown")
                    }
                    reconnected += 1
                    val connectStarted = System.currentTimeMillis()
                    handle = try {
                        harness.connect().let { fresh ->
                            val joined = chatId?.let { harness.chat(it) } ?: fresh
                            chatId = chatId ?: joined.id.value
                            joined
                        }
                    } catch (failure: Throwable) {
                        uncaught += "reconnect: ${failure.message}"
                        failures += 1
                        break
                    }
                    println("reconnect $reconnected ${System.currentTimeMillis() - connectStarted}ms chat=$chatId")
                }

                val current = handle
                if (current == null) {
                    failures += 1
                    break
                }

                val sendStarted = System.currentTimeMillis()
                val outcome = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                    runCatching {
                        when (val receipt = current.send(OutboundDraft(text = prompt))) {
                            is SendReceipt.Rejected -> throw HarnessError("send rejected: ${receipt.reason}")
                            is SendReceipt.Accepted -> receipt.turnId
                        }
                        current.settled(SETTLE_TIMEOUT_MS)
                    }
                }
                val elapsed = System.currentTimeMillis() - sendStarted
                slowest = maxOf(slowest, elapsed)
                val state = outcome?.getOrNull()
                if (state == null) {
                    failures += 1
                    println("send ${sent + 1} FAILED ${elapsed}ms ${outcome?.exceptionOrNull()?.message ?: "timeout"}")
                } else {
                    sent += 1
                    val settled = state.turns.lastOrNull { it.status != TurnStatus.Running } ?: state.turns.lastOrNull()
                    println(
                        "send $sent ok ${elapsed}ms chat=$chatId " +
                            "status=${settled?.status} answer=${settled?.answer?.text?.length ?: 0}chars",
                    )
                }

                // Keep the process inside its wall-clock slice unless the gateway is the slow part.
                val remaining = paceMs - (System.currentTimeMillis() - roundStarted)
                if (remaining > 0) delay(remaining)
            }

            // The remaining reconnects run as their own teardowns: with 200 sends every fourth
            // one is a reconnect, which leaves a couple over the end of the run.
            while (reconnected < reconnects) {
                harness.disconnect()
                val active = harness.core.activeChildJobs()
                if (active != 0) {
                    leaked += active
                    println("leak $active jobs after teardown")
                }
                try {
                    harness.connect()
                } catch (failure: Throwable) {
                    uncaught += "reconnect: ${failure.message}"
                    failures += 1
                    break
                }
                reconnected += 1
                println("reconnect $reconnected (tail) chat=$chatId")
            }

            harness.disconnect()
            val leakedAtEnd = harness.core.activeChildJobs()
            if (leakedAtEnd != 0) leaked += leakedAtEnd
            println(
                "soak done sends=$sent reconnects=$reconnected failures=$failures " +
                    "leakedJobs=$leaked uncaught=${uncaught.size} slowest=${slowest}ms " +
                    "elapsed=${(System.currentTimeMillis() - started) / 1000}s " +
                    "heapStart=${heapStart}Mb heapEnd=${usedHeapMb()}Mb",
            )
            for (line in uncaught.take(5)) println("uncaught: $line")
            if (failures != 0 || leaked != 0 || uncaught.isNotEmpty()) exitProcess(1)
        }
    }

    /**
     * The prompts one `watch` sends: `--prompt` once, or `--prompts "a|b|c"` for a chat with
     * several turns in it. A one-turn chat is a thin transcript to diff; three, one of them
     * using a tool, is what makes the comparison worth something.
     */
    private fun promptsToSend(flags: Map<String, String>): List<String> = when {
        flags["prompts"] != null -> flags.getValue("prompts").split("|").map { it.trim() }.filter { it.isNotEmpty() }
        flags["prompt"] != null -> listOf(flags.getValue("prompt"))
        else -> emptyList()
    }

    /** One file through the greeting's upload capability, as the composer's card does it. */
    private suspend fun upload(
        handle: de.arjmandi.nanobot.companion.facade.ChatHandle,
        file: File,
    ): de.arjmandi.nanobot.companion.facade.AttachmentRef {
        if (!file.isFile) throw HarnessError("no such file: ${file.path}")
        val picked = de.arjmandi.nanobot.companion.facade.PickedFile(
            name = file.name,
            mimeType = mimeOf(file.name),
            bytes = file.readBytes(),
        )
        return when (val result = handle.upload(picked)) {
            is de.arjmandi.nanobot.companion.facade.UploadResult.Uploaded -> {
                println("uploaded: ${file.name} -> ${result.reference}")
                de.arjmandi.nanobot.companion.facade.AttachmentRef(reference = result.reference, name = file.name)
            }

            is de.arjmandi.nanobot.companion.facade.UploadResult.Rejected ->
                throw HarnessError("upload rejected: ${result.reason.copyKey}")

            is de.arjmandi.nanobot.companion.facade.UploadResult.Failed ->
                throw HarnessError("upload failed: ${result.error}")
        }
    }

    /** Enough of a type map for the files an instrument attaches; the gateway decides the rest. */
    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "txt", "log", "md" -> "text/plain"
        else -> "application/octet-stream"
    }

    // --- shared plumbing ------------------------------------------------------------------

    internal fun run(name: String, args: List<String>, body: suspend (Map<String, String>) -> Unit) {
        val flags = parse(args)
        try {
            runBlocking { body(flags) }
        } catch (failure: HarnessError) {
            System.err.println("$name: ${failure.message}")
            exitProcess(1)
        } catch (failure: Throwable) {
            System.err.println("$name: ${failure::class.simpleName}: ${failure.message}")
            exitProcess(1)
        }
    }

    internal fun profile(flags: Map<String, String>): File =
        File(flags["profile"] ?: File(repoRoot(), ".dev/profile.json").path)

    private fun parse(args: List<String>): Map<String, String> {
        val flags = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index]
            if (!key.startsWith("--")) throw HarnessError("unexpected argument: $key")
            val next = args.getOrNull(index + 1)
            val value = if (next == null || next.startsWith("--")) "" else next
            flags[key.removePrefix("--")] = value
            index += if (value.isEmpty()) 1 else 2
        }
        return flags
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }

    private fun ConnectionState.line(): String = when (this) {
        is ConnectionState.Failed -> "failed(${failure.copyKey}${detail?.let { ": $it" }.orEmpty()})"
        else -> toString()
    }

    private fun SessionRow.line(): String =
        "$key | ${title.ifBlank { preview }} | ${updatedAt ?: "-"} | ${modelPreset ?: "-"}"

    private fun usedHeapMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    private fun diff(live: String, history: String) {
        val liveLines = live.lines()
        val historyLines = history.lines()
        for (index in 0 until maxOf(liveLines.size, historyLines.size)) {
            val a = liveLines.getOrNull(index)
            val b = historyLines.getOrNull(index)
            if (a != b) {
                println("- live   ${index + 1}: ${a ?: "<missing>"}")
                println("+ webui  ${index + 1}: ${b ?: "<missing>"}")
            }
        }
    }

    private fun replay(history: JsonObject, chatId: String): ThreadState =
        HistoryReplay.fromEvents(history, chatId)

    private const val SETTLE_TIMEOUT_MS: Long = 180_000
}
