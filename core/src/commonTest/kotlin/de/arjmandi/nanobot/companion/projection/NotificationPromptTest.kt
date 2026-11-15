package de.arjmandi.nanobot.companion.projection

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The permission is asked at the first moment it is justified, not at launch: a banner the app
 * wanted to show and could not is exactly that moment.
 */
class NotificationPromptTest {

    @Test
    fun nothingIsAskedBeforeANoticeWentMissing() {
        assertFalse(NotificationPrompt.shouldAsk(NotificationPermission.NotAsked, missedANotice = false))
    }

    @Test
    fun theFirstMissedNoticeIsTheMomentToAsk() {
        assertTrue(NotificationPrompt.shouldAsk(NotificationPermission.NotAsked, missedANotice = true))
    }

    @Test
    fun aRefusalIsNeverRepeatedAsADialog() {
        // Denied is an answer. Asking again would be a dialog the system refuses to show, so the
        // offer becomes the system's own screen for this app.
        assertFalse(NotificationPrompt.shouldAsk(NotificationPermission.Denied, missedANotice = true))
        assertFalse(NotificationPrompt.shouldAsk(NotificationPermission.Granted, missedANotice = true))
    }
}
