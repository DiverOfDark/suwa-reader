package app.suwareader.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GraphQLException(message: String) : IOException(message)

class NotConfiguredException : IOException("No server configured. Open Settings and enter your Suwayomi server address.")

class GraphQLClient(
    private val client: () -> OkHttpClient,
    private val baseUrl: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()

    suspend fun execute(query: String, variables: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val base = baseUrl().trimEnd('/')
        if (base.isBlank()) throw NotConfiguredException()
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables ?: JsonObject(emptyMap()))
        }.toString().toRequestBody(mediaType)
        val request = Request.Builder().url("$base/api/graphql").post(body).build()
        client().newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful && text.isBlank()) {
                throw IOException("HTTP ${response.code} from server")
            }
            if (response.code == 401) throw IOException("Server rejected the login (HTTP 401). Check user and password in Settings.")
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw IOException("Unexpected response from server (HTTP ${response.code})")
            }
            val errors = root["errors"]
            if (errors != null && errors != JsonNull) {
                val message = errors.jsonArray.joinToString("; ") {
                    it.jsonObject["message"]?.jsonPrimitive?.content?.substringBefore("\n")
                        // "Exception while fetching data (/fetchChapterPages) : <the actual problem>"
                        ?.replace(Regex("^Exception while fetching data \\([^)]*\\) : "), "")
                        ?.trim()?.take(300) ?: "error"
                }
                val data = root["data"]
                if (data == null || data == JsonNull || data.jsonObject.values.all { it == JsonNull }) {
                    throw GraphQLException(message)
                }
            }
            root["data"]?.jsonObject ?: throw GraphQLException("Empty response")
        }
    }

    fun <T> decode(deserializer: kotlinx.serialization.DeserializationStrategy<T>, element: JsonElement): T =
        json.decodeFromJsonElement(deserializer, element)
}
