package com.nuvio.app.agent.tools

import com.nuvio.app.agent.AgentPlayerState
import com.nuvio.app.agent.core.ToolCategory
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.core.okResult
import com.nuvio.app.agent.core.optDouble
import com.nuvio.app.agent.core.optInt
import com.nuvio.app.agent.core.optString
import com.nuvio.app.agent.core.int
import kotlinx.coroutines.delay
import java.util.Locale

internal fun ToolRegistry.registerPlayerTools() {
    tool("get_player_state") {
        category = ToolCategory.Player
        readOnly = true
        description = """
            What is playing: title/episode, position and duration (seconds), playing/paused, speed,
            audio tracks, built-in subtitle tracks and addon subtitles (with indices/ids), subtitle delay.
        """
        covers("AgentPlayerHandle.state")
        run { okResult("player" to player().state().toMap()) }
    }

    tool("player_pause") {
        category = ToolCategory.Player
        description = "Pause playback."
        covers("AgentPlayerHandle.pause")
        run { player().pause(); delay(300); okResult("playing" to player().state().isPlaying) }
    }

    tool("player_resume") {
        category = ToolCategory.Player
        description = "Resume (play) playback."
        covers("AgentPlayerHandle.play")
        run { player().play(); delay(500); okResult("playing" to player().state().isPlaying) }
    }

    tool("player_seek") {
        category = ToolCategory.Player
        description = """
            Seek. Use position_seconds for an absolute position (e.g. 90 = 1:30) or offset_seconds
            to jump relative to now (e.g. -10 to rewind ten seconds).
        """
        covers("AgentPlayerHandle.seekTo", "AgentPlayerHandle.seekBy")
        params {
            number("position_seconds", "Absolute position in seconds", min = 0.0)
            number("offset_seconds", "Relative jump in seconds (negative rewinds)")
        }
        run { args ->
            val handle = player()
            val absolute = args.optDouble("position_seconds")
            val offset = args.optDouble("offset_seconds")
            when {
                absolute != null -> handle.seekTo((absolute * 1000).toLong())
                offset != null -> handle.seekBy((offset * 1000).toLong())
                else -> throw ToolFailure("Pass position_seconds or offset_seconds", "invalid_arguments")
            }
            delay(700)
            val state = handle.state()
            okResult("position_s" to state.positionMs / 1000, "duration_s" to state.durationMs / 1000)
        }
    }

    tool("player_set_speed") {
        category = ToolCategory.Player
        description = "Set the playback speed (0.25–4.0, 1.0 = normal)."
        covers("AgentPlayerHandle.setSpeed")
        params { number("speed", "Playback speed multiplier", required = true, min = 0.25, max = 4.0) }
        run { args ->
            player().setSpeed(args.optDouble("speed")!!.toFloat())
            delay(400)
            okResult("speed" to player().state().playbackSpeed)
        }
    }

    tool("player_select_audio") {
        category = ToolCategory.Player
        description = "Switch the audio track by index (from get_player_state) or by language (e.g. \"en\", \"English\", \"ja\")."
        covers("AgentPlayerHandle.selectAudioTrack")
        params {
            integer("index", "Audio track index")
            string("language", "Language name or code")
        }
        run { args ->
            val handle = player()
            val state = handle.state()
            val index = args.optInt("index") ?: args.optString("language")?.let { lang ->
                state.audioTracks.firstOrNull { matchesLanguage(it.language, it.label, lang) }?.index
                    ?: throw ToolFailure("No audio track in '$lang'. Available: ${state.audioTracks.joinToString { "${it.index}:${it.label}" }}", "not_found")
            } ?: throw ToolFailure("Pass index or language", "invalid_arguments")
            if (!handle.selectAudioTrack(index)) throw ToolFailure("Audio track $index does not exist", "not_found")
            delay(400)
            okResult("selected_audio" to state.audioTracks.firstOrNull { it.index == index }?.label)
        }
    }

    tool("player_select_subtitles") {
        category = ToolCategory.Player
        timeoutMs = 40_000
        description = """
            Turn subtitles on/off or change them. Pass language (e.g. "English") to pick the best match
            from built-in tracks first, then addon subtitles (fetched automatically if needed);
            or pass track_index / addon_subtitle_id exactly; or off=true to disable.
        """
        covers(
            "AgentPlayerHandle.selectSubtitleTrack", "AgentPlayerHandle.selectAddonSubtitle",
            "AgentPlayerHandle.disableSubtitles", "AgentPlayerHandle.fetchAddonSubtitles",
        )
        params {
            string("language", "Language name or ISO code")
            integer("track_index", "Built-in subtitle track index")
            string("addon_subtitle_id", "Addon subtitle id from get_player_state")
            boolean("off", "Disable subtitles")
        }
        run { args ->
            val handle = player()
            if (args["off"]?.toString() == "true") {
                handle.disableSubtitles()
                return@run okResult("subtitles" to "off")
            }
            args.optInt("track_index")?.let { index ->
                if (!handle.selectSubtitleTrack(index)) throw ToolFailure("No subtitle track $index", "not_found")
                return@run okResult("subtitles" to handle.state().subtitleTracks.firstOrNull { it.index == index }?.label)
            }
            args.optString("addon_subtitle_id")?.let { id ->
                if (!handle.selectAddonSubtitle(id)) throw ToolFailure("No addon subtitle '$id'", "not_found")
                return@run okResult("subtitles" to id)
            }
            val language = args.optString("language") ?: throw ToolFailure("Pass language, track_index, addon_subtitle_id or off", "invalid_arguments")
            var state = handle.state()
            state.subtitleTracks.firstOrNull { matchesLanguage(it.language, it.label, language) }?.let { track ->
                handle.selectSubtitleTrack(track.index)
                return@run okResult("subtitles" to track.label, "source" to "built-in")
            }
            if (state.addonSubtitles.none { matchesLanguage(it.language, it.label, language) }) {
                handle.fetchAddonSubtitles()
                repeat(30) {
                    delay(500)
                    state = handle.state()
                    if (state.addonSubtitles.any { matchesLanguage(it.language, it.label, language) }) return@repeat
                }
            }
            val addonSub = state.addonSubtitles.firstOrNull { matchesLanguage(it.language, it.label, language) }
                ?: throw ToolFailure(
                    "No $language subtitles found. Built-in: ${state.subtitleTracks.joinToString { it.label }.ifEmpty { "none" }}; " +
                        "addon: ${state.addonSubtitles.map { it.language }.distinct().joinToString().ifEmpty { "none (install OpenSubtitles?)" }}",
                    "not_found",
                )
            handle.selectAddonSubtitle(addonSub.id)
            delay(500)
            okResult("subtitles" to addonSub.label, "source" to (addonSub.addonName ?: "addon"), "id" to addonSub.id)
        }
    }

    tool("player_set_subtitle_delay") {
        category = ToolCategory.Player
        description = "Shift subtitle timing in milliseconds (positive = later, negative = earlier, 0 = reset)."
        covers("AgentPlayerHandle.setSubtitleDelay")
        params { integer("delay_ms", "Delay in ms (-60000..60000)", required = true, min = -60_000, max = 60_000) }
        run { args ->
            player().setSubtitleDelay(args.int("delay_ms"))
            okResult("subtitle_delay_ms" to player().state().subtitleDelayMs)
        }
    }

    tool("player_next_episode") {
        category = ToolCategory.Player
        timeoutMs = 45_000
        description = "Play the next episode of the series that is playing (uses the app's next-episode flow)."
        covers("AgentPlayerHandle.playNextEpisode")
        run {
            val handle = player()
            if (!handle.state().hasNextEpisode) throw ToolFailure("There is no next episode (not a series, or it hasn't aired).", "not_available")
            val started = handle.playNextEpisode()
            delay(2_000)
            okResult("requested" to started, "now" to player().state().let { "S${it.seasonNumber}E${it.episodeNumber} ${it.episodeTitle.orEmpty()}" })
        }
    }

    tool("player_close") {
        category = ToolCategory.Player
        description = "Close the player (progress is saved) and return to the previous screen."
        covers("AgentPlayerHandle.close")
        run {
            player().close()
            okResult("closed" to true)
        }
    }
}

