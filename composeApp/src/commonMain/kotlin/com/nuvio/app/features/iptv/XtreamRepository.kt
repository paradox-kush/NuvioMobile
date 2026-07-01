package com.nuvio.app.features.iptv

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class XtreamUiState(
    val accounts: List<XtreamAccount> = emptyList(),
    val isValidating: Boolean = false,
    val error: String? = null
)

/**
 * Xtream IPTV accounts, persisted locally per profile. Object-singleton with a
 * MutableStateFlow, mirroring AddonRepository / DebridSettingsRepository. KMP twin of
 * NuvioTV's XtreamAccountStore + XtreamSettingsViewModel.
 */
object XtreamRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(XtreamUiState())
    val uiState: StateFlow<XtreamUiState> = _uiState.asStateFlow()

    private var loaded = false
    private var currentProfileId = 1

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        currentProfileId = ProfileRepository.activeProfileId
        _uiState.update { it.copy(accounts = parse(XtreamAccountStorage.loadAccountsJson(currentProfileId))) }
    }

    /** Reload this profile's accounts on a profile switch so no data leaks across profiles. */
    fun onProfileChanged(profileId: Int) {
        loaded = true
        currentProfileId = profileId
        _uiState.value = XtreamUiState(accounts = parse(XtreamAccountStorage.loadAccountsJson(profileId)))
    }

    /** Parse a pasted portal/M3U URL, verify the credentials live, then persist. */
    fun addFromUrl(input: String, name: String?, onResult: (Boolean) -> Unit) {
        verifyAndSave(parseXtreamAccount(input, name), "Couldn't read a username & password from that URL", onResult)
    }

    /** Add from manually-entered server URL + username + password. */
    fun addManual(serverUrl: String, username: String, password: String, name: String?, onResult: (Boolean) -> Unit) {
        verifyAndSave(
            xtreamAccountFromFields(serverUrl, username, password, name),
            "Enter a server URL, username and password",
            onResult
        )
    }

    private fun verifyAndSave(account: XtreamAccount?, parseError: String, onResult: (Boolean) -> Unit) {
        if (account == null) {
            _uiState.update { it.copy(error = parseError) }
            onResult(false)
            return
        }
        scope.launch {
            _uiState.update { it.copy(isValidating = true, error = null) }
            XtreamClient.verify(account)
                .onSuccess {
                    val updated = _uiState.value.accounts.filterNot { it.id == account.id } + account
                    _uiState.update { it.copy(accounts = updated, isValidating = false) }
                    persist()
                    onResult(true)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isValidating = false, error = e.message ?: "Could not reach the panel") }
                    onResult(false)
                }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _uiState.update { state ->
            state.copy(accounts = state.accounts.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
        persist()
    }

    fun remove(id: String) {
        _uiState.update { it.copy(accounts = it.accounts.filterNot { acc -> acc.id == id }) }
        persist()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun persist() {
        XtreamAccountStorage.saveAccountsJson(currentProfileId, json.encodeToString(_uiState.value.accounts))
    }

    private fun parse(stored: String?): List<XtreamAccount> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<XtreamAccount>>(stored) }.getOrDefault(emptyList())
    }
}
