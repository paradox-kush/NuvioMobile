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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Syncs Xtream IPTV accounts (playlists) per profile to Supabase, mirroring CollectionSyncService.
 * Push = full-replace RPC on change (debounced); pull = direct RLS-scoped select on login. Only runs
 * for a real (non-anonymous) session — the local "Anonymous" state keeps playlists device-local.
 */
object XtreamAccountSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("XtreamAccountSyncService")
    private const val PUSH_DEBOUNCE_MS = 600L

    @Volatile
    var isSyncingFromRemote: Boolean = false
    private var pushJob: Job? = null

    @Serializable
    private data class Row(
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
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_accounts", buildJsonArray {
                    accounts.forEachIndexed { index, acc ->
                        addJsonObject {
                            put("base_url", acc.baseUrl)
                            put("username", acc.username)
                            put("password", acc.password)
                            put("name", acc.name)
                            put("enabled", acc.enabled)
                            put("sort_order", index)
                        }
                    }
                })
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_xtream_accounts", params)
            log.d { "pushToRemote — ${accounts.size} accounts" }
        }.onFailure { e -> log.e(e) { "pushToRemote — FAILED" } }
    }

    /** Pull this profile's playlists on login. Empty remote + non-empty local => migrate local up. */
    suspend fun pullFromServer(profileId: Int) {
        if (!authed() || ProfileRepository.activeProfileId != profileId) return
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("xtream_accounts")
                .select {
                    filter { eq("profile_id", profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<Row>()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching
            if (rows.isEmpty()) {
                if (XtreamRepository.uiState.value.accounts.isNotEmpty()) {
                    log.i { "pullFromServer — remote empty, migrating local playlists up" }
                    pushToRemote(profileId)
                }
                return@runCatching
            }
            val accounts = rows.map {
                XtreamAccount(
                    id = "${it.baseUrl}|${it.username}",
                    name = it.name ?: it.baseUrl,
                    baseUrl = it.baseUrl,
                    username = it.username,
                    password = it.password,
                    enabled = it.enabled,
                )
            }
            isSyncingFromRemote = true
            XtreamRepository.applyFromRemote(profileId, accounts)
            isSyncingFromRemote = false
            log.i { "pullFromServer — applied ${accounts.size} accounts" }
        }.onFailure { e ->
            isSyncingFromRemote = false
            log.e(e) { "pullFromServer — FAILED" }
        }
    }
}
