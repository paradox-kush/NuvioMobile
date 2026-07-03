package com.nuvio.app.features.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSecretTextField

/**
 * Which playlist the "Add Playlist" form is editing — set right before navigating to
 * SettingsPage.IptvAddPlaylist. Mirrors XtreamContentPage. `editId == null` => add mode.
 */
internal object XtreamAddPage {
    var editId: String? = null
        private set

    val isEdit: Boolean get() = editId != null

    /** Fresh "Add Playlist". */
    fun openAdd() {
        editId = null
    }

    /** Edit an existing playlist (prefills from state). */
    fun openEdit(id: String) {
        editId = id
    }
}

/**
 * The full set of fields the "Add Playlist" form collects. Handed to
 * XtreamRepository.addFromForm / editFromForm which verifies + persists. Content types &
 * category selections are edited on the separate "Content & Categories" page, not here.
 */
internal data class XtreamFormInput(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String?,
    val epgUrl: String?,
    val dnsProvider: String,
    val autoRefreshHours: Int,
)

/**
 * The four playlist source types from the reference design. Only [XTREAM] is functional in P1;
 * the rest render with a "Soon" badge and can't be selected. Flip [enabled] per phase as each
 * source type's field-set + parsing lands (URL/FILE = P2, STALKER = P4).
 */
internal enum class XtreamSourceType(val label: String, val enabled: Boolean) {
    URL("URL", enabled = false),
    FILE("File", enabled = false),
    XTREAM("Xtream", enabled = true),
    STALKER("Stalker", enabled = false),
}

// DNS-over-HTTPS providers offered per playlist (Android only — iOS can't do per-playlist DNS).
// key = the persisted XtreamAccount.dnsProvider value; label = what the tile shows.
private val DNS_PROVIDERS = listOf(
    "system" to "System",
    "cloudflare" to "Cloudflare",
    "google" to "Google",
    "mullvad" to "Mullvad",
    "quad9" to "Swiss",
    "dnssb" to "DNS.SB",
)

// Auto-refresh choices (hours). 0 = off; 24 is the product default. Persisted as autoRefreshHours.
private val AUTO_REFRESH_OPTIONS = listOf(
    0 to "Off",
    6 to "Every 6 hours",
    12 to "Every 12 hours",
    24 to "Every 24 hours",
    48 to "Every 48 hours",
    72 to "Every 72 hours",
)

private fun autoRefreshLabel(hours: Int): String =
    AUTO_REFRESH_OPTIONS.firstOrNull { it.first == hours }?.second ?: "Every $hours hours"

/**
 * "Add Playlist" / "Edit Playlist" form. A settings sub-page (parentPage = Iptv) rather than a
 * dialog — the form is too tall for an AlertDialog. Built entirely from the settings design system
 * (SettingsSection/SettingsGroup, marigold FilterChips, SettingsSecretTextField, NuvioDropdownChip,
 * NuvioPrimaryButton). Xtream is the only functional source type in P1; the other tiles are disabled.
 * [onDone] pops back to the playlist list on a successful save.
 */
internal fun LazyListScope.xtreamAddPlaylistContent(
    isTablet: Boolean,
    state: XtreamUiState,
    onDone: () -> Unit,
) {
    item {
        val editing = XtreamAddPage.editId?.let { id -> state.accounts.firstOrNull { it.id == id } }

        // Prefilled once per target playlist (add mode -> blank). Fields are plain remembered state.
        var sourceType by remember(editing?.id) { mutableStateOf(XtreamSourceType.XTREAM) }
        var server by remember(editing?.id) { mutableStateOf(editing?.baseUrl ?: "") }
        var username by remember(editing?.id) { mutableStateOf(editing?.username ?: "") }
        var password by remember(editing?.id) { mutableStateOf(editing?.password ?: "") }
        var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
        var epgUrl by remember(editing?.id) { mutableStateOf(editing?.epgUrl ?: "") }
        var dnsProvider by remember(editing?.id) { mutableStateOf(editing?.dnsProvider ?: "system") }
        var autoRefreshHours by remember(editing?.id) { mutableStateOf(editing?.autoRefreshHours ?: 24) }

        SourceTypeSection(
            isTablet = isTablet,
            selected = sourceType,
            onSelected = { sourceType = it },
        )

        when (sourceType) {
            XtreamSourceType.XTREAM -> XtreamFieldsSection(
                isTablet = isTablet,
                server = server,
                onServerChange = { input ->
                    // Xtream = portal + username + password. Pasting a full get.php URL into Server URL
                    // auto-fills user/pass; otherwise it's just the portal URL.
                    val parsed = parseXtreamAccount(input)
                    if (parsed != null) {
                        server = parsed.baseUrl
                        username = parsed.username
                        password = parsed.password
                        if (name.isBlank()) name = parsed.name
                    } else {
                        server = input
                    }
                },
                username = username,
                onUsernameChange = { username = it },
                password = password,
                onPasswordChange = { password = it },
                name = name,
                onNameChange = { name = it },
            )
            // URL / File / Stalker field-sets are P2/P4 — the tiles above are disabled so these
            // branches are never reached in P1. TODO(playlist-manager P2/P4): render each source
            // type's own field-set here (M3U URL + optional EPG url; Stalker portal + MAC).
            else -> Unit
        }

        EpgUrlSection(
            isTablet = isTablet,
            epgUrl = epgUrl,
            onEpgUrlChange = { epgUrl = it },
        )

        DnsProviderSection(
            isTablet = isTablet,
            selected = dnsProvider,
            onSelected = { dnsProvider = it },
        )

        AutoRefreshSection(
            isTablet = isTablet,
            selectedHours = autoRefreshHours,
            onSelected = { autoRefreshHours = it },
        )

        SaveSection(
            isTablet = isTablet,
            state = state,
            isEdit = XtreamAddPage.isEdit,
            canSave = sourceType.enabled &&
                server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            onSave = {
                val input = XtreamFormInput(
                    serverUrl = server,
                    username = username,
                    password = password,
                    name = name.trim().ifEmpty { null },
                    epgUrl = epgUrl.trim().ifEmpty { null },
                    dnsProvider = dnsProvider,
                    autoRefreshHours = autoRefreshHours,
                )
                val editId = XtreamAddPage.editId
                if (editId != null) {
                    XtreamRepository.editFromForm(editId, input) { ok -> if (ok) onDone() }
                } else {
                    XtreamRepository.addFromForm(input) { ok -> if (ok) onDone() }
                }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SourceTypeSection(
    isTablet: Boolean,
    selected: XtreamSourceType,
    onSelected: (XtreamSourceType) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "Source Type", isTablet = isTablet) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            XtreamSourceType.entries.forEach { type ->
                FilterChip(
                    selected = selected == type,
                    enabled = type.enabled,
                    onClick = { if (type.enabled) onSelected(type) },
                    label = {
                        Text(
                            text = if (type.enabled) type.label else "${type.label} · Soon",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = type.enabled,
                        selected = selected == type,
                        borderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
                        selectedBorderColor = tokens.colors.accent.copy(alpha = tokens.opacity.strong),
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.selected),
                        selectedLabelColor = tokens.colors.textPrimary,
                        labelColor = tokens.colors.textSecondary,
                        disabledLabelColor = tokens.colors.textDisabled,
                    ),
                )
            }
        }
    }
}

@Composable
private fun XtreamFieldsSection(
    isTablet: Boolean,
    server: String,
    onServerChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
) {
    SettingsSection(title = "Xtream Account", isTablet = isTablet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s2),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            FormOutlinedField(
                value = server,
                onValueChange = onServerChange,
                label = "Server URL",
                placeholder = "portal, e.g. http://host:port",
            )
            FormOutlinedField(
                value = username,
                onValueChange = onUsernameChange,
                label = "Username",
            )
            SettingsSecretTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "Password",
                modifier = Modifier.fillMaxWidth(),
            )
            FormOutlinedField(
                value = name,
                onValueChange = onNameChange,
                label = "Name (optional)",
            )
        }
    }
}

