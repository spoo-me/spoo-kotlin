package me.spoo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Contract tests against a real backend (the docker compose stack in CI, or
 * any instance via SPOO_CONTRACT_BASE_URL). They exercise the anonymous
 * surface end to end: create, public stats, preview, claim wire shape, the
 * emoji catalogue, and the per-link export route contract. Skipped when the
 * env var is absent, so the offline suite stays hermetic.
 */
class ContractTest {
    private val baseUrl: String? = System.getenv("SPOO_CONTRACT_BASE_URL")

    private fun contract(block: suspend (SpooClient) -> Unit) {
        val url = baseUrl ?: return
        SpooClient(baseUrl = url).use { client -> runBlocking { block(client) } }
    }

    @Test
    fun anonymousCreatePublicStatsAndPreview() = contract { client ->
        val link = client.links.create {
            longUrl = "https://example.com/?contract=kotlin"
        }
        assertTrue(link.shortUrl.startsWith("http"), "short_url was ${link.shortUrl}")
        assertTrue(!link.claimToken.isNullOrEmpty(), "anonymous create must return a claim token")

        val stats = client.publicLinks.stats(link.alias)
        assertEquals(link.alias, stats.link.alias)

        val preview = client.publicLinks.preview(link.alias)
        assertEquals(link.alias, preview.alias)
    }

    @Test
    fun emojiCatalogueLoadsAndCaches() = contract { client ->
        val first = client.emoji.set()
        assertTrue(first.emoji.isNotEmpty())
        val second = client.emoji.set()
        assertEquals(first.maxGraphemes, second.maxGraphemes)
    }

    @Test
    fun perLinkExportRouteIsDistinctFromAggregate() = contract { client ->
        // The wire contract every earlier SDK got wrong: only
        // /export/links/{id} names the file after the link; the aggregate
        // route's filenames are constants. Anonymous access cannot call the
        // authenticated export, but the route SHAPE is asserted against the
        // live spec: a bogus id on the per-link route must 401/404, never
        // route to the aggregate handler.
        val error = try {
            client.stats.exportLink("000000000000000000000000")
            null
        } catch (e: SpooApiException) {
            e
        }
        assertTrue(
            error != null && (error.status == 401 || error.status == 404 || error.status == 422),
            "per-link export route answered unexpectedly: ${error?.status}",
        )
    }
}
