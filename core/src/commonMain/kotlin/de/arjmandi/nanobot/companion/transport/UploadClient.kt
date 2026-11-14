package de.arjmandi.nanobot.companion.transport

import de.arjmandi.nanobot.companion.wire.UploadCapability
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal sealed interface UploadOutcome {
    data class Uploaded(val reference: String) : UploadOutcome
    data class Failed(val error: CoreError) : UploadOutcome
}

/**
 * `POST {ready.upload.path}` with the capability token (plan.md C3.6, D15.5). Bytes are
 * sent as one length-delimited body: the gateway refuses a chunked request, so streaming
 * the upload is not an option — one file, one request, whole.
 */
internal class UploadClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {

    suspend fun upload(capability: UploadCapability, file: CandidateFile, bytes: ByteArray): UploadOutcome = try {
        val response = http.post("${baseUrl.trimEnd('/')}${capability.path}") {
            header(HttpHeaders.Authorization, "Bearer ${capability.token}")
            file.name.let { name -> header("X-Attachment-Name", percentEncode(name)) }
            setBody(ByteArrayContent(bytes, ContentType.parse(file.mimeType)))
        }
        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                UploadOutcome.Failed(CoreError.AuthFailed)

            response.status == HttpStatusCode.Created -> {
                val reference = runCatching {
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject["reference"]!!.jsonPrimitive.content
                }.getOrNull()
                if (reference == null) {
                    UploadOutcome.Failed(CoreError.MissingField("reference"))
                } else {
                    UploadOutcome.Uploaded(reference)
                }
            }

            else -> UploadOutcome.Failed(CoreError.ServerError(status = response.status.value))
        }
    } catch (failure: IOException) {
        UploadOutcome.Failed(classifyFailure(failure))
    } catch (failure: Exception) {
        UploadOutcome.Failed(classifyFailure(failure))
    }

    private fun percentEncode(name: String): String = buildString {
        for (byte in name.encodeToByteArray()) {
            val char = byte.toInt().toChar()
            if (char.isLetterOrDigit() || char in "-._~") {
                append(char)
            } else {
                append('%').append(byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
