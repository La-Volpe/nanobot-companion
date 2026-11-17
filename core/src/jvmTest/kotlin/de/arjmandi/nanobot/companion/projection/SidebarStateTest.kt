package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.platform.InMemoryFileStore
import de.arjmandi.nanobot.companion.platform.JsonFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The sidebar's own state. It has to satisfy three consumers at once: the
 * local file (so the order survives a launch), the `set_sidebar_state` payload (so the
 * WebUI on the same gateway agrees), and the session list (which supplies the keys).
 * The payload's shape is the live gateway's, not the docs': `snake_case`, `schema_version`
 * 1, and `archived_keys` still present on the wire.
 */
class SidebarStateTest {

    private val alpha = "websocket:alpha"
    private val beta = "websocket:beta"
    private val gamma = "websocket:gamma"

    @Test
    fun renamePinArchivePersistLocally() {
        val state = SidebarState()
            .rename(alpha, "  Project notes  ")
            .pin(beta)
            .archive(gamma)

        assertEquals(mapOf(alpha to "Project notes"), state.titleOverrides, "a title override is trimmed, not stored raw")
        assertEquals(listOf(beta), state.pinnedKeys)
        assertEquals(listOf(gamma), state.archivedKeys)

        val files = InMemoryFileStore()
        val store = JsonFileStore(files)
        store.write("sidebar.json", state, SidebarState.serializer())

        assertEquals(state, store.read("sidebar.json", SidebarState.serializer()), "the blob survives a relaunch")

        // Renaming to blank drops the override instead of storing an empty title.
        assertEquals(emptyMap(), state.rename(alpha, "   ").titleOverrides)
        // Unpinning and unarchiving remove the key rather than writing false.
        assertEquals(emptyList(), state.pin(beta, false).pinnedKeys)
        assertEquals(emptyList(), state.archive(gamma, false).archivedKeys)
        // A key is pinned once, however often it is pinned.
        assertEquals(listOf(beta), state.pin(beta).pinnedKeys)
    }

    @Test
    fun reorderIsStable() {
        val ordered = SidebarState().move(alpha, 0).move(beta, 1).move(gamma, 2)
        assertEquals(listOf(alpha, beta, gamma), ordered.sessionOrder)

        val moved = ordered.move(gamma, 0)
        assertEquals(listOf(gamma, alpha, beta), moved.sessionOrder, "only the moved key changes place")

        val clamped = ordered.move(gamma, 99)
        assertEquals(listOf(alpha, beta, gamma), clamped.sessionOrder, "an out-of-range index lands at the end")
        assertEquals(ordered, ordered.move(gamma, 2), "moving a key where it already is changes nothing")

        val first = SidebarState().move(alpha, 5)
        assertEquals(listOf(alpha), first.sessionOrder, "moving into an empty order still records the key")
    }

    @Test
    fun stateRoundTripsThroughFileStore() {
        val state = SidebarState()
            .rename(alpha, "Renamed")
            .pin(alpha)
            .move(beta, 0)
            .mergeOrder(listOf(alpha, beta, gamma))

        val json = Json.parseToJsonElement(SidebarState.json.encodeToString(SidebarState.serializer(), state))

        // The keys are the gateway's, because this exact object is the outbound payload.
        for (key in listOf("schema_version", "pinned_keys", "archived_keys", "session_order", "title_overrides")) {
            assertTrue(json.let { it is kotlinx.serialization.json.JsonObject && it.containsKey(key) }, "payload is missing $key: $json")
        }
        assertEquals(
            state,
            SidebarState.json.decodeFromString(SidebarState.serializer(), json.toString()),
            "decoding what it encoded gives the same blob",
        )

        val files = InMemoryFileStore()
        JsonFileStore(files).write("sidebar.json", state, SidebarState.serializer())
        assertEquals(
            state,
            JsonFileStore(files).read("sidebar.json", SidebarState.serializer()),
            "a second launch reads the same sidebar back",
        )
    }

    @Test
    fun manualOrderOverridesTheServerOrderAndPinsFloat() {
        val state = SidebarState().move(gamma, 0).pin(beta)

        assertEquals(
            listOf(beta, gamma, alpha),
            state.order(listOf(alpha, beta, gamma)),
            "the pinned row floats, then the manual order, then whatever the server sent",
        )
        assertEquals(
            listOf(alpha, beta, gamma),
            SidebarState().order(listOf(alpha, beta, gamma)),
            "with no manual order the server's order is the order",
        )
    }

    @Test
    fun mergeOrderDropsSessionsThatAreGone() {
        val state = SidebarState().move(gamma, 0).move(alpha, 1)

        assertEquals(
            listOf(gamma, alpha, beta),
            state.mergeOrder(listOf(alpha, beta, gamma)).sessionOrder,
            "a new session is appended, the manual order of the rest is kept",
        )
        assertEquals(
            listOf(gamma),
            state.mergeOrder(listOf(gamma)).sessionOrder,
            "a session that no longer exists leaves the order",
        )
    }
}
