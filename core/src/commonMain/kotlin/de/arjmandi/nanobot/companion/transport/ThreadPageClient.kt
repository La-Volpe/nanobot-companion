package de.arjmandi.nanobot.companion.transport

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal sealed interface ThreadPageOutcome {
    data class Loaded(val page: JsonObject) : ThreadPageOutcome
    data class Failed(val error: CoreError) : ThreadPageOutcome
}

/**
 * `GET /api/sessions/{key}/webui-thread` (plan.md C4.6, D46): the page the WebUI reads when a
 * session is opened. The socket's `attach` does not replay history — the gateway answers it with
 * runtime facts only (`session_projection.attach_fields`) — so this read is how an opened chat
 * shows the turns that came before. Its events are the socket's own vocabulary, which is why the
 * caller reduces them with the same `ThreadReducer` (D9).
 */
internal class ThreadPageClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(apiToken: String, sessionKey: String): ThreadPageOutcome = try {
        // The WebUI builds this path with `encodeURIComponent(sessionKey)`, which escapes the
        // colon of `websocket:<id>`; Ktor's path encoder leaves a colon alone, so it is escaped
        // here to ask for the same URL the browser asks for (D46).
        val key = sessionKey.encodeURLPathPart().replace(":", "%3A")
        val url = "${baseUrl.trimEnd('/')}/api/sessions/$key/webui-thread?limit=$PAGE_LIMIT&direction=$PAGE_DIRECTION"
        val response = http.get(url) {
            header(HttpHeaders.Authorization, "Bearer $apiToken")
        }
        when {
            // A session the gateway has never stored has no page, and that is not a failure.
            response.status == HttpStatusCode.NotFound -> ThreadPageOutcome.Loaded(JsonObject(emptyMap()))

            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                ThreadPageOutcome.Failed(CoreError.AuthFailed)

            response.status != HttpStatusCode.OK ->
                ThreadPageOutcome.Failed(CoreError.ServerError(status = response.status.value))

            else -> when (val page = json.parseToJsonElement(response.bodyAsText())) {
                is JsonObject -> ThreadPageOutcome.Loaded(page)
                // A page that is not an object has no events this client can reduce.
                else -> ThreadPageOutcome.Failed(CoreError.MissingField("events"))
            }
        }
    } catch (failure: IOException) {
        ThreadPageOutcome.Failed(classifyFailure(failure))
    } catch (failure: Exception) {
        ThreadPageOutcome.Failed(classifyFailure(failure))
    }

    private companion object {
        /** The WebUI's own page size: the latest turns, not the whole conversation (`api.ts`). */
        const val PAGE_LIMIT: Int = 120

        /** Newest first from the gateway, so the newest turns are the ones that arrive. */
        const val PAGE_DIRECTION: String = "latest"
    }
}
