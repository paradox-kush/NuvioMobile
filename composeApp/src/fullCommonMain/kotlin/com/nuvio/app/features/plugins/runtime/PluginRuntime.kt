package com.nuvio.app.features.plugins.runtime

import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.plugins.runtime.crypto.CryptoBridge
import com.nuvio.app.features.plugins.runtime.dom.DomBridge
import com.nuvio.app.features.plugins.runtime.host.HostApiRegistry
import com.nuvio.app.features.plugins.runtime.host.HostFunctions
import com.nuvio.app.features.plugins.runtime.js.JsBindings
import com.nuvio.app.features.plugins.runtime.js.JsRuntime
import com.nuvio.app.features.plugins.runtime.network.FetchBridge
import com.nuvio.app.features.plugins.runtime.network.UrlBridge
import com.nuvio.app.features.plugins.runtime.wasm.WasmBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val PLUGIN_TIMEOUT_MS = 60_000L

internal object PluginRuntime {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
    ): List<PluginRuntimeResult> = withContext(Dispatchers.Default) {
        val scraperSettingsJson = PluginStorage.loadScraperSettings(scraperId) ?: "{}"
        val scraperSettingsMap = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(scraperSettingsJson)
        }.getOrElse { emptyMap() }

        withTimeout(PLUGIN_TIMEOUT_MS) {
            executePluginInternal(
                code = code,
                tmdbId = tmdbId,
                mediaType = mediaType,
                season = season,
                episode = episode,
                scraperId = scraperId,
                scraperSettings = scraperSettingsMap,
            )
        }
    }

    suspend fun getPluginSettingsLayout(
        code: String,
        scraperId: String,
    ): String? = withContext(Dispatchers.Default) {
        withTimeout(PLUGIN_TIMEOUT_MS) {
            val jsRuntime = JsRuntime()
            var resultJson: String? = null

            try {
                jsRuntime.use {
                    val polyfillCode = JsBindings.buildPolyfillCode(
                        scraperIdJson = JsonPrimitive(scraperId).toString(),
                        settingsJson = "{}"
                    )
                    evaluate<Any?>(polyfillCode)

                    val wrappedCode = """
                        var module = { exports: {} };
                        var exports = module.exports;
                        (function() {
                            $code
                        })();
                    """.trimIndent()
                    evaluate<Any?>(wrappedCode)

                    val callCode = """
                        (async function() {
                            try {
                                var onSettings = module.exports.onSettings || globalThis.onSettings;
                                if (onSettings) {
                                    var layout = await onSettings();
                                    globalThis.__settings_layout_result = JSON.stringify(layout || []);
                                } else {
                                    globalThis.__settings_layout_result = null;
                                }
                            } catch (e) {
                                console.error("onSettings error:", e);
                                globalThis.__settings_layout_result = null;
                            }
                        })();
                    """.trimIndent()
                    evaluate<Any?>(callCode)
                    resultJson = evaluate<String?>("globalThis.__settings_layout_result")
                }
                resultJson
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun executePluginInternal(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, JsonElement>,
    ): List<PluginRuntimeResult> {
        val jsRuntime = JsRuntime()
        var resultJson = "[]"

        val domBridge = DomBridge()
        val hostRegistry = HostApiRegistry().apply {
            addModule(HostFunctions(scraperId) { resultJson = it })
            addModule(FetchBridge())
            addModule(UrlBridge())
            addModule(CryptoBridge())
            addModule(WasmBridge())
            addModule(domBridge)
        }

        try {
            jsRuntime.use {
                hostRegistry.registerAll(this)

                val settingsJson = JsonObject(scraperSettings).toString()
                val polyfillCode = JsBindings.buildPolyfillCode(
                    scraperIdJson = JsonPrimitive(scraperId).toString(),
                    settingsJson = settingsJson,
                )
                evaluate<Any?>(polyfillCode)

                val wrappedCode = """
                    var module = { exports: {} };
                    var exports = module.exports;
                    (function() {
                        $code
                    })();
                """.trimIndent()
                evaluate<Any?>(wrappedCode)

                val tmdbIdArg = JsonPrimitive(tmdbId).toString()
                val mediaTypeArg = JsonPrimitive(mediaType).toString()
                val seasonArg = season?.toString() ?: "undefined"
                val episodeArg = episode?.toString() ?: "undefined"
                val callCode = """
                    (async function() {
                        try {
                            var getStreams = module.exports.getStreams || globalThis.getStreams;
                            if (!getStreams) {
                                console.error("getStreams function not found on module.exports or globalThis");
                                __capture_result(JSON.stringify([]));
                                return;
                            }
                            var result = await getStreams($tmdbIdArg, $mediaTypeArg, $seasonArg, $episodeArg);
                            __capture_result(JSON.stringify(result || []));
                        } catch (e) {
                            console.error("getStreams error:", e && e.message ? e.message : e, e && e.stack ? e.stack : "");
                            __capture_result(JSON.stringify([]));
                        }
                    })();
                """.trimIndent()
                evaluate<Any?>(callCode)
            }

            return parseJsonResults(resultJson)
        } finally {
            domBridge.clear()
        }
    }

    private fun parseJsonResults(rawJson: String): List<PluginRuntimeResult> {
        return runCatching {
            val array = json.parseToJsonElement(rawJson) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = when (val urlValue = item["url"]) {
                    is JsonPrimitive -> urlValue.contentOrNull?.takeIf { it.isNotBlank() }
                    is JsonObject -> urlValue["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    else -> null
                } ?: return@mapNotNull null

                val headers = (item["headers"] as? JsonObject)
                    ?.mapNotNull { (key, value) ->
                        value.jsonPrimitive.contentOrNull?.let { key to it }
                    }
                    ?.toMap()
                    ?.takeIf { it.isNotEmpty() }

                PluginRuntimeResult(
                    title = item.stringOrNull("title") ?: item.stringOrNull("name") ?: "Unknown",
                    name = item.stringOrNull("name"),
                    url = url,
                    quality = item.stringOrNull("quality"),
                    size = item.stringOrNull("size"),
                    language = item.stringOrNull("language"),
                    provider = item.stringOrNull("provider"),
                    type = item.stringOrNull("type"),
                    seeders = item["seeders"]?.jsonPrimitive?.intOrNull,
                    peers = item["peers"]?.jsonPrimitive?.intOrNull,
                    infoHash = item.stringOrNull("infoHash"),
                    headers = headers,
                )
            }.filter { it.url.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && !it.contains("[object") }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> JsonObject(
            value.entries
                .filter { it.key is String }
                .associate { (it.key as String) to toJsonElement(it.value) },
        )
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        else -> JsonPrimitive(value.toString())
    }
}
