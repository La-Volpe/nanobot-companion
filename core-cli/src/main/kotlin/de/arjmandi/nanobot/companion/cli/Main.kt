package de.arjmandi.nanobot.companion.cli

import de.arjmandi.nanobot.companion.cli.record.RecordCommand

/**
 * The harness. `record` is live from C2.5; the rest arrive with C8.3.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null, "--help", "-h" -> println(usage())
        "record" -> RecordCommand.run(args.drop(1))
        else -> {
            System.err.println("unknown command: ${args[0]} (try --help)")
            kotlin.system.exitProcess(2)
        }
    }
}

private fun usage(): String =
    """
    nanobot-companion-cli — drives :core without a UI (plan.md C8.3)

    usage: core-cli <command> [options]

    commands:
      record      capture wire fixtures for :core tests
                    [--profile .dev/profile.json] [--out core/src/commonTest/resources/fixtures]
                    [--prompt TEXT] [--gateway-rev REV] [--nanobot-src DIR]

    planned (phase C8):
      connect     bootstrap a gateway and open a socket
      sessions    list sessions
      send        send one message and stream the reply
      watch       follow a chat's frames as plain lines
      soak        reconnect/attach storm harness
    """.trimIndent()
