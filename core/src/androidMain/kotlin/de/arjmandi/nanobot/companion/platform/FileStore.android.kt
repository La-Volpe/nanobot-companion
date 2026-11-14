package de.arjmandi.nanobot.companion.platform

import android.content.Context
import java.io.File

/** The Android app's private files directory: never the shared/external storage. */
public fun appSupportFileStore(context: Context, namespace: String): FileStore =
    DirectoryFileStore(File(context.filesDir, namespace))

internal class DirectoryFileStore(private val root: File) : FileStore {

    override fun read(name: String): String? = pathFor(name).takeIf { it.isFile }?.readText()

    override fun write(name: String, text: String) {
        val file = pathFor(name)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun delete(name: String) {
        pathFor(name).delete()
    }

    /** A name that would leave the store is a bug in the caller, not something to clamp. */
    private fun pathFor(name: String): File {
        require(name.isNotBlank()) { "empty file name" }
        require(!name.startsWith("/") && !name.split('/').contains("..")) { "path escapes the store: $name" }
        return File(root, name)
    }
}
