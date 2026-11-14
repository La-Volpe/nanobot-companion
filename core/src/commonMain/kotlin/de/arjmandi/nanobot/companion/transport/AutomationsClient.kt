package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.AutomationJob
import de.arjmandi.nanobot.companion.wire.AutomationsParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException

internal sealed interface AutomationsOutcome {
    data class Loaded(val jobs: List<AutomationJob>) : AutomationsOutcome
    data class Failed(val error: CoreError) : AutomationsOutcome
}

/**
 * `GET /api/webui/automations` (plan.md C7.2) — the only automation endpoint that is a plain
 * read. Every action is a socket mutation, because the gateway refuses WebUI writes over
 * HTTP with a 405 ("WebUI mutations require an authenticated WebSocket").
 */
internal class AutomationsClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {

    suspend fun fetch(apiToken: String): AutomationsOutcome = try {
        val response = http.get("${baseUrl.trimEnd('/')}/api/webui/automations") {
            header(HttpHeaders.Authorization, "Bearer $apiToken")
        }
        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                AutomationsOutcome.Failed(CoreError.AuthFailed)

            response.status != HttpStatusCode.OK ->
                AutomationsOutcome.Failed(CoreError.ServerError(status = response.status.value))

            else -> AutomationsOutcome.Loaded(AutomationsParser.parse(response.bodyAsText()))
        }
    } catch (failure: IOException) {
        AutomationsOutcome.Failed(classifyFailure(failure))
    } catch (failure: Exception) {
        AutomationsOutcome.Failed(classifyFailure(failure))
    }
}
