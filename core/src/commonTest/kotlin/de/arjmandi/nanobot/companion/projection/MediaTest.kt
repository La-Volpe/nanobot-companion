package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.wire.FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A turn's media, as the gateway sends it (`nanobot/webui/outbound_delivery.py:465` on the
 * socket, `nanobot/webui/transcript.py:1567` in the stored page): `media_urls` is a list of
 * `{url, name}` with no `kind`, because the WebUI infers it (`webui/src/lib/media.ts:38-53`),
 * and the url is the origin-relative signed path `/api/media/<sig>/<payload>`.
 *
 * The rows are what the two apps draw, so what they are and what they are called is decided
 * here, once, rather than twice in two UIs.
 */
class MediaTest {

    private fun message(urls: String, turnId: String = "turn-1") =
        FrameCodec.decode(
            """{"event":"message","chat_id":"chat-1","turn_id":"$turnId",
               "text":"here it is","media_urls":$urls}""",
        )

    private fun reduce(vararg frames: de.arjmandi.nanobot.companion.wire.Frame): ThreadState =
        frames.fold(ThreadState(chatId = "chat-1")) { state, frame ->
            ThreadReducer.reduce(state, frame, nowMs = 0).state
        }

    @Test
    fun anAnswerCarriesItsImageAsAnImageRow() {
        val state = reduce(message("""[{"url":"/api/media/sig/abc","name":"shot.png"}]"""))

        val media = state.turns.single().answer.media.single()
        assertEquals(MediaKind.Image, media.kind, "the extension says what it is, not the frame")
        assertEquals("shot.png", media.name)
        assertEquals("/api/media/sig/abc", media.url, "the url is kept exactly as the gateway sent it")
    }

    @Test
    fun videoAndAudioAreTheirOwnKinds() {
        val state = reduce(
            message(
                """[{"url":"/api/media/s/c.mp4","name":"clip.mp4"},{"url":"/api/media/s/v.m4a","name":"note.m4a"}]""",
            ),
        )

        assertEquals(listOf(MediaKind.Video, MediaKind.Audio), state.turns.single().answer.media.map { it.kind })
    }

    /** A future gateway may name the kind itself; a word this build does not know is not a crash. */
    @Test
    fun anExplicitKindWinsOverTheExtension() {
        val state = reduce(
            message("""[{"url":"/api/media/s/x","name":"render","kind":"video"},{"url":"/api/media/s/y","name":"y","kind":"hologram"}]"""),
        )

        val media = state.turns.single().answer.media
        assertEquals(MediaKind.Video, media[0].kind, "the gateway's own word beats a missing extension")
        assertEquals(MediaKind.File, media[1].kind, "an unknown kind degrades to a file row")
    }

    /**
     * The live gateway has two words and only one of them is specific: `media_attachment_kind`
     * (`media_api.py:169`) answers `image`, `video` or `file`, so a recording comes back as
     * `kind: "file"` with a `.wav` name. The WebUI reads the extension first for exactly this
     * reason (`webui/src/lib/media.ts:55`), and a `file` on a `.m4a` is a recording, not a file.
     */
    @Test
    fun theExtensionBeatsAGenericFileKind() {
        val state = reduce(message("""[{"url":"/api/media/s/note.m4a","name":"note.m4a","kind":"file"}]"""))

        assertEquals(MediaKind.Audio, state.turns.single().answer.media.single().kind)
    }

    @Test
    fun anUnknownTypeIsAFileRowRatherThanNothing() {
        val state = reduce(message("""[{"url":"/api/media/s/report","name":"report"}]"""))

        assertEquals(MediaKind.File, state.turns.single().answer.media.single().kind)
    }

    /** The socket sends the same `message` again for each progress step, so the row is keyed. */
    @Test
    fun theSameFrameTwiceDoesNotDuplicateTheRow() {
        val frame = message("""[{"url":"/api/media/s/abc","name":"shot.png"}]""")

        val state = reduce(frame, frame)

        assertEquals(1, state.turns.single().answer.media.size, "one picture is one row")
    }

