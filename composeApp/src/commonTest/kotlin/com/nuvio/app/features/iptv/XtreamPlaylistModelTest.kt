package com.nuvio.app.features.iptv

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playlist-manager P1 contract tests: additive model defaults (old persisted JSON must load
 * unchanged), the sync_push_iptv_playlists payload/params shape (field names, omission rules
 * and source-type scoping match the backend migration exactly), the pull's usable-row filter,
 * edit option carry-over, and lenient category_selections decoding.
 */
class XtreamPlaylistModelTest {

    // Same config as XtreamRepository's Json.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val base = XtreamAccount(
        id = "http://h:80|u",
        name = "My panel",
        baseUrl = "http://h:80",
        username = "u",
        password = "p",
    )

    @Test
    fun oldPersistedJsonDecodesWithPlaylistDefaults() {
        // Exactly what pre-playlist-manager builds wrote to SharedPreferences/NSUserDefaults.
        val stored = """[{"id":"http://h:80|u","name":"My panel","baseUrl":"http://h:80","username":"u","password":"p","enabled":false}]"""
        val acc = json.decodeFromString<List<XtreamAccount>>(stored).single()
        assertEquals("xtream", acc.sourceType)
        assertNull(acc.epgUrl)
        assertEquals("system", acc.dnsProvider)
        // missing field → the 24h product default
        assertEquals(24, acc.autoRefreshHours)
        assertEquals(setOf("live", "movies", "series"), acc.contentTypes)
        assertTrue(acc.categorySelections.allNull)
        assertFalse(acc.enabled)
    }

    @Test
    fun roundTripPreservesPlaylistOptions() {
        val tuned = base.copy(
            epgUrl = "http://epg.example/xmltv.php",
            dnsProvider = "cloudflare",
            autoRefreshHours = 24,
            contentTypes = setOf(CONTENT_TYPE_LIVE, CONTENT_TYPE_MOVIES),
            categorySelections = CategorySelections(live = listOf("1", "2"), movies = emptyList()),
        )
        val decoded = json.decodeFromString<List<XtreamAccount>>(json.encodeToString(listOf(tuned))).single()
        assertEquals(tuned, decoded)
    }

    @Test
    fun pushPayloadShapeMatchesMigrationContract() {
        val plain = base.copy(name = "")   // blank name -> omitted
        val tuned = base.copy(
            name = "Named",
            enabled = false,
            epgUrl = "http://epg.example",
            dnsProvider = "quad9",
            autoRefreshHours = 12,
            contentTypes = setOf(CONTENT_TYPE_LIVE),
            categorySelections = CategorySelections(live = listOf("1", "2")),
        )
        val payload = playlistPushPayload(listOf(plain, tuned))

        val first = payload[0].jsonObject
        assertEquals("xtream", first["source_type"]!!.jsonPrimitive.content)
        assertFalse("name" in first)                    // blank name omitted
        assertFalse("epg_url" in first)                 // null epg_url omitted
        assertFalse("category_selections" in first)     // all-null selections omitted
        assertEquals(true, first["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, first["sort_order"]!!.jsonPrimitive.int)
        assertEquals("http://h:80", first["base_url"]!!.jsonPrimitive.content)
        assertEquals("u", first["username"]!!.jsonPrimitive.content)
        assertEquals("p", first["password"]!!.jsonPrimitive.content)
        assertEquals("system", first["dns_provider"]!!.jsonPrimitive.content)
        assertEquals(24, first["auto_refresh_hours"]!!.jsonPrimitive.int)   // default
        assertEquals(
            setOf("live", "movies", "series"),
            first["content_types"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        val second = payload[1].jsonObject
        assertEquals("Named", second["name"]!!.jsonPrimitive.content)
        assertEquals(false, second["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, second["sort_order"]!!.jsonPrimitive.int)
        assertEquals("http://epg.example", second["epg_url"]!!.jsonPrimitive.content)
        assertEquals("quad9", second["dns_provider"]!!.jsonPrimitive.content)
        assertEquals(12, second["auto_refresh_hours"]!!.jsonPrimitive.int)
        assertEquals(listOf("live"), second["content_types"]!!.jsonArray.map { it.jsonPrimitive.content })
        val selections = second["category_selections"]!!.jsonObject
        assertEquals(listOf("1", "2"), selections["live"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("movies" in selections)             // null per-type list omitted
        assertFalse("series" in selections)
    }

    @Test
    fun pushParamsScopeEveryPushToXtreamSourceType() {
        val params = playlistPushParams(profileId = 3, accounts = listOf(base))
        assertEquals(3, params["p_profile_id"]!!.jsonPrimitive.int)
        assertEquals(1, params["p_playlists"]!!.jsonArray.size)
        // Every push is scoped so a P1 full-replace can't delete a newer client's m3u/stalker rows.
        assertEquals(listOf("xtream"), params["p_source_types"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Regular pushes omit the migration guard entirely.
        assertFalse("p_only_if_empty" in params)
    }

    @Test
    fun migrationPushParamsSetOnlyIfEmpty() {
        val params = playlistPushParams(profileId = 1, accounts = listOf(base), onlyIfEmpty = true)
        // Two-device first-login race: the loser's migration push must no-op server-side.
        assertEquals(true, params["p_only_if_empty"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("xtream"), params["p_source_types"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun pullWithOnlyForeignSourceRowsIsAnEmptyRemote() {
        val foreign = listOf(
            PlaylistRow(sourceType = "m3u_url", name = "M3U"),
            PlaylistRow(sourceType = "stalker", name = "Portal"),
        )
        // Zero usable xtream rows => empty remote; never applied as an empty list over local state.
        assertTrue(usableRemoteAccounts(foreign).isEmpty())

        // Malformed xtream rows (missing identity columns) are skipped too.
        assertTrue(usableRemoteAccounts(listOf(PlaylistRow(baseUrl = null, username = "u"))).isEmpty())

        val mixed = foreign + PlaylistRow(baseUrl = "http://h:80", username = "u", password = "p")
        val usable = usableRemoteAccounts(mixed).single()
        assertEquals("http://h:80|u", usable.id)
    }

    @Test
    fun editCarriesProviderSpecificOptionsOnlyForTheSamePlaylist() {
        val old = base.copy(
            enabled = false,
            epgUrl = "http://epg.example",
            dnsProvider = "quad9",
            autoRefreshHours = 12,
            contentTypes = setOf(CONTENT_TYPE_LIVE),
            categorySelections = CategorySelections(live = listOf("1")),
        )

        // Same playlist (same username, moved domain): everything carries over.
        val moved = XtreamAccount(id = "http://new:80|u", name = "Moved", baseUrl = "http://new:80", username = "u", password = "p2")
        val sameCarried = carryPlaylistOptions(old, moved)
        assertFalse(sameCarried.enabled)
        assertEquals("http://epg.example", sameCarried.epgUrl)
        assertEquals("quad9", sameCarried.dnsProvider)
        assertEquals(12, sameCarried.autoRefreshHours)
        assertEquals(setOf(CONTENT_TYPE_LIVE), sameCarried.contentTypes)
        assertEquals(CategorySelections(live = listOf("1")), sameCarried.categorySelections)

        // Same server, rotated username: still the same playlist.
        val rotated = XtreamAccount(id = "http://h:80|u2", name = "Rotated", baseUrl = "http://h:80", username = "u2", password = "p")
        assertEquals(CategorySelections(live = listOf("1")), carryPlaylistOptions(old, rotated).categorySelections)

        // Different playlist: provider-specific options reset, provider-agnostic ones carry.
        val other = XtreamAccount(id = "http://other:80|x", name = "Other", baseUrl = "http://other:80", username = "x", password = "y")
        val reset = carryPlaylistOptions(old, other)
        assertEquals(CategorySelections(), reset.categorySelections)
        assertNull(reset.epgUrl)
        assertFalse(reset.enabled)
        assertEquals("quad9", reset.dnsProvider)
        assertEquals(12, reset.autoRefreshHours)
        assertEquals(setOf(CONTENT_TYPE_LIVE), reset.contentTypes)
    }

    @Test
    fun addPlaylistFormMapsOptionFieldsOntoAccount() {
        // The "Add Playlist" form collects epgUrl/dnsProvider/autoRefreshHours; the mapping must
        // land them on the XtreamAccount that gets verified + persisted (the model already syncs them).
        val account = xtreamAccountFromForm(
            XtreamFormInput(
                serverUrl = "host.example.org:8080",
                username = "user1",
                password = "secret",
                name = "  My Playlist  ",
                epgUrl = "  http://epg.example/xmltv.php  ",
                dnsProvider = "cloudflare",
                autoRefreshHours = 48,
            ),
        )!!
        assertEquals("http://host.example.org:8080|user1", account.id)
        assertEquals("My Playlist", account.name)                 // trimmed
        assertEquals("http://host.example.org:8080", account.baseUrl)
        assertEquals("user1", account.username)
        assertEquals("secret", account.password)
        assertEquals("http://epg.example/xmltv.php", account.epgUrl)   // trimmed, persisted
        assertEquals("cloudflare", account.dnsProvider)               // persisted
        assertEquals(48, account.autoRefreshHours)                    // persisted
        assertEquals("xtream", account.sourceType)
        // Content types + category selections aren't on the form → stay at defaults.
        assertEquals(setOf("live", "movies", "series"), account.contentTypes)
        assertTrue(account.categorySelections.allNull)
    }

    @Test
    fun addPlaylistFormBlanksBecomeNullOrDefault() {
        val account = xtreamAccountFromForm(
            XtreamFormInput(
                serverUrl = "http://h:80",
                username = "u",
                password = "p",
                name = null,
                epgUrl = "   ",           // blank -> null (not persisted / synced as empty)
                dnsProvider = "system",
                autoRefreshHours = 0,     // "Off"
            ),
        )!!
        assertNull(account.epgUrl)
        assertEquals("system", account.dnsProvider)
        assertEquals(0, account.autoRefreshHours)
        assertEquals("h", account.name)   // falls back to host when no name given

        // Missing identity fields => no account (form Save is gated on this too).
        assertNull(
            xtreamAccountFromForm(
                XtreamFormInput(serverUrl = "", username = "u", password = "p", name = null, epgUrl = null, dnsProvider = "system", autoRefreshHours = 24),
            ),
        )
    }

    @Test
    fun editFormKeepsCandidateOptionsButCarriesContentSelections() {
        // Editing via the full form: the form OWNS epg/dns/auto-refresh (it shows them), so the
        // candidate's values win over the old account's; content types + category selections live on
        // a different page the form doesn't touch, so they carry over (same-playlist edit).
        val old = base.copy(
            epgUrl = "http://old.epg",
            dnsProvider = "quad9",
            autoRefreshHours = 12,
            contentTypes = setOf(CONTENT_TYPE_LIVE),
            categorySelections = CategorySelections(live = listOf("1")),
        )
        val candidate = xtreamAccountFromForm(
            XtreamFormInput(
                serverUrl = "http://h:80", username = "u", password = "p2", name = "Renamed",
                epgUrl = "http://new.epg", dnsProvider = "google", autoRefreshHours = 72,
            ),
        )!!
        val merged = carryPlaylistOptions(old, candidate, keepCandidateFormOptions = true)
        // form-owned option fields: candidate wins
        assertEquals("http://new.epg", merged.epgUrl)
        assertEquals("google", merged.dnsProvider)
        assertEquals(72, merged.autoRefreshHours)
        // not-on-form fields: carried from old
        assertEquals(setOf(CONTENT_TYPE_LIVE), merged.contentTypes)
        assertEquals(CategorySelections(live = listOf("1")), merged.categorySelections)
    }

    @Test
    fun categorySelectionsColumnDecodesLeniently() {
        assertTrue(parseCategorySelections(null).allNull)
        assertTrue(parseCategorySelections(JsonNull).allNull)
        assertTrue(parseCategorySelections(JsonPrimitive(42)).allNull)

        val parsed = parseCategorySelections(json.parseToJsonElement("""{"live":["5","6"],"series":null}"""))
        assertEquals(listOf("5", "6"), parsed.live)
        assertNull(parsed.movies)
        assertNull(parsed.series)

        // Wrong-typed shapes never throw, they just mean "all".
        assertNull(parseCategorySelections(json.parseToJsonElement("""{"live":"oops"}""")).live)
        val empty = parseCategorySelections(json.parseToJsonElement("""{"movies":[]}"""))
        assertEquals(emptyList(), empty.movies)   // explicit empty = none selected, NOT all
        assertNull(empty.live)
    }

    @Test
    fun typeEnabledAndAllowsCategorySemantics() {
        val acc = base.copy(
            contentTypes = setOf(CONTENT_TYPE_LIVE),
            categorySelections = CategorySelections(live = listOf("1")),
        )
        assertTrue(acc.typeEnabled(CONTENT_TYPE_LIVE))
        assertFalse(acc.typeEnabled(CONTENT_TYPE_MOVIES))
        assertTrue(acc.allowsCategory(CONTENT_TYPE_LIVE, "1"))
        assertFalse(acc.allowsCategory(CONTENT_TYPE_LIVE, "2"))
        assertFalse(acc.allowsCategory(CONTENT_TYPE_LIVE, null))     // listed selection can't match no-category
        assertTrue(acc.allowsCategory(CONTENT_TYPE_MOVIES, "anything"))  // null selection = all
        assertTrue(acc.allowsCategory(CONTENT_TYPE_MOVIES, null))

        // withType/forType round-trip incl. the materialize-then-toggle flow
        val materialized = acc.categorySelections.withType(CONTENT_TYPE_MOVIES, listOf("a", "b"))
        assertEquals(listOf("a", "b"), materialized.forType(CONTENT_TYPE_MOVIES))
        assertEquals(listOf("1"), materialized.forType(CONTENT_TYPE_LIVE))
        assertNull(materialized.withType(CONTENT_TYPE_MOVIES, null).forType(CONTENT_TYPE_MOVIES))
    }
}
