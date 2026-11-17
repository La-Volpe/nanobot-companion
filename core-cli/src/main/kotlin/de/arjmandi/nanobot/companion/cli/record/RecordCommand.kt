package de.arjmandi.nanobot.companion.cli.record

import java.io.File
import kotlin.system.exitProcess

/**
 * `core-cli record [--profile PATH] [--out DIR] [--scenario NAME] [--prompt TEXT]
 * [--gateway-rev REV] [--nanobot-src DIR]`
 *
 * The provenance header is `manifest.json`: a JSON file cannot carry a comment, and a sidecar
 * is what the codec reads anyway.
 */
internal object RecordCommand {

    private const val DEFAULT_PROMPT = "Reply with exactly: pong"

    fun run(args: List<String>) {
        val flags = parse(args)
        val repo = repoRoot()
        val profileFile = File(flags["profile"] ?: File(repo, ".dev/profile.json").path)
        if (!profileFile.isFile) {
            fail("no profile at $profileFile; start the gateway with tools/nanobot-dev.sh start")
        }
        val nanobotSrc = File(flags["nanobot-src"] ?: System.getProperty("user.home") + "/Dev/nanobot")
        val commit = flags["gateway-rev"] ?: gitRevision(nanobotSrc)
            ?: fail("cannot read the gateway revision from $nanobotSrc; pass --gateway-rev <sha>")

        val scenarios = Scenarios.all(flags["prompt"] ?: DEFAULT_PROMPT)
            .let { all ->
                val wanted = flags["scenario"] ?: "all"
                if (wanted == "all") {
                    all
                } else {
                    val selected = all.filter { it.name == wanted }
                    if (selected.isEmpty()) fail("unknown scenario '$wanted'; try: ${all.joinToString() { it.name }} or all")
                    selected
                }
            }

        val recorder = Recorder(
            profile = GatewayProfile.read(profileFile.readText()),
            outRoot = File(flags["out"] ?: File(repo, "core/src/commonTest/resources/fixtures").path),
            scenarios = scenarios,
            gatewayCommit = commit,
            homeDir = System.getProperty("user.home"),
        )

        if (flags.containsKey("reads-only")) {
            val captured = try {
                recorder.captureReads()
            } catch (failure: Exception) {
                fail("capture failed: ${failure.message}")
            }
            println("captured reads into ${captured.path}")
            return
        }

        val set = try {
            recorder.run()
        } catch (failure: Exception) {
            fail("recording failed: ${failure.message}")
        }
        println("recorded ${set.path}")
        set.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { dir ->
            println("  ${dir.name}/ (${dir.listFiles().orEmpty().size} frames)")
        }
    }

    private fun parse(args: List<String>): Map<String, String> {
        val flags = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index]
            if (!key.startsWith("--")) fail("unexpected argument: $key")
            // A flag with no value is a switch (`--reads-only`); one with a value reads it.
            val next = args.getOrNull(index + 1)
            val value = if (next == null || next.startsWith("--")) "" else next
            flags[key.removePrefix("--")] = value
            index += if (value.isEmpty()) 1 else 2
        }
        return flags
    }

    private fun gitRevision(nanobotSrc: File): String? {
        if (!nanobotSrc.isDirectory) return null
        return try {
            val process = ProcessBuilder("git", "-C", nanobotSrc.path, "rev-parse", "--short", "HEAD")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText().trim()
                .takeIf { process.waitFor() == 0 && it.isNotEmpty() }
        } catch (missingGit: java.io.IOException) {
            null
        }
    }

    /** Gradle runs :core-cli from the root project, but a run from any subdirectory should work. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }

    private fun fail(message: String): Nothing {
        System.err.println("record: $message")
        exitProcess(1)
    }
}
