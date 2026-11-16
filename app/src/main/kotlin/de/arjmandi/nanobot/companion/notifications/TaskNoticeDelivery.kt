package de.arjmandi.nanobot.companion.notifications

import de.arjmandi.nanobot.companion.projection.NoticeSwitches
import de.arjmandi.nanobot.companion.projection.NotificationPermission
import de.arjmandi.nanobot.companion.projection.NotificationPrompt
import de.arjmandi.nanobot.companion.projection.TaskNotice

/** The system calls a banner needs, so this class can be tested without an `NotificationManager`. */
interface BannerPosting {
    fun permission(): NotificationPermission

    /** True when the banner is on the phone now; false when the system refused to show it. */
    fun post(notice: TaskNotice): Boolean
}

/**
 * One notice in, at most one banner out (plan.md D8). The decision *whether* a frame deserves a
 * banner is `:core`'s (`TaskNoticeRule`); this is the part that only makes sense on a phone: a
 * banner the system would not show is remembered so the permission is asked once, at the first
 * moment it is justified rather than at launch.
 */
class TaskNoticeDelivery(
    private val posting: BannerPosting,
    /**
     * The reader's three switches, read at post time (T43) so a change applies to the next notice
     * rather than to the next launch. Passed in rather than defaulted: a delivery nobody wired to
     * the reader's choices is the switch that gated nothing, and the compiler should say so.
     */
    private val switches: () -> NoticeSwitches,
) {

    /** The last key posted or refused, so a repeated notice is not a second banner. */
    private var announced: String? = null

    /** A banner went missing: the next foreground is when the permission is worth asking for. */
    var missedNotice: Boolean = false
        private set

    /** True when it is worth showing the system dialog now (`NotificationPrompt` owns the rule). */
    val shouldAskPermission: Boolean
        get() = NotificationPrompt.shouldAsk(posting.permission(), missedNotice)

    /** Returns whether a banner was posted; a null notice is the core saying "nothing happened". */
    fun deliver(notice: TaskNotice?): Boolean {
        if (notice == null) return false
        // T43: a switch the reader turned off is not a permission problem and not a memory of this
        // notice — turning it back on lets the next one through.
        if (!switches().allows(notice.outcome)) return false
        if (notice.key == announced) return false
        announced = notice.key
        val posted = posting.post(notice)
        missedNotice = !posted
        return posted
    }

    /** The permission answer, whichever way it went: a granted one clears the missed banner. */
    fun permissionAnswered(granted: Boolean) {
        if (granted) {
            missedNotice = false
            announced = null
        }
    }
}
