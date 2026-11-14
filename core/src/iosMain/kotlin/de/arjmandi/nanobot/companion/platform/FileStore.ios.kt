package de.arjmandi.nanobot.companion.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

/** The iOS container's Application Support directory, kept out of user-visible storage. */
public fun appSupportFileStore(namespace: String): FileStore {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: error("no Application Support directory on this platform")
    return DirectoryFileStore("$base/$namespace")
}

@OptIn(ExperimentalForeignApi::class)
internal class DirectoryFileStore(private val root: String) : FileStore {

    private val files = NSFileManager.defaultManager

    override fun read(name: String): String? {
        val path = pathFor(name)
        if (!files.fileExistsAtPath(path)) return null
        val data = NSData.create(contentsOfFile = path) ?: return null
        return data.toByteArray().decodeToString()
    }

    override fun write(name: String, text: String) {
        val path = pathFor(name)
        files.createDirectoryAtPath(path.substringBeforeLast('/', missingDelimiterValue = ""), true, null, null)
        check(text.encodeToByteArray().toNSData().writeToFile(path, true)) { "could not write $name" }
    }

    override fun delete(name: String) {
        files.removeItemAtPath(pathFor(name), null)
    }

    /** A name that would leave the store is a bug in the caller, not something to clamp. */
    private fun pathFor(name: String): String {
        require(name.isNotBlank()) { "empty file name" }
        require(!name.startsWith("/") && !name.split('/').contains("..")) { "path escapes the store: $name" }
        return "$root/$name"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.convert())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val pointer: CPointer<ByteVar> = bytes?.reinterpret() ?: return ByteArray(0)
    return pointer.readBytes(length.toInt())
}
