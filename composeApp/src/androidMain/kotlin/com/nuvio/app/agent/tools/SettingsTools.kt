package com.nuvio.app.agent.tools

import com.nuvio.app.agent.core.ConfirmationRequest
import com.nuvio.app.agent.core.ToolArgumentException
import com.nuvio.app.agent.core.ToolCategory
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.core.okResult
import com.nuvio.app.agent.core.optBoolean
import com.nuvio.app.agent.core.optString
import com.nuvio.app.agent.core.string
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.search.SearchHistoryRepository
import com.nuvio.app.features.settings.AppLanguage
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.features.watchprogress.ContinueWatchingSortMode

/** One user-facing setting: how to read it, how to write it and which repository setter it maps to. */
class SettingSpec(
    val key: String,
    val section: String,
    val description: String,
    val type: String,
    val options: List<String>? = null,
    val covers: String,
    val read: () -> Any?,
    val write: (String) -> Unit,
)

private fun bool(raw: String): Boolean = when (raw.trim().lowercase()) {
    "true", "on", "yes", "1", "enabled" -> true
    "false", "off", "no", "0", "disabled" -> false
    else -> throw ToolArgumentException("Expected true/false, got '$raw'")
}

private fun int(raw: String, range: IntRange): Int =
    raw.trim().toDoubleOrNull()?.toInt()?.takeIf { it in range } ?: throw ToolArgumentException("Expected an integer in $range, got '$raw'")

private fun float(raw: String, range: ClosedFloatingPointRange<Float>): Float =
    raw.trim().toFloatOrNull()?.takeIf { it in range } ?: throw ToolArgumentException("Expected a number in $range, got '$raw'")

private inline fun <reified E : Enum<E>> enumValue(raw: String): E =
    enumValues<E>().firstOrNull { it.name.equals(raw.trim().replace(' ', '_'), ignoreCase = true) }
        ?: throw ToolArgumentException("Expected one of ${enumValues<E>().joinToString { it.name.lowercase() }}, got '$raw'")

private inline fun <reified E : Enum<E>> enumOptions(): List<String> = enumValues<E>().map { it.name.lowercase() }

private class SettingsBuilder {
    val specs = mutableListOf<SettingSpec>()
    lateinit var section: String

    fun bool(key: String, description: String, covers: String, read: () -> Boolean, write: (Boolean) -> Unit) {
        specs += SettingSpec(key, section, description, "boolean", null, covers, read) { write(bool(it)) }
    }

    fun int(key: String, description: String, range: IntRange, covers: String, read: () -> Int, write: (Int) -> Unit) {
        specs += SettingSpec(key, section, "$description ($range)", "integer", null, covers, read) { write(int(it, range)) }
    }

    fun float(key: String, description: String, range: ClosedFloatingPointRange<Float>, covers: String, read: () -> Float, write: (Float) -> Unit) {
        specs += SettingSpec(key, section, "$description ($range)", "number", null, covers, read) { write(float(it, range)) }
    }

    fun text(key: String, description: String, covers: String, read: () -> String?, write: (String) -> Unit) {
        specs += SettingSpec(key, section, description, "string", null, covers, read) { write(it) }
    }

    inline fun <reified E : Enum<E>> choice(key: String, description: String, covers: String, noinline read: () -> E, noinline write: (E) -> Unit) {
        specs += SettingSpec(key, section, description, "enum", enumOptions<E>(), covers, { read().name.lowercase() }) { write(enumValue<E>(it)) }
    }
}

