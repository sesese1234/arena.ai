package com.nuvio.app.agent.llm

import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.core.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Google Gemini `streamGenerateContent` client (AI Studio key auth). With [vertexTokenProvider]
 * set it talks to Gemini on Vertex AI using an OAuth bearer token instead.
 */
class GeminiClient(
    private val resolved: ResolvedModel,
    private val http: OkHttpClient = AgentHttp.client,
    private val vertexTokenProvider: (suspend () -> String)? = null,
) : LlmClient {

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val headers = buildMap {
            if (vertexTokenProvider != null) {
                put("Authorization", "Bearer ${vertexTokenProvider.invoke()}")
            } else {
                resolved.secret(ProviderConfig.SECRET_API_KEY)?.let { put("x-goog-api-key", it) }
            }
            putAll(resolved.provider.headers)
        }
        val httpRequest = jsonPost(endpointUrl(request.model), buildBody(request), headers)
        emitAll(
            streamingCall(http, resolved.provider.name, httpRequest) { response ->
                val text = StringBuilder()
                val thoughts = StringBuilder()
                var signature: String? = null
                val calls = mutableListOf<ToolCall>()
                var stopReason: String? = null
                var usage: TokenUsage? = null
                val source = response.body.source()
                source.forEachSseEvent { event ->
                    val json = runCatching { AgentJson.parseToJsonElement(event.data).jsonObject }.getOrNull()
                        ?: return@forEachSseEvent true
                    json["error"]?.let { throw LlmException("${resolved.provider.name} error: ${it.toString().take(400)}", retryable = true) }
                    json["usageMetadata"]?.jsonObject?.let { u ->
                        usage = TokenUsage(
                            u["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                            u["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        )
                    }
                    val candidate = (json["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@forEachSseEvent true
                    candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
                    val parts = candidate["content"]?.jsonObject?.get("parts") as? JsonArray ?: return@forEachSseEvent true
                    parts.forEach { element ->
                        val part = element.jsonObject
                        part["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.let { signature = it }
                        val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                        part["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { chunk ->
                            if (isThought) {
                                thoughts.append(chunk)
                                emit(LlmEvent.ReasoningDelta(chunk))
                            } else {
                                text.append(chunk)
                                emit(LlmEvent.TextDelta(chunk))
                            }
                        }
                        part["functionCall"]?.jsonObject?.let { call ->
                            val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: "gemini_${calls.size}_$name"
                            val args = call["args"] ?: JsonObject(emptyMap())
                            calls += ToolCall(id, name, AgentJson.encodeToString(JsonElement.serializer(), args))
                            emit(LlmEvent.ToolCallStarted(id, name))
                        }
                    }
                    true
                }
                emit(
                    LlmEvent.Completed(
                        text = text.toString(),
                        toolCalls = calls,
                        reasoning = thoughts.toString().ifEmpty { null },
                        reasoningSignature = signature,
                        stopReason = stopReason,
                        usage = usage,
                    ),
                )
            },
        )
    }

    internal fun endpointUrl(model: String): String =
        if (vertexTokenProvider != null) {
            vertexModelUrl(resolved, publisher = "google", method = "streamGenerateContent", sse = true)
        } else {
            val base = resolved.baseUrl
            val path = if (model.startsWith("models/")) model else "models/$model"
            "$base/$path:streamGenerateContent?alt=sse"
        }

    internal fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        if (request.system.isNotBlank()) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", request.system) }) })
            })
        }
        put("contents", buildContents(request.messages))
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        request.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                val params = sanitizeGeminiSchema(tool.parameters)
                                if ((params as? JsonObject)?.get("properties")?.jsonObject?.isNotEmpty() == true) {
                                    put("parameters", params)
                                }
                            })
                        }
                    })
                })
            })
        }
        put("generationConfig", buildJsonObject {
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("maxOutputTokens", it) }
        })
    }

    private fun buildContents(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var pendingResponses = mutableListOf<JsonObject>()
        fun flush(target: kotlinx.serialization.json.JsonArrayBuilder) {
            if (pendingResponses.isEmpty()) return
            target.add(buildJsonObject { put("role", "user"); put("parts", JsonArray(pendingResponses.toList())) })
            pendingResponses = mutableListOf()
        }
        messages.forEach { message ->
            when (message) {
                is ChatMessage.ToolResult -> pendingResponses += buildJsonObject {
                    put("functionResponse", buildJsonObject {
                        put("name", message.name)
                        put("response", toolResponseObject(message.content, message.isError))
                    })
                }
                is ChatMessage.User -> {
                    flush(this)
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", message.text) }) })
                    })
                }
                is ChatMessage.Assistant -> {
                    flush(this)
                    add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            if (message.text.isNotBlank()) add(buildJsonObject { put("text", message.text) })
                            message.toolCalls.forEachIndexed { index, call ->
                                add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", call.name)
                                        put("args", parseArgsObject(call.toToolCall()))
                                    })
                                    // Gemini 2.5+/3 require the thought signature on the first function call part.
                                    if (index == 0 && message.reasoningSignature != null) {
                                        put("thoughtSignature", message.reasoningSignature)
                                    }
                                })
                            }
                            if (message.text.isBlank() && message.toolCalls.isEmpty()) add(buildJsonObject { put("text", "") })
                        })
                    })
                }
            }
        }
        flush(this)
    }

    private fun toolResponseObject(content: String, isError: Boolean): JsonObject {
        val parsed = runCatching { AgentJson.parseToJsonElement(content) }.getOrNull()
        return when {
            parsed is JsonObject -> parsed
            parsed != null -> buildJsonObject { put(if (isError) "error" else "result", parsed) }
            else -> buildJsonObject { put(if (isError) "error" else "result", content) }
        }
    }
}

/** Gemini accepts an OpenAPI subset: drop keywords it rejects (additionalProperties, min/max…). */
internal fun sanitizeGeminiSchema(schema: JsonElement): JsonElement = when (schema) {
    is JsonObject -> buildJsonObject {
        schema.forEach { (key, value) ->
            when (key) {
                "additionalProperties", "\$schema", "minimum", "maximum", "default", "examples" -> Unit
                "properties" -> put(key, buildJsonObject {
                    value.jsonObject.forEach { (name, prop) -> put(name, sanitizeGeminiSchema(prop)) }
                })
                "required" -> if ((value as? JsonArray)?.isNotEmpty() == true) put(key, value)
                else -> put(key, sanitizeGeminiSchema(value))
            }
        }
    }
    is JsonArray -> JsonArray(schema.map(::sanitizeGeminiSchema))
    else -> schema
}

/** Builds a Vertex AI publisher model URL; location "global" uses the non-regional host. */
internal fun vertexModelUrl(resolved: ResolvedModel, publisher: String, method: String, sse: Boolean): String {
    val vars = resolved.provider.variables
    val project = vars[ProviderConfig.VAR_VERTEX_PROJECT]?.takeIf { it.isNotBlank() }
        ?: throw LlmException("Set GOOGLE_VERTEX_PROJECT for ${resolved.provider.name} in Agent settings.")
    val location = vars[ProviderConfig.VAR_VERTEX_LOCATION]?.takeIf { it.isNotBlank() } ?: "global"
    val host = if (location == "global") "aiplatform.googleapis.com" else "$location-aiplatform.googleapis.com"
    val base = resolved.model.baseUrlOverride ?: "https://$host/v1"
    return "$base/projects/$project/locations/$location/publishers/$publisher/models/${resolved.model.id}:$method" +
        if (sse) "?alt=sse" else ""
}

internal fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.arr(name: String): JsonArray? = this[name]?.let { it as? JsonArray ?: it.jsonArray }
