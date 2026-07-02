package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Syncs IPTV playlists per profile to Supabase, mirroring CollectionSyncService.
 * Push = full-replace RPC on change (debounced); pull = direct RLS-scoped select on login. Only runs
 * for a real (non-anonymous) session — the local "Anonymous" state keeps playlists device-local.
 *
 * Playlist-manager P1: reads/writes the new `iptv_playlists` table. Every push is scoped to
 * source_type 'xtream' (p_source_types) so this client can never delete a newer client's
 * m3u/stalker rows. When the table holds no usable xtream rows, the pull falls back to the
 * legacy `xtream_accounts` rows (written by older app versions), migrates them up (guarded by
 * p_only_if_empty against a two-device first-login race), then clears the legacy rows — a
 * one-shot migration, so stale legacy rows can't resurrect playlists deleted later.
 */
object XtreamAccountSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("XtreamAccountSyncService")
    private const val PUSH_DEBOUNCE_MS = 600L

    @Volatile
    var isSyncingFromRemote: Boolean = false
    private var pushJob: Job? = null

    /** Legacy `xtream_accounts` row — read-only fallback for rows synced by older app versions. */
    @Serializable
    private data class LegacyRow(
        @SerialName("base_url") val baseUrl: String,
        val username: String,
        val password: String,
        val name: String? = null,
        val enabled: Boolean = true,
        @SerialName("sort_order") val sortOrder: Int = 0,
    )

    private fun authed(): Boolean {
        val s = AuthRepository.state.value
        return s is AuthState.Authenticated && !s.isAnonymous
    }

    /** Debounced push after a local account change (called from XtreamRepository.persist()). */
    fun triggerPush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            val profileId = ProfileRepository.activeProfileId
            delay(PUSH_DEBOUNCE_MS)
            if (ProfileRepository.activeProfileId != profileId) return@launch
            if (isSyncingFromRemote || !authed()) return@launch
            pushToRemote(profileId)
        }
    }

    private suspend fun pushToRemote(profileId: Int) {
        runCatching {
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val accounts = XtreamRepository.uiState.value.accounts
            SupabaseProvider.client.postgrest
                .rpc("sync_push_iptv_playlists", playlistPushParams(profileId, accounts))
            log.d { "pushToRemote — ${accounts.size} playlists" }
        }.onFailure { e -> log.e(e) { "pushToRemote — FAILED" } }
    }

    /**
     * One-shot legacy migration: push the just-applied legacy playlists up with p_only_if_empty
     * (two devices racing on first login — the loser no-ops instead of clobbering the winner),
     * then clear the legacy rows via the old RPC. Without the clear, deleting every playlist
     * later (empty new table + stale legacy rows) would resurrect them on the next login pull.
     * The clear only runs after a successful new-table push, so a failed push retries whole.
     */
    private suspend fun migrateLegacyUp(profileId: Int) {
        runCatching {
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val accounts = XtreamRepository.uiState.value.accounts
            SupabaseProvider.client.postgrest
                .rpc("sync_push_iptv_playlists", playlistPushParams(profileId, accounts, onlyIfEmpty = true))
            SupabaseProvider.client.postgrest.rpc("sync_push_xtream_accounts", buildJsonObject {
                put("p_profile_id", profileId)
                put("p_accounts", JsonArray(emptyList()))
            })
            log.i { "migrateLegacyUp — ${accounts.size} playlists migrated, legacy rows cleared" }
        }.onFailure { e -> log.e(e) { "migrateLegacyUp — FAILED" } }
    }

    /**
     * Pull this profile's playlists on login. New table first; no usable xtream rows there
     * falls back to the legacy rows (then migrates them up, one-shot); both empty + non-empty
     * local => migrate local up.
     */
    suspend fun pullFromServer(profileId: Int) {
        if (!authed() || ProfileRepository.activeProfileId != profileId) return
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("iptv_playlists")
                .select {
                    filter { eq("profile_id", profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<PlaylistRow>()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            val playlists = usableRemoteAccounts(rows)
            if (playlists.isNotEmpty()) {
                apply(profileId, playlists)
                return@runCatching
            }
            // Zero usable xtream rows (empty table, or only a newer client's m3u/stalker rows)
            // = empty remote for this client — never apply an empty list over local state.

            val legacy = SupabaseProvider.client.postgrest
                .from("xtream_accounts")
                .select {
                    filter { eq("profile_id", profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<LegacyRow>()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            if (legacy.isNotEmpty()) {
                log.i { "pullFromServer — migrating ${legacy.size} legacy xtream_accounts rows up" }
                apply(profileId, legacy.map { it.toAccount() })
                migrateLegacyUp(profileId)
            } else if (XtreamRepository.uiState.value.accounts.isNotEmpty()) {
                log.i { "pullFromServer — remote empty, migrating local playlists up" }
                pushToRemote(profileId)
            }
        }.onFailure { e ->
            isSyncingFromRemote = false
            log.e(e) { "pullFromServer — FAILED" }
        }
    }

    private fun apply(profileId: Int, accounts: List<XtreamAccount>) {
        isSyncingFromRemote = true
        XtreamRepository.applyFromRemote(profileId, accounts)
        isSyncingFromRemote = false
        log.i { "pullFromServer — applied ${accounts.size} playlists" }
    }

    private fun LegacyRow.toAccount(): XtreamAccount = XtreamAccount(
        id = "$baseUrl|$username",
        name = name ?: baseUrl,
        baseUrl = baseUrl,
        username = username,
        password = password,
        enabled = enabled,
    )
}

/** `iptv_playlists` row (only the columns this client uses; the rest ignore-unknown away). internal for tests. */
@Serializable
internal data class PlaylistRow(
    @SerialName("source_type") val sourceType: String = "xtream",
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("base_url") val baseUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("epg_url") val epgUrl: String? = null,
    @SerialName("dns_provider") val dnsProvider: String = "system",
    @SerialName("auto_refresh_hours") val autoRefreshHours: Int = 0,
    @SerialName("content_types") val contentTypes: List<String> = ALL_CONTENT_TYPES.toList(),
    // jsonb; decoded leniently by hand — a malformed shape must not sink the whole pull
    @SerialName("category_selections") val categorySelections: JsonElement? = null,
)

/** P1 clients only understand xtream rows; other source types (P2/P4) are skipped, not synced down. */
internal fun PlaylistRow.toAccount(): XtreamAccount? {
    if (sourceType != "xtream") return null
    val base = baseUrl ?: return null
    val user = username ?: return null
    return XtreamAccount(
        id = "$base|$user",
        name = name ?: base,
        baseUrl = base,
        username = user,
        password = password ?: "",
        enabled = enabled,
        sourceType = sourceType,
        epgUrl = epgUrl,
        dnsProvider = dnsProvider,
        autoRefreshHours = autoRefreshHours,
        contentTypes = contentTypes.toSet(),
        categorySelections = parseCategorySelections(categorySelections),
    )
}

/**
 * The pull's emptiness decision happens AFTER this filter: rows of only foreign source types
 * (a newer client's m3u/stalker playlists) are an empty remote for this P1 client — they must
 * never be applied as an empty list over local state. internal for tests.
 */
internal fun usableRemoteAccounts(rows: List<PlaylistRow>): List<XtreamAccount> =
    rows.mapNotNull { it.toAccount() }

/**
 * RPC params for `sync_push_iptv_playlists`. Every push is scoped to p_source_types ['xtream']
 * so the full-replace can never delete a newer client's m3u/stalker rows; p_only_if_empty is
 * only set on the legacy-migration push (omitted otherwise). internal for tests.
 */
internal fun playlistPushParams(
    profileId: Int,
    accounts: List<XtreamAccount>,
    onlyIfEmpty: Boolean = false,
): JsonObject = buildJsonObject {
    put("p_profile_id", profileId)
    put("p_playlists", playlistPushPayload(accounts))
    if (onlyIfEmpty) put("p_only_if_empty", true)
    put("p_source_types", JsonArray(listOf(JsonPrimitive("xtream"))))
}

/**
 * Per-row JSON for `sync_push_iptv_playlists` — field names match the iptv_playlists migration
 * exactly. Omissions are contract: blank name, null epg_url, and all-null category_selections
 * are left out so the RPC's coalesce defaults apply. internal for tests.
 */
internal fun playlistPushPayload(accounts: List<XtreamAccount>): JsonArray = buildJsonArray {
    accounts.forEachIndexed { index, acc ->
        addJsonObject {
            put("source_type", acc.sourceType)
            acc.name.takeIf { it.isNotBlank() }?.let { put("name", it) }
            put("enabled", acc.enabled)
            put("sort_order", index)
            put("base_url", acc.baseUrl)
            put("username", acc.username)
            put("password", acc.password)
            acc.epgUrl?.let { put("epg_url", it) }
            put("dns_provider", acc.dnsProvider)
            put("auto_refresh_hours", acc.autoRefreshHours)
            put("content_types", JsonArray(acc.contentTypes.map { JsonPrimitive(it) }))
            val cs = acc.categorySelections
            if (!cs.allNull) {
                put("category_selections", buildJsonObject {
                    cs.live?.let { put("live", JsonArray(it.map(::JsonPrimitive))) }
                    cs.movies?.let { put("movies", JsonArray(it.map(::JsonPrimitive))) }
                    cs.series?.let { put("series", JsonArray(it.map(::JsonPrimitive))) }
                })
            }
        }
    }
}

/** Lenient decode of the jsonb category_selections column: any malformed shape -> all-null (= all). */
internal fun parseCategorySelections(element: JsonElement?): CategorySelections {
    val obj = element as? JsonObject ?: return CategorySelections()
    fun list(key: String): List<String>? =
        (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    return CategorySelections(live = list("live"), movies = list("movies"), series = list("series"))
}
