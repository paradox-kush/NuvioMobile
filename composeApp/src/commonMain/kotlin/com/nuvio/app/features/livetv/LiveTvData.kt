package com.nuvio.app.features.livetv

import com.nuvio.app.features.epg.EpgMirrorRepository
import com.nuvio.app.features.iptv.IptvClient
import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamKind
import com.nuvio.app.features.iptv.XtreamLiveRecents
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.iptv.XtreamSearchIndex
import com.nuvio.app.features.iptv.resolveLivePlaybackUrl
import com.nuvio.app.features.trakt.TraktPlatformClock

/** A resolved, ready-to-play live source (post DoH/IP-rewrite). */
data class LiveChannelSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** A single channel row in the guide. */
data class LiveGuideChannel(
    val contentId: String,
    val name: String,
    val logo: String?,
    val streamId: Int,
    val categoryId: String?,
)

/**
 * Shared live-TV data access for the docked Live screen: resolves a channel's playable URL (reusing
 * the same registry + DoH path as the old direct-to-player launch) and loads guide channels + EPG
 * windows. Stateless — callers own their own coroutine scope and caches.
 */
object LiveTvData {

    /**
     * Resolves the playable source for a live channel id, mirroring `App.launchLiveChannel`:
     * registry lookup (sync then async for M3U/Stalker) → DoH-resolved live URL. Also records the
     * channel to the live "recently watched" LRU. Returns null if the URL can't be resolved.
     */
    suspend fun resolveSource(contentId: String, name: String, logo: String?): LiveChannelSource? {
        val immediate = XtreamItemRegistry.liveStreamUrlFor(contentId)
            ?: XtreamItemRegistry.liveStreamUrlForAsync(contentId)
            ?: return null
        val dnsProvider = XtreamItemRegistry.dnsProviderFor(contentId)
        val playback = resolveLivePlaybackUrl(immediate, dnsProvider)
        XtreamLiveRecents.record(contentId, name, logo)
        return LiveChannelSource(url = playback.url, headers = playback.headers)
    }

    /**
     * Live channels for the account that owns [contentId], for the guide's channel column. The
     * current channel's own category is surfaced first so the guide opens on relevant neighbours.
     */
    suspend fun guideChannels(contentId: String): List<LiveGuideChannel> {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return emptyList()
        if (parsed.kind != XtreamKind.LIVE) return emptyList()
        XtreamRepository.ensureLoaded()
        val account = XtreamRepository.uiState.value.accounts
            .firstOrNull { it.id == parsed.accountId } ?: return emptyList()
        val channels = runCatching { XtreamSearchIndex.liveChannelsFor(account) }
            .getOrDefault(emptyList())
        if (channels.isEmpty()) return emptyList()

        val currentCategory = channels.firstOrNull { it.streamId.toString() == parsed.id }?.categoryId
        val mapped = channels.map { ch ->
            LiveGuideChannel(
                contentId = XtreamItemRegistry.liveId(account.id, ch.streamId),
                name = ch.name,
                logo = ch.logo,
                streamId = ch.streamId,
                categoryId = ch.categoryId,
            )
        }
        return if (currentCategory != null) {
            mapped.sortedByDescending { it.categoryId == currentCategory }
        } else {
            mapped
        }
    }

    /**
     * Programme window for a channel — the panel's own short EPG (now + upcoming) with a fallback to
     * the mirrored canonical EPG when the provider ships no per-stream EPG (the common case).
     */
    suspend fun programmes(contentId: String, limit: Int = 8): List<XtreamProgram> {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return emptyList()
        if (parsed.kind != XtreamKind.LIVE) return emptyList()
        val streamId = parsed.id.toIntOrNull() ?: return emptyList()
        val account = XtreamRepository.uiState.value.accounts
            .firstOrNull { it.id == parsed.accountId } ?: return emptyList()
        val fromPanel = runCatching {
            IptvClient.forAccount(account).shortEpg(account, streamId, limit).getOrDefault(emptyList())
        }.getOrDefault(emptyList())
        if (fromPanel.isNotEmpty()) return fromPanel
        return runCatching {
            EpgMirrorRepository.nowNextProgrammes(account.id, streamId, TraktPlatformClock.nowEpochMs())
        }.getOrDefault(emptyList())
    }
}
