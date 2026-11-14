package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.CliAppMention
import de.arjmandi.nanobot.companion.wire.PalettePayloads
import de.arjmandi.nanobot.companion.wire.SlashCommand
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException

internal sealed interface PaletteOutcome {
    data class Loaded(val commands: List<SlashCommand>, val apps: List<CliAppMention>) : PaletteOutcome
    data class Failed(val error: CoreError) : PaletteOutcome
}

/**
 * The two reads the palettes need (plan.md C6.4): `/api/commands` and the installed CLI
 * apps. Both take the bootstrap `api_token` as a bearer like every other HTTP read
 * (D15.6), and both are cheap enough to fetch when the sheet opens — the WebUI loads its
 * mention catalogs on demand rather than at connect (`thread-shell.test.tsx:5687`).
 */
internal class PaletteClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {

    suspend fun fetch(apiToken: String): PaletteOutcome = try {
        val base = baseUrl.trimEnd('/')
        val commands = read("$base/api/commands", apiToken)
        when (commands) {
            is Read.Failed -> PaletteOutcome.Failed(commands.error)
            is Read.Body -> {
                val apps = read("$base/api/settings/cli-apps?installed_only=1", apiToken)
                when (apps) {
                    is Read.Failed -> PaletteOutcome.Failed(apps.error)
                    is Read.Body -> PaletteOutcome.Loaded(
                        commands = PalettePayloads.commands(commands.text),
                        apps = PalettePayloads.installedApps(apps.text),
                    )
                }
            }
        }
    } catch (failure: IOException) {
        PaletteOutcome.Failed(classifyFailure(failure))
    } catch (failure: Exception) {
        PaletteOutcome.Failed(classifyFailure(failure))
    }

    private suspend fun read(url: String, apiToken: String): Read {
        val response = http.get(url) { header(HttpHeaders.Authorization, "Bearer $apiToken") }
        return when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                Read.Failed(CoreError.AuthFailed)

            response.status != HttpStatusCode.OK ->
                Read.Failed(CoreError.ServerError(status = response.status.value))

            else -> Read.Body(response.bodyAsText())
        }
    }

    private sealed interface Read {
        data class Body(val text: String) : Read
        data class Failed(val error: CoreError) : Read
    }
}
