package com.nuvio.app.agent.tools

import com.nuvio.app.AppScreenTab
import com.nuvio.app.agent.AgentBridge
import com.nuvio.app.agent.AgentSettingsPage
import com.nuvio.app.agent.core.ToolCategory
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.core.int
import com.nuvio.app.agent.core.okResult
import com.nuvio.app.agent.core.optInt
import com.nuvio.app.agent.core.optString
import com.nuvio.app.agent.core.string
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.navigation.CatalogRoute
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadsRoute
import com.nuvio.app.navigation.PersonDetailRoute
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.StreamRoute
import com.nuvio.app.navigation.TabsRoute

internal fun ToolRegistry.registerStateTools() {
    tool("get_app_state") {
        category = ToolCategory.State
        readOnly = true
        description = """
            Read the current app state: which screen is open (and its title/id), the selected tab,
            the navigation back stack, account/profile, addon and library counts, and a short
            summary of what is playing. Call this first when you need to know where the user is.
        """
        covers("AgentNavigationHandle.currentRoute", "AgentNavigationHandle.routes", "AgentNavigationHandle.selectedTab")
        run {
            val nav = AgentBridge.navigation.value
            val auth = AuthRepository.state.value
            val profiles = ProfileRepository.state.value
            val player = AgentBridge.player.value?.state()
            okResult(
                "current_screen" to nav?.currentRoute.describe(),
                "selected_tab" to nav?.selectedTab?.name?.lowercase(),
                "back_stack" to nav?.routes?.map { it.describe()["screen"] },
                "signed_in" to when (auth) {
                    is AuthState.Authenticated -> if (auth.isAnonymous) "guest" else "account:${auth.email.orEmpty()}"
                    AuthState.Unauthenticated -> "signed_out"
                    AuthState.Loading -> "loading"
                },
                "active_profile" to profiles.activeProfile?.let { mapOf("index" to it.profileIndex, "name" to it.name) },
                "installed_addons" to AddonRepository.uiState.value.addons.size,
                "library_items" to LibraryRepository.uiState.value.items.size,
                "now_playing" to player?.let {
                    mapOf(
                        "title" to it.title,
                        "episode" to it.episodeTitle,
                        "position_s" to it.positionMs / 1000,
                        "duration_s" to it.durationMs / 1000,
                        "playing" to it.isPlaying,
                    )
                },
            )
        }
    }

    tool("get_visible_items") {
        category = ToolCategory.State
        readOnly = true
        description = """
            List the items currently shown on screen (home rows, search results, catalog page,
            title details, stream list or library), with ids you can pass to other tools.
        """
        covers(
            "HomeRepository.uiState", "SearchRepository.uiState", "CatalogRepository.uiState",
            "MetaDetailsRepository.uiState", "StreamsRepository.uiState",
        )
        params { integer("limit", "Max items per list (default 15)", min = 1, max = 60) }
        run { args ->
            val limit = args.optInt("limit") ?: 15
            val nav = navigation()
            val route = nav.currentRoute
            val content: Any? = when (route) {
                is DetailRoute -> MetaDetailsRepository.uiState.value.meta?.summary()
                is StreamRoute -> StreamsRepository.uiState.value.let { state ->
                    mapOf(
                        "loading" to state.isAnyLoading,
                        "streams" to state.allStreams.take(limit).mapIndexed { i, s -> s.summary(i) },
                        "addons" to state.groups.map { mapOf("addon" to it.addonName, "count" to it.streams.size, "loading" to it.isLoading, "error" to it.error) },
                    )
                }
                is CatalogRoute -> CatalogRepository.uiState.value.let { state ->
                    mapOf("loading" to state.isLoading, "items" to state.items.take(limit).map { it.summary() }, "can_load_more" to state.canLoadMore)
                }
                is PlayerRoute -> AgentBridge.player.value?.state()
                is TabsRoute -> when (nav.selectedTab) {
                    AppScreenTab.Home -> HomeRepository.uiState.value.let { state ->
                        mapOf(
                            "loading" to state.isLoading,
                            "rows" to state.sections.take(12).map { section ->
                                mapOf("key" to section.key, "title" to section.title, "addon" to section.addonName, "items" to section.items.take(limit).map { it.summary() })
                            },
                        )
                    }
                    AppScreenTab.Search -> SearchRepository.uiState.value.let { state ->
                        mapOf(
                            "loading" to state.isLoading,
                            "sections" to state.sections.map { s -> mapOf("title" to s.title, "items" to s.items.take(limit).map { it.summary() }) },
                            "empty_reason" to state.emptyStateReason?.name,
                        )
                    }
                    AppScreenTab.Library -> LibraryRepository.uiState.value.items.take(limit).map {
                        mapOf("id" to it.id, "type" to it.type, "name" to it.name, "year" to it.releaseInfo)
                    }
                    AppScreenTab.Settings -> "Settings root: use list_settings / open_settings for details"
                }
                else -> "No structured list for this screen"
            }
            okResult("screen" to route.describe(), "content" to content)
        }
    }
}