internal fun AgentPlayerState.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "episode" to if (isSeries) mapOf("season" to seasonNumber, "episode" to episodeNumber, "title" to episodeTitle) else null,
    "id" to parentMetaId,
    "type" to parentMetaType,
    "stream" to streamTitle,
    "source" to providerName,
    "loading" to isLoading,
    "playing" to isPlaying,
    "ended" to isEnded,
    "position_s" to positionMs / 1000,
    "duration_s" to durationMs / 1000,
    "speed" to playbackSpeed,
    "audio_tracks" to audioTracks.map { mapOf("index" to it.index, "label" to it.label, "language" to it.language, "selected" to (it.index == selectedAudioIndex)) },
    "subtitle_tracks" to subtitleTracks.map { mapOf("index" to it.index, "label" to it.label, "language" to it.language, "selected" to (it.index == selectedSubtitleIndex)) },
    "addon_subtitles" to addonSubtitles.take(25).map { mapOf("id" to it.id, "language" to it.language, "label" to it.label, "addon" to it.addonName, "selected" to (it.id == selectedAddonSubtitleId)) },
    "subtitles_on" to (selectedSubtitleIndex >= 0 || selectedAddonSubtitleId != null),
    "subtitle_delay_ms" to subtitleDelayMs,
    "has_next_episode" to hasNextEpisode,
    "error" to errorMessage,
)

private val LANGUAGE_ALIASES = mapOf(
    "en" to listOf("en", "eng", "english"), "es" to listOf("es", "spa", "spanish", "español", "espanol"),
    "fr" to listOf("fr", "fre", "fra", "french", "français"), "de" to listOf("de", "ger", "deu", "german", "deutsch"),
    "it" to listOf("it", "ita", "italian"), "pt" to listOf("pt", "por", "portuguese", "português", "pob", "pt-br"),
    "ja" to listOf("ja", "jpn", "japanese"), "ko" to listOf("ko", "kor", "korean"), "zh" to listOf("zh", "chi", "zho", "chinese"),
    "ru" to listOf("ru", "rus", "russian"), "ar" to listOf("ar", "ara", "arabic"), "hi" to listOf("hi", "hin", "hindi"),
    "nl" to listOf("nl", "dut", "nld", "dutch"), "pl" to listOf("pl", "pol", "polish"), "tr" to listOf("tr", "tur", "turkish"),
    "sv" to listOf("sv", "swe", "swedish"), "he" to listOf("he", "heb", "hebrew"),
)

internal fun matchesLanguage(code: String?, label: String?, wanted: String): Boolean {
    val w = wanted.trim().lowercase(Locale.ROOT)
    val aliases = LANGUAGE_ALIASES.values.firstOrNull { w in it } ?: listOf(w)
    val haystack = listOfNotNull(code, label).map { it.lowercase(Locale.ROOT) }
    return haystack.any { value -> aliases.any { alias -> value == alias || value.startsWith("$alias-") || value.split(Regex("[^a-zà-ÿ]+")).contains(alias) } }
}
