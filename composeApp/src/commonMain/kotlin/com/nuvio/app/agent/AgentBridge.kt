package com.nuvio.app.agent

import com.nuvio.app.AppScreenTab
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.navigation.AppRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Entry points the shared UI publishes so the in-app agent (Android only) can read UI state and
 * drive the app through the same code paths the screens use. Each screen registers a handle while
 * it is composed and clears it on dispose; the agent never touches composables directly.
 */
object AgentBridge {
    private val _navigation = MutableStateFlow<AgentNavigationHandle?>(null)
    val navigation: StateFlow<AgentNavigationHandle?> = _navigation.asStateFlow()

    private val _streams = MutableStateFlow<AgentStreamScreenHandle?>(null)
    val streams: StateFlow<AgentStreamScreenHandle?> = _streams.asStateFlow()

    private val _player = MutableStateFlow<AgentPlayerHandle?>(null)
    val player: StateFlow<AgentPlayerHandle?> = _player.asStateFlow()

    private val _pendingSearchQuery = MutableStateFlow<String?>(null)
    /** A query the agent asked the Search screen to show in its text field. */
    val pendingSearchQuery: StateFlow<String?> = _pendingSearchQuery.asStateFlow()

    fun attachNavigation(handle: AgentNavigationHandle) { _navigation.value = handle }
    fun detachNavigation(handle: AgentNavigationHandle) { _navigation.compareAndSet(handle, null) }

    fun attachStreams(handle: AgentStreamScreenHandle) { _streams.value = handle }
    fun detachStreams(handle: AgentStreamScreenHandle) { _streams.compareAndSet(handle, null) }

    fun attachPlayer(handle: AgentPlayerHandle) { _player.value = handle }
    fun detachPlayer(handle: AgentPlayerHandle) { _player.compareAndSet(handle, null) }

    fun requestSearchQuery(query: String) { _pendingSearchQuery.value = query }
    fun consumeSearchQuery(query: String) { _pendingSearchQuery.compareAndSet(query, null) }
}

/** Parameters for starting playback of a title or episode (opens the stream picker / autoplay flow). */
data class AgentPlayRequest(
    val type: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val resumePositionMs: Long? = null,
    val manualSelection: Boolean = true,
    val startFromBeginning: Boolean = false,
)

interface AgentNavigationHandle {
    val currentRoute: AppRoute?
    val routes: List<AppRoute>
    val selectedTab: AppScreenTab
    fun selectTab(tab: AppScreenTab)
    fun navigate(route: AppRoute)
    fun back(): Boolean
    fun openDetails(type: String, id: String, title: String?)
    fun openCatalog(title: String, subtitle: String?, target: CatalogTarget)
    fun openSettingsPage(page: AgentSettingsPage)
    fun openStreams(request: AgentPlayRequest)
}

/** Settings destinations the agent can open; names match the app's settings pages. */
enum class AgentSettingsPage {
    Root, Account, Addons, Plugins, Playback, Streams, Appearance, PosterCustomization, Homescreen,
    MetaScreen, ContinueWatching, ContentDiscovery, Integrations, Notifications, Debrid, Advanced,
    LicensesAttributions, Downloads, Collections,
}

interface AgentStreamScreenHandle {
    /** What the open stream list is for (title, ids, episode). */
    val request: AgentPlayRequest
    fun playStream(stream: StreamItem)
}

data class AgentPlayerState(
    val title: String,
    val episodeTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val isSeries: Boolean,
    val parentMetaId: String,
    val parentMetaType: String,
    val streamTitle: String?,
    val providerName: String?,
    val isLoading: Boolean,
    val isPlaying: Boolean,
    val isEnded: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val audioTracks: List<AudioTrack>,
    val selectedAudioIndex: Int,
    val subtitleTracks: List<SubtitleTrack>,
    val selectedSubtitleIndex: Int,
    val addonSubtitles: List<AgentAddonSubtitle>,
    val selectedAddonSubtitleId: String?,
    val subtitleDelayMs: Int,
    val errorMessage: String?,
    val hasNextEpisode: Boolean,
)

data class AgentAddonSubtitle(val id: String, val language: String, val label: String, val addonName: String?)

interface AgentPlayerHandle {
    fun state(): AgentPlayerState
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun setSpeed(speed: Float)
    fun selectAudioTrack(index: Int): Boolean
    fun selectSubtitleTrack(index: Int): Boolean
    fun selectAddonSubtitle(id: String): Boolean
    fun disableSubtitles()
    fun fetchAddonSubtitles()
    fun setSubtitleDelay(delayMs: Int)
    fun playNextEpisode(): Boolean
    fun close()
}
