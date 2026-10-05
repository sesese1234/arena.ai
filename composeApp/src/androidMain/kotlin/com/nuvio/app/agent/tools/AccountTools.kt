package com.nuvio.app.agent.tools

import com.nuvio.app.agent.core.ConfirmationRequest
import com.nuvio.app.agent.core.ToolCategory
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.core.int
import com.nuvio.app.agent.core.okResult
import com.nuvio.app.agent.core.optString
import com.nuvio.app.agent.core.string
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamsRepository

internal fun ToolRegistry.registerAccountTools() {
    tool("get_account") {
        category = ToolCategory.Account
        readOnly = true
        description = "Account and profile info: signed-in state (guest or email), profiles and which one is active."
        covers("AuthRepository.state", "ProfileRepository.state", "ProfileRepository.activeProfileId")
        run {
            val auth = AuthRepository.state.value
            val profiles = ProfileRepository.state.value
            okResult(
                "auth" to when (auth) {
                    is AuthState.Authenticated -> mapOf("signed_in" to true, "guest" to auth.isAnonymous, "email" to auth.email)
                    AuthState.Unauthenticated -> mapOf("signed_in" to false)
                    AuthState.Loading -> mapOf("loading" to true)
                },
                "active_profile" to profiles.activeProfile?.profileIndex,
                "profiles" to profiles.profiles.map { mapOf("index" to it.profileIndex, "name" to it.name, "pin_protected" to it.pinEnabled) },
            )
        }
    }

    tool("switch_profile") {
        category = ToolCategory.Account
        description = "Switch to another profile (changes library, progress and addons shown). Asks the user to confirm. PIN-protected profiles must be opened by the user."
        covers("ProfileRepository.selectProfile")
        params { integer("profile_index", "Profile index from get_account", required = true, min = 1) }
        confirm { args ->
            val name = ProfileRepository.state.value.profiles.firstOrNull { it.profileIndex == args.int("profile_index") }?.name ?: "#${args.int("profile_index")}"
            ConfirmationRequest("Switch profile?", "The assistant wants to switch to the profile “$name”.", "Switch", destructive = false)
        }
        run { args ->
            val index = args.int("profile_index")
            val profile = ProfileRepository.state.value.profiles.firstOrNull { it.profileIndex == index }
                ?: throw ToolFailure("No profile with index $index", "not_found")
            if (profile.pinEnabled) throw ToolFailure("“${profile.name}” is PIN-protected; ask the user to switch from the profile picker.", "needs_user")
            ProfileRepository.selectProfile(index)
            okResult("active_profile" to ProfileRepository.state.value.activeProfile?.name)
        }
    }

    tool("sign_out") {
        category = ToolCategory.Account
        timeoutMs = 30_000
        description = "Sign out of the Nuvio account on this device. Asks the user to confirm."
        covers("AuthRepository.signOut")
        confirm {
            ConfirmationRequest("Sign out?", "The assistant wants to sign you out of your account on this device.", "Sign out")
        }
        run {
            val result = AuthRepository.signOut()
            result.exceptionOrNull()?.let { throw ToolFailure("Sign-out failed: ${it.message}", "failed") }
            okResult("signed_out" to true)
        }
    }
}

