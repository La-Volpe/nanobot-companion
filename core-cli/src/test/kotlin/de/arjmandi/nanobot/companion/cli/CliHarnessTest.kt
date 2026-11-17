package de.arjmandi.nanobot.companion.cli

import de.arjmandi.nanobot.companion.projection.AnswerState
import de.arjmandi.nanobot.companion.projection.Notice
import de.arjmandi.nanobot.companion.projection.StreamSegment
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.projection.Turn
import de.arjmandi.nanobot.companion.projection.TurnStatus
import de.arjmandi.nanobot.companion.projection.UserPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `core-cli watch` prints a transcript, and that transcript is the instrument for the parity
 * claim, "matches the WebUI's transcript for the same chat". So the rendering has one hard
 * property: it must depend only on what both feeds agree on. Turn ids are not one of those (a
 * live turn is named by this client until the gateway renames it, while the history page only
 * ever knows the gateway's), so the transcript numbers turns by position and shows everything
 * else.
 */
class CliHarnessTest {

    private fun state(
        chatId: String = "websocket:a",
        turns: List<Turn> = listOf(turn),
        notices: List<Notice> = emptyList(),
    ): ThreadState = ThreadState(
        chatId = chatId,
        turns = turns,
        notices = notices,
        modelPreset = "primary",
        modelName = "deepseek-v4.1-flash:cloud",
        contextWindowTokens = 32_768,
    )

    private val turn = Turn(
        id = "companion-1000-0",
        prompt = UserPrompt(text = "Reply with exactly the word: pong", optimistic = false),
        answer = AnswerState(
            segments = listOf(StreamSegment(id = "s1", text = "pon\ng", closed = true)),
        ),
        status = TurnStatus.Complete,
    )

    @Test
    fun transcriptShowsThePromptAnswerAndStatus() {
        val rendered = Transcript.render(state())

        assertTrue("turn 1" in rendered, rendered)
        assertTrue("prompt | Reply with exactly the word: pong" in rendered, rendered)
        assertTrue("answer | pon\\ng" in rendered, "an answer is one line, so a diff is one line: $rendered")
        assertTrue("status | Complete" in rendered, rendered)
    }

    @Test
    fun transcriptOmitsVolatileIds() {
        val first = Transcript.render(state())
        val again = Transcript.render(
            state(
                turns = listOf(turn.copy(id = "turn-1", answer = turn.answer.copy(segments = listOf(StreamSegment(id = "stream:9", text = "pon\ng", closed = true))))),
            ),
        )

        // Same turn, two ids: the transcript a user diffs cannot see the difference.
        assertEquals(first, again)
        assertFalse("companion-1000-0" in first)
        assertFalse("turn-1" in first)
    }

    @Test
    fun anOptimisticSecondPromptIsVisibleAsItsOwnTurn() {
        // This is the failure the instrument exists to catch: a live thread that keeps the
        // composer's bubble *and* the gateway's turn shows two prompts where history shows one.
        val duplicated = state(
            turns = listOf(
                turn.copy(id = "companion-1000-0", answer = AnswerState(), status = TurnStatus.Running),
                turn.copy(id = "turn-1"),
            ),
        )
        val rendered = Transcript.render(duplicated)

        assertTrue("turn 1" in rendered && "turn 2" in rendered, rendered)
        assertEquals(2, rendered.lines().count { it.startsWith("  prompt |") }, rendered)
        assertTrue("status | Running" in rendered, "the unsettled one says so")
    }

    @Test
    fun noticesAreRenderedWithTheirCopyKey() {
        val rendered = Transcript.render(
            state(notices = listOf(Notice.Sandbox(id = "sandbox", issue = de.arjmandi.nanobot.companion.projection.SandboxIssue.Path))),
        )

        assertTrue("notice | sandbox | notice.sandbox.path" in rendered, "a notice is named by its copy key, not its copy: $rendered")
    }

    @Test
    fun theChatAndModelAreCommentaryNotThread() {
        // Both feeds do not share `turn_model_updated`, so the model line is a `#` comment:
        // the body is what the parity diff compares.
        val header = Transcript.header(state())
        assertTrue("# chat websocket:a" in header, header)
        assertTrue("# model primary | deepseek-v4.1-flash:cloud | 32768" in header, header)
        assertFalse("chat " in Transcript.render(state()), "the body carries no chat id")
    }

    @Test
    fun anEmptyThreadRendersNothingToCompare() {
        assertEquals("", Transcript.render(ThreadState(chatId = "websocket:a")))
    }
}