/** The settings the agent may read and change. Add a line here to expose a new setting. */
internal val agentSettings: List<SettingSpec> by lazy {
    val p = { PlayerSettingsRepository.ensureLoaded(); PlayerSettingsRepository.uiState.value }
    val m = { MetaScreenSettingsRepository.ensureLoaded(); MetaScreenSettingsRepository.uiState.value }
    val c = { ContinueWatchingPreferencesRepository.ensureLoaded(); ContinueWatchingPreferencesRepository.uiState.value }
    val h = { HomeCatalogSettingsRepository.uiState.value }
    SettingsBuilder().apply {
        section = "playback"
        bool("player.legacy_layout", "Use the legacy player controls layout", "PlayerSettingsRepository.setUseLegacyPlayerLayout", { p().useLegacyPlayerLayout }, PlayerSettingsRepository::setUseLegacyPlayerLayout)
        bool("player.loading_overlay", "Show the artwork overlay while a stream loads", "PlayerSettingsRepository.setShowLoadingOverlay", { p().showLoadingOverlay }, PlayerSettingsRepository::setShowLoadingOverlay)
        bool("player.loading_status", "Show loading status text in the player", "PlayerSettingsRepository.setShowPlayerLoadingStatus", { p().showPlayerLoadingStatus }, PlayerSettingsRepository::setShowPlayerLoadingStatus)
        bool("player.pause_overlay", "Show title info when paused", "PlayerSettingsRepository.setPauseOverlayEnabled", { p().pauseOverlayEnabled }, PlayerSettingsRepository::setPauseOverlayEnabled)
        bool("player.parental_guide", "Show the parental guide card at start", "PlayerSettingsRepository.setShowParentalGuide", { p().showParentalGuide }, PlayerSettingsRepository::setShowParentalGuide)
        choice<PlayerResizeMode>("player.resize_mode", "Video scaling", "PlayerSettingsRepository.setResizeMode", { p().resizeMode }, PlayerSettingsRepository::setResizeMode)
        bool("player.hold_to_speed", "Long-press to play faster", "PlayerSettingsRepository.setHoldToSpeedEnabled", { p().holdToSpeedEnabled }, PlayerSettingsRepository::setHoldToSpeedEnabled)
        float("player.hold_to_speed_value", "Speed while long-pressing", 1f..4f, "PlayerSettingsRepository.setHoldToSpeedValue", { p().holdToSpeedValue }, PlayerSettingsRepository::setHoldToSpeedValue)
        bool("player.touch_gestures", "Brightness/volume swipe gestures", "PlayerSettingsRepository.setTouchGesturesEnabled", { p().touchGesturesEnabled }, PlayerSettingsRepository::setTouchGesturesEnabled)
        bool("player.external_player", "Open streams in an external player app", "PlayerSettingsRepository.setExternalPlayerEnabled", { p().externalPlayerEnabled }, PlayerSettingsRepository::setExternalPlayerEnabled)
        text("player.external_player_id", "External player app id (e.g. vlc, mx)", "PlayerSettingsRepository.setExternalPlayerId", { p().externalPlayerId }) { PlayerSettingsRepository.setExternalPlayerId(it.ifBlank { null }) }
        bool("player.external_forward_subtitles", "Send subtitles to the external player", "PlayerSettingsRepository.setExternalPlayerForwardSubtitles", { p().externalPlayerForwardSubtitles }, PlayerSettingsRepository::setExternalPlayerForwardSubtitles)
        bool("player.external_skip_segments", "Send intro/outro timestamps to the external player", "PlayerSettingsRepository.setExternalPlayerSendSkipSegments", { p().externalPlayerSendSkipSegments }, PlayerSettingsRepository::setExternalPlayerSendSkipSegments)
        text("player.audio_language", "Preferred audio language code (e.g. en, ja, device)", "PlayerSettingsRepository.setPreferredAudioLanguage", { p().preferredAudioLanguage }, PlayerSettingsRepository::setPreferredAudioLanguage)
        text("player.audio_language_secondary", "Fallback audio language code (empty = none)", "PlayerSettingsRepository.setSecondaryPreferredAudioLanguage", { p().secondaryPreferredAudioLanguage }) { PlayerSettingsRepository.setSecondaryPreferredAudioLanguage(it.ifBlank { null }) }
        text("player.subtitle_language", "Preferred subtitle language code (e.g. en; none = off)", "PlayerSettingsRepository.setPreferredSubtitleLanguage", { p().preferredSubtitleLanguage }, PlayerSettingsRepository::setPreferredSubtitleLanguage)
        text("player.subtitle_language_secondary", "Fallback subtitle language code (empty = none)", "PlayerSettingsRepository.setSecondaryPreferredSubtitleLanguage", { p().secondaryPreferredSubtitleLanguage }) { PlayerSettingsRepository.setSecondaryPreferredSubtitleLanguage(it.ifBlank { null }) }
        bool("player.libass", "Render ASS/SSA subtitles with libass", "PlayerSettingsRepository.setUseLibass", { p().useLibass }, PlayerSettingsRepository::setUseLibass)
        text("player.libass_render_type", "libass render type (CUES, OVERLAY…)", "PlayerSettingsRepository.setLibassRenderType", { p().libassRenderType }, PlayerSettingsRepository::setLibassRenderType)
        choice<AndroidPlaybackEngine>("player.engine", "Playback engine", "PlayerSettingsRepository.setAndroidPlaybackEngine", { p().androidPlaybackEngine }, PlayerSettingsRepository::setAndroidPlaybackEngine)
        bool("player.mpv_hardware_decoding", "libmpv hardware decoding", "PlayerSettingsRepository.setAndroidLibmpvHardwareDecodingEnabled", { p().androidLibmpvHardwareDecodingEnabled }, PlayerSettingsRepository::setAndroidLibmpvHardwareDecodingEnabled)
        bool("player.mpv_yuv420p", "libmpv force yuv420p output", "PlayerSettingsRepository.setAndroidLibmpvYuv420pEnabled", { p().androidLibmpvYuv420pEnabled }, PlayerSettingsRepository::setAndroidLibmpvYuv420pEnabled)
        int("player.decoder_priority", "ExoPlayer decoder priority (0 device only, 1 prefer device, 2 prefer app)", 0..2, "PlayerSettingsRepository.setDecoderPriority", { p().decoderPriority }, PlayerSettingsRepository::setDecoderPriority)
        bool("player.map_dv7_to_hevc", "Map Dolby Vision profile 7 to HEVC", "PlayerSettingsRepository.setMapDV7ToHevc", { p().mapDV7ToHevc }, PlayerSettingsRepository::setMapDV7ToHevc)
        bool("player.tunneling", "Tunneled playback", "PlayerSettingsRepository.setTunnelingEnabled", { p().tunnelingEnabled }, PlayerSettingsRepository::setTunnelingEnabled)
        bool("player.skip_intro", "Detect intros/recaps (IntroDB)", "PlayerSettingsRepository.setSkipIntroEnabled", { p().skipIntroEnabled }, PlayerSettingsRepository::setSkipIntroEnabled)
        bool("player.anime_skip", "Also use AnimeSkip timestamps", "PlayerSettingsRepository.setAnimeSkipEnabled", { p().animeSkipEnabled }, PlayerSettingsRepository::setAnimeSkipEnabled)
        bool("player.intro_submit", "Allow submitting intro timestamps", "PlayerSettingsRepository.setIntroSubmitEnabled", { p().introSubmitEnabled }, PlayerSettingsRepository::setIntroSubmitEnabled)
        section = "streams"
        choice<StreamAutoPlayMode>("streams.autoplay_mode", "Pick a stream automatically: manual, first_stream or regex_match", "PlayerSettingsRepository.setStreamAutoPlayMode", { p().streamAutoPlayMode }, PlayerSettingsRepository::setStreamAutoPlayMode)
        choice<StreamAutoPlaySource>("streams.autoplay_source", "Which sources autoplay may use", "PlayerSettingsRepository.setStreamAutoPlaySource", { p().streamAutoPlaySource }, PlayerSettingsRepository::setStreamAutoPlaySource)
        text("streams.autoplay_regex", "Regex a stream name must match for regex autoplay", "PlayerSettingsRepository.setStreamAutoPlayRegex", { p().streamAutoPlayRegex }, PlayerSettingsRepository::setStreamAutoPlayRegex)
        int("streams.autoplay_timeout_s", "Seconds to wait for sources before autoplaying", 1..60, "PlayerSettingsRepository.setStreamAutoPlayTimeoutSeconds", { p().streamAutoPlayTimeoutSeconds }, PlayerSettingsRepository::setStreamAutoPlayTimeoutSeconds)
        bool("streams.reuse_last_link", "Reuse the last working link for a title", "PlayerSettingsRepository.setStreamReuseLastLinkEnabled", { p().streamReuseLastLinkEnabled }, PlayerSettingsRepository::setStreamReuseLastLinkEnabled)
        int("streams.reuse_last_link_hours", "How long to reuse a link (hours)", 1..720, "PlayerSettingsRepository.setStreamReuseLastLinkCacheHours", { p().streamReuseLastLinkCacheHours }, PlayerSettingsRepository::setStreamReuseLastLinkCacheHours)
        bool("streams.autoplay_next_episode", "Automatically play the next episode", "PlayerSettingsRepository.setStreamAutoPlayNextEpisodeEnabled", { p().streamAutoPlayNextEpisodeEnabled }, PlayerSettingsRepository::setStreamAutoPlayNextEpisodeEnabled)
        bool("streams.next_episode_fallback", "Fall back to any source for the next episode", "PlayerSettingsRepository.setStreamAutoPlayNextEpisodeFallbackEnabled", { p().streamAutoPlayNextEpisodeFallbackEnabled }, PlayerSettingsRepository::setStreamAutoPlayNextEpisodeFallbackEnabled)
        bool("streams.prefer_binge_group", "Prefer the same release group for the next episode", "PlayerSettingsRepository.setStreamAutoPlayPreferBingeGroup", { p().streamAutoPlayPreferBingeGroup }, PlayerSettingsRepository::setStreamAutoPlayPreferBingeGroup)
        bool("streams.reuse_binge_group", "Reuse the binge group across sessions", "PlayerSettingsRepository.setStreamAutoPlayReuseBingeGroup", { p().streamAutoPlayReuseBingeGroup }, PlayerSettingsRepository::setStreamAutoPlayReuseBingeGroup)
        choice<NextEpisodeThresholdMode>("streams.next_episode_threshold_mode", "When to offer the next episode", "PlayerSettingsRepository.setNextEpisodeThresholdMode", { p().nextEpisodeThresholdMode }, PlayerSettingsRepository::setNextEpisodeThresholdMode)
        float("streams.next_episode_threshold_percent", "Percent watched before offering the next episode", 50f..100f, "PlayerSettingsRepository.setNextEpisodeThresholdPercent", { p().nextEpisodeThresholdPercent }, PlayerSettingsRepository::setNextEpisodeThresholdPercent)
        float("streams.next_episode_threshold_minutes", "Minutes before the end to offer the next episode", 0f..30f, "PlayerSettingsRepository.setNextEpisodeThresholdMinutesBeforeEnd", { p().nextEpisodeThresholdMinutesBeforeEnd }, PlayerSettingsRepository::setNextEpisodeThresholdMinutesBeforeEnd)
        bool("streams.preload_next_episode", "Preload next-episode sources", "PlayerSettingsRepository.setPreloadNextEpisodeSources", { p().preloadNextEpisodeSources }, PlayerSettingsRepository::setPreloadNextEpisodeSources)
        section = "appearance"
        choice<AppTheme>("appearance.theme", "Color theme", "ThemeSettingsRepository.setTheme", { ThemeSettingsRepository.ensureLoaded(); ThemeSettingsRepository.selectedTheme.value }, ThemeSettingsRepository::setTheme)
        bool("appearance.amoled", "Pure black (AMOLED) backgrounds", "ThemeSettingsRepository.setAmoled", { ThemeSettingsRepository.amoledEnabled.value }, ThemeSettingsRepository::setAmoled)
        choice<AppLanguage>("appearance.language", "App language", "ThemeSettingsRepository.setAppLanguage", { ThemeSettingsRepository.selectedAppLanguage.value }, ThemeSettingsRepository::setAppLanguage)
        choice<NavBarStyle>("appearance.nav_bar_style", "Bottom navigation bar style", "ThemeSettingsRepository.setNavBarStyle", { ThemeSettingsRepository.navBarStyle.value }, ThemeSettingsRepository::setNavBarStyle)
        bool("appearance.nav_bar_glow", "Glow under the selected tab", "ThemeSettingsRepository.setNavBarGlowEnabled", { ThemeSettingsRepository.navBarGlowEnabled.value }, ThemeSettingsRepository::setNavBarGlowEnabled)
        bool("appearance.liquid_glass_tab_bar", "Liquid-glass native tab bar (where supported)", "ThemeSettingsRepository.setLiquidGlassNativeTabBar", { ThemeSettingsRepository.liquidGlassNativeTabBarEnabled.value }, ThemeSettingsRepository::setLiquidGlassNativeTabBar)
        section = "details_screen"
        choice<MetaScreenBackgroundMode>("details.background_mode", "Title page background", "MetaScreenSettingsRepository.setBackgroundMode", { m().backgroundMode }, MetaScreenSettingsRepository::setBackgroundMode)
        bool("details.cinematic_background", "Cinematic backdrop", "MetaScreenSettingsRepository.setCinematicBackground", { m().cinematicBackground }, MetaScreenSettingsRepository::setCinematicBackground)
        bool("details.hero_trailer", "Autoplay trailers in the header", "MetaScreenSettingsRepository.setHeroTrailerPlayback", { m().heroTrailerPlayback }, MetaScreenSettingsRepository::setHeroTrailerPlayback)
        bool("details.tab_layout", "Group sections into tabs", "MetaScreenSettingsRepository.setTabLayout", { m().tabLayout }, MetaScreenSettingsRepository::setTabLayout)
        choice<MetaEpisodeCardStyle>("details.episode_card_style", "Episode list style", "MetaScreenSettingsRepository.setEpisodeCardStyle", { m().episodeCardStyle }, MetaScreenSettingsRepository::setEpisodeCardStyle)
        bool("details.blur_unwatched_episodes", "Blur thumbnails of unwatched episodes (spoilers)", "MetaScreenSettingsRepository.setBlurUnwatchedEpisodes", { m().blurUnwatchedEpisodes }, MetaScreenSettingsRepository::setBlurUnwatchedEpisodes)
        bool("details.show_overall_ratings", "Show overall ratings", "MetaScreenSettingsRepository.setShowOverallRatings", { m().showOverallRatings }, MetaScreenSettingsRepository::setShowOverallRatings)
        bool("details.poster_transition", "Animated poster transition into the title page", "MetaScreenSettingsRepository.setPosterTransitionEnabled", { m().posterTransitionEnabled }, MetaScreenSettingsRepository::setPosterTransitionEnabled)
        section = "continue_watching"
        bool("continue_watching.visible", "Show the Continue Watching row", "ContinueWatchingPreferencesRepository.setVisible", { c().isVisible }, ContinueWatchingPreferencesRepository::setVisible)
        choice<ContinueWatchingSectionStyle>("continue_watching.style", "Row card style", "ContinueWatchingPreferencesRepository.setStyle", { c().style }, ContinueWatchingPreferencesRepository::setStyle)
        choice<ContinueWatchingSortMode>("continue_watching.sort", "Sort order", "ContinueWatchingPreferencesRepository.setSortMode", { c().sortMode }, ContinueWatchingPreferencesRepository::setSortMode)
        bool("continue_watching.up_next_from_furthest", "Up Next follows the furthest watched episode", "ContinueWatchingPreferencesRepository.setUpNextFromFurthestEpisode", { c().upNextFromFurthestEpisode }, ContinueWatchingPreferencesRepository::setUpNextFromFurthestEpisode)
        bool("continue_watching.episode_thumbnails", "Use episode thumbnails", "ContinueWatchingPreferencesRepository.setUseEpisodeThumbnails", { c().useEpisodeThumbnails }, ContinueWatchingPreferencesRepository::setUseEpisodeThumbnails)
        bool("continue_watching.show_unaired", "Show unaired Up Next episodes", "ContinueWatchingPreferencesRepository.setShowUnairedNextUp", { c().showUnairedNextUp }, ContinueWatchingPreferencesRepository::setShowUnairedNextUp)
        bool("continue_watching.blur_next_up", "Blur Up Next thumbnails", "ContinueWatchingPreferencesRepository.setBlurNextUp", { c().blurNextUp }, ContinueWatchingPreferencesRepository::setBlurNextUp)
        bool("continue_watching.resume_prompt", "Offer to resume on app launch", "ContinueWatchingPreferencesRepository.setShowResumePromptOnLaunch", { c().showResumePromptOnLaunch }, ContinueWatchingPreferencesRepository::setShowResumePromptOnLaunch)
        section = "home"
        bool("home.hero", "Show the hero carousel", "HomeCatalogSettingsRepository.setHeroEnabled", { h().heroEnabled }, HomeCatalogSettingsRepository::setHeroEnabled)
        bool("home.show_catalog_type", "Show the content type next to row titles", "HomeCatalogSettingsRepository.setShowCatalogType", { h().showCatalogType }, HomeCatalogSettingsRepository::setShowCatalogType)
        bool("home.hide_unreleased", "Hide unreleased titles", "HomeCatalogSettingsRepository.setHideUnreleasedContent", { h().hideUnreleasedContent }, HomeCatalogSettingsRepository::setHideUnreleasedContent)
        section = "search"
        bool("search.history", "Remember recent searches", "SearchHistoryRepository.setEnabled", { SearchHistoryRepository.ensureLoaded(); SearchHistoryRepository.enabled.value }, SearchHistoryRepository::setEnabled)
    }.specs
}

