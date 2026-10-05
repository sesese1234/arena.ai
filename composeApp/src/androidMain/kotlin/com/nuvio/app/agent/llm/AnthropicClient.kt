package com.nuvio.app.agent.llm

import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.core.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Native Anthropic Messages API client (streaming + tool use + extended-thinking passthrough).
 * With [vertexTokenProvider] set it targets Claude on Google Vertex AI (`:streamRawPredict`).
 */
class AnthropicClient(
    private val resolved: ResolvedModel,
    private val http: OkHttpClient = AgentHttp.client,
    private val vertexTokenProvider: (suspend () -> String)? = null,
) : LlmClient {

    override fun stream(request: ChatRequest): Flow<LlmEvent> {
        val providerName = resolved.provider.name
        return kotlinx.coroutines.flow.flow {
            val headers = buildMap {
                put("Accept", "text/event-stream")
                if (vertexTokenProvider != null) {
                    put("Authorization", "Bearer ${vertexTokenProvider.invoke()}")
                } else {
                    resolved.secret(ProviderConfig.SECRET_API_KEY)?.let { put("x-api-key", it) }
                    put("anthropic-version", API_VERSION)
                }
                putAll(resolved.provider.headers)
            }
            val httpRequest = jsonPost(endpointUrl(), buildBody(request), headers)
            emitAll(streamingCall(http, providerName, httpRequest) { response -> parseStream(providerName, response) })
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<LlmEvent>.parseStream(
        providerName: String,
        response: okhttp3.Response,
    ) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        var signature: String? = null
        val blocks = mutableMapOf<Int, String>() // index -> block type
        val toolCalls = ToolCallAccumulator()
        var stopReason: String? = null
        var inputTokens = 0
        var outputTokens = 0
        val source = response.body.source()
        source.forEachSseEvent { event ->
            val json = runCatching { AgentJson.parseToJsonElement(event.data).jsonObject }.getOrNull()
                ?: return@forEachSseEvent true
            when (json["type"]?.jsonPrimitive?.contentOrNull ?: event.event) {
                "message_start" -> json["message"]?.jsonObject?.get("usage")?.jsonObject?.let { usage ->
                    inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                }
                "content_block_start" -> {
                    val index = json["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val block = json["content_block"]?.jsonObject ?: return@forEachSseEvent true
                    val type = block["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    blocks[index] = type
                    if (type == "tool_use") {
                        val id = block["id"]?.jsonPrimitive?.contentOrNull
                        val name = block["name"]?.jsonPrimitive?.contentOrNull
                        toolCalls.add(index, id, name, null)
                        emit(LlmEvent.ToolCallStarted(id ?: "toolu_$index", name.orEmpty()))
                    }
                }
                "content_block_delta" -> {
                    val index = json["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val delta = json["delta"]?.jsonObject ?: return@forEachSseEvent true
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let {
                            text.append(it)
                            emit(LlmEvent.TextDelta(it))
                        }
                        "input_json_delta" -> toolCalls.add(index, null, null, delta["partial_json"]?.jsonPrimitive?.contentOrNull)
                        "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let {
                            thinking.append(it)
                            emit(LlmEvent.ReasoningDelta(it))
                        }
                        "signature_delta" -> signature = (signature.orEmpty() + delta["signature"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                }
                "message_delta" -> {
                    json["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
                    json["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
                }
                "error" -> {
                    val error = json["error"]?.jsonObject
                    val type = error?.get("type")?.jsonPrimitive?.contentOrNull
                    throw LlmException(
                        "$providerName stream error: ${error?.get("message")?.jsonPrimitive?.contentOrNull ?: json}",
                        retryable = type == "overloaded_error" || type == "api_error" || type == "rate_limit_error",
                    )
                }
                "message_stop" -> return@forEachSseEvent false
            }
            true
        }
        emit(
            LlmEvent.Completed(
                text = text.toString(),
                toolCalls = toolCalls.build("toolu"),
                reasoning = thinking.toString().ifEmpty { null },
                reasoningSignature = signature,
                stopReason = stopReason,
                usage = TokenUsage(inputTokens, outputTokens),
            ),
        )
    }

    internal fun endpointUrl(): String {
        if (vertexTokenProvider != null) {
            return vertexModelUrl(resolved, publisher = "anthropic", method = "streamRawPredict", sse = false)
        }
        val base = resolved.baseUrl
        return if (base.endsWith("/messages")) base else "$base/messages"
    }

    internal fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        if (vertexTokenProvider != null) put("anthropic_version", VERTEX_VERSION) else put("model", request.model)
        put("stream", true)
        put("max_tokens", request.maxTokens ?: DEFAULT_MAX_TOKENS)
        if (request.system.isNotBlank()) put("system", request.system)
        request.temperature?.let { put("temperature", it) }
        put("messages", buildMessages(request.messages))
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                    })
                }
            })
            put("tool_choice", buildJsonObject {
                put("type", "auto")
                if (!request.parallelToolCalls) put("disable_parallel_tool_use", true)
            })
        }
    }

    private fun buildMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        // Anthropic needs alternating roles; consecutive tool results merge into one user turn.
        var pendingResults = mutableListOf<JsonObject>()
        fun flushResults(target: kotlinx.serialization.json.JsonArrayBuilder) {
            if (pendingResults.isEmpty()) return
            target.add(buildJsonObject {
                put("role", "user")
                put("content", JsonArray(pendingResults.toList()))
            })
            pendingResults = mutableListOf()
        }
        messages.forEach { message ->
            when (message) {
                is ChatMessage.ToolResult -> pendingResults += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", message.callId)
                    put("content", message.content)
                    if (message.isError) put("is_error", true)
                }
                is ChatMessage.User -> {
                    flushResults(this)
                    add(buildJsonObject { put("role", "user"); put("content", message.text) })
                }
                is ChatMessage.Assistant -> {
                    flushResults(this)
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", buildJsonArray {
                            if (message.reasoning != null && message.reasoningSignature != null) {
                                add(buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", message.reasoning)
                                    put("signature", message.reasoningSignature)
                                })
                            }
                            if (message.text.isNotBlank()) {
                                add(buildJsonObject { put("type", "text"); put("text", message.text) })
                            }
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("input", parseArgsObject(call.toToolCall()))
                                })
                            }
                        })
                    })
                }
            }
        }
        flushResults(this)
    }

    companion object {
        const val API_VERSION = "2023-06-01"
        const val VERTEX_VERSION = "vertex-2023-10-16"
        const val DEFAULT_MAX_TOKENS = 4096
    }
}

/** Arguments as a JSON object for providers that want structured input (Anthropic, Gemini, Bedrock). */
internal fun parseArgsObject(call: ToolCall): JsonElement =
    runCatching { AgentJson.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }) }
        .getOrNull()
        ?.takeIf { it is JsonObject }
        ?: JsonObject(emptyMap())
