package com.nuvio.app.features.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

internal object PlatformPlaybackDataSourceFactory {
    fun create(
        defaultRequestHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
    ): DataSource.Factory =
        DefaultHttpDataSource.Factory().setDefaultRequestProperties(defaultRequestHeaders)
}