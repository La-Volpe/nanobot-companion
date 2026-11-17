package de.arjmandi.nanobot.companion.platform

/**
 * Drafts, the sidebar blob and the thread cache live as JSON files in the app's own
 * container. Names are relative to the store's directory; a name that
 * would leave it is a bug, not a fallback, so implementations reject it.
 */
public interface FileStore {
    public fun read(name: String): String?

    public fun write(name: String, text: String)

    public fun delete(name: String)
}
