package de.arjmandi.nanobot.companion.projection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The sidebar blob (plan.md C5.2): one immutable value that is both the local file and the
 * `set_sidebar_state` payload. The shape is the live gateway's, read from
 * `GET /api/webui/sidebar-state` — snake_case keys, `schema_version` 1, and the legacy
 * `archived_keys` list still on the wire, which this client keeps but never writes.
 *
 * `session_order` is the *manual* order: a session that is not in it falls back to the
 * server's `updated_at` order (C5.1), so [move] only has to keep what the user dragged.
 */
@Serializable
internal data class SidebarState(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("pinned_keys") val pinnedKeys: List<String> = emptyList(),
    @SerialName("archived_keys") val archivedKeys: List<String> = emptyList(),
    @SerialName("session_order") val sessionOrder: List<String> = emptyList(),
    @SerialName("title_overrides") val titleOverrides: Map<String, String> = emptyMap(),
    @SerialName("project_name_overrides") val projectNameOverrides: Map<String, String> = emptyMap(),
    @SerialName("tags_by_key") val tagsByKey: Map<String, List<String>> = emptyMap(),
    @SerialName("collapsed_groups") val collapsedGroups: Map<String, Boolean> = emptyMap(),
    @SerialName("updated_at") val updatedAt: String? = null,
) {

    /** Renaming to nothing removes the override, so the gateway's own title shows again. */
    fun rename(key: String, title: String): SidebarState {
        val cleaned = title.trim()
        return copy(
            titleOverrides = if (cleaned.isEmpty()) titleOverrides - key else titleOverrides + (key to cleaned),
        )
    }

    fun pin(key: String, pinned: Boolean = true): SidebarState =
        copy(pinnedKeys = if (pinned) (pinnedKeys + key).distinct() else pinnedKeys - key)

    fun archive(key: String, archived: Boolean = true): SidebarState =
        copy(archivedKeys = if (archived) (archivedKeys + key).distinct() else archivedKeys - key)

    /** Take the key out of the manual order and put it back at [toIndex], clamped. */
    fun move(key: String, toIndex: Int): SidebarState {
        val without = sessionOrder - key
        val target = toIndex.coerceIn(0, without.size)
        return copy(sessionOrder = without.take(target) + key + without.drop(target))
    }

    /** Reconcile the manual order with the sessions that exist right now. */
    fun mergeOrder(keys: List<String>): SidebarState {
        val present = keys.toSet()
        val kept = sessionOrder.filter { it in present }
        val added = keys.filterNot { it in kept }
        return copy(sessionOrder = kept + added)
    }

    /**
     * The order a list of sessions renders in: pinned rows float, then whatever the user
     * dragged, then the rest in the order the caller passed (the server's `updated_at`).
     */
    fun order(keys: List<String>): List<String> {
        val pinned = keys.filter { it in pinnedKeys }
        val manual = sessionOrder.filter { it in keys && it !in pinnedKeys }
        val rest = keys.filter { it !in pinned && it !in manual }
        return pinned + manual + rest
    }

    internal companion object {
        const val SCHEMA_VERSION: Int = 1

        /**
         * Defaults are encoded: the gateway normalises an absent `schema_version` to its own
         * default rather than rejecting it, and an absent `pinned_keys` would silently
         * unpin everything. This blob is sent whole or not at all.
         */
        val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