internal fun ToolRegistry.registerSettingsTools() {
    tool("list_settings") {
        category = ToolCategory.Settings
        readOnly = true
        description = "List the app settings the assistant can change, with their current values, types and allowed options."
        params { string("section", "Only this section", enum = agentSettings.map { it.section }.distinct()) }
        run { args ->
            val section = args.optString("section")
            okResult(
                "settings" to agentSettings.filter { section == null || it.section == section }.map {
                    mapOf("key" to it.key, "value" to runCatching { it.read() }.getOrNull(), "type" to it.type, "options" to it.options, "description" to it.description)
                },
            )
        }
    }

    tool("set_setting") {
        category = ToolCategory.Settings
        description = "Change one setting by key (from list_settings). Values: true/false, numbers, or one of the listed options."
        agentSettings.forEach { covers(it.covers) }
        params {
            string("key", "Setting key, e.g. player.subtitle_language", required = true)
            string("value", "New value", required = true)
        }
        run { args ->
            val key = args.string("key")
            val spec = agentSettings.firstOrNull { it.key.equals(key, true) }
                ?: throw ToolFailure("Unknown setting '$key'. Use list_settings to see keys.", "not_found")
            val before = runCatching { spec.read() }.getOrNull()
            spec.write(args.string("value"))
            okResult("key" to spec.key, "before" to before, "after" to runCatching { spec.read() }.getOrNull())
        }
    }

    tool("list_home_rows_settings") {
        category = ToolCategory.Settings
        readOnly = true
        description = "List Home screen catalog rows with their enabled state, custom titles and order."
        covers("HomeCatalogSettingsRepository.uiState")
        run {
            okResult("rows" to HomeCatalogSettingsRepository.uiState.value.items.map {
                mapOf("key" to it.key, "title" to it.displayTitle, "addon" to it.addonName, "enabled" to it.enabled, "hero_source" to it.heroSourceEnabled, "order" to it.order)
            })
        }
    }

    tool("configure_home_row") {
        category = ToolCategory.Settings
        description = "Show/hide a Home row, rename it, include it in the hero carousel, or move it up/down."
        covers(
            "HomeCatalogSettingsRepository.setEnabled", "HomeCatalogSettingsRepository.setCustomTitle",
            "HomeCatalogSettingsRepository.setHeroSourceEnabled", "HomeCatalogSettingsRepository.moveUp",
            "HomeCatalogSettingsRepository.moveDown", "HomeCatalogSettingsRepository.moveByIndex",
        )
        params {
            string("key", "Row key from list_home_rows_settings", required = true)
            boolean("enabled", "Show (true) or hide (false) the row")
            string("title", "Custom title (empty resets)")
            boolean("hero_source", "Use this row in the hero carousel")
            string("move", "Move the row", enum = listOf("up", "down"))
        }
        run { args ->
            val key = args.string("key")
            HomeCatalogSettingsRepository.uiState.value.items.firstOrNull { it.key == key } ?: throw ToolFailure("No home row '$key'", "not_found")
            args.optBoolean("enabled")?.let { HomeCatalogSettingsRepository.setEnabled(key, it) }
            args.optString("title")?.let { HomeCatalogSettingsRepository.setCustomTitle(key, it) }
            args.optBoolean("hero_source")?.let { HomeCatalogSettingsRepository.setHeroSourceEnabled(key, it) }
            when (args.optString("move")) {
                "up" -> HomeCatalogSettingsRepository.moveUp(key)
                "down" -> HomeCatalogSettingsRepository.moveDown(key)
            }
            okResult("row" to HomeCatalogSettingsRepository.uiState.value.items.first { it.key == key }.let { mapOf("title" to it.displayTitle, "enabled" to it.enabled, "order" to it.order) })
        }
    }

    tool("reset_home_rows") {
        category = ToolCategory.Settings
        description = "Reset Home rows (order, visibility, titles) to defaults. Asks the user to confirm."
        covers("HomeCatalogSettingsRepository.resetToDefaults")
        confirm { ConfirmationRequest("Reset Home rows?", "Row order, visibility and custom titles go back to defaults.", "Reset") }
        run {
            HomeCatalogSettingsRepository.resetToDefaults()
            okResult("reset" to true)
        }
    }

    tool("reset_details_layout") {
        category = ToolCategory.Settings
        description = "Reset the title-page section layout to defaults. Asks the user to confirm."
        covers("MetaScreenSettingsRepository.resetToDefaults")
        confirm { ConfirmationRequest("Reset title page layout?", "Section order and visibility on title pages go back to defaults.", "Reset") }
        run {
            MetaScreenSettingsRepository.resetToDefaults()
            okResult("reset" to true)
        }
    }
}