internal fun ToolRegistry.registerNavigationTools() {
    tool("open_tab") {
        category = ToolCategory.Navigation
        description = "Switch to a main tab: home, search, library or settings (the Profile tab). Pops any screens on top."
        covers("AgentNavigationHandle.selectTab")
        params { string("tab", "Tab to open", required = true, enum = listOf("home", "search", "library", "settings")) }
        run { args ->
            val tab = AppScreenTab.fromName(args.string("tab"))
            val nav = navigation()
            while (nav.currentRoute !is TabsRoute && nav.back()) Unit
            nav.selectTab(tab)
            okResult("tab" to tab.name.lowercase())
        }
    }

    tool("go_back") {
        category = ToolCategory.Navigation
        description = "Navigate back one screen (like the system back button)."
        covers("AgentNavigationHandle.back")
        params { integer("times", "How many screens to go back (default 1)", min = 1, max = 10) }
        run { args ->
            val nav = navigation()
            var popped = 0
            repeat(args.optInt("times") ?: 1) { if (nav.back()) popped++ }
            okResult("went_back" to popped, "now_on" to nav.currentRoute.describe())
        }
    }

    tool("open_title") {
        category = ToolCategory.Navigation
        description = """
            Open the details screen of a movie or series by id (IMDb ids like tt0133093 or addon ids).
            Use search_titles first if you only know the name.
        """
        covers("AgentNavigationHandle.openDetails", "MetaDetailsRepository.load")
        params {
            string("id", "Title id, e.g. tt0133093", required = true)
            string("type", "movie or series", required = true, enum = listOf("movie", "series", "anime", "channel", "tv"))
        }
        run { args ->
            val id = args.string("id")
            val type = normalizeType(args.string("type"))
            val meta = runCatching { fetchMeta(type, id) }.getOrNull()
            navigation().openDetails(type, id, meta?.name)
            val route = awaitRoute { it is DetailRoute && it.id == id }
            okResult("opened" to (route != null), "title" to meta?.name, "year" to meta?.releaseInfo)
        }
    }

    tool("open_person") {
        category = ToolCategory.Navigation
        description = "Open a cast/crew member's page (needs the tmdb_person_id from get_title_details)."
        covers("AgentNavigationHandle.navigate")
        params {
            integer("tmdb_person_id", "TMDB person id", required = true)
            string("name", "Person name", required = true)
        }
        run { args ->
            navigation().navigate(PersonDetailRoute(personId = args.int("tmdb_person_id"), personName = args.string("name")))
            okResult("opened" to args.string("name"))
        }
    }

    tool("open_settings") {
        category = ToolCategory.Navigation
        description = "Open a settings screen (addons, playback, appearance, account, downloads, …)."
        covers("AgentNavigationHandle.openSettingsPage")
        params {
            string("page", "Settings page", required = true, enum = AgentSettingsPage.entries.map { it.name.lowercase() })
        }
        run { args ->
            val page = AgentSettingsPage.entries.first { it.name.equals(args.string("page"), true) }
            navigation().openSettingsPage(page)
            okResult("opened" to page.name.lowercase())
        }
    }

    tool("open_downloads") {
        category = ToolCategory.Navigation
        description = "Open the Downloads screen (offline files)."
        run {
            navigation().navigate(DownloadsRoute("Downloads"))
            okResult("opened" to "downloads")
        }
    }

    tool("open_catalog") {
        category = ToolCategory.Navigation
        description = """
            Open a full catalog page from an installed addon (e.g. Cinemeta "Popular" movies), optionally
            filtered by genre. Get addon and catalog ids from list_addons or list_home_rows.
        """
        covers("AgentNavigationHandle.openCatalog", "CatalogRepository.load")
        params {
            string("addon", "Installed addon name, id or manifest URL", required = true)
            string("type", "Content type of the catalog, e.g. movie or series", required = true)
            string("catalog_id", "Catalog id from the addon manifest, e.g. top", required = true)
            string("genre", "Optional genre filter")
        }
        run { args ->
            val addon = findAddon(args.string("addon"))
            val catalog = addon.manifest?.catalogs?.firstOrNull { it.id == args.string("catalog_id") && it.type == normalizeType(args.string("type")) }
                ?: addon.manifest?.catalogs?.firstOrNull { it.id == args.string("catalog_id") }
                ?: throw ToolFailure("${addon.displayTitle} has no catalog ${args.string("catalog_id")}", "not_found")
            val target = CatalogTarget.Addon(
                manifestUrl = addon.manifestUrl,
                contentType = catalog.type,
                catalogId = catalog.id,
                genre = args.optString("genre"),
                supportsPagination = catalog.extra.any { it.name == "skip" },
            )
            navigation().openCatalog(catalog.name, addon.displayTitle, target)
            okResult("opened" to catalog.name, "addon" to addon.displayTitle)
        }
    }
}
