package com.nuvio.app.features.iptv

import io.ktor.http.Url

/**
 * Turns a pasted portal/M3U URL into an [XtreamAccount]. KMP twin of NuvioTV's
 * XtreamUrlParser (uses Ktor's Url instead of okhttp HttpUrl). Accepts any of:
 *   http://host:port/get.php?username=U&password=P&type=m3u_plus&output=ts
 *   http://host:port/player_api.php?username=U&password=P
 * The path is ignored; only scheme+host+port and the username/password query params matter.
 */
fun parseXtreamAccount(input: String, name: String? = null): XtreamAccount? {
    val url = try {
        Url(input.trim())
    } catch (e: Exception) {
        return null
    }
    if (url.host.isBlank()) return null
    val user = url.parameters["username"]?.takeIf { it.isNotBlank() } ?: return null
    val pass = url.parameters["password"]?.takeIf { it.isNotBlank() } ?: return null
    val base = buildString {
        append(url.protocol.name).append("://").append(url.host)
        if (url.port != url.protocol.defaultPort) append(":").append(url.port)
    }
    return XtreamAccount(
        id = "$base|$user",
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = base,
        username = user,
        password = pass
    )
}

/**
 * Builds an account from manually-entered fields. The server field may be "host", "host:port",
 * or a full URL; only scheme+host+port are kept. Defaults to http when no scheme is given.
 * KMP twin of NuvioTV's xtreamAccountFromFields.
 */
fun xtreamAccountFromFields(serverUrl: String, username: String, password: String, name: String? = null): XtreamAccount? {
    val user = username.trim()
    val pass = password.trim()
    if (user.isEmpty() || pass.isEmpty()) return null
    val raw = serverUrl.trim()
    if (raw.isEmpty()) return null
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val url = try {
        Url(withScheme)
    } catch (e: Exception) {
        return null
    }
    if (url.host.isBlank()) return null
    val base = buildString {
        append(url.protocol.name).append("://").append(url.host)
        if (url.port != url.protocol.defaultPort) append(":").append(url.port)
    }
    return XtreamAccount(
        id = "$base|$user",
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = base,
        username = user,
        password = pass
    )
}

/**
 * Builds an account from the full "Add Playlist" form: the identity fields (server/username/password/
 * name) plus the playlist options the form collects (EPG URL, DNS provider, auto-refresh). Returns
 * null if the identity fields don't resolve to a valid host + credentials. Content types + category
 * selections are edited on the separate "Content & Categories" page, so they keep XtreamAccount's
 * defaults here. internal for unit tests.
 */
internal fun xtreamAccountFromForm(input: XtreamFormInput): XtreamAccount? {
    val base = xtreamAccountFromFields(input.serverUrl, input.username, input.password, input.name)
        ?: return null
    return base.copy(
        epgUrl = input.epgUrl?.trim()?.takeIf { it.isNotEmpty() },
        dnsProvider = input.dnsProvider,
        autoRefreshHours = input.autoRefreshHours,
    )
}
