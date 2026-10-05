package com.nuvio.app.agent.runtime

import com.nuvio.app.agent.llm.ChatMessage

/**
 * Keeps the conversation inside the model's context window. Strategy, cheapest first:
 *  1. shorten tool results from older turns (the model already acted on them);
 *  2. drop whole old turns (a turn = user message + everything until the next user message),
 *     replacing them with a short extractive summary so the model keeps the gist.
 * Turn boundaries are preserved so tool calls and tool results always stay paired.
 */
class ContextManager(
    private val charsPerToken: Double = 3.5,
    private val keepRecentTurns: Int = 2,
    private val oldToolResultChars: Int = 600,
) {
    fun estimateTokens(text: String): Int = (text.length / charsPerToken).toInt() + 4

    fun estimateTokens(messages: List<ChatMessage>): Int = messages.sumOf { message ->
        when (message) {
            is ChatMessage.User -> estimateTokens(message.text)
            is ChatMessage.Assistant -> estimateTokens(message.text) + estimateTokens(message.reasoning.orEmpty()) +
                message.toolCalls.sumOf { estimateTokens(it.argumentsJson) + 8 }
            is ChatMessage.ToolResult -> estimateTokens(message.content)
        }
    }

    data class Fitted(val messages: List<ChatMessage>, val trimmed: Boolean, val droppedTurns: Int)

    fun fit(messages: List<ChatMessage>, budgetTokens: Int): Fitted {
        if (estimateTokens(messages) <= budgetTokens) return Fitted(messages, trimmed = false, droppedTurns = 0)

        val turns = splitTurns(messages)
        // 1) Shrink tool outputs outside the most recent turns.
        val shrunk = turns.mapIndexed { index, turn ->
            if (index >= turns.size - keepRecentTurns) turn else turn.map { message ->
                if (message is ChatMessage.ToolResult && message.content.length > oldToolResultChars) {
                    message.copy(content = message.content.take(oldToolResultChars) + "…[older tool output trimmed]")
                } else {
                    message
                }
            }
        }
        var working = shrunk
        if (estimateTokens(working.flatten()) <= budgetTokens) return Fitted(working.flatten(), trimmed = true, droppedTurns = 0)

        // 2) Drop oldest turns into a summary until we fit (always keep the latest turn).
        val dropped = mutableListOf<List<ChatMessage>>()
        while (working.size > 1 && estimateTokens(listOf(summaryMessage(dropped)) + working.flatten()) > budgetTokens) {
            dropped += working.first()
            working = working.drop(1)
        }
        val result = if (dropped.isEmpty()) working.flatten() else listOf(summaryMessage(dropped)) + working.flatten()
        return Fitted(result, trimmed = true, droppedTurns = dropped.size)
    }

    private fun splitTurns(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val turns = mutableListOf<MutableList<ChatMessage>>()
        messages.forEach { message ->
            if (message is ChatMessage.User || turns.isEmpty()) turns += mutableListOf<ChatMessage>()
            turns.last() += message
        }
        return turns
    }

    private fun summaryMessage(dropped: List<List<ChatMessage>>): ChatMessage.User {
        val lines = dropped.flatten().mapNotNull { message ->
            when (message) {
                is ChatMessage.User -> if (message.text.startsWith(SUMMARY_PREFIX)) message.text.removePrefix(SUMMARY_PREFIX).trim() else "User asked: ${message.text.take(200)}"
                is ChatMessage.Assistant -> buildString {
                    if (message.toolCalls.isNotEmpty()) append("Agent used ${message.toolCalls.joinToString { it.name }}. ")
                    if (message.text.isNotBlank()) append("Agent replied: ${message.text.take(200)}")
                }.takeIf { it.isNotBlank() }
                is ChatMessage.ToolResult -> null
            }
        }
        return ChatMessage.User("$SUMMARY_PREFIX\n" + lines.takeLast(30).joinToString("\n") { "- $it" })
    }

    companion object {
        const val SUMMARY_PREFIX = "[Summary of earlier conversation, older messages were trimmed to fit the context window]"
    }
}
