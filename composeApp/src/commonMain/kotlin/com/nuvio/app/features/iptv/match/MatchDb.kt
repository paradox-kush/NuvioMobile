package com.nuvio.app.features.iptv.match

import androidx.sqlite.SQLiteConnection

/**
 * Opens the on-disk SQLite database backing the TMDB->Xtream match index.
 * Platform actuals pick the driver: AndroidSQLiteDriver (framework SQLite) on Android,
 * NativeSQLiteDriver (system libsqlite3) on iOS — zero bundled binaries on both.
 *
 * Disk-backed on purpose: a 175k-item catalog would cost ~90MB as an in-memory map —
 * fatal on low-RAM devices — vs ~2MB of SQLite page cache, and the index survives
 * restarts so it only rebuilds when a playlist refreshes.
 */
internal expect object MatchDbDriver {
    fun openConnection(): SQLiteConnection
}
