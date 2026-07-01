package com.nuvio.app.features.iptv

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Xtream has no search API, so we pull each enabled account's full live/series/VOD lists once,
 * cache them, and substring-filter in memory. Live + series are fetched on the first search
 * (smaller); the VOD list (often 50k+) loads in the BACKGROUND so channels/series results show
 * immediately and movies fill in on a later keystroke — matching NuvioTV's index.
 *
 * ponytail: whole lists cached in RAM; fine for a handful of accounts. Cap/stream if it grows.
 */
object XtreamSearchIndex {
    private data class AccountLists(
        val channels: List<XtreamChannel> = emptyList(),
        val series: List<XtreamSeriesItem> = emptyList(),
        val movies: List<XtreamMovie> = emptyList(),
    )

    private val cache = mutableMapOf<String, AccountLists>()
    private val coreJobs = mutableMapOf<String, Deferred<*>>()
    private val mutex = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val PER_TYPE_CAP = 30

    suspend fun search(query: String): List<HomeCatalogSection> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        XtreamRepository.ensureLoaded()
        val accounts = XtreamRepository.uiState.value.accounts.filter { it.enabled }
        if (accounts.isEmpty()) return emptyList()

        val channels = mutableListOf<MetaPreview>()
        val series = mutableListOf<MetaPreview>()
        val movies = mutableListOf<MetaPreview>()
        for (account in accounts) {
            val lists = ensureLists(account)
            lists.channels.asSequence().filter { it.name.contains(q, ignoreCase = true) }.take(PER_TYPE_CAP).forEach {
                XtreamItemRegistry.registerChannel(account.id, it); channels += it.toMetaPreview(account.id)
            }
            lists.series.asSequence().filter { it.name.contains(q, ignoreCase = true) }.take(PER_TYPE_CAP).forEach {
                XtreamItemRegistry.registerSeries(account.id, it); series += it.toMetaPreview(account.id)
            }
            lists.movies.asSequence().filter { it.name.contains(q, ignoreCase = true) }.take(PER_TYPE_CAP).forEach {
                XtreamItemRegistry.registerMovie(account.id, it); movies += it.toMetaPreview(account.id)
            }
        }
        return listOfNotNull(
            section("xtream_channels", "IPTV Channels", "tv", channels),
            section("xtream_movies", "IPTV Movies", "movie", movies),
            section("xtream_series", "IPTV Series", "series", series),
        )
    }

    private fun section(key: String, title: String, type: String, items: List<MetaPreview>): HomeCatalogSection? {
        if (items.isEmpty()) return null
        return HomeCatalogSection(
            key = key,
            title = title,
            subtitle = "IPTV",
            addonName = "IPTV",
            target = CatalogTarget.Library(contentType = type, sectionType = "xtream"),
            items = items,
            availableItemCount = items.size,
            hasMore = false,
        )
    }

    /**
     * Fetch channels + series (in parallel) once per account, in [bgScope] so a keystroke that
     * cancels the search job can't abort the fetch — otherwise fast typing starves the huge live/
     * series lists while VOD (already backgrounded) fills in, which looks like "only movies work".
     */
    private suspend fun ensureLists(account: XtreamAccount): AccountLists {
        val job = mutex.withLock {
            coreJobs.getOrPut(account.id) {
                bgScope.async {
                    val channelsD = async { XtreamClient.liveChannels(account) }
                    val seriesD = async { XtreamClient.series(account) }
                    val channelsResult = channelsD.await()
                    val seriesResult = seriesD.await()
                    mutex.withLock {
                        cache[account.id] = AccountLists(
                            channels = channelsResult.getOrDefault(emptyList()),
                            series = seriesResult.getOrDefault(emptyList()),
                            movies = cache[account.id]?.movies ?: emptyList(),
                        )
                    }
                    bgScope.launch {
                        val movies = XtreamClient.vodMovies(account).getOrDefault(emptyList())
                        mutex.withLock {
                            cache[account.id] = (cache[account.id] ?: AccountLists()).copy(movies = movies)
                        }
                    }
                }
            }
        }
        job.await()
        return cache[account.id] ?: AccountLists()
    }

    fun resetForProfile() {
        cache.clear()
        coreJobs.clear()
    }
}
