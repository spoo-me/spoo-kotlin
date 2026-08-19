package me.spoo

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

private val STATS_BODY = """
    {
      "scope": "all",
      "filters": {"browser": ["Chrome"]},
      "group_by": ["time"],
      "timezone": "UTC",
      "time_range": {"start_date": "2026-01-01T00:00:00Z", "end_date": "2026-01-08T00:00:00Z"},
      "summary": {
        "total_clicks": 120,
        "unique_clicks": 80,
        "first_click": "2026-01-01T10:00:00Z",
        "avg_redirection_time": 42.5
      },
      "metrics": {
        "clicks_by_time": [
          {"time": "2026-01-01", "clicks": 60, "clicks_percentage": 50.0},
          {"time": "2026-01-02", "clicks": 60, "clicks_percentage": 50.0}
        ]
      },
      "time_bucket_info": {
        "strategy": "daily",
        "mongo_format": "%Y-%m-%d",
        "display_format": "MMM D",
        "timezone": "UTC"
      }
    }
""".trimIndent()

class StatsAndPaginationTest {
    @Test
    fun accountStatsQueryEncoding() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals("time,browser", request.url.parameters["group_by"])
            assertEquals("clicks,unique_clicks", request.url.parameters["metrics"])
            assertEquals("Asia/Kolkata", request.url.parameters["timezone"])
            assertEquals(
                """{"browser":["Chrome"],"url_id":["665f0c2f9e7a4b1d2c3d4e5f"]}""",
                request.url.parameters["filters"],
            )
            jsonResponse(STATS_BODY)
        }
        val report = client.stats.account(
            AccountStatsRequest(
                query = StatsQuery(
                    groupBy = listOf(Dimension.TIME, Dimension.BROWSER),
                    metrics = listOf(Metric.CLICKS, Metric.UNIQUE_CLICKS),
                    timezone = "Asia/Kolkata",
                    filters = mapOf(FilterDimension.BROWSER to listOf("Chrome")),
                ),
                urlIds = listOf("665f0c2f9e7a4b1d2c3d4e5f"),
            ),
        )
        assertEquals(120, report.summary.totalClicks)
        assertEquals(2, report.metrics.getValue("clicks_by_time").size)
        assertEquals("daily", report.timeBucketInfo?.strategy)
        assertEquals(StatsScope.ALL, report.scope)
    }

    @Test
    fun linkStatsCarriesIdentity() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals("/api/v1/stats/links/665f0c2f9e7a4b1d2c3d4e5f", request.url.encodedPath)
            val body = STATS_BODY.trimEnd().removeSuffix("}") +
                ""","url_id":"665f0c2f9e7a4b1d2c3d4e5f","alias":"launch"}"""
            jsonResponse(body)
        }
        val report = client.stats.forLink("665f0c2f9e7a4b1d2c3d4e5f")
        assertEquals("launch", report.alias)
        assertEquals(80, report.summary.uniqueClicks)
    }

    @Test
    fun exportUsesServerFilenameWhenSafe() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals("/api/v1/export/links/665f0c2f9e7a4b1d2c3d4e5f", request.url.encodedPath)
            assertEquals("json", request.url.parameters["format"])
            jsonResponse(
                """{"rows":[]}""",
                extraHeaders = mapOf(
                    "Content-Disposition" to "attachment; filename=\"launch-stats.json\"",
                ),
            )
        }
        val export = client.stats.exportLink("665f0c2f9e7a4b1d2c3d4e5f", ExportFormat.JSON)
        assertEquals("launch-stats.json", export.filename)
        assertEquals("""{"rows":[]}""", export.bytes().decodeToString())
    }

    @Test
    fun hostileExportFilenamesFallBack() = runTest {
        val cases = listOf(
            "attachment; filename=\"../../../evil.json\"",
            "attachment; filename=\"/tmp/absolute-evil.json\"",
            "attachment; filename*=utf-8''%2e%2e%2f%2e%2e%2fesc.json",
            "attachment; filename=\"..\"",
        )
        for (disposition in cases) {
            val (client, _) = mockClient { _ ->
                jsonResponse("{}", extraHeaders = mapOf("Content-Disposition" to disposition))
            }
            val export = client.stats.export(ExportFormat.JSON)
            assertTrue(
                '/' !in export.filename && '\\' !in export.filename &&
                    export.filename != ".." && export.filename.isNotEmpty(),
                "unsafe filename escaped for $disposition: ${export.filename}",
            )
        }
    }

    @Test
    fun csvFallbackExtensionIsZip() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals("csv", request.url.parameters["format"])
            jsonResponse("PK")
        }
        val export = client.stats.export(ExportFormat.CSV)
        assertEquals("spoo-export.zip", export.filename)
    }

    @Test
    fun paginationWalksAndFlattens() = runTest {
        fun item(id: String) = """{"id":"$id","alias":"$id","password_set":false}"""
        val (client, engine) = mockClient { request ->
            when (request.url.parameters["page"]) {
                "1" -> jsonResponse(
                    """{"items":[${item("a")},${item("b")}],"page":1,"pageSize":2,""" +
                        """"total":3,"hasNext":true,"sortBy":"created_at","sortOrder":"descending"}""",
                )
                else -> jsonResponse(
                    """{"items":[${item("c")}],"page":2,"pageSize":2,"total":3,""" +
                        """"hasNext":false,"sortBy":"created_at","sortOrder":"descending"}""",
                )
            }
        }
        val ids = client.links
            .listPaginated(ListLinksRequest(pageSize = 2))
            .items()
            .toList()
            .map { it.id }
        assertEquals(listOf("a", "b", "c"), ids)
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun publicStatsPasswordSwitchesToPost() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals(io.ktor.http.HttpMethod.Post, request.method)
            jsonResponse(
                """{"generation":"v1","link":{"alias":"locked","short_url":"https://spoo.me/locked",""" +
                    """"status":"active","block_bots":true,"password_protected":true},"stats":{}}""",
            )
        }
        val stats = client.publicLinks.stats("locked", password = "hunter@22")
        assertTrue(stats.link.passwordProtected)
        assertEquals(Generation.V1, stats.generation)
    }

    @Test
    fun emojiSetRevalidatesWithEtag() = runTest {
        var calls = 0
        val body = """{"accept_max_version":15.1,"generate_max_version":14.0,"max_graphemes":15,""" +
            """"emoji":[{"c":"🚀","n":"rocket","g":"Travel & Places","gen":true}]}"""
        val (client, engine) = mockClient { request ->
            calls += 1
            if (calls == 1) {
                jsonResponse(body, extraHeaders = mapOf("ETag" to "\"v42\""))
            } else {
                assertEquals("\"v42\"", request.headers["If-None-Match"])
                respond("", HttpStatusCode.NotModified)
            }
        }
        val first = client.emoji.set()
        assertEquals(1, first.emoji.size)
        val second = client.emoji.set()
        assertEquals("rocket", second.emoji.single().name)
        assertEquals(2, engine.requestHistory.size)
    }
}
