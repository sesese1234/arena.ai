package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.nuvio.app.agent.AgentAddonSubtitle
import com.nuvio.app.agent.AgentBridge
import com.nuvio.app.agent.AgentPlayerHandle
import com.nuvio.app.agent.AgentPlayerState

/**
 * Publishes the running player to the in-app assistant. Every action routes through the same
 * runtime functions the on-screen controls and track sheets use.
 */
@Composable
internal fun PlayerScreenRuntime.BindAgentPlayerHandle() {
    val runtime = this
    DisposableEffect(runtime) {
        val handle = object : AgentPlayerHandle {
            override fun state(): AgentPlayerState = AgentPlayerState(
                title = title,
                episodeTitle = activeEpisodeTitle,
                seasonNumber = activeSeasonNumber,
                episodeNumber = activeEpisodeNumber,
                isSeries = isSeries,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                streamTitle = activeStreamTitle,
                providerName = activeProviderName,
                isLoading = playbackSnapshot.isLoading,
                isPlaying = playbackSnapshot.isPlaying,
                isEnded = playbackSnapshot.isEnded,
                positionMs = playbackSnapshot.positionMs,
                durationMs = playbackSnapshot.durationMs,
                playbackSpeed = playbackSnapshot.playbackSpeed,
                audioTracks = audioTracks,
                selectedAudioIndex = selectedAudioIndex,
                subtitleTracks = subtitleTracks,
                selectedSubtitleIndex = selectedSubtitleIndex,
                addonSubtitles = visibleAddonSubtitles.map {
                    AgentAddonSubtitle(id = it.selectionKey, language = it.language, label = it.display, addonName = it.addonName)
                },
                selectedAddonSubtitleId = selectedAddonSubtitleId,
                subtitleDelayMs = subtitleDelayMs,
                errorMessage = errorMessage,
                hasNextEpisode = nextEpisodeInfo?.hasAired == true,
            )

            override fun play() {
                if (playbackSnapshot.isEnded) playerController?.seekTo(0L)
                shouldPlay = true
                playerController?.play()
            }

            override fun pause() {
                shouldPlay = false
                playerController?.pause()
            }

            override fun seekTo(positionMs: Long) {
                val target = positionMs.coerceAtLeast(0L).let {
                    if (playbackSnapshot.durationMs > 0L) it.coerceAtMost(playbackSnapshot.durationMs) else it
                }
                playerController?.seekTo(target)
                scheduleProgressSyncAfterSeek()
            }

            override fun seekBy(offsetMs: Long) = runtime.seekBy(offsetMs)

            override fun setSpeed(speed: Float) {
                playerController?.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
            }

            override fun selectAudioTrack(index: Int): Boolean {
                if (audioTracks.none { it.index == index }) return false
                selectAudioTrackFromUser(index)
                return true
            }

            override fun selectSubtitleTrack(index: Int): Boolean {
                if (subtitleTracks.none { it.index == index }) return false
                selectBuiltInSubtitleFromUser(index)
                return true
            }

            override fun selectAddonSubtitle(id: String): Boolean {
                val subtitle = visibleAddonSubtitles.firstOrNull { it.matchesSelection(id) }
                    ?: addonSubtitles.firstOrNull { it.matchesSelection(id) }
                    ?: return false
                selectAddonSubtitleFromUser(subtitle)
                return true
            }

            override fun disableSubtitles() = selectBuiltInSubtitleFromUser(-1)

            override fun fetchAddonSubtitles() = fetchAddonSubtitlesForActiveItem()

            override fun setSubtitleDelay(delayMs: Int) = runtime.setSubtitleDelay(delayMs)

            override fun playNextEpisode(): Boolean {
                if (nextEpisodeInfo?.hasAired != true) return false
                runtime.playNextEpisode(automatic = false)
                return true
            }

            override fun close() {
                flushWatchProgress()
                args.onBack()
            }
        }
        AgentBridge.attachPlayer(handle)
        onDispose { AgentBridge.detachPlayer(handle) }
    }
}
