package de.arjmandi.nanobot.companion.cli.record

/**
 * One scripted conversation the recorder drives over a fresh socket.
 *
 * The fixture set started as a single plain turn, which left tool rows (C4.4), notices
 * (C7.3) and the error taxonomy (C7.4) modelled from the gateway source instead of from
 * what it sends (P3). A scenario is the smallest script that makes the gateway emit the
 * frames a task needs, and the four together are the set of frames `:core` must decode.
 *
 * A scenario is two things: the envelopes it sends, and how many turns have to settle
 * before the socket is done. Most scenarios are one envelope on `ready`; a failure needs
 * a preset switch first, so its second envelope goes out after the first `turn_end`.
 */
internal class Scenario(
    val name: String,
    val prompt: String,
    /** Directory inside the set: the plain turn keeps `ws/`, every other scenario its own name. */
    val framesDir: String,
    /**
     * The prefix of the turn ids this scenario sends. The plain turn keeps `turn`, because
     * the codec test asserts against the ids the first recording carried; the others name
     * themselves so a frame's scenario is readable in isolation.
     */
    val turnIdPrefix: String,
    /** `turn_end` and `error` frames to wait for before the socket is closed. */
    val settles: Int,
    /** The error scenario rejects a made-up chat id, so there is no thread to fetch for it. */
    val recordsHistory: Boolean,
    val steps: List<Step>,
) {

    /** A frame sent once the socket has settled [afterSettles] turns; 0 means on `ready`. */
    internal class Step(val afterSettles: Int, val frame: (chatId: String, turnId: String) -> String)

    fun turnIdOf(stepIndex: Int): String = "$turnIdPrefix-${stepIndex + 1}"
}

internal object Scenarios {

    /**
     * The prompt has to make the model reach for a tool, or the recording carries no tool
     * row. Asking for a shell command and its exact output does that reliably with the
     * gateway's model; asking a question a tool cannot help with does not.
     */
    const val TOOL_PROMPT: String =
        "Run this with your shell tool: printf 'nanobot-tool-probe\\n'. Then report the exact output in one line."

    /** The scenario whose write the recorder clears first, so its diff exists on every run. */
    const val FILE_TARGET_SCENARIO: String = "file"

    /** The file the file scenario writes; the recorder clears it first so the diff exists again. */
    const val FILE_TARGET: String = "tool-probe.txt"

    /** A filesystem write is what produces `file_edit`, which C4.4 turns into diff rows. */
    const val FILE_PROMPT: String =
        "Use your filesystem tool to create $FILE_TARGET in the workspace containing exactly: nanobot. Then say done."

    /** A real markdown reply: the fuzz corpus for C4.8 is a transcript, not a fixture I typed. */
    const val MARKDOWN_PROMPT: String =
        "Reply with a short markdown document and nothing else: one level-2 heading, " +
            "a two-item bullet list, a table with a header row and one data row, and a fenced " +
            "kotlin code block containing val x = 1."

    const val FAILURE_PROMPT: String = "Say hello in one line."
    const val ERROR_PROMPT: String = "This turn proves the gateway's error frame."

    /** `^[A-Za-z0-9_:-]{1,64}$` is the accepted chat id, so a space is a rejection. */
    const val INVALID_CHAT_ID: String = "not a chat id"

    /** Written by tools/nanobot-dev.sh: a real preset pointing at a model ollama does not have. */
    const val BROKEN_PRESET: String = "broken"

    fun all(defaultPrompt: String): List<Scenario> = listOf(
        Scenario(
            name = "plain",
            prompt = defaultPrompt,
            framesDir = "ws",
            turnIdPrefix = "turn",
            settles = 1,
            recordsHistory = true,
            steps = listOf(
                Scenario.Step(0) { chatId, turnId -> GatewayClient.messageEnvelope(chatId, turnId, defaultPrompt) },
            ),
        ),
        Scenario(
            name = "tool",
            prompt = TOOL_PROMPT,
            framesDir = "tool",
            turnIdPrefix = "tool",
            settles = 1,
            recordsHistory = true,
            steps = listOf(
                Scenario.Step(0) { chatId, turnId -> GatewayClient.messageEnvelope(chatId, turnId, TOOL_PROMPT) },
            ),
        ),
        Scenario(
            name = "markdown",
            prompt = MARKDOWN_PROMPT,
            framesDir = "markdown",
            turnIdPrefix = "markdown",
            settles = 1,
            recordsHistory = true,
            steps = listOf(
                Scenario.Step(0) { chatId, turnId -> GatewayClient.messageEnvelope(chatId, turnId, MARKDOWN_PROMPT) },
            ),
        ),
        Scenario(
            name = "file",
            prompt = FILE_PROMPT,
            framesDir = "file",
            turnIdPrefix = "file",
            settles = 1,
            recordsHistory = true,
            steps = listOf(
                Scenario.Step(0) { chatId, turnId -> GatewayClient.messageEnvelope(chatId, turnId, FILE_PROMPT) },
            ),
        ),
        Scenario(
            name = "error",
            prompt = ERROR_PROMPT,
            framesDir = "error",
            turnIdPrefix = "error",
            settles = 1,
            recordsHistory = false,
            steps = listOf(
                Scenario.Step(0) { _, turnId -> GatewayClient.messageEnvelope(INVALID_CHAT_ID, turnId, ERROR_PROMPT) },
            ),
        ),
        Scenario(
            name = "failure",
            prompt = FAILURE_PROMPT,
            framesDir = "failure",
            turnIdPrefix = "failure",
            settles = 2,
            recordsHistory = true,
            steps = listOf(
                Scenario.Step(0) { chatId, turnId ->
                    GatewayClient.messageEnvelope(chatId, turnId, "/model $BROKEN_PRESET")
                },
                Scenario.Step(1) { chatId, turnId -> GatewayClient.messageEnvelope(chatId, turnId, FAILURE_PROMPT) },
            ),
        ),
    )
}
