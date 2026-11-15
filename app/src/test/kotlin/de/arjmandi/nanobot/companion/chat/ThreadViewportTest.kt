package de.arjmandi.nanobot.companion.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule the owner reported missing (T9), taken from the WebUI rather than invented:
 * `ThreadViewport.tsx:1002` measures the distance from the end of the scroll and hands it to
 * `thread-motion.ts:353`, which pauses following anywhere but the tail. New content only follows
 * a reader who is already there; a reader who scrolled up keeps their place until they come back.
 */
class ThreadViewportTest {

    @Test
    fun aReaderAtTheEndFollowsNewContent() {
        assertTrue(ThreadViewport().scrolled(0).followsNewContent(), "a reader at the end is brought along")
    }

    @Test
    fun aReaderWhoScrolledUpIsLeftWhereTheyAre() {
        val away = ThreadViewport().scrolled(600)

        assertFalse(away.following)
        assertFalse(away.followsNewContent(), "new content must not yank a browsing reader down")
    }

    @Test
    fun returningToTheEndResumesFollowing() {
        val away = ThreadViewport().scrolled(600)
        val back = away.scrolled(0)

        assertTrue(back.following)
        assertTrue(back.followsNewContent(), "at the end again, the stream follows again")
    }

    /** `NEAR_BOTTOM_PX = 48` (ThreadViewport.tsx:80): the web's own threshold, not a round guess. */
    @Test
    fun theThresholdIsTheWebsOwn() {
        assertTrue(ThreadViewport().scrolled(47).following)
        assertFalse(ThreadViewport().scrolled(48).following)
    }

    @Test
    fun askingForTheTailFollowsAgain() {
        val away = ThreadViewport().scrolled(600)

        assertTrue(away.pinned().following, "the reader asked for the latest, so the stream follows")
    }
}
