package com.nuvio.app.agent.tools

import com.nuvio.app.AppScreenTab
import com.nuvio.app.agent.AgentBridge
import com.nuvio.app.agent.AgentPlayRequest
import com.nuvio.app.agent.core.ToolCategory
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.core.int
import com.nuvio.app.agent.core.okResult
import com.nuvio.app.agent.core.optBoolean
import com.nuvio.app.agent.core.optInt
import com.nuvio.app.agent.core.optString
import com.nuvio.app.agent.core.string
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.search.SearchHistoryRepository
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.StreamRoute
import com.nuvio.app.navigation.TabsRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal fun ToolRegistry.registerBrowseTools() {
    tool("search_titles") {
        category = ToolCategory.Browse
        timeoutMs = 45_000
        description = """
            Search movies and series across the installed addons' search catalogs (same as the Search tab).
            Returns ids/types to use with open_title, get_title_details or play_title. By default the
            query is also shown on the Search tab so the user sees the results.
        """
        covers("SearchRepository.search", "SearchRepository.clear", "SearchHistoryRepository.recordSearch")
        params {
            string("query", "Title or keywords, e.g. \"The Matrix\"", required = true)
            string("type", "Only return this type", enum = listOf("movie", "series", "any"))
            boolean("show_in_app", "Show the query on the Search tab (default true)")
            integer("limit", "Max results (default 10)", min = 1, max = 40)
        }
        run { args ->
            val query = args.string("query")
            val limit = args.optInt("limit") ?: 10
            AddonRepository.initialize()
            AddonRepository.awaitManifestsLoaded()
            if (args.optBoolean("show_in_app") != false) {
                val nav = navigation()
                while (nav.currentRoute !is TabsRoute) {
                if (!nav.back()) break
            }
                nav.selectTab(AppScreenTab.Search)
                AgentBridge.requestSearchQuery(query)
            } else {
                SearchRepository.search(query, AddonRepository.uiState.value.addons)
            }
            delay(400)
            val state = withTimeoutOrNull(30_000) {
                SearchRepository.uiState.first { !it.isLoading && (it.sections.isNotEmpty() || it.emptyStateReason != null) }
            } ?: SearchRepository.uiState.value
            val typeFilter = args.optString("type")?.takeIf { it != "any" }
            val seen = mutableSetOf<String>()
            val results = state.sections.flatMap { section -> section.items.map { it to section.title } }
                .filter { (item, _) -> typeFilter == null || item.type == typeFilter }
                .filter { (item, _) -> seen.add("${item.type}:${item.id}") }
                .take(limit)
                .map { (item, catalog) -> item.summary() + ("catalog" to catalog) }
            if (results.isEmpty() && state.emptyStateReason != null) {
                return@run okResult(
                    "results" to emptyList<Any>(),
                    "reason" to state.emptyStateReason.name,
                    "hint" to "If there are no active addons, install Cinemeta: install_addon(url=\"https://v3-cinemeta.strem.io/manifest.json\")",
                )
            }
            okResult("query" to query, "results" to results)
        }
    }

    tool("list_home_rows") {
        category = ToolCategory.Browse
        readOnly = true
        description = "List the catalog rows on the Home screen (key, title, addon) with their first items."
        covers("HomeRepository.uiState")
        params { integer("items_per_row", "Items to include per row (default 5)", min = 0, max = 30) }
        run { args ->
            val per = args.optInt("items_per_row") ?: 5
            val state = HomeRepository.uiState.value
            okResult(
                "loading" to state.isLoading,
                "rows" to state.sections.map { section ->
                    mapOf(
                        "key" to section.key,
                        "title" to section.title,
                        "addon" to section.addonName,
                        "items" to section.items.take(per).map { it.summary() },
                    )
                },
            )
        }
    }

    tool("get_catalog_page") {
        category = ToolCategory.Browse
        timeoutMs = 40_000
        description = "Load the next page of the catalog that is currently open (after open_catalog) and return its items."
        covers("CatalogRepository.loadMore")
        params { boolean("next_page", "Load the next page first (default false)") }
        run { args ->
            if (args.optBoolean("next_page") == true) CatalogRepository.loadMore()
            delay(300)
            val state = withTimeoutOrNull(25_000) { CatalogRepository.uiState.first { !it.isLoading } } ?: CatalogRepository.uiState.value
            okResult("items" to state.items.map { it.summary() }, "can_load_more" to state.canLoadMore, "error" to state.errorMessage)
        }
    }

    tool("discover") {
        category = ToolCategory.Browse
        timeoutMs = 40_000
        description = """
            Browse the Discover section (Search tab with an empty query): pick a content type, a catalog
            and a genre, and return matching titles. Call with no arguments to see the available options.
        """
        covers(
            "SearchRepository.refreshDiscover", "SearchRepository.selectDiscoverType",
            "SearchRepository.selectDiscoverCatalog", "SearchRepository.selectDiscoverGenre",
            "SearchRepository.loadMoreDiscover", "SearchRepository.discoverUiState",
        )
        params {
            string("type", "Content type, e.g. movie or series")
            string("catalog", "Catalog key or name from the options")
            string("genre", "Genre from the options")
            boolean("more", "Load the next page")
        }
        run { args ->
            SearchRepository.refreshDiscover(AddonRepository.uiState.value.addons)
            args.optString("type")?.let { SearchRepository.selectDiscoverType(normalizeType(it)) }
            args.optString("catalog")?.let { wanted ->
                val option = SearchRepository.discoverUiState.value.catalogOptions.firstOrNull {
                    it.key == wanted || it.catalogName.equals(wanted, true) || it.catalogId.equals(wanted, true)
                } ?: throw ToolFailure("Unknown catalog '$wanted'", "not_found")
                SearchRepository.selectDiscoverCatalog(option.key)
            }
            args.optString("genre")?.let { SearchRepository.selectDiscoverGenre(it) }
            if (args.optBoolean("more") == true) SearchRepository.loadMoreDiscover()
            delay(300)
            val state = withTimeoutOrNull(25_000) { SearchRepository.discoverUiState.first { !it.isLoading } }
                ?: SearchRepository.discoverUiState.value
            okResult(
                "types" to state.typeOptions,
                "selected_type" to state.selectedType,
                "catalogs" to state.catalogOptions.map { mapOf("key" to it.key, "name" to it.catalogName, "addon" to it.addonName, "type" to it.type) },
                "selected_catalog" to state.selectedCatalogKey,
                "genres" to state.genreOptions.take(40),
                "selected_genre" to state.selectedGenre,
                "items" to state.items.take(30).map { it.summary() },
            )
        }
    }

    tool("get_search_history") {
        category = ToolCategory.Browse
        readOnly = true
        description = "Return the recent search queries."
        covers("SearchHistoryRepository.uiState", "SearchHistoryRepository.enabled")
        run {
            SearchHistoryRepository.ensureLoaded()
            okResult("enabled" to SearchHistoryRepository.enabled.value, "recent" to SearchHistoryRepository.uiState.value)
        }
    }

    tool("remove_search_history_entry") {
        category = ToolCategory.Browse
        description = "Remove one query from the recent searches list."
        covers("SearchHistoryRepository.removeSearch")
        params { string("query", "The exact recent query to remove", required = true) }
        run { args ->
            SearchHistoryRepository.removeSearch(args.string("query"))
            okResult("removed" to args.string("query"))
        }
    }
}

