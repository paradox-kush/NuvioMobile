package com.nuvio.app.core.sync

import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.features.details.MetaScreenSectionItem
import com.nuvio.app.features.details.MetaScreenSectionKey
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.subtitleColorFromStorage
import com.nuvio.app.features.player.toStorageHexString
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private const val PUSH_DEBOUNCE_MS = 1500L

object ProfileSettingsSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileSettingsSync")
    private val syncMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var isApplyingRemoteBlob: Boolean = false

    @Volatile
    private var isServerSyncInFlight: Boolean = false

    @Volatile
    private var skipNextPushSignature: String? = null

    private var observeJob: Job? = null

    fun startObserving() {
        if (observeJob?.isActive == true) return
        ensureRepositoriesLoaded()
        observeLocalChangesAndPush()
    }

    suspend fun pull(profileId: Int): Boolean {
        ensureRepositoriesLoaded()
        return syncMutex.withLock {
            isServerSyncInFlight = true
            try {
                val localBlob = exportSettingsBlob()
                val localSignature = buildSignature(localBlob)

                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_platform", MOBILE_SYNC_PLATFORM)
                }
                val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profile_settings_blob", params)
                val response = result.decodeList<SettingsBlobResponse>().firstOrNull()
                val remoteJson = response?.settingsJson

                if (remoteJson == null) {
                    log.i { "pull(profileId=$profileId) — no remote settings blob found" }
                    if (localSignature != defaultSignature()) {
                        pushToRemoteLocked(profileId, localBlob)
                    }
                    return@withLock false
                }

                val remoteBlob = runCatching {
                    json.decodeFromJsonElement(ProfileSettingsBlob.serializer(), remoteJson)
                }.getOrElse { error ->
                    log.e(error) { "pull(profileId=$profileId) — failed to decode remote settings blob" }
                    return@withLock false
                }

                val remoteSignature = buildSignature(remoteBlob)
                if (remoteSignature == localSignature) {
                    log.d { "pull(profileId=$profileId) — remote matches local" }
                    return@withLock false
                }

                isApplyingRemoteBlob = true
                try {
                    applyRemoteBlob(remoteBlob)
                    skipNextPushSignature = remoteSignature
                } finally {
                    isApplyingRemoteBlob = false
                }

                log.i { "pull(profileId=$profileId) — applied remote settings blob" }
                true
            } catch (error: Exception) {
                log.e(error) { "pull(profileId=$profileId) — FAILED" }
                false
            } finally {
                isServerSyncInFlight = false
            }
        }
    }

    suspend fun pushCurrentProfileToRemote() {
        ensureRepositoriesLoaded()
        syncMutex.withLock {
            runCatching {
                pushToRemoteLocked(ProfileRepository.activeProfileId, exportSettingsBlob())
            }.onFailure { error ->
                log.e(error) { "pushCurrentProfileToRemote() — FAILED" }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLocalChangesAndPush() {
        val signatureFlows = listOf(
            ThemeSettingsRepository.selectedTheme.map { "theme=${it.name}" },
            ThemeSettingsRepository.amoledEnabled.map { "amoled=$it" },
            PlayerSettingsRepository.uiState.map { buildPlayerSignature(it) },
            TmdbSettingsRepository.uiState.map { buildTmdbSignature(it) },
            MdbListSettingsRepository.uiState.map { buildMdbListSignature(it) },
            MetaScreenSettingsRepository.uiState.map { buildMetaScreenSignature(it) },
            ContinueWatchingPreferencesRepository.uiState.map { buildContinueWatchingSignature(it) },
            TraktCommentsSettings.enabled.map { "trakt_comments=$it" },
            EpisodeReleaseNotificationsRepository.uiState.map { "episode_release_alerts=${it.isEnabled}" },
        )

        observeJob = scope.launch {
            combine(signatureFlows) { parts ->
                parts.joinToString(separator = "||")
            }
                .drop(1)
                .distinctUntilChanged()
                .debounce(PUSH_DEBOUNCE_MS)
                .collect { signature ->
                    val authState = AuthRepository.state.value
                    if (authState !is AuthState.Authenticated || authState.isAnonymous) return@collect
                    if (isApplyingRemoteBlob || isServerSyncInFlight) return@collect
                    if (signature == skipNextPushSignature) {
                        skipNextPushSignature = null
                        return@collect
                    }
                    pushCurrentProfileToRemote()
                }
        }
    }

    private suspend fun pushToRemoteLocked(profileId: Int, blob: ProfileSettingsBlob) {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", MOBILE_SYNC_PLATFORM)
            put("p_settings_json", json.encodeToJsonElement(ProfileSettingsBlob.serializer(), blob))
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_profile_settings_blob", params)
        log.d { "pushToRemoteLocked(profileId=$profileId) — success" }
    }

    private fun exportSettingsBlob(): ProfileSettingsBlob {
        ensureRepositoriesLoaded()

        val themePayload = ThemeSettingsPayload(
            selectedTheme = ThemeSettingsRepository.selectedTheme.value.name,
            amoledEnabled = ThemeSettingsRepository.amoledEnabled.value,
        )

        val playerState = PlayerSettingsRepository.uiState.value
        val playerPayload = PlayerSettingsPayload(
            showLoadingOverlay = playerState.showLoadingOverlay,
            preferredAudioLanguage = playerState.preferredAudioLanguage,
            secondaryPreferredAudioLanguage = playerState.secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = playerState.preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = playerState.secondaryPreferredSubtitleLanguage,
            subtitleStyle = SubtitleStylePayload(
                textColorHex = playerState.subtitleStyle.textColor.toStorageHexString(),
                outlineEnabled = playerState.subtitleStyle.outlineEnabled,
                fontSizeSp = playerState.subtitleStyle.fontSizeSp,
                bottomOffset = playerState.subtitleStyle.bottomOffset,
            ),
            streamReuseLastLinkEnabled = playerState.streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = playerState.streamReuseLastLinkCacheHours,
            decoderPriority = playerState.decoderPriority,
            mapDV7ToHevc = playerState.mapDV7ToHevc,
            tunnelingEnabled = playerState.tunnelingEnabled,
            streamAutoPlayMode = playerState.streamAutoPlayMode.name,
            streamAutoPlaySource = playerState.streamAutoPlaySource.name,
            streamAutoPlaySelectedAddons = playerState.streamAutoPlaySelectedAddons.sorted(),
            streamAutoPlaySelectedPlugins = playerState.streamAutoPlaySelectedPlugins.sorted(),
            streamAutoPlayRegex = playerState.streamAutoPlayRegex,
            streamAutoPlayTimeoutSeconds = playerState.streamAutoPlayTimeoutSeconds,
            skipIntroEnabled = playerState.skipIntroEnabled,
            animeSkipEnabled = playerState.animeSkipEnabled,
            animeSkipClientId = playerState.animeSkipClientId,
            streamAutoPlayNextEpisodeEnabled = playerState.streamAutoPlayNextEpisodeEnabled,
            streamAutoPlayPreferBingeGroup = playerState.streamAutoPlayPreferBingeGroup,
            nextEpisodeThresholdMode = playerState.nextEpisodeThresholdMode.name,
            nextEpisodeThresholdPercent = playerState.nextEpisodeThresholdPercent,
            nextEpisodeThresholdMinutesBeforeEnd = playerState.nextEpisodeThresholdMinutesBeforeEnd,
            useLibass = playerState.useLibass,
            libassRenderType = playerState.libassRenderType,
        )

        val tmdbState = TmdbSettingsRepository.snapshot()
        val tmdbPayload = TmdbSettingsPayload(
            enabled = tmdbState.enabled,
            apiKey = tmdbState.apiKey,
            language = tmdbState.language,
            useTrailers = tmdbState.useTrailers,
            useArtwork = tmdbState.useArtwork,
            useBasicInfo = tmdbState.useBasicInfo,
            useDetails = tmdbState.useDetails,
            useCredits = tmdbState.useCredits,
            useProductions = tmdbState.useProductions,
            useNetworks = tmdbState.useNetworks,
            useEpisodes = tmdbState.useEpisodes,
            useSeasonPosters = tmdbState.useSeasonPosters,
            useMoreLikeThis = tmdbState.useMoreLikeThis,
            useCollections = tmdbState.useCollections,
        )

        val mdbListState = MdbListSettingsRepository.snapshot()
        val mdbListPayload = MdbListSettingsPayload(
            enabled = mdbListState.enabled,
            apiKey = mdbListState.apiKey,
            useImdb = mdbListState.useImdb,
            useTmdb = mdbListState.useTmdb,
            useTomatoes = mdbListState.useTomatoes,
            useMetacritic = mdbListState.useMetacritic,
            useTrakt = mdbListState.useTrakt,
            useLetterboxd = mdbListState.useLetterboxd,
            useAudience = mdbListState.useAudience,
        )

        val metaState = MetaScreenSettingsRepository.uiState.value
        val metaPayload = MetaScreenSettingsPayload(
            items = metaState.items
                .sortedBy { it.order }
                .map { item ->
                    MetaScreenItemPayload(
                        key = item.key.name,
                        enabled = item.enabled,
                        order = item.order,
                        tabGroup = item.tabGroup,
                    )
                },
            cinematicBackground = metaState.cinematicBackground,
            tabLayout = metaState.tabLayout,
        )

        val continueWatchingState = ContinueWatchingPreferencesRepository.uiState.value
        val continueWatchingPayload = ContinueWatchingSettingsPayload(
            isVisible = continueWatchingState.isVisible,
            style = continueWatchingState.style.name,
            upNextFromFurthestEpisode = continueWatchingState.upNextFromFurthestEpisode,
            dismissedNextUpKeys = continueWatchingState.dismissedNextUpKeys.sorted(),
        )

        val traktCommentsPayload = TraktCommentsPayload(
            enabled = TraktCommentsSettings.enabled.value,
        )

        val notificationsPayload = EpisodeReleaseNotificationsPayload(
            alertsEnabled = EpisodeReleaseNotificationsRepository.uiState.value.isEnabled,
        )

        return ProfileSettingsBlob(
            features = ProfileSettingsFeatures(
                theme = themePayload,
                player = playerPayload,
                tmdb = tmdbPayload,
                mdbList = mdbListPayload,
                metaScreen = metaPayload,
                continueWatching = continueWatchingPayload,
                traktComments = traktCommentsPayload,
                episodeReleaseNotifications = notificationsPayload,
            ),
        )
    }

    private fun applyRemoteBlob(blob: ProfileSettingsBlob) {
        val theme = blob.features.theme
        ThemeSettingsRepository.setTheme(
            runCatching { AppTheme.valueOf(theme.selectedTheme) }.getOrDefault(AppTheme.WHITE),
        )
        ThemeSettingsRepository.setAmoled(theme.amoledEnabled)

        val player = blob.features.player
        PlayerSettingsRepository.setShowLoadingOverlay(player.showLoadingOverlay)
        PlayerSettingsRepository.setPreferredAudioLanguage(player.preferredAudioLanguage)
        PlayerSettingsRepository.setSecondaryPreferredAudioLanguage(player.secondaryPreferredAudioLanguage)
        PlayerSettingsRepository.setPreferredSubtitleLanguage(player.preferredSubtitleLanguage)
        PlayerSettingsRepository.setSecondaryPreferredSubtitleLanguage(player.secondaryPreferredSubtitleLanguage)
        PlayerSettingsRepository.setSubtitleStyle(
            SubtitleStyleState(
                textColor = subtitleColorFromStorage(player.subtitleStyle.textColorHex) ?: Color.White,
                outlineEnabled = player.subtitleStyle.outlineEnabled,
                fontSizeSp = player.subtitleStyle.fontSizeSp,
                bottomOffset = player.subtitleStyle.bottomOffset,
            ),
        )
        PlayerSettingsRepository.setStreamReuseLastLinkEnabled(player.streamReuseLastLinkEnabled)
        PlayerSettingsRepository.setStreamReuseLastLinkCacheHours(player.streamReuseLastLinkCacheHours)
        PlayerSettingsRepository.setDecoderPriority(player.decoderPriority)
        PlayerSettingsRepository.setMapDV7ToHevc(player.mapDV7ToHevc)
        PlayerSettingsRepository.setTunnelingEnabled(player.tunnelingEnabled)
        PlayerSettingsRepository.setStreamAutoPlayMode(
            runCatching { StreamAutoPlayMode.valueOf(player.streamAutoPlayMode) }.getOrDefault(StreamAutoPlayMode.MANUAL),
        )
        PlayerSettingsRepository.setStreamAutoPlaySource(
            runCatching { StreamAutoPlaySource.valueOf(player.streamAutoPlaySource) }
                .getOrDefault(StreamAutoPlaySource.ALL_SOURCES),
        )
        PlayerSettingsRepository.setStreamAutoPlaySelectedAddons(player.streamAutoPlaySelectedAddons.toSet())
        PlayerSettingsRepository.setStreamAutoPlaySelectedPlugins(player.streamAutoPlaySelectedPlugins.toSet())
        PlayerSettingsRepository.setStreamAutoPlayRegex(player.streamAutoPlayRegex)
        PlayerSettingsRepository.setStreamAutoPlayTimeoutSeconds(player.streamAutoPlayTimeoutSeconds)
        PlayerSettingsRepository.setSkipIntroEnabled(player.skipIntroEnabled)
        PlayerSettingsRepository.setAnimeSkipEnabled(player.animeSkipEnabled)
        PlayerSettingsRepository.setAnimeSkipClientId(player.animeSkipClientId)
        PlayerSettingsRepository.setStreamAutoPlayNextEpisodeEnabled(player.streamAutoPlayNextEpisodeEnabled)
        PlayerSettingsRepository.setStreamAutoPlayPreferBingeGroup(player.streamAutoPlayPreferBingeGroup)
        PlayerSettingsRepository.setNextEpisodeThresholdMode(
            runCatching { NextEpisodeThresholdMode.valueOf(player.nextEpisodeThresholdMode) }
                .getOrDefault(NextEpisodeThresholdMode.PERCENTAGE),
        )
        PlayerSettingsRepository.setNextEpisodeThresholdPercent(player.nextEpisodeThresholdPercent)
        PlayerSettingsRepository.setNextEpisodeThresholdMinutesBeforeEnd(player.nextEpisodeThresholdMinutesBeforeEnd)
        PlayerSettingsRepository.setUseLibass(player.useLibass)
        PlayerSettingsRepository.setLibassRenderType(player.libassRenderType)

        val tmdb = blob.features.tmdb
        TmdbSettingsRepository.setApiKey(tmdb.apiKey)
        TmdbSettingsRepository.setLanguage(tmdb.language)
        TmdbSettingsRepository.setUseTrailers(tmdb.useTrailers)
        TmdbSettingsRepository.setUseArtwork(tmdb.useArtwork)
        TmdbSettingsRepository.setUseBasicInfo(tmdb.useBasicInfo)
        TmdbSettingsRepository.setUseDetails(tmdb.useDetails)
        TmdbSettingsRepository.setUseCredits(tmdb.useCredits)
        TmdbSettingsRepository.setUseProductions(tmdb.useProductions)
        TmdbSettingsRepository.setUseNetworks(tmdb.useNetworks)
        TmdbSettingsRepository.setUseEpisodes(tmdb.useEpisodes)
        TmdbSettingsRepository.setUseSeasonPosters(tmdb.useSeasonPosters)
        TmdbSettingsRepository.setUseMoreLikeThis(tmdb.useMoreLikeThis)
        TmdbSettingsRepository.setUseCollections(tmdb.useCollections)
        TmdbSettingsRepository.setEnabled(tmdb.enabled)

        val mdbList = blob.features.mdbList
        MdbListSettingsRepository.setApiKey(mdbList.apiKey)
        MdbListSettingsRepository.setProviderEnabled("imdb", mdbList.useImdb)
        MdbListSettingsRepository.setProviderEnabled("tmdb", mdbList.useTmdb)
        MdbListSettingsRepository.setProviderEnabled("tomatoes", mdbList.useTomatoes)
        MdbListSettingsRepository.setProviderEnabled("metacritic", mdbList.useMetacritic)
        MdbListSettingsRepository.setProviderEnabled("trakt", mdbList.useTrakt)
        MdbListSettingsRepository.setProviderEnabled("letterboxd", mdbList.useLetterboxd)
        MdbListSettingsRepository.setProviderEnabled("audience", mdbList.useAudience)
        MdbListSettingsRepository.setEnabled(mdbList.enabled)

        val metaScreen = blob.features.metaScreen
        MetaScreenSettingsRepository.applyFromSync(
            items = metaScreen.items.map { item ->
                MetaScreenSectionItem(
                    key = runCatching { MetaScreenSectionKey.valueOf(item.key) }
                        .getOrDefault(MetaScreenSectionKey.ACTIONS),
                    title = "",
                    description = "",
                    enabled = item.enabled,
                    order = item.order,
                    tabGroup = item.tabGroup,
                )
            },
            cinematicBackground = metaScreen.cinematicBackground,
            tabLayout = metaScreen.tabLayout,
        )

        val continueWatching = blob.features.continueWatching
        ContinueWatchingPreferencesRepository.applyFromSync(
            isVisible = continueWatching.isVisible,
            style = runCatching { ContinueWatchingSectionStyle.valueOf(continueWatching.style) }
                .getOrDefault(ContinueWatchingSectionStyle.Wide),
            upNextFromFurthestEpisode = continueWatching.upNextFromFurthestEpisode,
            dismissedNextUpKeys = continueWatching.dismissedNextUpKeys.toSet(),
        )

        TraktCommentsSettings.setEnabled(blob.features.traktComments.enabled)
        EpisodeReleaseNotificationsRepository.applyFromSyncEnabled(blob.features.episodeReleaseNotifications.alertsEnabled)
    }

    private fun ensureRepositoriesLoaded() {
        ThemeSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        TraktCommentsSettings.ensureLoaded()
        EpisodeReleaseNotificationsRepository.ensureLoaded()
    }

    private fun buildSignature(blob: ProfileSettingsBlob): String =
        json.encodeToString(ProfileSettingsBlob.serializer(), blob)

    private fun defaultSignature(): String =
        buildSignature(ProfileSettingsBlob())

    private fun buildPlayerSignature(state: com.nuvio.app.features.player.PlayerSettingsUiState): String =
        json.encodeToString(PlayerSettingsPayload.serializer(), PlayerSettingsPayload(
            showLoadingOverlay = state.showLoadingOverlay,
            preferredAudioLanguage = state.preferredAudioLanguage,
            secondaryPreferredAudioLanguage = state.secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = state.preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = state.secondaryPreferredSubtitleLanguage,
            subtitleStyle = SubtitleStylePayload(
                textColorHex = state.subtitleStyle.textColor.toStorageHexString(),
                outlineEnabled = state.subtitleStyle.outlineEnabled,
                fontSizeSp = state.subtitleStyle.fontSizeSp,
                bottomOffset = state.subtitleStyle.bottomOffset,
            ),
            streamReuseLastLinkEnabled = state.streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = state.streamReuseLastLinkCacheHours,
            decoderPriority = state.decoderPriority,
            mapDV7ToHevc = state.mapDV7ToHevc,
            tunnelingEnabled = state.tunnelingEnabled,
            streamAutoPlayMode = state.streamAutoPlayMode.name,
            streamAutoPlaySource = state.streamAutoPlaySource.name,
            streamAutoPlaySelectedAddons = state.streamAutoPlaySelectedAddons.sorted(),
            streamAutoPlaySelectedPlugins = state.streamAutoPlaySelectedPlugins.sorted(),
            streamAutoPlayRegex = state.streamAutoPlayRegex,
            streamAutoPlayTimeoutSeconds = state.streamAutoPlayTimeoutSeconds,
            skipIntroEnabled = state.skipIntroEnabled,
            animeSkipEnabled = state.animeSkipEnabled,
            animeSkipClientId = state.animeSkipClientId,
            streamAutoPlayNextEpisodeEnabled = state.streamAutoPlayNextEpisodeEnabled,
            streamAutoPlayPreferBingeGroup = state.streamAutoPlayPreferBingeGroup,
            nextEpisodeThresholdMode = state.nextEpisodeThresholdMode.name,
            nextEpisodeThresholdPercent = state.nextEpisodeThresholdPercent,
            nextEpisodeThresholdMinutesBeforeEnd = state.nextEpisodeThresholdMinutesBeforeEnd,
            useLibass = state.useLibass,
            libassRenderType = state.libassRenderType,
        ))

    private fun buildTmdbSignature(state: com.nuvio.app.features.tmdb.TmdbSettings): String =
        json.encodeToString(TmdbSettingsPayload.serializer(), TmdbSettingsPayload(
            enabled = state.enabled,
            apiKey = state.apiKey,
            language = state.language,
            useTrailers = state.useTrailers,
            useArtwork = state.useArtwork,
            useBasicInfo = state.useBasicInfo,
            useDetails = state.useDetails,
            useCredits = state.useCredits,
            useProductions = state.useProductions,
            useNetworks = state.useNetworks,
            useEpisodes = state.useEpisodes,
            useSeasonPosters = state.useSeasonPosters,
            useMoreLikeThis = state.useMoreLikeThis,
            useCollections = state.useCollections,
        ))

    private fun buildMdbListSignature(state: com.nuvio.app.features.mdblist.MdbListSettings): String =
        json.encodeToString(MdbListSettingsPayload.serializer(), MdbListSettingsPayload(
            enabled = state.enabled,
            apiKey = state.apiKey,
            useImdb = state.useImdb,
            useTmdb = state.useTmdb,
            useTomatoes = state.useTomatoes,
            useMetacritic = state.useMetacritic,
            useTrakt = state.useTrakt,
            useLetterboxd = state.useLetterboxd,
            useAudience = state.useAudience,
        ))

    private fun buildMetaScreenSignature(state: com.nuvio.app.features.details.MetaScreenSettingsUiState): String =
        json.encodeToString(MetaScreenSettingsPayload.serializer(), MetaScreenSettingsPayload(
            items = state.items.sortedBy { it.order }.map { item ->
                MetaScreenItemPayload(
                    key = item.key.name,
                    enabled = item.enabled,
                    order = item.order,
                    tabGroup = item.tabGroup,
                )
            },
            cinematicBackground = state.cinematicBackground,
            tabLayout = state.tabLayout,
        ))

    private fun buildContinueWatchingSignature(state: com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState): String =
        json.encodeToString(ContinueWatchingSettingsPayload.serializer(), ContinueWatchingSettingsPayload(
            isVisible = state.isVisible,
            style = state.style.name,
            upNextFromFurthestEpisode = state.upNextFromFurthestEpisode,
            dismissedNextUpKeys = state.dismissedNextUpKeys.sorted(),
        ))
}

@Serializable
private data class SettingsBlobResponse(
    @SerialName("profile_id") val profileId: Int = 0,
    @SerialName("settings_json") val settingsJson: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class ProfileSettingsBlob(
    val version: Int = 1,
    val features: ProfileSettingsFeatures = ProfileSettingsFeatures(),
)

@Serializable
private data class ProfileSettingsFeatures(
    val theme: ThemeSettingsPayload = ThemeSettingsPayload(),
    val player: PlayerSettingsPayload = PlayerSettingsPayload(),
    val tmdb: TmdbSettingsPayload = TmdbSettingsPayload(),
    @SerialName("mdb_list") val mdbList: MdbListSettingsPayload = MdbListSettingsPayload(),
    @SerialName("meta_screen") val metaScreen: MetaScreenSettingsPayload = MetaScreenSettingsPayload(),
    @SerialName("continue_watching") val continueWatching: ContinueWatchingSettingsPayload = ContinueWatchingSettingsPayload(),
    @SerialName("trakt_comments") val traktComments: TraktCommentsPayload = TraktCommentsPayload(),
    @SerialName("episode_release_notifications") val episodeReleaseNotifications: EpisodeReleaseNotificationsPayload = EpisodeReleaseNotificationsPayload(),
)

@Serializable
private data class ThemeSettingsPayload(
    val selectedTheme: String = AppTheme.WHITE.name,
    val amoledEnabled: Boolean = false,
)

@Serializable
private data class SubtitleStylePayload(
    val textColorHex: String = Color.White.toStorageHexString(),
    val outlineEnabled: Boolean = false,
    val fontSizeSp: Int = 18,
    val bottomOffset: Int = 20,
)

@Serializable
private data class PlayerSettingsPayload(
    val showLoadingOverlay: Boolean = true,
    val preferredAudioLanguage: String = "device",
    val secondaryPreferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String = "none",
    val secondaryPreferredSubtitleLanguage: String? = null,
    val subtitleStyle: SubtitleStylePayload = SubtitleStylePayload(),
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val decoderPriority: Int = 1,
    val mapDV7ToHevc: Boolean = false,
    val tunnelingEnabled: Boolean = false,
    val streamAutoPlayMode: String = StreamAutoPlayMode.MANUAL.name,
    val streamAutoPlaySource: String = StreamAutoPlaySource.ALL_SOURCES.name,
    val streamAutoPlaySelectedAddons: List<String> = emptyList(),
    val streamAutoPlaySelectedPlugins: List<String> = emptyList(),
    val streamAutoPlayRegex: String = "",
    val streamAutoPlayTimeoutSeconds: Int = 3,
    val skipIntroEnabled: Boolean = true,
    val animeSkipEnabled: Boolean = false,
    val animeSkipClientId: String = "",
    val streamAutoPlayNextEpisodeEnabled: Boolean = false,
    val streamAutoPlayPreferBingeGroup: Boolean = true,
    val nextEpisodeThresholdMode: String = NextEpisodeThresholdMode.PERCENTAGE.name,
    val nextEpisodeThresholdPercent: Float = 99f,
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f,
    val useLibass: Boolean = false,
    val libassRenderType: String = "CUES",
)

@Serializable
private data class TmdbSettingsPayload(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val language: String = "en",
    val useTrailers: Boolean = true,
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useSeasonPosters: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useCollections: Boolean = true,
)

@Serializable
private data class MdbListSettingsPayload(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val useImdb: Boolean = true,
    val useTmdb: Boolean = true,
    val useTomatoes: Boolean = true,
    val useMetacritic: Boolean = true,
    val useTrakt: Boolean = true,
    val useLetterboxd: Boolean = true,
    val useAudience: Boolean = true,
)

@Serializable
private data class MetaScreenItemPayload(
    val key: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    val tabGroup: Int? = null,
)

@Serializable
private data class MetaScreenSettingsPayload(
    val items: List<MetaScreenItemPayload> = emptyList(),
    val cinematicBackground: Boolean = false,
    val tabLayout: Boolean = false,
)

@Serializable
private data class ContinueWatchingSettingsPayload(
    val isVisible: Boolean = true,
    val style: String = ContinueWatchingSectionStyle.Wide.name,
    val upNextFromFurthestEpisode: Boolean = true,
    val dismissedNextUpKeys: List<String> = emptyList(),
)

@Serializable
private data class TraktCommentsPayload(
    val enabled: Boolean = true,
)

@Serializable
private data class EpisodeReleaseNotificationsPayload(
    val alertsEnabled: Boolean = false,
)
