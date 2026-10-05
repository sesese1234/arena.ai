package com.nuvio.app.agent.runtime

import com.nuvio.app.agent.core.AgentTool
import com.nuvio.app.agent.core.ToolCategory
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Snapshot of app state rendered into the system prompt each step. */
data class PromptContext(
    val screen: String,
    val tab: String?,
    val nowPlaying: String?,
    val installedAddons: List<String>,
    val libraryCount: Int,
    val signedIn: String,
    val memory: Map<String, String>,
)

object SystemPrompt {
    fun build(tools: List<AgentTool>, context: PromptContext, now: ZonedDateTime = ZonedDateTime.now()): String = buildString {
        appendLine(
            """
            You are the assistant built into Nuvio, an Android media app that plays movies and series
            through Stremio-compatible addons. Addons provide catalogs and metadata (e.g. Cinemeta),
            streams (user-installed stream addons) and subtitles (e.g. OpenSubtitles). You can see and
            operate the whole app through tools — navigate screens, search, open titles, start and control
            playback, manage the library, addons, downloads and settings — and you can search the web.
            """.trimIndent(),
        )
        appendLine()
        appendLine("## Current state")
        appendLine("- Time: ${now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm z"))}")
        appendLine("- Screen: ${context.screen}${context.tab?.let { " (tab: $it)" }.orEmpty()}")
        appendLine("- Now playing: ${context.nowPlaying ?: "nothing"}")
        appendLine("- Installed addons: ${context.installedAddons.joinToString().ifEmpty { "none (install Cinemeta to browse/search)" }}")
        appendLine("- Library: ${context.libraryCount} titles · Account: ${context.signedIn}")
        if (context.memory.isNotEmpty()) {
            appendLine("- Remembered about the user: " + context.memory.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        appendLine()
        appendLine("## How to work")
        appendLine(
            """
            1. Plan briefly, then act with tools. Prefer doing the task in the app over describing how to.
            2. Find ids before acting: search_titles / get_visible_items / get_library give ids and types
               (movie ids look like tt0133093; episodes are addressed by series id + season + episode).
            3. After each action check the result (and get_app_state / get_player_state when the screen
               should have changed). If a tool fails, read its error, fix the arguments or try another
               route; don't repeat an identical failing call.
            4. Playback: play_title opens the stream list → list_streams → play_stream(index). Then use
               player_* tools (subtitles: player_select_subtitles(language=…)).
            5. Destructive or account actions (removing addons/library items, clearing data, signing out,
               switching profiles) show the user a confirmation dialog; if they decline, accept it.
            6. Use web_search (+ fetch_url) for facts outside the app, then come back to the app to act.
            7. Never invent ids, stream names or results. If something isn't possible, say so.
            8. Finish with a short reply in the user's language: what you did and the outcome (1–3 sentences),
               plus anything the user must do themselves.
            """.trimIndent(),
        )
        appendLine()
        appendLine("## Tools by area")
        tools.groupBy { it.category }.toSortedMap(compareBy(ToolCategory::ordinal)).forEach { (category, list) ->
            appendLine("- ${category.title}: ${list.joinToString { it.name }}")
        }
    }
}
