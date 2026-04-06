package com.nuvio.app.features.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.nuvio.app.features.trailer.YoutubeChunkedDataSourceFactory

internal object PlatformPlaybackDataSourceFactory {
    fun create(
        defaultRequestHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
    ): DataSource.Factory =
        if (useYoutubeChunkedPlayback) {
            YoutubeChunkedDataSourceFactory(defaultRequestHeaders = defaultRequestHeaders)
        } else {
            DefaultHttpDataSource.Factory().setDefaultRequestProperties(defaultRequestHeaders)
        }
}