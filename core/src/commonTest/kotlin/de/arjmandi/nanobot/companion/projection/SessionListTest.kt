package de.arjmandi.nanobot.companion.projection

import de.arjmandi.nanobot.companion.transport.CoreError
import de.arjmandi.nanobot.companion.transport.SessionListClient
import de.arjmandi.nanobot.companion.wire.Iso8601
import de.arjmandi.nanobot.companion.wire.SessionListParser
import de.arjmandi.nanobot.companion.transport.SessionListOutcome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Session rows: `GET /api/sessions` with the bootstrap api token, ISO-8601 timestamps parsed to
 * instants, newest first, and a refused token mapped to the taxonomy instead of an empty drawer.
 */
class SessionListTest {

    private val page = """
        {"sessions":[
          {"key":"websocket:aaa","created_at":"2000-01-01T00:00:00.000000","updated_at":"2000-01-01T00:00:01.000000",
           "title":"old","preview":"first","model_preset":null,
           "workspace_scope":{"access_mode":"full","project_name":"workspace"}},
          {"key":"websocket:bbb","created_at":"2000-01-01T00:00:02.000000","updated_at":"2000-01-01T00:00:09.000000",
           "title":"newest","preview":"second","model_preset":"primary",
           "workspace_scope":{"access_mode":"default","project_name":"notes"}}
        ]}
    """.trimIndent()

    @Test
    fun mapsPayloadToRows() {
        val rows = SessionListParser.parse(page)

        assertEquals(2, rows.size)
        val newest = rows.first()
        assertEquals("websocket:bbb", newest.key, "the key is the session key")
        assertEquals("bbb", newest.chatId)
        assertEquals("newest", newest.title)
        assertEquals("second", newest.preview)
        assertEquals("primary", newest.modelPreset, "the drawer shows the preset per row")
        assertEquals("default", newest.accessMode)
        assertEquals("notes", newest.projectName)
    }

    @Test
    fun parsesIsoTimestamps() {
        assertEquals(946_684_801_000L, Iso8601.toEpochMillis("2000-01-01T00:00:01.000000"), "epoch, not a guess")
        assertEquals(946_684_800_000L, Iso8601.toEpochMillis("2000-01-01T00:00:00Z"), "a trailing Z is UTC")
        assertEquals(946_684_801_667L, Iso8601.toEpochMillis("2000-01-01T00:00:01.667888"), "microseconds truncate to millis")
        assertEquals(null, Iso8601.toEpochMillis("yesterday"), "an unknown shape is null, never a wrong instant")
        assertEquals(null, Iso8601.toEpochMillis("2000-13-01T00:00:00"), "an impossible month is null")
    }

    @Test
    fun sortsByUpdatedDesc() {
        val rows = SessionListParser.parse(page)

        assertEquals(listOf("websocket:bbb", "websocket:aaa"), rows.map { it.key })
        assertTrue(rows[0].updatedAtMs!! > rows[1].updatedAtMs!!)
    }

    @Test
    fun unauthorizedIsAuthFailed() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val client = SessionListClient(HttpClient(engine), "http://127.0.0.1:18765")

        val outcome = client.fetch(apiToken = "expired")

        assertEquals(CoreError.AuthFailed, (outcome as SessionListOutcome.Failed).error)
    }

    @Test
    fun fetchSendsTheApiTokenAsBearer() = runTest {
        var authorization: String? = null
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(page, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val outcome = SessionListClient(HttpClient(engine), "http://127.0.0.1:18765").fetch("api-token")

        assertEquals("Bearer api-token", authorization, "/api/sessions wants the bootstrap api_token")
        assertEquals(2, (outcome as SessionListOutcome.Loaded).rows.size)
    }
}