@Composable
private fun EpgUrlSection(
    isTablet: Boolean,
    epgUrl: String,
    onEpgUrlChange: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "EPG URL (optional)", isTablet = isTablet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s2),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            FormOutlinedField(
                value = epgUrl,
                onValueChange = onEpgUrlChange,
                label = "XMLTV EPG URL",
                placeholder = "http://host:port/xmltv.php?username=…&password=…",
            )
            Text(
                text = "Override the guide source with a custom XMLTV URL. Leave blank to use the provider's built-in EPG.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DnsProviderSection(
    isTablet: Boolean,
    selected: String,
    onSelected: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "DNS Provider", isTablet = isTablet) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                DNS_PROVIDERS.forEach { (key, label) ->
                    FilterChip(
                        selected = selected == key,
                        onClick = { onSelected(key) },
                        label = {
                            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected == key,
                            borderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
                            selectedBorderColor = tokens.colors.accent.copy(alpha = tokens.opacity.strong),
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.selected),
                            selectedLabelColor = tokens.colors.textPrimary,
                            labelColor = tokens.colors.textSecondary,
                        ),
                    )
                }
            }
            Text(
                text = "Choose a DNS server for resolving this playlist's addresses. Cloudflare or Google can improve connection reliability.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            Text(
                text = "Android only — iOS ignores this setting.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun AutoRefreshSection(
    isTablet: Boolean,
    selectedHours: Int,
    onSelected: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(title = "Auto-Refresh", isTablet = isTablet) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            NuvioDropdownChip(
                title = "Auto-Refresh",
                label = autoRefreshLabel(selectedHours),
                selectedKey = selectedHours.toString(),
                options = AUTO_REFRESH_OPTIONS.map { NuvioDropdownOption(it.first.toString(), it.second) },
                onSelected = { option -> option.key.toIntOrNull()?.let(onSelected) },
            )
            Text(
                text = "Periodically check this playlist for new movies and series.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun SaveSection(
    isTablet: Boolean,
    state: XtreamUiState,
    isEdit: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        state.error?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.danger,
            )
        }
        val saveLabel = when {
            state.isValidating -> "Verifying…"
            isEdit -> "Save changes"
            else -> "Add playlist"
        }
        NuvioPrimaryButton(
            text = saveLabel,
            enabled = canSave && !state.isValidating,
            onClick = onSave,
        )
        if (state.isValidating) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(tokens.icons.md),
                    strokeWidth = tokens.borders.medium,
                    color = tokens.colors.accent,
                )
                Spacer(Modifier.width(NuvioTokens.Space.s8))
                Text(
                    text = "Contacting the provider…",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        Spacer(Modifier.height(NuvioTokens.Space.s8))
    }
}

/** OutlinedTextField pre-styled with the Nuvio settings token colors (marigold focus border). */
@Composable
private fun FormOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.colors.borderFocus.copy(alpha = tokens.opacity.strong),
            unfocusedBorderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
            focusedContainerColor = tokens.colors.surface,
            unfocusedContainerColor = tokens.colors.surface,
            disabledContainerColor = tokens.colors.surface,
            focusedLabelColor = tokens.colors.textSecondary,
            unfocusedLabelColor = tokens.colors.textMuted,
            focusedTextColor = tokens.colors.textPrimary,
            unfocusedTextColor = tokens.colors.textPrimary,
            cursorColor = tokens.colors.accent,
        ),
    )
}
