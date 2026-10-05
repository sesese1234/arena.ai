package com.nuvio.app.agent.llm

import com.nuvio.app.agent.core.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Provider-neutral conversation model. Each client translates it to its own wire format. */
@Serializable
sealed class ChatMessage {
    @Serializable
    data class User(val text: String) : ChatMessage()

    @Serializable
    data class Assistant(
        val text: String = "",
        val toolCalls: List<SerializableToolCall> = emptyList(),
        /** Reasoning text some providers require echoed back during tool loops (DeepSeek, Anthropic thinking). */
        val reasoning: String? = null,
        /** Opaque provider signature for reasoning blocks (Anthropic thinking signature, Gemini thought signature). */
        val reasoningSignature: String? = null,
    ) : ChatMessage()

    @Serializable
    data class ToolResult(
        val callId: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
    ) : ChatMessage()
}

@Serializable
data class SerializableToolCall(val id: String, val name: String, val argumentsJson: String) {
    fun toToolCall() = ToolCall(id, name, argumentsJson)
}

fun ToolCall.serializable() = SerializableToolCall(id, name, argumentsJson)

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val parallelToolCalls: Boolean = true,
)

data class TokenUsage(val inputTokens: Int = 0, val outputTokens: Int = 0)

sealed class LlmEvent {
    data class TextDelta(val text: String) : LlmEvent()
    data class ReasoningDelta(val text: String) : LlmEvent()
    /** Emitted when a tool call starts streaming, so the UI can show it before arguments arrive. */
    data class ToolCallStarted(val id: String, val name: String) : LlmEvent()
    data class Completed(
        val text: String,
        val toolCalls: List<ToolCall>,
        val reasoning: String?,
        val reasoningSignature: String?,
        val stopReason: String?,
        val usage: TokenUsage?,
    ) : LlmEvent()
}

interface LlmClient {
    /** Streams one assistant turn. Throws [LlmException] on HTTP/protocol errors. */
    fun stream(request: ChatRequest): Flow<LlmEvent>
}

class LlmException(
    message: String,
    val statusCode: Int? = null,
    val retryable: Boolean = false,
    /** True when the provider/model refused the tools payload (lets the agent retry without tools). */
    val toolsRejected: Boolean = false,
    val retryAfterMs: Long? = null,
) : RuntimeException(message)

internal fun classifyHttpError(provider: String, code: Int, body: String, retryAfterHeader: String?): LlmException {
    val snippet = body.replace(Regex("\\s+"), " ").take(400)
    val lower = snippet.lowercase()
    val toolsRejected = code in 400..422 && (
        "tool" in lower && ("support" in lower || "not allowed" in lower || "invalid" in lower || "unsupported" in lower) ||
            "function calling" in lower || "functions are not supported" in lower
        )
    val message = when (code) {
        401, 403 -> "$provider rejected the API key (HTTP $code). Check the key in Agent settings. $snippet"
        404 -> "$provider returned 404 — check the base URL and model id. $snippet"
        429 -> "$provider rate limit hit (HTTP 429). $snippet"
        else -> "$provider error HTTP $code: $snippet"
    }
    return LlmException(
        message = message,
        statusCode = code,
        retryable = code == 408 || code == 409 || code == 429 || code >= 500,
        toolsRejected = toolsRejected,
        retryAfterMs = retryAfterHeader?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() },
    )
}
