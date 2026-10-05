package com.nuvio.app.agent.tools

import com.nuvio.app.agent.core.ConfirmationRequest
import com.nuvio.app.agent.core.ToolCategory
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.core.okResult
import com.nuvio.app.agent.core.optBoolean
import com.nuvio.app.agent.core.optInt
import com.nuvio.app.agent.core.optString
import com.nuvio.app.agent.core.string
import com.nuvio.app.features.addons.AddAddonResult
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.delay

internal fun ToolRegistry.registerLibraryTools() {
    tool("get_library") {
        category = ToolCategory.Library
        readOnly = true
        description = "List the titles saved in the user's library (optionally only movies or series)."
        covers("LibraryRepository.uiState", "LibraryRepository.savedItem")
        params {
            string("type", "Filter by type", enum = listOf("movie", "series", "any"))
            integer("limit", "Max items (default 50)", min = 1, max = 200)
        }
        run { args ->
            LibraryRepository.ensureLoaded()
            val type = args.optString("type")?.takeIf { it != "any" }
            val state = LibraryRepository.uiState.value
            val items = state.items.filter { type == null || it.type == type }
            okResult(
                "source" to state.sourceMode.name.lowercase(),
                "count" to items.size,
                "items" to items.take(args.optInt("limit") ?: 50).map { mapOf("id" to it.id, "type" to it.type, "name" to it.name, "year" to it.releaseInfo) },
            )
        }
    }

    tool("is_in_library") {
        category = ToolCategory.Library
        readOnly = true
        description = "Check whether a title is saved in the library."
        covers("LibraryRepository.isSaved")
        params {
            string("id", "Title id", required = true)
            string("type", "movie or series")
        }
        run { args -> okResult("saved" to LibraryRepository.isSaved(args.string("id"), args.optString("type")?.let(::normalizeType))) }
    }

    tool("add_to_library") {
        category = ToolCategory.Library
        timeoutMs = 30_000
        description = "Save a title to the library (no-op if already saved). Use the id/type of the title, e.g. the one on screen."
        covers("LibraryRepository.toggleSaved", "LibraryRepository.save", "LibraryRepository.toggleLocalSaved")
        params {
            string("id", "Title id", required = true)
            string("type", "movie or series", required = true)
        }
        run { args ->
            val type = normalizeType(args.string("type"))
            val id = args.string("id")
            if (LibraryRepository.isSaved(id, type)) return@run okResult("saved" to true, "already" to true)
            val meta = fetchMeta(type, id)
            LibraryRepository.toggleSaved(meta.toLibraryItem(LibraryClock.nowEpochMs()))
            delay(300)
            okResult("saved" to LibraryRepository.isSaved(id, type), "title" to meta.name)
        }
    }

    tool("remove_from_library") {
        category = ToolCategory.Library
        timeoutMs = 30_000
        description = "Remove a title from the library. Asks the user to confirm."
        covers("LibraryRepository.remove", "LibraryRepository.removeFromList")
        params {
            string("id", "Title id", required = true)
            string("type", "movie or series", required = true)
        }
        confirm { args ->
            val name = LibraryRepository.uiState.value.items.firstOrNull { it.id == args.string("id") }?.name ?: args.string("id")
            ConfirmationRequest("Remove from library?", "The assistant wants to remove “$name” from your library.", "Remove")
        }
        run { args ->
            val type = normalizeType(args.string("type"))
            val id = args.string("id")
            val item = LibraryRepository.uiState.value.items.firstOrNull { it.id == id }
                ?: throw ToolFailure("“$id” is not in the library", "not_found")
            LibraryRepository.toggleSaved(item)
            delay(300)
            okResult("removed" to !LibraryRepository.isSaved(id, type))
        }
    }

    tool("clear_library") {
        category = ToolCategory.Library
        timeoutMs = 60_000
        description = "Remove EVERY title from the library. Destructive: the user must confirm."
        covers("LibraryRepository.clearLocalState")
        confirm {
            val count = LibraryRepository.uiState.value.items.size
            ConfirmationRequest("Clear the whole library?", "This removes all $count saved titles from your library.", "Clear library")
        }
        run {
            val items = LibraryRepository.uiState.value.items.toList()
            items.forEach { item -> LibraryRepository.toggleSaved(item) }
            delay(500)
            okResult("removed" to items.size, "remaining" to LibraryRepository.uiState.value.items.size)
        }
    }
}

