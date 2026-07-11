package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mobile mirror of NuvioTV's StalkerSessionReauthTest — same "works first login, portal error on
 * return" regression, verified through the injectable [httpGet] seam instead of MockWebServer.
 *
 * A Stalker handshake OVERWRITES the MAC's token server-side, so N concurrent browse calls that all
 * hit a stale token must trigger exactly ONE re-handshake — not N. Without the single-flight guard in
 * [StalkerSession.reauthenticate], each stale call re-handshakes, rotating the token out from under
 * the others' retries -> "portal error".
 *
 * Determinism: the first [CONCURRENCY] content calls (the initial batch) are answered stale and held
 * at a barrier, so they ALL enter re-auth together before any completes. Retries are answered valid.
 */
class StalkerSessionReauthTest {

    @Test
    fun `concurrent stale requests trigger exactly one re-handshake`() {
        val handshakes = AtomicInteger(0)
        val contentCalls = AtomicInteger(0)
        val staleGate = CyclicBarrier(CONCURRENCY)

        // Fake portal: branch on the `action` query param. Token-agnostic on purpose — endpoint
        // probing already burns a handshake, so the live token isn't predictable.
        val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
            when (Regex("action=([^&]+)").find(url)?.groupValues?.get(1)) {
                "handshake" -> """{"js":{"token":"T${handshakes.incrementAndGet()}"}}"""
                "get_profile" -> """{"js":{"watchdog_timeout":120}}"""
                else -> if (contentCalls.incrementAndGet() <= CONCURRENCY) {
                    runCatching { staleGate.await(5, TimeUnit.SECONDS) }
                    """{"js":false}"""            // stale token
                } else {
                    """{"js":[]}"""               // empty list = valid (no channels)
                }
            }
        }

        val account = XtreamAccount(
            id = "t", name = "portal", baseUrl = "http://portal.test",
            username = "", password = "", sourceType = "stalker",
            macAddress = "00:1A:79:58:B3:A6",
        )
        val session = StalkerSession(account, fakePortal)

        // A fixed pool of CONCURRENCY threads guarantees every call is genuinely in-flight at once —
        // the blocking barrier in the fake needs that simultaneity to force a real re-auth stampede.
        val pool = Executors.newFixedThreadPool(CONCURRENCY).asCoroutineDispatcher()
        try {
            val results = runBlocking {
                (1..CONCURRENCY).map {
                    async(pool) {
                        runCatching { session.request(mapOf("type" to "itv", "action" to "get_genres")) }
                    }
                }.awaitAll()
            }
            assertEquals(CONCURRENCY, results.count { it.isSuccess })   // every call recovered
            // Initial auth = 2 handshakes (endpoint probe + real handshake). The stampede must add
            // exactly ONE more (the single collapsed re-auth), not one-per-stale-call.
            assertEquals(3, handshakes.get())
        } finally {
            pool.close()
        }
    }

    private companion object { const val CONCURRENCY = 6 }
}
