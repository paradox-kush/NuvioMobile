package com.nuvio.app.features.plugins

import kotlinx.serialization.json.Json

internal object PluginManifestParser {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun parse(payload: String): PluginManifest {
        val manifest = json.decodeFromString<PluginManifest>(payload)
        require(manifest.name.isNotBlank()) { "Manifest name is missing." }
        require(manifest.version.isNotBlank()) { "Manifest version is missing." }
        require(manifest.scrapers.isNotEmpty()) { "Manifest has no providers." }
        return manifest
    }
}
