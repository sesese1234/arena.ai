package com.nuvio.app.agent.tools

/**
 * Coverage contract between the app's public actions and the agent's tools.
 *
 * Nuvio has no ViewModels: screens talk to singleton repositories (`object …Repository`) and the
 * UI-bound bridges in [com.nuvio.app.agent.AgentBridge]. `AgentToolCoverageTest` reflects over
 * every owner listed in [OWNERS] and fails if a public member is neither declared in some tool's
 * `covers(...)` nor listed in [EXEMPTIONS] with a reason. Adding a public action to one of these
 * owners therefore forces a decision: expose it as a tool, or document why not.
 */
object AgentToolCoverage {
    val OWNERS: List<String> = listOf(
        "com.nuvio.app.features.addons.AddonRepository",
        "com.nuvio.app.features.library.LibraryRepository",
        "com.nuvio.app.features.search.SearchRepository",
        "com.nuvio.app.features.search.SearchHistoryRepository",
        "com.nuvio.app.features.catalog.CatalogRepository",
        "com.nuvio.app.features.details.MetaDetailsRepository",
        "com.nuvio.app.features.streams.StreamsRepository",
        "com.nuvio.app.features.watched.WatchedRepository",
        "com.nuvio.app.features.watchprogress.WatchProgressRepository",
        "com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository",
        "com.nuvio.app.features.player.PlayerSettingsRepository",
        "com.nuvio.app.features.settings.ThemeSettingsRepository",
        "com.nuvio.app.features.details.MetaScreenSettingsRepository",
        "com.nuvio.app.features.home.HomeCatalogSettingsRepository",
        "com.nuvio.app.features.home.HomeRepository",
        "com.nuvio.app.features.downloads.DownloadsRepository",
        "com.nuvio.app.features.collection.CollectionRepository",
        "com.nuvio.app.features.profiles.ProfileRepository",
        "com.nuvio.app.core.auth.AuthRepository",
        "com.nuvio.app.agent.AgentNavigationHandle",
        "com.nuvio.app.agent.AgentStreamScreenHandle",
        "com.nuvio.app.agent.AgentPlayerHandle",
    )

    private const val LIFECYCLE = "Lifecycle/bootstrapping called by the app itself (load, profile switch, logout cleanup); not a user action."
    private const val SYNC = "Background sync with the Nuvio/Trakt/Simkl backends, triggered automatically; not a user action."
    private const val INTERNAL = "Implementation detail used by other repository methods or the UI layout (scroll positions, caches, tokens)."
    private const val IOS = "iOS-only player option (this is the Android app)."
    private const val CREDENTIALS = "Requires credentials or a PIN the assistant must never handle; the user does this in the UI."
    private const val UI_ONLY = "Pure presentation state of a screen (overlay visibility, scroll, animations)."

