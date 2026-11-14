package de.arjmandi.nanobot.companion.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/** Runs the shared contract against a real directory in the simulator's temp space. */
@OptIn(ExperimentalForeignApi::class)
class FileStoreTest : FileStoreContract() {

    private val root: String by lazy {
        val path = NSTemporaryDirectory() + "nanobot-companion-" + NSUUID().UUIDString
        platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        path
    }

    override fun newStore(): FileStore = DirectoryFileStore(root)

    override fun reopenStore(): FileStore = DirectoryFileStore(root)
}