internal fun ToolRegistry.registerProgressTools() {
    tool("get_continue_watching") {
        category = ToolCategory.Progress
        readOnly = true
        description = "List the Continue Watching items (in-progress movies/episodes) with progress and resume points."
        covers("WatchProgressRepository.continueWatching", "WatchProgressRepository.uiState")
        params { integer("limit", "Max items (default 20)", min = 1, max = 100) }
        run { args ->
            WatchProgressRepository.ensureLoaded()
            val entries = WatchProgressRepository.continueWatching()
            okResult(
                "count" to entries.size,
                "items" to entries.take(args.optInt("limit") ?: 20).map { entry ->
                    mapOf(
                        "id" to entry.parentMetaId,
                        "type" to entry.parentMetaType,
                        "title" to entry.title,
                        "season" to entry.seasonNumber,
                        "episode" to entry.episodeNumber,
                        "episode_title" to entry.episodeTitle,
                        "position_s" to entry.lastPositionMs / 1000,
                        "duration_s" to entry.durationMs / 1000,
                        "percent" to (entry.progressPercent ?: if (entry.durationMs > 0) entry.lastPositionMs * 100f / entry.durationMs else null)?.let { "%.0f".format(it) },
                        "last_watched_epoch_ms" to entry.lastUpdatedEpochMs,
                    )
                },
            )
        }
    }

    tool("get_watch_progress") {
        category = ToolCategory.Progress
        readOnly = true
        description = "Get saved progress for a movie or an episode (by video id, or series id + season/episode)."
        covers("WatchProgressRepository.progressForVideo", "WatchProgressRepository.resumeEntryForSeries")
        params {
            string("id", "Movie id, series id or episode video id", required = true)
            integer("season", "Season (series)")
            integer("episode", "Episode (series)")
        }
        run { args ->
            val id = args.string("id")
            val entry = WatchProgressRepository.progressForVideo(id, parentMetaId = id, seasonNumber = args.optInt("season"), episodeNumber = args.optInt("episode"))
                ?: WatchProgressRepository.resumeEntryForSeries(id)
            okResult("progress" to entry?.let { mapOf("title" to it.title, "season" to it.seasonNumber, "episode" to it.episodeNumber, "position_s" to it.lastPositionMs / 1000, "duration_s" to it.durationMs / 1000, "completed" to it.isCompleted) })
        }
    }

    tool("remove_from_continue_watching") {
        category = ToolCategory.Progress
        description = "Remove a title (and its saved progress) from Continue Watching."
        covers("WatchProgressRepository.removeProgress", "WatchProgressRepository.clearProgress")
        params {
            string("id", "Movie or series id", required = true)
            integer("season", "Only this season (series)")
            integer("episode", "Only this episode (series)")
        }
        confirm { args -> ConfirmationRequest("Remove from Continue Watching?", "Saved progress for ${args.string("id")} will be deleted.", "Remove") }
        run { args ->
            WatchProgressRepository.removeProgress(args.string("id"), args.optInt("season"), args.optInt("episode"))
            okResult("removed" to args.string("id"))
        }
    }

    tool("set_watched") {
        category = ToolCategory.Progress
        timeoutMs = 30_000
        description = "Mark a movie or an episode as watched (watched=true) or unwatched (watched=false)."
        covers("WatchedRepository.markWatched", "WatchedRepository.unmarkWatched", "WatchedRepository.toggleWatched")
        params {
            string("id", "Movie or series id", required = true)
            string("type", "movie or series", required = true)
            integer("season", "Season (for an episode)")
            integer("episode", "Episode (for an episode)")
            boolean("watched", "true = watched, false = unwatched (default true)")
        }
        run { args ->
            val type = normalizeType(args.string("type"))
            val id = args.string("id")
            val season = args.optInt("season")
            val episode = args.optInt("episode")
            val meta = runCatching { fetchMeta(type, id) }.getOrNull()
            val video = meta?.videos?.firstOrNull { it.season == season && it.episode == episode }
            val item = WatchedItem(
                id = id, type = type, name = meta?.name ?: id, poster = meta?.poster, releaseInfo = meta?.releaseInfo,
                season = season, episode = episode, videoId = video?.id, markedAtEpochMs = System.currentTimeMillis(),
            )
            if (args.optBoolean("watched") != false) WatchedRepository.markWatched(item) else WatchedRepository.unmarkWatched(item)
            okResult("watched" to WatchedRepository.isWatched(id, type, season, episode))
        }
    }

    tool("is_watched") {
        category = ToolCategory.Progress
        readOnly = true
        description = "Check whether a movie, an episode, or a whole series is marked watched."
        covers("WatchedRepository.isWatched", "WatchedRepository.isFullyWatchedSeries", "WatchedRepository.uiState")
        params {
            string("id", "Movie or series id", required = true)
            string("type", "movie or series", required = true)
            integer("season", "Season")
            integer("episode", "Episode")
        }
        run { args ->
            val type = normalizeType(args.string("type"))
            okResult(
                "watched" to WatchedRepository.isWatched(args.string("id"), type, args.optInt("season"), args.optInt("episode")),
                "series_fully_watched" to if (type == "series") WatchedRepository.isFullyWatchedSeries(args.string("id"), type) else null,
            )
        }
    }
}

