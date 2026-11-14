package de.arjmandi.nanobot.companion.cli

/**
 * P0.4 skeleton. Real subcommands (connect, sessions, send, watch, record, soak)
 * arrive with C8.3; until then this only proves the module runs and can see :core.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
        println(
            """
            nanobot-companion-cli — drives :core without a UI (plan.md C8.3)

            usage: core-cli <command> [options]

            commands (added in phase C8):
              connect     bootstrap a gateway and open a socket
              sessions    list sessions
              send        send one message and stream the reply
              watch       follow a chat's frames as plain lines
              record      capture wire fixtures for :core tests
              soak        reconnect/attach storm harness
            """.trimIndent(),
        )
        return
    }
    println("unknown command: ${args[0]} (try --help)")
    kotlin.system.exitProcess(2)
}
