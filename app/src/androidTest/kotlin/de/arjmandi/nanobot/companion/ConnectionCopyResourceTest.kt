package de.arjmandi.nanobot.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.arjmandi.nanobot.companion.connect.ConnectionCopy
import de.arjmandi.nanobot.companion.facade.ConnectionFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The core's copy keys and this app's string resources are one pairing, not two lists that happen
 * to agree (the same guarantee iOS's `CopyTests` gives). The resource name is the key with
 * its dot turned into an underscore, so a renamed string fails here instead of falling back to
 * whatever the next resource with that id says.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionCopyResourceTest {

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun everyCopyKeyNamesItsOwnStringResource() {
        ConnectionFailure.entries.forEach { failure ->
            val id = ConnectionCopy.text(failure, null)
            val expected = failure.copyKey.replace(".", "_")
            assertEquals(
                expected,
                resources.getResourceEntryName(id),
                "${failure.copyKey} must resolve to @string/$expected and nothing else",
            )
        }
    }

    @Test
    fun noSentenceIsEmpty() {
        ConnectionFailure.entries.forEach { failure ->
            val text = resources.getString(ConnectionCopy.text(failure, null))
            assertTrue(text.isNotBlank(), "${failure.copyKey} has a sentence a person can read")
        }
    }

    @Test
    fun theLocalRefusalsAreStringsToo() {
        val refusals = listOf(
            ConnectionCopy.LocalRefusal.AddressMissing,
            ConnectionCopy.LocalRefusal.AddressNeedsScheme,
            ConnectionCopy.LocalRefusal.KeyMissing,
        )
        refusals.forEach { refusal ->
            assertTrue(resources.getString(refusal.text).isNotBlank())
        }
    }
}
