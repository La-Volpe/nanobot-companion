package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.SettingsPayloads
import de.arjmandi.nanobot.companion.wire.SettingsState
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException

internal sealed interface SettingsOutcome {
    data class Loaded(val state: SettingsState) : SettingsOutcome
    data class Failed(val error: CoreError) : SettingsOutcome
}

/**
 * Settings → Overview (plan.md C7.1): `GET /api/settings` and `GET /api/settings/usage`, both
 * with the bootstrap `api_token` (D15.6). One call, two documents, because Overview shows the
 * version beside the totals and a half-loaded screen would say "0 tokens spent".
 */
internal class SettingsClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {

    suspend fun fetch(apiToken: String): SettingsOutcome = try {
        val base = baseUrl.trimEnd('/')
        val settings = read("$base/api/settings", apiToken)
        when (settings) {
            is Read.Failed -> SettingsOutcome.Failed(settings.error)
            is Read.Body -> {
                val usage = read("$base/api/settings/usage", apiToken)
                when (usage) {
                    is Read.Failed -> SettingsOutcome.Failed(usage.error)
                    is Read.Body -> SettingsOutcome.Loaded(
                        SettingsPayloads.settings(settings.text)
                            .copy(usage = SettingsPayloads.usage(usage.text)),
                    )
                }
            }
        }
    } catch (failure: IOException) {
        SettingsOutcome.Failed(classifyFailure(failure))
    } catch (failure: Exception) {
        SettingsOutcome.Failed(classifyFailure(failure))
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
