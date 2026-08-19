package me.spoo

import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class LinksWireTest {
    @Test
    fun createSendsExactBodyAndDecodes() = runTest {
        val (client, engine) = mockClient { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/shorten", request.url.encodedPath)
            assertEquals("Bearer spoo_test_key", request.headers["Authorization"])
            val body = request.body.toByteArray().decodeToString()
            assertEquals(
                """{"long_url":"https://example.com/launch","alias":"launch",""" +
                    """"alias_type":"alphanumeric","password":"secure@123",""" +
                    """"block_bots":true,"max_clicks":100,""" +
                    """"expire_after":"2027-01-01T00:00:00Z","private_stats":true,""" +
                    """"geo_rules":{"IN":"https://example.in/"},""" +
                    """"meta_tags":{"title":"Launch","color":"#FF5733"}}""",
                body,
            )
            jsonResponse(LINK_BODY, HttpStatusCode.Created)
        }
        val link = client.links.create(
            CreateLinkRequest(
                longUrl = "https://example.com/launch",
                alias = "launch",
                aliasType = AliasKind.ALPHANUMERIC,
                password = "secure@123",
                blockBots = true,
                maxClicks = 100,
                expireAfter = Instant.parse("2027-01-01T00:00:00Z"),
                privateStats = true,
                geoRules = mapOf("IN" to "https://example.in/"),
                metaTags = MetaTags(title = "Launch", color = "#FF5733"),
            ),
        )
        assertEquals("665f0c2f9e7a4b1d2c3d4e5f", link.id)
        assertEquals(LinkStatus.ACTIVE, link.status)
        assertEquals(1704067200, link.createdAt.epochSeconds)
        assertEquals("tok_once", link.claimToken)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun createOmitsUnsetFieldsAndDslBuilds() = runTest {
        val (client, _) = mockClient { request ->
            val body = request.body.toByteArray().decodeToString()
            assertEquals("""{"long_url":"https://example.com/"}""", body)
            jsonResponse(LINK_BODY, HttpStatusCode.Created)
        }
        client.links.create { longUrl = "https://example.com/" }
    }

    @Test
    fun updatePatchTristateWireBytes() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals(HttpMethod.Patch, request.method)
            val body = request.body.toByteArray().decodeToString()
            // password cleared (explicit null), max_clicks set, everything
            // else absent from the body entirely.
            assertEquals("""{"password":null,"max_clicks":500}""", body)
            jsonResponse(
                """{"id":"x","password_set":false,"max_clicks":500,"updated_at":1704067300}""",
            )
        }
        val updated = client.links.update("x") {
            removePassword()
            maxClicks(500)
        }
        assertFalse(updated.passwordSet)
        assertEquals(1704067300, updated.updatedAt.epochSeconds)
    }

    @Test
    fun systemDomainSendsNull() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals(
                """{"domain":null}""",
                request.body.toByteArray().decodeToString(),
            )
            jsonResponse("""{"id":"x","password_set":false,"updated_at":1704067300}""")
        }
        client.links.update("x") { systemDomain() }
    }

    @Test
    fun listFilterIsOneSortedJsonParam() = runTest {
        val (client, _) = mockClient { request ->
            val filter = request.url.parameters["filter"]
            assertEquals(
                """{"passwordSet":true,"search":"promo","status":"ACTIVE"}""",
                filter,
            )
            jsonResponse(
                """{"items":[],"page":1,"pageSize":20,"total":0,"hasNext":false,""" +
                    """"sortBy":"created_at","sortOrder":"descending"}""",
            )
        }
        client.links.list(
            ListLinksRequest(
                status = SettableStatus.ACTIVE,
                passwordSet = true,
                search = "promo",
            ),
        )
    }

    @Test
    fun claimWireFieldIsToken() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals(
                """{"claims":[{"url_id":"665f0c2f9e7a4b1d2c3d4e5f","token":"tok_once"}]}""",
                request.body.toByteArray().decodeToString(),
            )
            jsonResponse(
                """{"results":[{"url_id":"665f0c2f9e7a4b1d2c3d4e5f","status":"claimed"}],"claimed":1}""",
            )
        }
        val outcome = client.links.claim(
            listOf(ClaimRequest(urlId = "665f0c2f9e7a4b1d2c3d4e5f", token = "tok_once")),
        )
        assertEquals(1, outcome.claimed)
        assertEquals(ClaimStatus.CLAIMED, outcome.results.single().status)
    }

    @Test
    fun bulkExpiryClearSendsNull() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals(
                """{"ids":["a"],"expire_after":null}""",
                request.body.toByteArray().decodeToString(),
            )
            jsonResponse(
                """{"summary":{"total":1,"succeeded":1,"failed":0},"results":[{"id":"a","ok":true}]}""",
            )
        }
        client.links.bulkSetExpiry(listOf("a"), null)
    }

    @Test
    fun bulkPartialSuccessIsData() = runTest {
        val (client, _) = mockClient { _ ->
            jsonResponse(
                """{"summary":{"total":2,"succeeded":1,"failed":1},"results":[""" +
                    """{"id":"a","alias":"a","ok":true},""" +
                    """{"id":"b","ok":false,"error_code":"not_found","error":"no such URL"}]}""",
            )
        }
        val outcome = client.links.bulkSetStatus(listOf("a", "b"), SettableStatus.INACTIVE)
        assertEquals(1, outcome.summary.failed)
        assertEquals(BulkErrorCode.NOT_FOUND, outcome.results[1].errorCode)
    }

    @Test
    fun unknownEnumValuesDoNotBreakDecoding() = runTest {
        val (client, _) = mockClient { _ ->
            jsonResponse(
                """{"id":"x","status":"QUARANTINED","password_set":false,"brand_new_field":{"nested":true}}""",
            )
        }
        val link = client.links.get("x")
        assertEquals(LinkStatus.UNKNOWN, link.status)
    }

    @Test
    fun pathSegmentsCannotRewriteTheTarget() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals("/api/v1/urls/x%3Fy", request.url.fullPath)
            jsonResponse("""{"id":"x?y","password_set":false}""")
        }
        client.links.get("x?y")
    }

    @Test
    fun checkAliasDecodesTypedReason() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals("taken-one", request.url.parameters["alias"])
            jsonResponse("""{"available":false,"reason":"taken"}""")
        }
        val check = client.links.checkAlias("taken-one")
        assertFalse(check.available)
        assertEquals(AliasIssue.TAKEN, check.reason)
    }

    @Test
    fun timestampsNormalizeAcrossWireFormats() = runTest {
        val (client, _) = mockClient { _ -> jsonResponse(LINK_ITEM_BODY) }
        val item = client.links.get("665f0c2f9e7a4b1d2c3d4e5f")
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), item.createdAt)
        assertEquals(1767225599, item.expireAfter?.epochSeconds)
        assertTrue(item.alias == "launch")
    }
}
