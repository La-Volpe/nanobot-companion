package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.SessionListParser
import de.arjmandi.nanobot.companion.wire.SessionRow
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException

internal sealed interface SessionListOutcome {
    data class Loaded(val rows: List<SessionRow>) : SessionListOutcome
    data class Failed(val error: CoreError) : SessionListOutcome
}

/**
 * `GET /api/sessions`. The write side of this gateway is socket-only, so this is a plain
 * read; it needs the bootstrap `api_token` as a bearer, which is why a session list cannot
 * be refreshed without a fresh bootstrap.
 */
internal class SessionListClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {

    suspend fun fetch(apiToken: String): SessionListOutcome = try {
        val response = http.get("${baseUrl.trimEnd('/')}/api/sessions") {
            header(HttpHeaders.Authorization, "Bearer $apiToken")
        }
        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                SessionListOutcome.Failed(CoreError.AuthFailed)

            response.status != HttpStatusCode.OK ->
                SessionListOutcome.Failed(CoreError.ServerError(status = response.status.value))

            else -> {
                val body = response.bodyAsText()
                SessionListOutcome.Loaded(SessionListParser.parse(body))
            }
        }
    } catch (failure: IOException) {
        SessionListOutcome.Failed(classifyFailure(failure))
    } catch (failure: Exception) {
        SessionListOutcome.Failed(classifyFailure(failure))
    }
}