internal fun ToolRegistry.registerDetailsTools() {
    tool("get_title_details") {
        category = ToolCategory.Details
        readOnly = true
        timeoutMs = 30_000
        description = """
            Get full metadata for a movie or series: year, plot, genres, director, writers, cast
            (with tmdb_person_id), ratings, seasons, similar titles. Does not change the screen.
        """
        covers("MetaDetailsRepository.fetch", "MetaDetailsRepository.peek")
        params {
            string("id", "Title id, e.g. tt0133093", required = true)
            string("type", "movie or series", required = true)
        }
        run { args -> okResult("title" to fetchMeta(normalizeType(args.string("type")), args.string("id")).summary(maxCast = 15)) }
    }

    tool("list_episodes") {
        category = ToolCategory.Details
        readOnly = true
        timeoutMs = 30_000
        description = "List the episodes of a series (optionally one season) with their video ids and air dates."
        covers("MetaDetails.videos")
        params {
            string("id", "Series id", required = true)
            integer("season", "Season number (omit for all)")
        }
        run { args ->
            val meta = fetchMeta("series", args.string("id"))
            val season = args.optInt("season")
            val episodes = meta.videos.filter { season == null || it.season == season }.sortedWith(compareBy({ it.season }, { it.episode }))
            okResult(
                "series" to meta.name,
                "episodes" to episodes.take(80).map {
                    mapOf("video_id" to it.id, "season" to it.season, "episode" to it.episode, "title" to it.title, "released" to it.released?.take(10), "available" to it.available)
                },
                "total" to episodes.size,
            )
        }
    }
}

