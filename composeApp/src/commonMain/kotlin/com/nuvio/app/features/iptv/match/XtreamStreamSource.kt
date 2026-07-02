package com.nuvio.app.features.iptv.match

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamClient
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.tmdb.TmdbService

/**
 * Turns a TMDB movie/episode into playable Xtream [StreamItem]s for one account —
 * the bridge that lets IPTV VOD show up next to addon/debrid streams on TMDB-driven
 * detail screens. Returns empty (never throws) when the account doesn't carry the title.
 */
internal object XtreamStreamSource {

    fun groupId(acc: XtreamAccount): String = "xtream-match:${acc.id}"

    suspend fun streamsFor(acc: XtreamAccount, type: String, videoId: String, season: Int?, episode: Int?): List<StreamItem> {
        val kind = when (TmdbService.normalizeMediaType(type)) {
            "movie" -> MatchKind.MOVIE
            "tv" -> MatchKind.SERIES
            else -> return emptyList()
        }
        val tmdbId = TmdbService.ensureTmdbId(videoId, type)?.toIntOrNull() ?: return emptyList()
        val titles = TmdbService.titleBundle(tmdbId, type) ?: return emptyList()
        val match = XtreamTmdbResolver.resolve(acc, kind, tmdbId, titles) ?: return emptyList()

        return when (kind) {
            MatchKind.MOVIE -> {
                // id-tagged catalogs often carry several editions (4K/HD/language) of the same
                // film — surface them all as separate streams
                val editions = XtreamMatchIndex.byTmdb(acc.id, kind, tmdbId).ifEmpty { listOf(match.item) }
                editions.map { item ->
                    StreamItem(
                        name = "Direct",
                        title = item.name,
                        url = XtreamClient.movieStreamUrl(acc, item.sid, item.ext ?: "mp4"),
                        addonName = acc.name,
                        addonId = groupId(acc),
                    )
                }
            }
            MatchKind.SERIES -> {
                val s = season ?: return emptyList()
                val e = episode ?: return emptyList()
                val detail = XtreamClient.seriesInfo(acc, match.item.sid).getOrNull() ?: return emptyList()
                detail.episodes.filter { it.season == s && it.episodeNum == e }.map { ep ->
                    StreamItem(
                        name = "Direct",
                        title = "${detail.name ?: match.item.name} · ${ep.title}",
                        url = XtreamClient.episodeStreamUrl(acc, ep.episodeId, ep.containerExtension ?: "mp4"),
                        addonName = acc.name,
                        addonId = groupId(acc),
                    )
                }
            }
        }
    }
}
