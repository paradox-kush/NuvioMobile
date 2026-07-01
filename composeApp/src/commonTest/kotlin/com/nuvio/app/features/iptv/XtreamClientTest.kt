package com.nuvio.app.features.iptv

import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamClientTest {

    @Test
    fun decodeBase64DecodesAndPassesThroughGarbage() {
        val enc = "News at Ten".encodeToByteArray().encodeBase64()
        assertEquals("News at Ten", decodeXtreamBase64(enc))
        assertEquals("", decodeXtreamBase64(null))
        assertEquals("", decodeXtreamBase64("   "))
    }

    @Test
    fun flexIntToleratesIntStringAndBoolAcrossPanels() {
        val json = Json { ignoreUnknownKeys = true }
        // panel A: ints
        val a = json.decodeFromString<XtreamLiveStreamDto>(
            """{"name":"A","stream_id":42,"category_id":"3","tv_archive":1}"""
        )
        assertEquals(42, a.streamId)
        assertEquals(1, a.tvArchive)
        // panel B: quoted strings + bool
        val b = json.decodeFromString<XtreamLiveStreamDto>(
            """{"name":"B","stream_id":"42","category_id":"3","tv_archive":true}"""
        )
        assertEquals(42, b.streamId)
        assertEquals(1, b.tvArchive)
        // empty id -> null, no throw
        val c = json.decodeFromString<XtreamLiveStreamDto>("""{"name":"C","stream_id":""}""")
        assertNull(c.streamId)
    }

    @Test
    fun parseXtreamAccountExtractsHostPortCreds() {
        val a = parseXtreamAccount("http://provider.example.com:8080/get.php?username=user1&password=pass1&type=m3u_plus&output=mpegts")!!
        assertEquals("http://provider.example.com:8080", a.baseUrl)
        assertEquals("user1", a.username)
        assertEquals("pass1", a.password)

        val b = parseXtreamAccount("http://panel.example.net/get.php?username=u1&password=p1&type=m3u_plus&output=ts")!!
        assertEquals("http://panel.example.net", b.baseUrl)   // default port omitted
        assertEquals("panel.example.net", b.name)

        assertNull(parseXtreamAccount("http://panel.example.net/get.php?type=m3u_plus"))  // no creds
        assertNull(parseXtreamAccount("not a url"))
    }

    @Test
    fun xtreamAccountFromFieldsNormalizesServerAndRequiresCreds() {
        val a = xtreamAccountFromFields("host.example.org:8080", "demo", "secret", null)!!
        assertEquals("http://host.example.org:8080", a.baseUrl)
        assertEquals("demo", a.username)
        val b = xtreamAccountFromFields("http://panel.example.net/c/", "u", "p", "Home")!!
        assertEquals("http://panel.example.net", b.baseUrl)
        assertEquals("Home", b.name)
        assertNull(xtreamAccountFromFields("http://panel.example.net", "", "p", null))
        assertNull(xtreamAccountFromFields("", "u", "p", null))
    }

    @Test
    fun toProgramConvertsSecondsToMillisAndNowPlaying() {
        val p = XtreamEpgEntryDto(
            title = "VGl0bGU=",            // "Title"
            description = "RGVzYw==",      // "Desc"
            startTimestamp = "1700000000",
            stopTimestamp = "1700003600",
            nowPlaying = 1
        ).toProgram()
        assertEquals("Title", p.title)
        assertEquals("Desc", p.description)
        assertEquals(1700000000_000L, p.startMs)
        assertEquals(1700003600_000L, p.endMs)
        assertTrue(p.nowPlaying)
    }
}
