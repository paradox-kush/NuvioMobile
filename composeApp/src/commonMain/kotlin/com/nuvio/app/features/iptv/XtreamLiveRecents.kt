package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class XtreamLiveRecent(
    val contentId: String,
    val name: String,
    val logo: String?,
)

/**
 * Recently-watched live channels, profile-scoped + persisted. Live playback records no watch
 * progress (no duration), so this backs the "Live TV" row of Continue Watching. LRU, newest first.
 */
object XtreamLiveRecents {
    private const val CAP = 20
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _recents = MutableStateFlow<List<XtreamLiveRecent>>(emptyList())
    val recents: StateFlow<List<XtreamLiveRecent>> = _recents.asStateFlow()

    private var loaded = false
    private var currentProfileId = 1

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        currentProfileId = ProfileRepository.activeProfileId
        _recents.value = parse(XtreamAccountStorage.loadRecentsJson(currentProfileId))
    }

    fun record(contentId: String, name: String, logo: String?) {
        ensureLoaded()
        val entry = XtreamLiveRecent(contentId, name, logo)
        val updated = (listOf(entry) + _recents.value.filterNot { it.contentId == contentId }).take(CAP)
        _recents.value = updated
        XtreamAccountStorage.saveRecentsJson(currentProfileId, json.encodeToString(updated))
    }

    /** Reload this profile's recents on a profile switch (the Home Live TV row observes them live). */
    fun onProfileChanged(profileId: Int) {
        loaded = true
        currentProfileId = profileId
        _recents.value = parse(XtreamAccountStorage.loadRecentsJson(profileId))
    }

    private fun parse(stored: String?): List<XtreamLiveRecent> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<XtreamLiveRecent>>(stored) }.getOrDefault(emptyList())
    }
}

fun XtreamLiveRecent.toMetaPreview(): MetaPreview = MetaPreview(
    id = contentId,
    type = "tv",
    name = name,
    poster = logo,
    logo = logo,
    posterShape = PosterShape.Landscape,
)
