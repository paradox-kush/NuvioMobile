package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the request storm that got a live portal's Cloudflare to block the user's IP.
 *
 * `get_ordered_list` already returns each item's `cmd` (the create_link input), but the cmd lookups
 * used to THROW IT AWAY and re-page the entire catalog (genre=*, up to MAX_PAGES=200 requests) to find
 * one item again — so a single tap-to-play cost ~200 requests, and browsing a few titles was a DoS.
 *
 * These tests pin the request COUNT, which is the only thing that actually catches a regression here:
 * the feature still "works" when it's hammering the portal, it just gets you banned.
 */
class StalkerRequestCountTest {

    private val requests = mutableListOf<String>()

    /** A fake portal: 3 pages x 2 channels, each row carrying its `cmd` like a real one. */
    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        val type = Regex("type=([^&]+)").find(url)?.groupValues?.get(1)
        requests += "$type/$action"
        when (action) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_ordered_list" -> {
                val p = Regex("[&?]p=([0-9]+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
                if (p <= 3) {
                    val data = listOf((p - 1) * 2 + 1, (p - 1) * 2 + 2).joinToString(",") {
                        """{"id":"$it","name":"Ch $it","cmd":"ffmpeg http://portal/ch/$it"}"""
                    }
                    """{"js":{"total_items":6,"max_page_items":2,"data":[$data]}}"""
                } else {
                    """{"js":{"total_items":6,"max_page_items":2,"data":[]}}"""
                }
            }
            "create_link" -> """{"js":{"cmd":"ffmpeg http://portal/live/999.ts?token=x"}}"""
            else -> """{"js":[]}"""
        }
    }

    private fun account(id: String) = XtreamAccount(
        id = id, name = "portal", baseUrl = "http://portal.test",
        username = "", password = "", sourceType = "stalker",
        macAddress = "00:1A:79:58:B3:A6",
    )

    @AfterTest
    fun tearDown() {
        StalkerClient.sessionFactory = { StalkerSession(it) }
    }

    @Test
    fun `playing a browsed channel costs exactly one request`() = runBlocking {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal) }
        val acc = account("rc-browsed")

        val channels = StalkerClient.liveChannels(acc, "cat1").getOrThrow()
        assertEquals(6, channels.size)                       // 3 pages x 2, all cached with their cmd
        val afterBrowse = requests.size

        val url = StalkerClient.resolveLiveUrl(acc, 5)
        assertEquals("http://portal/live/999.ts?token=x", url)
        // The whole point: create_link and NOTHING else. Pre-fix this re-paged the catalog first.
        assertEquals(1, requests.size - afterBrowse)
        assertEquals("itv/create_link", requests.last())
    }

    @Test
    fun `cold-start play stops paging at the match instead of slurping the catalog`() = runBlocking {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal) }
        val acc = account("rc-cold")

        // Nothing browsed: the lookup must scan, but stop at the page holding the id — not read on.
        val url = StalkerClient.resolveLiveUrl(acc, 1)       // id 1 is on page 1
        assertEquals("http://portal/live/999.ts?token=x", url)
        val lists = requests.count { it == "itv/get_ordered_list" }
        assertEquals(1, lists)                               // stopped after page 1, not all 3 pages
    }
}