internal fun ToolRegistry.registerDownloadTools() {
    tool("list_downloads") {
        category = ToolCategory.Downloads
        readOnly = true
        description = "List downloads with status (downloading, paused, completed, failed) and progress."
        covers("DownloadsRepository.uiState")
        run {
            DownloadsRepository.ensureLoaded()
            okResult("downloads" to DownloadsRepository.uiState.value.items.map {
                mapOf(
                    "id" to it.id, "title" to it.title, "season" to it.seasonNumber, "episode" to it.episodeNumber,
                    "status" to it.status.name.lowercase(),
                    "percent" to it.totalBytes?.takeIf { total -> total > 0 }?.let { total -> it.downloadedBytes * 100 / total },
                    "error" to it.errorMessage,
                )
            })
        }
    }

    tool("download_stream") {
        category = ToolCategory.Downloads
        timeoutMs = 30_000
        description = "Download stream `index` from the open stream list for offline viewing (direct HTTP streams only)."
        covers("DownloadsRepository.enqueueFromStream", "AgentStreamScreenHandle.request")
        params { integer("index", "Stream index from list_streams", required = true, min = 0) }
        run { args ->
            val request = streamScreen().request
            val stream = StreamsRepository.uiState.value.allStreams.getOrNull(args.int("index"))
                ?: throw ToolFailure("No stream at index ${args.int("index")}", "not_found")
            val result = DownloadsRepository.enqueueFromStream(
                contentType = request.type,
                videoId = request.videoId,
                parentMetaId = request.parentMetaId,
                parentMetaType = request.parentMetaType,
                title = request.title,
                logo = request.logo,
                poster = request.poster,
                background = request.background,
                seasonNumber = request.seasonNumber,
                episodeNumber = request.episodeNumber,
                episodeTitle = request.episodeTitle,
                episodeThumbnail = request.episodeThumbnail,
                stream = stream,
            )
            okResult("result" to result.toastMessage())
        }
    }

    tool("manage_download") {
        category = ToolCategory.Downloads
        description = "Pause, resume, retry or cancel a download (cancel deletes the partial file and asks to confirm)."
        covers(
            "DownloadsRepository.pauseDownload", "DownloadsRepository.resumeDownload",
            "DownloadsRepository.retryDownload", "DownloadsRepository.cancelDownload",
            "DownloadsRepository.pauseActiveDownloads",
        )
        params {
            string("action", "What to do", required = true, enum = listOf("pause", "resume", "retry", "cancel", "pause_all"))
            string("download_id", "Download id from list_downloads (not needed for pause_all)")
        }
        confirm { args ->
            if (args.string("action") == "cancel") ConfirmationRequest("Cancel download?", "The download and its partial file will be deleted.", "Cancel download") else null
        }
        run { args ->
            val id = args.optString("download_id")
            fun requireId() = id ?: throw ToolFailure("download_id is required", "invalid_arguments")
            when (args.string("action")) {
                "pause" -> DownloadsRepository.pauseDownload(requireId())
                "resume" -> DownloadsRepository.resumeDownload(requireId())
                "retry" -> DownloadsRepository.retryDownload(requireId())
                "cancel" -> DownloadsRepository.cancelDownload(requireId())
                "pause_all" -> DownloadsRepository.pauseActiveDownloads()
            }
            okResult("status" to id?.let { did -> DownloadsRepository.uiState.value.items.firstOrNull { it.id == did }?.status?.name?.lowercase() ?: "removed" })
        }
    }
}

internal fun ToolRegistry.registerCollectionTools() {
    tool("list_collections") {
        category = ToolCategory.Collections
        readOnly = true
        description = "List the user's collections (custom groups of catalogs) and their folders."
        covers("CollectionRepository.collections", "CollectionRepository.getCollection")
        run {
            CollectionRepository.initialize()
            okResult("collections" to CollectionRepository.collections.value.map { c ->
                mapOf("id" to c.id, "title" to c.title, "pinned" to c.pinToTop, "folders" to c.folders.map { it.title })
            })
        }
    }

    tool("delete_collection") {
        category = ToolCategory.Collections
        description = "Delete a collection. Asks the user to confirm."
        covers("CollectionRepository.removeCollection")
        params { string("collection_id", "Collection id from list_collections", required = true) }
        confirm { args ->
            val title = CollectionRepository.getCollection(args.string("collection_id"))?.title ?: args.string("collection_id")
            ConfirmationRequest("Delete collection?", "The collection “$title” will be deleted.", "Delete")
        }
        run { args ->
            CollectionRepository.getCollection(args.string("collection_id")) ?: throw ToolFailure("No such collection", "not_found")
            CollectionRepository.removeCollection(args.string("collection_id"))
            okResult("deleted" to true)
        }
    }

    tool("move_collection") {
        category = ToolCategory.Collections
        description = "Move a collection up or down in the list."
        covers("CollectionRepository.moveUp", "CollectionRepository.moveDown", "CollectionRepository.moveByIndex")
        params {
            string("collection_id", "Collection id", required = true)
            string("direction", "up or down", required = true, enum = listOf("up", "down"))
        }
        run { args ->
            val index = CollectionRepository.collections.value.indexOfFirst { it.id == args.string("collection_id") }
            if (index < 0) throw ToolFailure("No such collection", "not_found")
            if (args.string("direction") == "up") CollectionRepository.moveUp(index) else CollectionRepository.moveDown(index)
            okResult("order" to CollectionRepository.collections.value.map { it.title })
        }
    }
}
