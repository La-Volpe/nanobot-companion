package de.arjmandi.nanobot.companion.cli

import de.arjmandi.nanobot.companion.cli.record.RecordCommand

/**
 * The harness (plan.md C8.3). `record` captures fixtures; the rest drive `:core` against a
 * live gateway, and two of them are the instruments for G1's soak and transcript-parity items.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null, "--help", "-h" -> println(usage())
        "record" -> RecordCommand.run(args.drop(1))
        "connect" -> HarnessCommands.connect(args.drop(1))
        "sessions" -> HarnessCommands.sessions(args.drop(1))
        "send" -> HarnessCommands.send(args.drop(1))
        "watch" -> HarnessCommands.watch(args.drop(1))
        "soak" -> HarnessCommands.soak(args.drop(1))
        "session" -> SessionCommand.run(args.drop(1))
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
                    [--scenario plain|tool|error|failure|all] [--prompt TEXT]
                    [--gateway-rev REV] [--nanobot-src DIR]
                    [--reads-only]        capture only the HTTP reads into the newest set

      connect     bootstrap a gateway, open a socket, print what answered
      sessions    list the gateway's sessions as plain lines
      send        send one message and print the settled transcript
                    [--chat CHAT_ID] [--prompt TEXT] [--media PATH]
      watch       follow one chat and print a plain, diffable transcript
                    [--chat CHAT_ID] [--prompt TEXT | --prompts "a|b|c"] [--out FILE]
                    [--verify] [--history-out FILE]    compare with the WebUI's thread page
      session     the 20-minute scripted session G2 asks for (plan.md §7): connect,
                    send, stream, attach, transcribe, stop, reconnect mid-turn, resume
                    [--minutes 20] [--prompt TEXT]
      soak        reconnect/send soak harness (plan.md C8.4)
                    [--sends 200] [--reconnects 50] [--minutes 30] [--prompt TEXT]

    every command takes [--profile .dev/profile.json]
    """.trimIndent()
