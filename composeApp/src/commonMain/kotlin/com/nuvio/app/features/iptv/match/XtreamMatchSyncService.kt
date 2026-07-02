package com.nuvio.app.features.iptv.match

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Syncs verified TMDB->stream mappings with the `iptv_tmdb_map` table, mirroring
 * XtreamAccountSyncService's shape. Rows are per user+provider (profiles share them).
 * Pull once per provider per session (LWW merge into the local SQLite mirror); push is
 * a debounced upsert of locally-confirmed rows. Anonymous sessions stay device-local.
 */
internal object XtreamMatchSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("XtreamMatchSync")
    private const val PUSH_DEBOUNCE_MS = 2_000L

    private val pulledProviders = mutableSetOf<String>()
    private val pullMutex = Mutex()
    private var pushJob: Job? = null

    @Serializable
    private data class MapRow(
        @SerialName("provider_key") val providerKey: String,
        @SerialName("content_type") val contentType: String,
        @SerialName("tmdb_id") val tmdbId: Int,
        @SerialName("stream_id") val streamId: Int? = null,
        @SerialName("matched_name") val matchedName: String? = null,
        @SerialName("updated_at_ms") val updatedAtMs: Long,
    )

    private fun authed(): Boolean {
        val s = AuthRepository.state.value
        return s is AuthState.Authenticated && !s.isAnonymous
    }

    /** Merge this provider's remote mappings into the local mirror. No-op after the first call per session. */
    suspend fun pullOnce(provider: String) {
        if (!authed()) return
        pullMutex.withLock { if (!pulledProviders.add(provider)) return }
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("iptv_tmdb_map")
                .select { filter { eq("provider_key", provider) } }
                .decodeList<MapRow>()
            var applied = 0
            for (row in rows) {
                val kind = MatchKind.entries.firstOrNull { it.slug == row.contentType } ?: continue
                val local = XtreamMatchIndex.cachedMapping(provider, kind, row.tmdbId)
                if (local == null || row.updatedAtMs > local.updatedAtMs) {
                    XtreamMatchIndex.putMapping(
                        provider, kind, row.tmdbId, row.streamId, row.matchedName,
                        synced = true, updatedAtMs = row.updatedAtMs,
                    )
                    applied++
                }
            }
            log.i { "pullOnce($provider) — ${rows.size} rows, $applied applied" }
        }.onFailure { e ->
            pullMutex.withLock { pulledProviders.remove(provider) } // retry next resolve
            log.w(e) { "pullOnce($provider) — FAILED" }
        }
    }

    /** Debounced push of not-yet-synced local mappings for this provider. */
    fun triggerPush(provider: String) {
        if (!authed()) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            runCatching {
                val pending = XtreamMatchIndex.unsyncedMappings(provider)
                if (pending.isEmpty()) return@runCatching
                val rows = pending.map {
                    MapRow(
                        providerKey = provider,
                        contentType = it.kind,
                        tmdbId = it.tmdb,
                        streamId = it.sid,
                        matchedName = it.matchedName,
                        updatedAtMs = it.updatedAtMs,
                    )
                }
                SupabaseProvider.client.postgrest.from("iptv_tmdb_map").upsert(rows)
                for (row in pending) XtreamMatchIndex.markSynced(provider, row.kind, row.tmdb)
                log.d { "pushed ${rows.size} mappings for $provider" }
            }.onFailure { e -> log.w(e) { "push($provider) — FAILED" } }
        }
    }

    /** Call on profile switch/logout so the next session re-pulls. */
    fun reset() {
        pushJob?.cancel()
        pushJob = null
        scope.launch { pullMutex.withLock { pulledProviders.clear() } }
    }
}
