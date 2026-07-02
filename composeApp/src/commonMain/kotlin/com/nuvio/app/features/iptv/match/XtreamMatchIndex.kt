package com.nuvio.app.features.iptv.match

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private inline fun <R> SQLiteStatement.use(block: (SQLiteStatement) -> R): R =
    try { block(this) } finally { close() }

internal enum class MatchKind(val slug: String) { MOVIE("movie"), SERIES("series") }

/** One catalog entry as stored in the index. [ext] = container extension (movies only). */
internal data class IndexedItem(val sid: Int, val name: String, val year: Int?, val tmdb: Int?, val ext: String?)

/** A confirmed (or confirmed-absent when [sid] is null) TMDB->stream mapping. */
internal data class CachedMapping(val sid: Int?, val matchedName: String?, val updatedAtMs: Long)

/**
 * Disk-backed lookup index per provider+kind: normalized-name keys and bulk-list tmdb ids
 * over the full catalog, plus the cache of verified tmdb->sid mappings (the thing Supabase
 * syncs across devices). All lookups are single indexed SELECTs — O(log n) pages, sub-ms.
 */
internal object XtreamMatchIndex {

    private val mutex = Mutex()
    private var conn: SQLiteConnection? = null

    private fun connection(): SQLiteConnection = conn ?: MatchDbDriver.openConnection().also {
        it.execSQL("CREATE TABLE IF NOT EXISTS items(provider TEXT NOT NULL, kind TEXT NOT NULL, sid INTEGER NOT NULL, name TEXT NOT NULL, year INTEGER, tmdb INTEGER, ext TEXT, PRIMARY KEY(provider, kind, sid)) WITHOUT ROWID")
        it.execSQL("CREATE INDEX IF NOT EXISTS items_tmdb ON items(provider, kind, tmdb)")
        it.execSQL("CREATE TABLE IF NOT EXISTS keys(provider TEXT NOT NULL, kind TEXT NOT NULL, k TEXT NOT NULL, sid INTEGER NOT NULL, PRIMARY KEY(provider, kind, k, sid)) WITHOUT ROWID")
        it.execSQL("CREATE TABLE IF NOT EXISTS idx_meta(provider TEXT NOT NULL, kind TEXT NOT NULL, built_at INTEGER NOT NULL, item_count INTEGER NOT NULL, PRIMARY KEY(provider, kind)) WITHOUT ROWID")
        it.execSQL("CREATE TABLE IF NOT EXISTS tmdb_map(provider TEXT NOT NULL, kind TEXT NOT NULL, tmdb INTEGER NOT NULL, sid INTEGER, matched_name TEXT, updated_at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind, tmdb)) WITHOUT ROWID")
        conn = it
    }

    private fun now(): Long = TraktPlatformClock.nowEpochMs()

    suspend fun builtAt(provider: String, kind: MatchKind): Long? = mutex.withLock {
        connection().prepare("SELECT built_at FROM idx_meta WHERE provider = ? AND kind = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug)
            if (st.step()) st.getLong(0) else null
        }
    }

