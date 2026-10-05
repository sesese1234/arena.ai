package com.nuvio.app.agent.tools

import com.nuvio.app.agent.AgentBridge
import com.nuvio.app.agent.AgentNavigationHandle
import com.nuvio.app.agent.AgentPlayerHandle
import com.nuvio.app.agent.AgentStreamScreenHandle
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.CatalogRoute
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.StreamRoute
import com.nuvio.app.navigation.TabsRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal fun navigation(): AgentNavigationHandle =
    AgentBridge.navigation.value ?: throw ToolFailure("The app UI is not ready yet (no main screen). Try again in a moment.", "ui_not_ready")

internal fun player(): AgentPlayerHandle =
    AgentBridge.player.value ?: throw ToolFailure(
        "Nothing is playing. Start playback first (play_title then play_stream).",
        "no_player",
    )

internal fun streamScreen(): AgentStreamScreenHandle =
    AgentBridge.streams.value ?: throw ToolFailure(
        "The stream list is not open. Call play_title first to open the streams screen.",
        "no_stream_screen",
    )

/** Waits (polling the bridge) until the UI reaches a route matching [predicate]. */
internal suspend fun awaitRoute(timeoutMs: Long = 8_000, predicate: (AppRoute?) -> Boolean): AppRoute? =
    withTimeoutOrNull(timeoutMs) {
        while (true) {
            val route = AgentBridge.navigation.value?.currentRoute
            if (predicate(route)) return@withTimeoutOrNull route
            delay(150)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

internal suspend fun awaitPlayer(timeoutMs: Long = 15_000): AgentPlayerHandle? =
    withTimeoutOrNull(timeoutMs) { AgentBridge.player.first { it != null } }

internal suspend fun awaitStreamScreen(timeoutMs: Long = 10_000): AgentStreamScreenHandle? =
    withTimeoutOrNull(timeoutMs) { AgentBridge.streams.first { it != null } }

internal fun AppRoute?.describe(): Map<String, Any?> = when (this) {
    null -> mapOf("screen" to "unknown")
    is TabsRoute -> mapOf("screen" to "tabs", "tab" to AgentBridge.navigation.value?.selectedTab?.name?.lowercase())
    is DetailRoute -> mapOf("screen" to "title_details", "type" to type, "id" to id, "title" to title)
    is StreamRoute -> mapOf("screen" to "streams", "title" to title)
    is PlayerRoute -> mapOf("screen" to "player", "title" to title)
    is CatalogRoute -> mapOf("screen" to "catalog", "title" to title, "subtitle" to subtitle)
    else -> mapOf("screen" to (this::class.simpleName ?: "unknown").removeSuffix("Route").replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(), "title" to title)
}

internal fun MetaPreview.summary(): Map<String, Any?> = mapOf(
    "id" to id,
    "type" to type,
    "name" to name,
    "year" to releaseInfo,
    "imdb_rating" to imdbRating,
    "genres" to genres.take(4).takeIf { it.isNotEmpty() },
)

internal fun MetaDetails.summary(maxCast: Int = 10): Map<String, Any?> = mapOf(
    "id" to id,
    "type" to type,
    "name" to name,
    "imdb_id" to imdbId,
    "year" to releaseInfo,
    "status" to status,
    "runtime" to runtime,
    "imdb_rating" to imdbRating,
    "age_rating" to ageRating,
    "genres" to genres,
    "description" to description?.take(700),
    "director" to director,
    "writer" to writer.take(5),
    "cast" to cast.take(maxCast).map { mapOf("name" to it.name, "role" to it.role, "tmdb_person_id" to it.tmdbId) },
    "country" to country,
    "language" to language,
    "seasons" to videos.mapNotNull { it.season }.filter { it > 0 }.distinct().sorted().takeIf { it.isNotEmpty() },
    "episode_count" to videos.count { it.episode != null }.takeIf { it > 0 },
    "more_like_this" to moreLikeThis.take(8).map { mapOf("id" to it.id, "type" to it.type, "name" to it.name, "year" to it.releaseInfo) },
    "collection" to collectionName?.let { name -> mapOf("name" to name, "items" to collectionItems.take(10).map { mapOf("id" to it.id, "type" to it.type, "name" to it.name, "year" to it.releaseInfo) }) },
)

internal fun StreamItem.summary(index: Int): Map<String, Any?> = mapOf(
    "index" to index,
    "name" to name?.replace('\n', ' '),
    "title" to (title ?: description)?.replace('\n', ' ')?.take(160),
    "addon" to addonName,
    "kind" to when {
        url != null -> "http"
        infoHash != null -> "torrent"
        externalUrl != null -> "external"
        else -> "other"
    },
    "subtitles" to externalSubtitles.size.takeIf { it > 0 },
)

internal fun ManagedAddon.summary(index: Int): Map<String, Any?> = mapOf(
    "index" to index,
    "id" to manifest?.id,
    "name" to displayTitle,
    "version" to manifest?.version,
    "manifest_url" to manifestUrl,
    "enabled" to enabled,
    "loaded" to (manifest != null),
    "error" to errorMessage,
    "resources" to manifest?.resources?.map { it.name },
    "types" to manifest?.types,
    "catalogs" to manifest?.catalogs?.map { "${it.type}/${it.id} (${it.name})" }?.take(12),
)

/** Finds an installed addon by manifest URL, manifest id or (case-insensitive) name. */
internal fun findAddon(query: String): ManagedAddon {
    val addons = AddonRepository.uiState.value.addons
    val q = query.trim()
    return addons.firstOrNull { it.manifestUrl.equals(q, true) }
        ?: addons.firstOrNull { it.manifest?.id.equals(q, true) }
        ?: addons.firstOrNull { it.displayTitle.equals(q, true) }
        ?: addons.firstOrNull { it.displayTitle.contains(q, true) || it.manifestUrl.contains(q, true) }
        ?: throw ToolFailure("No installed addon matches '$query'. Installed: ${addons.joinToString { it.displayTitle }}", "not_found")
}

/** Loads title metadata through the app's repository (same path the details screen uses). */
internal suspend fun fetchMeta(type: String, id: String): MetaDetails {
    AddonRepository.initialize()
    AddonRepository.awaitManifestsLoaded()
    return MetaDetailsRepository.fetch(type, id)
        ?: throw ToolFailure("Could not load metadata for $type/$id. Check that a metadata addon (e.g. Cinemeta) is installed.", "not_found")
}

internal fun normalizeType(type: String?): String = when (type?.lowercase()?.trim()) {
    null, "", "movie", "movies", "film" -> "movie"
    "series", "show", "tv", "tv show", "shows" -> "series"
    else -> type.lowercase()
}
