package com.nuvio.app.features.watching.sync

import com.nuvio.app.features.watchprogress.WatchProgressEntry

data class ProgressSyncRecord(
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    val lastWatched: Long = 0L,
    // Display metadata synced alongside the position so a device that never played
    // this content can still render its Continue Watching card.
    val name: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val episodeTitle: String? = null,
)

data class ProgressDeltaEvent(
    val eventId: Long,
    val operation: String,
    val progressKey: String,
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    val lastWatched: Long = 0L,
    val name: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val episodeTitle: String? = null,
)

interface ProgressSyncAdapter {
    suspend fun pull(
        profileId: Int,
        sinceLastWatched: Long? = null,
        limit: Int? = null,
    ): List<ProgressSyncRecord>

    suspend fun getDeltaCursor(profileId: Int): Long? = null

    suspend fun pullDelta(
        profileId: Int,
        sinceEventId: Long,
        limit: Int,
    ): List<ProgressDeltaEvent> = emptyList()

    suspend fun push(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    )

    suspend fun delete(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    )
}