    val EXEMPTIONS: Map<String, String> = buildMap {
        // Lifecycle
        listOf(
            "AddonRepository.initialize", "AddonRepository.onProfileChanged", "AddonRepository.clearLocalState",
            "LibraryRepository.ensureLoaded", "LibraryRepository.onProfileChanged",
            "SearchHistoryRepository.ensureLoaded", "SearchHistoryRepository.onProfileChanged",
            "WatchedRepository.ensureLoaded", "WatchedRepository.onProfileChanged", "WatchedRepository.clearLocalState",
            "WatchProgressRepository.ensureLoaded", "WatchProgressRepository.onProfileChanged", "WatchProgressRepository.clearLocalState",
            "ContinueWatchingPreferencesRepository.ensureLoaded", "ContinueWatchingPreferencesRepository.onProfileChanged", "ContinueWatchingPreferencesRepository.clearLocalState",
            "PlayerSettingsRepository.ensureLoaded", "PlayerSettingsRepository.onProfileChanged", "PlayerSettingsRepository.clearLocalState",
            "ThemeSettingsRepository.ensureLoaded", "ThemeSettingsRepository.onProfileChanged", "ThemeSettingsRepository.clearLocalState",
            "MetaScreenSettingsRepository.ensureLoaded", "MetaScreenSettingsRepository.onProfileChanged", "MetaScreenSettingsRepository.clearLocalState",
            "HomeCatalogSettingsRepository.onProfileChanged", "HomeCatalogSettingsRepository.clearLocalState",
            "DownloadsRepository.ensureLoaded", "DownloadsRepository.onProfileChanged", "DownloadsRepository.clearLocalState",
            "CollectionRepository.initialize", "CollectionRepository.onProfileChanged", "CollectionRepository.clearLocalState",
            "ProfileRepository.ensureLoaded", "ProfileRepository.clearInMemory", "ProfileRepository.loadCachedProfiles",
            "AuthRepository.initialize", "AuthRepository.reinitialize", "AuthRepository.clearError",
            "HomeRepository.clear", "HomeRepository.applyCurrentSettings", "HomeRepository.refresh",
            "MetaDetailsRepository.clear", "StreamsRepository.clear", "CatalogRepository.clear", "SearchRepository.reset",
        ).forEach { put(it, LIFECYCLE) }
        // Sync
        listOf(
            "AddonRepository.pullFromServer", "LibraryRepository.pullFromServer",
            "WatchedRepository.pullFromServer", "WatchedRepository.forceSnapshotRefreshFromServer",
            "WatchProgressRepository.pullFromServer", "WatchProgressRepository.forceSnapshotRefreshFromServer",
            "WatchProgressRepository.clearLocalAndForceSnapshotRefreshFromServer", "WatchProgressRepository.selectWatchProgressSource",
            "WatchProgressRepository.refreshEpisodeProgress", "WatchProgressRepository.prepareNextUpProgressEntries",
            "WatchedRepository.reconcileSeriesWatchedState", "WatchedRepository.reconcileFullyWatchedSeriesState",
            "WatchedRepository.updateFullyWatchedSeries",
            "HomeCatalogSettingsRepository.syncCatalogs", "HomeCatalogSettingsRepository.syncCollections",
            "HomeCatalogSettingsRepository.exportToSyncPayload", "HomeCatalogSettingsRepository.applyFromRemote",
            "ProfileRepository.pullProfiles", "ProfileRepository.pushProfiles", "ProfileRepository.pullProfileLocks",
            "CollectionRepository.setCollections", "AuthRepository.signOutIfSessionInvalid", "AuthRepository.prepareForServerSwitch",
            "LibraryRepository.applyMembershipChanges", "LibraryRepository.getMembershipSnapshot", "LibraryRepository.libraryListTabs",
        ).forEach { put(it, SYNC) }
        // Internal details
        listOf(
            "CatalogRepository.scrollPosition", "CatalogRepository.saveScrollPosition",
            "StreamsRepository.requestToken", "StreamsRepository.reload", "StreamsRepository.consumeAutoPlay",
            "StreamsRepository.skipAutoPlayStream", "StreamsRepository.cancelLoading", "StreamsRepository.setOverlayVisible",
            
            
            "WatchProgressRepository.upsertPlaybackProgress",
            "WatchProgressRepository.flushPlaybackProgress", "WatchProgressRepository.isDroppedShow",
            "WatchProgressRepository.activeProviderOwnsCompletedHistoryProjection", "WatchProgressRepository.activeProviderContinueWatchingCutoffEpochMs",
            "WatchProgressRepository.shouldUseAsNextUpSeed",
            "WatchedRepository.fullyWatchedSeriesKeys", "WatchedRepository.setExpandedFullyWatchedSeriesKeys",
            "WatchedRepository.currentExpandedSiblingKeys", "WatchedRepository.baseFullyWatchedSeriesKeys",
            "MetaDetailsRepository.findEmbeddedStreams",
            "DownloadsRepository.hasUnseenCompleted", "DownloadsRepository.markCompletedSeen", "DownloadsRepository.findPlayableDownloadByVideoId",
            "DownloadsRepository.findPlayableDownload", "DownloadsRepository.playableLocalFileUri",
            "CollectionRepository.generateId", "CollectionRepository.availableCatalogs", "CollectionRepository.validateJson",
            "ContinueWatchingPreferencesRepository.addDismissedNextUpKey", "ContinueWatchingPreferencesRepository.removeDismissedNextUpKeysForContent",
            "ContinueWatchingPreferencesRepository.uiState", "PlayerSettingsRepository.uiState",
            "ThemeSettingsRepository.selectedThemePreference", "ThemeSettingsRepository.selectedTheme", "ThemeSettingsRepository.customThemePreference",
            "ThemeSettingsRepository.customThemeColors", "ThemeSettingsRepository.amoledEnabled", "ThemeSettingsRepository.liquidGlassNativeTabBarEnabled",
            "ThemeSettingsRepository.selectedAppLanguage", "ThemeSettingsRepository.navBarStyle", "ThemeSettingsRepository.navBarGlowEnabled",
            "MetaScreenSettingsRepository.uiState", "AuthRepository.error", "SearchHistoryRepository.recordSearch",
        ).forEach { put(it, INTERNAL) }
        // Presentation-only or advanced editors left to the UI.
        listOf(
            "ThemeSettingsRepository.setCustomTheme" to "Custom colour editor needs a visual picker; the assistant can switch between themes via set_setting.",
            "MetaScreenSettingsRepository.setTabGroup" to UI_ONLY,
            "MetaScreenSettingsRepository.setEnabled" to "Section toggles for the title page are drag-and-drop layout edits done in the layout editor.",
            "MetaScreenSettingsRepository.moveByIndex" to UI_ONLY,
            "MetaScreenSettingsRepository.setEpisodeRatingsVisibility" to "Rarely used three-way layout option; available in the details settings screen (open_settings).",
            "CollectionRepository.addCollection" to "Collections are built in the visual collection editor (folders, artwork, catalog sources).",
            "CollectionRepository.updateCollection" to "Collections are built in the visual collection editor (folders, artwork, catalog sources).",
            "CollectionRepository.exportToJson" to "Export/import of collection JSON is a file-sharing flow handled by the UI.",
            "CollectionRepository.importFromJson" to "Export/import of collection JSON is a file-sharing flow handled by the UI.",
            "PlayerSettingsRepository.setSubtitleStyle" to "Subtitle font/colour/outline styling needs the visual style panel.",
            "PlayerSettingsRepository.setAndroidLibmpvVideoOutput" to "Low-level decoder output; misconfiguration can break playback, left to the advanced UI.",
            "PlayerSettingsRepository.setStreamAutoPlaySelectedAddons" to "Multi-select list edited in the playback settings UI.",
            "PlayerSettingsRepository.setStreamAutoPlaySelectedPlugins" to "Multi-select list edited in the playback settings UI.",
            "PlayerSettingsRepository.setAutoSkipSegmentTypeEnabled" to "Per-segment auto-skip matrix edited in the playback settings UI.",
            "PlayerSettingsRepository.setAnimeSkipClientId" to CREDENTIALS,
            "PlayerSettingsRepository.setIntroDbApiKey" to CREDENTIALS,
            "SearchHistoryRepository.uiState" to "Exposed through get_search_history.",
            "AuthRepository.signUpWithEmail" to CREDENTIALS,
            "AuthRepository.signInWithEmail" to CREDENTIALS,
            "AuthRepository.signInAnonymously" to "Onboarding choice made by the user before the app (and the assistant) is reachable.",
            "AuthRepository.deleteAccount" to "Irreversible account deletion is never delegated to the assistant.",
            "ProfileRepository.createProfile" to "Profile creation needs avatar/name choices in the profile editor.",
            "ProfileRepository.updateProfile" to "Profile editing needs avatar/name choices in the profile editor.",
            "ProfileRepository.deleteProfile" to "Deleting a profile and its data is left to the profile editor.",
            "ProfileRepository.verifyPin" to CREDENTIALS,
            "ProfileRepository.setPin" to CREDENTIALS,
            "ProfileRepository.clearPin" to CREDENTIALS,
            "ProfileRepository.clearPinWithPassword" to CREDENTIALS,
            "ProfileRepository.setRememberLastProfileEnabled" to "Profile-picker preference shown only on the profile picker.",
        ).forEach { (k, v) -> put(k, v) }
        // iOS-only player options
        listOf(
            "setIosVideoOutputPreset", "setIosToneMappingMode", "setIosTargetPrimaries", "setIosTargetTransfer",
            "setIosHardwareDecoderMode", "setIosAudioOutputMode", "setIosExtendedDynamicRangeEnabled",
            "setIosTargetColorspaceHintEnabled", "setIosHdrComputePeakEnabled", "setIosDebandEnabled",
            "setIosInterpolationEnabled", "resetIosVideoOutputTuning", "setIosBrightness", "setIosContrast", "setIosSaturation", "setIosGamma",
        ).forEach { put("PlayerSettingsRepository.$it", IOS) }
    }
}
