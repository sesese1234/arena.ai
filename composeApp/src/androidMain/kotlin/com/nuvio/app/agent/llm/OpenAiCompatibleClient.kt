package com.nuvio.app.agent.llm

import com.nuvio.app.agent.core.AgentJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Chat Completions client used by the majority of models.dev providers (DeepSeek, OpenAI,
 * OpenRouter, Groq, Mistral, xAI, Together, Ollama, LM Studio, LiteLLM, …) and by Azure OpenAI.
 */
class OpenAiCompatibleClient(
    private val resolved: ResolvedModel,
    private val http: OkHttpClient = AgentHttp.client,
) : LlmClient {

    override fun stream(request: ChatRequest): Flow<LlmEvent> {
        val providerName = resolved.provider.name
        val body = buildBody(request)
        val httpRequest = jsonPost(endpointUrl(), body, headers())
        return streamingCall(http, providerName, httpRequest) { response ->
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val toolCalls = ToolCallAccumulator()
            var stopReason: String? = null
            var usage: TokenUsage? = null
            val source = response.body.source()
            val contentType = response.header("content-type").orEmpty()
            if (!contentType.contains("event-stream") && contentType.contains("json")) {
                // Some gateways ignore stream=true; accept a plain JSON completion.
                val json = AgentJson.parseToJsonElement(source.readUtf8()).jsonObject
                parseNonStreaming(json, text, reasoning, toolCalls)?.let { stopReason = it }
                if (text.isNotEmpty()) emit(LlmEvent.TextDelta(text.toString()))
            } else {
                source.forEachSseEvent { event ->
                    val data = event.data.trim()
                    if (data == "[DONE]") return@forEachSseEvent false
                    val json = runCatching { AgentJson.parseToJsonElement(data).jsonObject }.getOrNull()
                        ?: return@forEachSseEvent true
                    json["error"]?.let { error ->
                        throw LlmException("$providerName stream error: ${error.toString().take(400)}", retryable = true)
                    }
                    json["usage"]?.takeIf { it is JsonObject }?.jsonObject?.let { u ->
                        usage = TokenUsage(
                            inputTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                            outputTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        )
                    }
                    val choice = (json["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@forEachSseEvent true
                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
                    val delta = choice["delta"] as? JsonObject ?: return@forEachSseEvent true
                    val reasoningDelta = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                        ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull
                    if (!reasoningDelta.isNullOrEmpty()) {
                        reasoning.append(reasoningDelta)
                        emit(LlmEvent.ReasoningDelta(reasoningDelta))
                    }
                    delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                        text.append(it)
                        emit(LlmEvent.TextDelta(it))
                    }
                    (delta["tool_calls"] as? JsonArray)?.forEachIndexed { position, element ->
                        val call = element.jsonObject
                        val index = call["index"]?.jsonPrimitive?.intOrNull ?: position
                        val function = call["function"] as? JsonObject
                        val started = toolCalls.add(
                            index = index,
                            id = call["id"]?.jsonPrimitive?.contentOrNull,
                            name = function?.get("name")?.jsonPrimitive?.contentOrNull,
                            argumentsDelta = function?.get("arguments")?.jsonPrimitive?.contentOrNull,
                        )
                        if (started) emit(LlmEvent.ToolCallStarted(toolCalls.idOf(index) ?: "call_$index", toolCalls.nameOf(index)!!))
                    }
                    true
                }
            }
            emit(
                LlmEvent.Completed(
                    text = text.toString(),
                    toolCalls = toolCalls.build(),
                    reasoning = reasoning.toString().ifEmpty { null },
                    reasoningSignature = null,
                    stopReason = stopReason,
                    usage = usage,
                ),
            )
        }
    }

    private fun parseNonStreaming(
        json: JsonObject,
        text: StringBuilder,
        reasoning: StringBuilder,
        toolCalls: ToolCallAccumulator,
    ): String? {
        val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val message = choice["message"]?.jsonObject ?: return null
        message["content"]?.jsonPrimitive?.contentOrNull?.let(text::append)
        message["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let(reasoning::append)
        message["tool_calls"]?.jsonArray?.forEachIndexed { index, element ->
            val call = element.jsonObject
            val function = call["function"]?.jsonObject
            toolCalls.add(
                index,
                call["id"]?.jsonPrimitive?.contentOrNull,
                function?.get("name")?.jsonPrimitive?.contentOrNull,
                function?.get("arguments")?.jsonPrimitive?.contentOrNull,
            )
        }
        return choice["finish_reason"]?.jsonPrimitive?.contentOrNull
    }

    internal fun endpointUrl(): String {
        val base = resolved.baseUrl
        if (resolved.apiKind != ApiKind.AzureOpenAi) {
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }
        val apiVersion = resolved.provider.variables[ProviderConfig.VAR_AZURE_API_VERSION]?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AZURE_API_VERSION
        return if (apiVersion == "v1" || base.endsWith("/openai/v1")) {
            val root = base.removeSuffix("/openai/v1").removeSuffix("/openai")
            "$root/openai/v1/chat/completions"
        } else {
            val root = base.removeSuffix("/openai")
            "$root/openai/deployments/${resolved.model.id}/chat/completions?api-version=$apiVersion"
        }
    }

    internal fun headers(): Map<String, String> {
        val key = resolved.secret(ProviderConfig.SECRET_API_KEY)
        val auth = when {
            key == null -> emptyMap()
            resolved.apiKind == ApiKind.AzureOpenAi -> mapOf("api-key" to key)
            else -> mapOf("Authorization" to "Bearer $key")
        }
        return mapOf("Accept" to "text/event-stream") + auth + resolved.provider.headers
    }

    internal fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        put("messages", buildJsonArray {
            if (request.system.isNotBlank()) {
                add(buildJsonObject { put("role", "system"); put("content", request.system) })
            }
            request.messages.forEach { message ->
                when (message) {
                    is ChatMessage.User -> add(buildJsonObject { put("role", "user"); put("content", message.text) })
                    is ChatMessage.Assistant -> add(buildJsonObject {
                        put("role", "assistant")
                        put("content", message.text)
                        // DeepSeek thinking models require reasoning_content echoed back inside a tool loop.
                        if (message.reasoning != null && message.toolCalls.isNotEmpty()) {
                            put("reasoning_content", message.reasoning)
                        }
                        if (message.toolCalls.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                message.toolCalls.forEach { call ->
                                    add(buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", call.name)
                                            put("arguments", call.argumentsJson)
                                        })
                                    })
                                }
                            })
                        }
                    })
                    is ChatMessage.ToolResult -> add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", message.callId)
                        put("content", message.content)
                    })
                }
            }
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        })
                    })
                }
            })
            put("tool_choice", "auto")
            if (!request.parallelToolCalls) put("parallel_tool_calls", false)
        }
        request.temperature?.let { put("temperature", it) }
        request.maxTokens?.let { put("max_tokens", it) }
    }

    companion object {
        const val DEFAULT_AZURE_API_VERSION = "2024-10-21"
    }
}

internal fun JsonPrimitive.contentOrEmpty(): String = contentOrNull.orEmpty()