    /** The thread page re-signs a stored url (`test_websocket_http_routes.py:3343`), so the row
     * keeps its place and takes the newer signature instead of growing a second row. */
    @Test
    fun aResignedUrlReplacesThePreviousOne() {
        val state = reduce(
            message("""[{"url":"/api/media/old/abc","name":"shot.png"}]"""),
            message("""[{"url":"/api/media/new/abc","name":"shot.png"}]"""),
        )

        val media = state.turns.single().answer.media
        assertEquals(1, media.size)
        assertEquals("/api/media/new/abc", media.single().url, "the newest signature is the loadable one")
    }

    @Test
    fun aUserMessageCarriesWhatItWasSentWith() {
        val frame = FrameCodec.decode(
            """{"event":"user_message","chat_id":"chat-1","turn_id":"turn-2","text":"look",
               "media_urls":[{"kind":"image","url":"/api/media/s/u.png","name":"u.png"}]}""",
        )

        val media = reduce(frame).turns.single().prompt?.media.orEmpty()

        assertEquals(MediaKind.Image, media.single().kind)
        assertEquals("/api/media/s/u.png", media.single().url)
    }

    /** The socket re-sends a prompt as it settles; an echo with no files is not a deletion. */
    @Test
    fun aPromptEchoWithoutMediaKeepsTheRowsItHad() {
        val withMedia = FrameCodec.decode(
            """{"event":"user_message","chat_id":"chat-1","turn_id":"turn-2","text":"look",
               "media_urls":[{"url":"/api/media/s/u.png","name":"u.png"}]}""",
        )
        val echo = FrameCodec.decode(
            """{"event":"user_message","chat_id":"chat-1","turn_id":"turn-2","text":"look"}""",
        )

        val media = reduce(withMedia, echo).turns.single().prompt?.media.orEmpty()

        assertEquals(1, media.size, "the picture is still the prompt's")
    }

    /** `/api/media/<sig>/<payload>` is signed, not bearer-gated, but it is still relative. */
    @Test
    fun aRelativeMediaUrlResolvesAgainstTheGatewayOrigin() {
        val media = reduce(message("""[{"url":"/api/media/s/abc","name":"shot.png"}]"""))
            .turns.single().answer.media.single()

        assertEquals("http://127.0.0.1:18765/api/media/s/abc", media.absoluteUrl("http://127.0.0.1:18765"))
        assertEquals("http://127.0.0.1:18765/api/media/s/abc", media.absoluteUrl("http://127.0.0.1:18765/"))
        assertEquals(
            "https://example.test/api/media/s/abc",
            media.absoluteUrl("https://example.test"),
            "the gateway may answer under a prefix-free origin only",
        )
    }

    @Test
    fun anAlreadyAbsoluteUrlIsLeftAlone() {
        val media = reduce(message("""[{"url":"https://cdn.test/a.png","name":"a.png"}]"""))
            .turns.single().answer.media.single()

        assertEquals("https://cdn.test/a.png", media.absoluteUrl("http://127.0.0.1:18765"))
    }

    @Test
    fun anEntryWithNoUrlIsDroppedRatherThanDrawn() {
        val state = reduce(
            message("""[{"url":"","name":"gone.png"},{"name":"no-url.png"},{"url":"/api/media/s/ok.png","name":"ok.png"}]"""),
        )

        assertEquals(listOf("ok.png"), state.turns.single().answer.media.map { it.name })
    }

    /** A frame whose `media_urls` is not a list of objects must not break the thread. */
    @Test
    fun malformedMediaDoesNotThrow() {
        val state = reduce(message("""["nonsense",42,{"url":"/api/media/s/ok.png"}]"""))

        assertEquals(1, state.turns.single().answer.media.size)
        assertTrue(state.turns.single().answer.media.single().name.isEmpty(), "a nameless row is still a row")
    }
}
