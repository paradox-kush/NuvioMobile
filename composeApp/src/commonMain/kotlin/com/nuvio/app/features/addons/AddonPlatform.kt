package com.nuvio.app.features.addons

internal expect object AddonStorage {
    fun loadInstalledAddonUrls(profileId: Int): List<String>
    fun saveInstalledAddonUrls(profileId: Int, urls: List<String>)
    fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean>
    fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>)
}

data class RawHttpResponse(
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)

expect suspend fun httpGetText(url: String): String

expect suspend fun httpPostJson(url: String, body: String): String

expect suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String

expect suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String

expect suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean = true,
): RawHttpResponse

/**
 * Streams a text resource line-by-line to [onLine], NEVER materializing the whole body as a String.
 * Required for M3U ingestion: a provider playlist can be 190+ MB — [httpGetText] would OOM. The
 * response is gzip-decoded transparently when the server sends `Content-Encoding: gzip`. [onLine]
 * runs on a background thread; keep it cheap (buffer + flush) and do not block it. Throws on a
 * non-2xx status. Memory stays O(one line + the caller's buffer).
 */
expect suspend fun httpStreamLines(
    url: String,
    userAgent: String?,
    onLine: (String) -> Unit,
)
