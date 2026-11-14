package de.arjmandi.nanobot.companion.platform

import java.io.File
import java.nio.file.Files

/** Runs the shared contract against a real directory on the host JVM. */
class FileStoreTest : FileStoreContract() {

    private val root: File by lazy { Files.createTempDirectory("nanobot-companion-files").toFile() }

    override fun newStore(): FileStore = DirectoryFileStore(root)

    override fun reopenStore(): FileStore = DirectoryFileStore(root)
}
