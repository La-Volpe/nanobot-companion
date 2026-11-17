package de.arjmandi.nanobot.companion.chat

/**
 * Whether the thread follows its own tail, which is the WebUI's rule and not a guess
 * (`ThreadViewport.tsx:1002` measures the distance from the end and hands it to
 * `thread-motion.ts:353`; anything but the tail pauses the follow).
 *
 * The screen measures; this decides. A reader at the end is brought along as the answer grows,
 * a reader who scrolled up keeps their place, and coming back to the end resumes following.
 */
data class ThreadViewport(val following: Boolean = true) {

    /**
     * The reader's own scroll landed [distanceFromBottomPx] from the end, whether they scrolled
     * there or the gesture settled there. The end resumes the follow; anywhere else pauses it.
     */
    fun scrolled(distanceFromBottomPx: Int): ThreadViewport =
        copy(following = distanceFromBottomPx < NEAR_BOTTOM_PX)

    /** A prompt, or the scroll-to-latest control: the reader asked to see the tail. */
    fun pinned(): ThreadViewport = copy(following = true)

    /** Whether new streamed content should bring the tail into view. */
    fun followsNewContent(): Boolean = following

    companion object {
        /** `NEAR_BOTTOM_PX` (webui `ThreadViewport.tsx:80`): the web's own threshold, not a round guess. */
        const val NEAR_BOTTOM_PX: Int = 48
    }
}
