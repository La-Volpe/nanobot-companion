package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.BootstrapParser
import de.arjmandi.nanobot.companion.wire.BootstrapPayload
import de.arjmandi.nanobot.companion.wire.BootstrapResult
import de.arjmandi.nanobot.companion.wire.ProtocolError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException

internal sealed interface BootstrapOutcome {
    data class Ready(val payload: BootstrapPayload) : BootstrapOutcome
    data class Failed(val error: CoreError) : BootstrapOutcome
}

/**
 * `GET /webui/bootstrap` with the gateway secret. The token it returns is single-use, so
 * this is a fresh call for every connection attempt and never a cached payload.
 */
internal class BootstrapClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val timeoutMs: Long = 15_000,
) {

    suspend fun fetch(secret: String): BootstrapOutcome = try {
        val response = http.get("${baseUrl.trimEnd('/')}/webui/bootstrap") {
            header(HEADER, secret)
        }
        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                BootstrapOutcome.Failed(CoreError.AuthFailed)

            response.status.value >= 500 ->
                BootstrapOutcome.Failed(CoreError.ServerError(status = response.status.value))

            response.status.value >= 400 ->
                BootstrapOutcome.Failed(CoreError.ServerError(status = response.status.value, detail = response.bodyAsText()))

            else -> when (val parsed = BootstrapParser.parse(response.bodyAsText())) {
                is BootstrapResult.Parsed -> BootstrapOutcome.Ready(parsed.payload)
                is BootstrapResult.Failed -> BootstrapOutcome.Failed(coreErrorOf(parsed.error))
            }
        }
    } catch (failure: IOException) {
        BootstrapOutcome.Failed(classifyFailure(failure))
    } catch (failure: Exception) {
        BootstrapOutcome.Failed(classifyFailure(failure))
    }

    internal companion object {
        const val HEADER: String = "X-Nanobot-Auth"
    }
}

/** The protocol layer names the same eight problems; map them once, here. */
internal fun coreErrorOf(error: ProtocolError): CoreError = when (error) {
    is ProtocolError.MissingField -> CoreError.MissingField(error.field)
    is ProtocolError.ProtocolTooNew -> CoreError.ProtocolTooNew(error.hostMinProtocol, error.clientProtocol)
    is ProtocolError.MissingCapability -> CoreError.MissingCapability(error.feature, error.capability)
}