    /**
     * Replaces the whole index for one provider+kind. Chunked transactions keep the write
     * lock short so concurrent probes interleave; meta row is written LAST so a crashed
     * rebuild reads as stale, not as complete.
     */
    suspend fun rebuild(provider: String, kind: MatchKind, items: List<IndexedItem>) {
        mutex.withLock {
            val c = connection()
            c.execSQL("BEGIN IMMEDIATE")
            try {
                c.prepare("DELETE FROM items WHERE provider = ? AND kind = ?").use { st ->
                    st.bindText(1, provider); st.bindText(2, kind.slug); st.step()
                }
                c.prepare("DELETE FROM keys WHERE provider = ? AND kind = ?").use { st ->
                    st.bindText(1, provider); st.bindText(2, kind.slug); st.step()
                }
                c.prepare("DELETE FROM idx_meta WHERE provider = ? AND kind = ?").use { st ->
                    st.bindText(1, provider); st.bindText(2, kind.slug); st.step()
                }
                c.execSQL("COMMIT")
            } catch (t: Throwable) {
                c.execSQL("ROLLBACK"); throw t
            }
        }
        for (chunk in items.chunked(5_000)) {
            mutex.withLock {
                val c = connection()
                c.execSQL("BEGIN IMMEDIATE")
                try {
                    c.prepare("INSERT OR REPLACE INTO items(provider, kind, sid, name, year, tmdb, ext) VALUES(?,?,?,?,?,?,?)").use { st ->
                        for (it in chunk) {
                            st.reset()
                            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, it.sid.toLong())
                            st.bindText(4, it.name)
                            if (it.year != null) st.bindLong(5, it.year.toLong()) else st.bindNull(5)
                            if (it.tmdb != null) st.bindLong(6, it.tmdb.toLong()) else st.bindNull(6)
                            if (it.ext != null) st.bindText(7, it.ext) else st.bindNull(7)
                            st.step()
                        }
                    }
                    c.prepare("INSERT OR REPLACE INTO keys(provider, kind, k, sid) VALUES(?,?,?,?)").use { st ->
                        for (it in chunk) {
                            for (key in TitleNormalizer.keysOf(it.name)) {
                                st.reset()
                                st.bindText(1, provider); st.bindText(2, kind.slug); st.bindText(3, key); st.bindLong(4, it.sid.toLong())
                                st.step()
                            }
                        }
                    }
                    c.execSQL("COMMIT")
                } catch (t: Throwable) {
                    c.execSQL("ROLLBACK"); throw t
                }
            }
        }
        mutex.withLock {
            val c = connection()
            c.prepare("INSERT OR REPLACE INTO idx_meta(provider, kind, built_at, item_count) VALUES(?,?,?,?)").use { st ->
                st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, now()); st.bindLong(4, items.size.toLong())
                st.step()
            }
        }
    }

    /** All items indexed under a normalized key. */
    suspend fun probe(provider: String, kind: MatchKind, key: String): List<IndexedItem> = mutex.withLock {
        connection().prepare(
            "SELECT i.sid, i.name, i.year, i.tmdb, i.ext FROM keys x JOIN items i ON i.provider = x.provider AND i.kind = x.kind AND i.sid = x.sid WHERE x.provider = ? AND x.kind = ? AND x.k = ?"
        ).use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindText(3, key)
            readItems(st)
        }
    }

    /** Tier-1: items whose bulk-list tmdb id already matches. */
    suspend fun byTmdb(provider: String, kind: MatchKind, tmdb: Int): List<IndexedItem> = mutex.withLock {
        connection().prepare("SELECT sid, name, year, tmdb, ext FROM items WHERE provider = ? AND kind = ? AND tmdb = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, tmdb.toLong())
            readItems(st)
        }
    }

    suspend fun item(provider: String, kind: MatchKind, sid: Int): IndexedItem? = mutex.withLock {
        connection().prepare("SELECT sid, name, year, tmdb, ext FROM items WHERE provider = ? AND kind = ? AND sid = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, sid.toLong())
            readItems(st).firstOrNull()
        }
    }

    private fun readItems(st: SQLiteStatement): List<IndexedItem> {
        val out = ArrayList<IndexedItem>()
        while (st.step()) {
            out.add(
                IndexedItem(
                    sid = st.getLong(0).toInt(),
                    name = st.getText(1),
                    year = if (st.isNull(2)) null else st.getLong(2).toInt(),
                    tmdb = if (st.isNull(3)) null else st.getLong(3).toInt(),
                    ext = if (st.isNull(4)) null else st.getText(4),
                )
            )
        }
        return out
    }

    // --- verified-mapping cache (local mirror of the Supabase iptv_tmdb_map rows) ---

    suspend fun cachedMapping(provider: String, kind: MatchKind, tmdb: Int): CachedMapping? = mutex.withLock {
        connection().prepare("SELECT sid, matched_name, updated_at FROM tmdb_map WHERE provider = ? AND kind = ? AND tmdb = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, tmdb.toLong())
            if (st.step()) CachedMapping(
                sid = if (st.isNull(0)) null else st.getLong(0).toInt(),
                matchedName = if (st.isNull(1)) null else st.getText(1),
                updatedAtMs = st.getLong(2),
            ) else null
        }
    }

    suspend fun putMapping(provider: String, kind: MatchKind, tmdb: Int, sid: Int?, matchedName: String?, synced: Boolean = false, updatedAtMs: Long = now()) {
        mutex.withLock {
            connection().prepare("INSERT OR REPLACE INTO tmdb_map(provider, kind, tmdb, sid, matched_name, updated_at, synced) VALUES(?,?,?,?,?,?,?)").use { st ->
                st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, tmdb.toLong())
                if (sid != null) st.bindLong(4, sid.toLong()) else st.bindNull(4)
                if (matchedName != null) st.bindText(5, matchedName) else st.bindNull(5)
                st.bindLong(6, updatedAtMs)
                st.bindLong(7, if (synced) 1 else 0)
                st.step()
            }
        }
    }

    /** Rows not yet pushed to Supabase: (kind, tmdb, sid, matchedName, updatedAtMs). */
    suspend fun unsyncedMappings(provider: String): List<UnsyncedMapping> = mutex.withLock {
        connection().prepare("SELECT kind, tmdb, sid, matched_name, updated_at FROM tmdb_map WHERE provider = ? AND synced = 0").use { st ->
            st.bindText(1, provider)
            val out = ArrayList<UnsyncedMapping>()
            while (st.step()) {
                out.add(
                    UnsyncedMapping(
                        kind = st.getText(0),
                        tmdb = st.getLong(1).toInt(),
                        sid = if (st.isNull(2)) null else st.getLong(2).toInt(),
                        matchedName = if (st.isNull(3)) null else st.getText(3),
                        updatedAtMs = st.getLong(4),
                    )
                )
            }
            out
        }
    }

    suspend fun markSynced(provider: String, kind: String, tmdb: Int) {
        mutex.withLock {
            connection().prepare("UPDATE tmdb_map SET synced = 1 WHERE provider = ? AND kind = ? AND tmdb = ?").use { st ->
                st.bindText(1, provider); st.bindText(2, kind); st.bindLong(3, tmdb.toLong())
                st.step()
            }
        }
    }
}

internal data class UnsyncedMapping(val kind: String, val tmdb: Int, val sid: Int?, val matchedName: String?, val updatedAtMs: Long)