internal fun ToolRegistry.registerPlaybackTools() {
    tool("play_title") {
        category = ToolCategory.Playback
        timeoutMs = 45_000
        description = """
            Start watching a movie or a series episode: opens the stream list for it (the app's normal
            Play flow) and waits for streams to load. Then call play_stream(index) to start one.
            For series pass season and episode (default: S1E1, or the resume point if in progress).
        """
        covers("AgentNavigationHandle.openStreams", "StreamsRepository.load")
        params {
            string("id", "Title id, e.g. tt0133093", required = true)
            string("type", "movie or series", required = true)
            integer("season", "Season number (series)")
            integer("episode", "Episode number (series)")
            boolean("start_over", "Start from the beginning instead of resuming")
        }
        run { args ->
            val type = normalizeType(args.string("type"))
            val meta = fetchMeta(type, args.string("id"))
            var season = args.optInt("season")
            var episode = args.optInt("episode")
            val video = if (type == "movie") null else {
                val resume = com.nuvio.app.features.watchprogress.WatchProgressRepository.resumeEntryForSeries(meta.id)
                if (season == null && episode == null && resume != null) {
                    season = resume.seasonNumber
                    episode = resume.episodeNumber
                }
                meta.videos.firstOrNull { it.season == (season ?: 1) && it.episode == (episode ?: 1) }
                    ?: meta.videos.filter { (it.season ?: 0) > 0 }.minWithOrNull(compareBy({ it.season }, { it.episode }))
                    ?: throw ToolFailure("No episodes found for ${meta.name}", "not_found")
            }
            val videoId = video?.id ?: meta.defaultVideoId ?: meta.id
            navigation().openStreams(
                AgentPlayRequest(
                    type = type,
                    videoId = videoId,
                    parentMetaId = meta.id,
                    parentMetaType = type,
                    title = meta.name,
                    poster = meta.poster,
                    background = meta.background,
                    logo = meta.logo,
                    seasonNumber = video?.season,
                    episodeNumber = video?.episode,
                    episodeTitle = video?.title,
                    manualSelection = true,
                    startFromBeginning = args.optBoolean("start_over") == true,
                ),
            )
            val route = awaitRoute(10_000) { it is StreamRoute || it is PlayerRoute }
            if (route is PlayerRoute) return@run okResult("status" to "playing (downloaded copy or autoplay)", "title" to meta.name)
            awaitStreamScreen()
            val streams = waitForStreams(20_000)
            okResult(
                "status" to if (route != null) "stream_list_open" else "requested",
                "title" to meta.name,
                "episode" to video?.let { "S${it.season}E${it.episode} ${it.title}" },
                "streams" to streams,
                "next" to "Call play_stream with an index to start playback.",
            )
        }
    }

    tool("list_streams") {
        category = ToolCategory.Playback
        readOnly = true
        timeoutMs = 30_000
        description = "List the streams on the open stream screen (index, quality/name, addon, kind). Waits briefly for addons still loading."
        covers("StreamsRepository.uiState")
        params { string("addon", "Only streams from this addon name") }
        run { args ->
            streamScreen()
            val streams = waitForStreams(15_000).filter { item ->
                val filter = args.optString("addon") ?: return@filter true
                (item["addon"] as? String)?.contains(filter, true) == true
            }
            okResult("streams" to streams, "still_loading" to StreamsRepository.uiState.value.isAnyLoading)
        }
    }

    tool("filter_streams_by_addon") {
        category = ToolCategory.Playback
        description = "Filter the stream list on screen to a single addon (or clear the filter)."
        covers("StreamsRepository.selectFilter")
        params { string("addon", "Addon name or id; omit to show all") }
        run { args ->
            val wanted = args.optString("addon")
            val id = wanted?.let { w -> StreamsRepository.uiState.value.groups.firstOrNull { it.addonName.contains(w, true) || it.addonId == w }?.addonId }
            StreamsRepository.selectFilter(id)
            okResult("filter" to (id ?: "all"))
        }
    }

    tool("play_stream") {
        category = ToolCategory.Playback
        timeoutMs = 45_000
        description = "Start playing stream number `index` from list_streams (opens the player). Waits until the player is up."
        covers("AgentStreamScreenHandle.playStream")
        params { integer("index", "Stream index from list_streams", required = true, min = 0) }
        run { args ->
            val handle = streamScreen()
            val streams = StreamsRepository.uiState.value.allStreams
            val index = args.int("index")
            val stream = streams.getOrNull(index)
                ?: throw ToolFailure("No stream at index $index (there are ${streams.size}). Call list_streams.", "not_found")
            handle.playStream(stream)
            val player = awaitPlayer(20_000)
            okResult(
                "started" to (player != null),
                "stream" to stream.summary(index),
                "player" to player?.state()?.let { mapOf("title" to it.title, "loading" to it.isLoading, "error" to it.errorMessage) },
            )
        }
    }
}

private suspend fun waitForStreams(timeoutMs: Long): List<Map<String, Any?>> {
    withTimeoutOrNull(timeoutMs) {
        StreamsRepository.uiState.first { !it.isAnyLoading && (it.groups.isNotEmpty() || it.emptyStateReason != null) }
    }
    return StreamsRepository.uiState.value.allStreams.take(40).mapIndexed { index, stream -> stream.summary(index) }
}
