package de.arjmandi.nanobot.companion.projection

/**
 * When a frame is worth a notification (the WebUI's rule, `lib/browser-notifications.ts`
 * `taskNoticeKey`, plus the one case a browser does not have: a chat the user is looking at).
 *
 * Pure by construction — a settled turn or a recovery in, a decision out — because the same
 * answer has to hold on two platforms and neither may keep its own copy of the rule.
 */

/** What the user is being told happened, as a copy key the platform turns into words. */
public enum class TaskOutcome(public val copyKey: String) {
    Completed("notice.completed"),
    Failed("notice.failed"),
    Interrupted("notice.interrupted"),

    /** A recovery that is waiting on a person, not on the gateway. */
    NeedsAttention("notice.needsAttention"),
}

/**
 * Where the app is when the frame lands. The chat the thread is showing is the core's own
 * resolved id, so a chat the greeting minted is compared against what it actually became.
 */
public data class ScreenPresence(
    public val isForeground: Boolean = false,
    public val visibleChatId: String? = null,
)

/**
 * One thing the gateway said happened. [eventId] is the turn or recovery id, which is what
 * makes two events of one chat two events rather than one announced twice.
 */
public data class TaskEvent(
    public val chatId: String,
    public val outcome: TaskOutcome,
    /** `turn:<id>` or `recovery:<id>`, which is what makes two events of one chat distinct. */
    public val eventId: String,
) {
    /** What a platform compares to know it has not already posted this one. */
    public val key: String get() = "$chatId|$eventId|$outcome"
}

/** What one banner says: which chat to open, why, and what makes it once-only. */
public data class TaskNotice(
    public val chatId: String,
    public val outcome: TaskOutcome,
    public val key: String,
)

public sealed interface NoticeDecision {
    public data class Deliver(public val notice: TaskNotice) : NoticeDecision
    public data class Silent(public val reason: NoticeSilence) : NoticeDecision
}

public enum class NoticeSilence {
    /** The chat the user is looking at: the answer is already on screen. */
    ChatOnScreen,

    /** This exact turn or recovery was announced already. */
    AlreadyShown,
}

public object TaskNoticeRule {

    /**
     * A settled turn. `turn_end.outcome` is omitted when the turn completed, so a failure is
     * either that word or a `failure_kind` (`outbound_wire.py:341`, and what `ThreadReducer`
     * reads for the row's own state). A cancelled turn is the user's own doing: no banner.
     */
    public fun turnEnd(
        chatId: String?,
        turnId: String?,
        outcome: String?,
        failureKind: String? = null,
    ): TaskEvent? {
        if (chatId.isNullOrEmpty() || turnId.isNullOrEmpty()) return null
        val settled = when (outcome) {
            "cancelled" -> return null
            "failed" -> TaskOutcome.Failed
            "interrupted" -> TaskOutcome.Interrupted
            else -> if (failureKind != null) TaskOutcome.Failed else TaskOutcome.Completed
        }
        return TaskEvent(chatId, settled, "turn:$turnId")
    }

    /**
     * A recovery that stopped. Only the two states that need a person deserve a banner:
     * `resuming` is the gateway still working, `recovered` is it having worked.
     */
    public fun recoveryState(chatId: String?, recoveryId: String?, status: String?): TaskEvent? {
        if (chatId.isNullOrEmpty() || recoveryId.isNullOrEmpty()) return null
        if (status != "awaiting_user" && status != "failed") return null
        return TaskEvent(chatId, TaskOutcome.NeedsAttention, "recovery:$recoveryId")
    }

    /**
     * Whether to show one. A chat on screen in a foreground app is silent; a backgrounded app
     * is not "looking at" anything, so the chat it left behind is announced like any other.
     */
    public fun decide(
        event: TaskEvent,
        presence: ScreenPresence,
        seen: List<String> = emptyList(),
    ): NoticeDecision = when {
        presence.isForeground && presence.visibleChatId == event.chatId ->
            NoticeDecision.Silent(NoticeSilence.ChatOnScreen)

        event.key in seen -> NoticeDecision.Silent(NoticeSilence.AlreadyShown)

        else -> NoticeDecision.Deliver(TaskNotice(event.chatId, event.outcome, event.key))
    }
}

/**
 * The three switches Notifications draws (design README) and the outcome each one gates (T43).
 *
 * The gateway settles four outcomes and the screen has three rows, so the middle row is "failed or
 * stopped": a switch that names less than it gates would silence a turn the user watched stop, and
 * a control that lies is worse than one that is missing. The keys and the defaults live here
 * because both phones store the same three flags — a key that drifted between the row and the
 * poster would be a switch that quietly gates nothing.
 */
public enum class NoticeCategory(
    /** The store key the row writes and the poster reads. */
    public val preferenceKey: String,
    /** The design's default: finished and failed on, needs-attention off. */
    public val onByDefault: Boolean,
) {
    Finished("settings.notify.finished", onByDefault = true),
    FailedOrStopped("settings.notify.failedOrStopped", onByDefault = true),
    NeedsAttention("settings.notify.needsAttention", onByDefault = false),
    ;

    public companion object {

        /**
         * The switch a notice falls under. Every [TaskOutcome] has one, because a notice no switch
         * gates is a switch that does nothing.
         */
        public fun of(outcome: TaskOutcome): NoticeCategory = when (outcome) {
            TaskOutcome.Completed -> Finished
            // A stop is a turn that did not finish: it is not "needs attention", which is a
            // recovery waiting on a person.
            TaskOutcome.Failed, TaskOutcome.Interrupted -> FailedOrStopped
            TaskOutcome.NeedsAttention -> NeedsAttention
        }
    }
}

/**
 * The reader's three switches, read at post time (T43) so a change applies to the next notice and
 * not to the next launch. The defaults are [NoticeCategory]'s, so a fresh install posts exactly what
 * its switches say.
 */
public data class NoticeSwitches(
    public val finished: Boolean = NoticeCategory.Finished.onByDefault,
    public val failedOrStopped: Boolean = NoticeCategory.FailedOrStopped.onByDefault,
    public val needsAttention: Boolean = NoticeCategory.NeedsAttention.onByDefault,
) {

    /** Whether the reader asked to be told about this outcome. */
    public fun allows(outcome: TaskOutcome): Boolean = when (NoticeCategory.of(outcome)) {
        NoticeCategory.Finished -> finished
        NoticeCategory.FailedOrStopped -> failedOrStopped
        NoticeCategory.NeedsAttention -> needsAttention
    }
}

/** The system permission as both platforms can describe it (`UNUserNotificationCenter`, `POST_NOTIFICATIONS`). */
public enum class NotificationPermission { NotAsked, Granted, Denied }

/**
 * When to ask. Never at launch: the first moment the answer changes something is a notice the
 * app wanted to show and could not, and Settings is the other place a user goes looking. A
 * denied answer is not re-asked — the platform's own screen is the only honest offer then.
 */
public object NotificationPrompt {
    public fun shouldAsk(permission: NotificationPermission, missedANotice: Boolean): Boolean =
        missedANotice && permission == NotificationPermission.NotAsked
}
