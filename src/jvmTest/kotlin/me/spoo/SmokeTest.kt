package me.spoo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Scheduled production smoke: create a link, read its stats, fetch the
 * emoji catalogue, delete the link. Catches prod-vs-SDK drift that offline
 * tests cannot. Runs only with SPOO_SMOKE_API_KEY set (a dedicated
 * low-scope key); it is not part of the offline suite.
 */
class SmokeTest {
    @Test
    fun prodSmoke() {
        val key = System.getenv("SPOO_SMOKE_API_KEY") ?: return
        val client = SpooClient(
            SpooConfig(apiKey = key, clientTag = "sdk-kotlin-smoke"),
        )
        client.use {
            runBlocking {
                val link = client.links.create {
                    longUrl = "https://example.com/?smoke=${System.currentTimeMillis()}"
                }
                try {
                    assertTrue(link.shortUrl.startsWith("http"), "short_url was ${link.shortUrl}")

                    val stats = client.stats.forLink(link.id)
                    assertEquals(0, stats.summary.totalClicks, "fresh link must have zero clicks")

                    val emoji = client.emoji.set()
                    assertTrue(emoji.emoji.isNotEmpty(), "emoji catalogue must not be empty")
                } finally {
                    client.links.delete(link.id)
                }
            }
        }
    }
}