internal fun ToolRegistry.registerAddonTools() {
    tool("list_addons") {
        category = ToolCategory.Addons
        readOnly = true
        description = "List installed Stremio addons in order: name, id, manifest URL, enabled, resources, types and catalogs."
        covers("AddonRepository.uiState")
        run {
            AddonRepository.initialize()
            okResult("addons" to AddonRepository.uiState.value.addons.mapIndexed { i, addon -> addon.summary(i) })
        }
    }

    tool("install_addon") {
        category = ToolCategory.Addons
        timeoutMs = 45_000
        description = """
            Install a Stremio addon from its manifest URL (https://…/manifest.json or stremio://…).
            Well-known: Cinemeta https://v3-cinemeta.strem.io/manifest.json (metadata/catalogs),
            OpenSubtitles v3 https://opensubtitles-v3.strem.io/manifest.json (subtitles).
        """
        covers("AddonRepository.addAddon", "AddonRepository.awaitManifestsLoaded")
        params { string("url", "Manifest URL", required = true) }
        run { args ->
            AddonRepository.initialize()
            when (val result = AddonRepository.addAddon(args.string("url"))) {
                is AddAddonResult.Success -> okResult("installed" to result.manifest.name, "id" to result.manifest.id, "resources" to result.manifest.resources.map { it.name })
                is AddAddonResult.Error -> throw ToolFailure(result.message, "install_failed")
            }
        }
    }

    tool("remove_addon") {
        category = ToolCategory.Addons
        description = "Uninstall an addon (by name, id or manifest URL). Asks the user to confirm."
        covers("AddonRepository.removeAddon")
        params { string("addon", "Addon name, id or manifest URL", required = true) }
        confirm { args ->
            val name = runCatching { findAddon(args.string("addon")).displayTitle }.getOrDefault(args.string("addon"))
            ConfirmationRequest("Remove addon?", "The assistant wants to uninstall “$name”.", "Remove")
        }
        run { args ->
            val addon = findAddon(args.string("addon"))
            AddonRepository.removeAddon(addon.manifestUrl)
            okResult("removed" to addon.displayTitle, "still_installed" to AddonRepository.uiState.value.addons.any { it.manifestUrl == addon.manifestUrl })
        }
    }

    tool("set_addon_enabled") {
        category = ToolCategory.Addons
        description = "Enable or disable an installed addon without uninstalling it."
        covers("AddonRepository.setAddonEnabled")
        params {
            string("addon", "Addon name, id or manifest URL", required = true)
            boolean("enabled", "true to enable, false to disable", required = true)
        }
        run { args ->
            val addon = findAddon(args.string("addon"))
            AddonRepository.setAddonEnabled(addon.manifestUrl, args.optBoolean("enabled") == true)
            okResult("addon" to addon.displayTitle, "enabled" to findAddon(addon.manifestUrl).enabled)
        }
    }

    tool("move_addon") {
        category = ToolCategory.Addons
        description = "Reorder addons: move an addon to a new position (0 = first). Order affects stream and catalog priority."
        covers("AddonRepository.moveAddon")
        params {
            string("addon", "Addon name, id or manifest URL", required = true)
            integer("to_index", "New position (0-based)", required = true, min = 0)
        }
        run { args ->
            val addons = AddonRepository.uiState.value.addons
            val from = addons.indexOf(findAddon(args.string("addon")))
            val to = args.optInt("to_index")!!.coerceAtMost(addons.lastIndex)
            AddonRepository.moveAddon(from, to)
            okResult("order" to AddonRepository.uiState.value.addons.map { it.displayTitle })
        }
    }

    tool("refresh_addons") {
        category = ToolCategory.Addons
        description = "Re-download addon manifests (all, or one addon) to pick up updates or fix load errors."
        covers("AddonRepository.refreshAll", "AddonRepository.refreshAddon")
        params { string("addon", "Only this addon (name, id or URL)") }
        run { args ->
            val one = args.optString("addon")?.let(::findAddon)
            if (one != null) AddonRepository.refreshAddon(one.manifestUrl, forceRefresh = true) else AddonRepository.refreshAll()
            delay(1_500)
            AddonRepository.awaitManifestsLoaded()
            okResult("addons" to AddonRepository.uiState.value.addons.mapIndexed { i, a -> mapOf("name" to a.displayTitle, "loaded" to (a.manifest != null), "error" to a.errorMessage, "index" to i) })
        }
    }
}
