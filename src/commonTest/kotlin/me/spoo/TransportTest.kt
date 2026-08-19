package me.spoo

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class TransportTest {
    @Test
    fun clientTagHeaderIsSentFromTheFirstRequest() = runTest {
        val (client, _) = mockClient { request ->
            val tag = request.headers["X-Spoo-Client"]
            assertTrue(tag != null && tag.startsWith("sdk-kotlin/"), "tag was $tag")
            assertTrue(!tag.endsWith("/dev"), "the version constant must never rot to dev")
            jsonResponse("""{"id":"x","password_set":false}""")
        }
        client.links.get("x")
    }

    @Test
    fun getRetriesTransient500ThenSucceeds() = runTest {
        var calls = 0
        val (client, engine) = mockClient { _ ->
            calls += 1
            if (calls == 1) {
                jsonResponse("""{"error":"boom","code":"internal"}""", HttpStatusCode.InternalServerError)
            } else {
                jsonResponse("""{"id":"x","password_set":false}""")
            }
        }
        client.links.get("x")
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun postDoesNotRetry500() = runTest {
        val (client, engine) = mockClient { _ ->
            jsonResponse("""{"error":"exploded","code":"internal"}""", HttpStatusCode.InternalServerError)
        }
        val error = assertFailsWith<UnknownApiException> {
            client.links.create { longUrl = "https://example.com/" }
        }
        assertEquals(500, error.status)
        assertEquals(1, engine.requestHistory.size, "a replayed POST could duplicate a link")
    }

    @Test
    fun postRetries429WhereServerDidNoWork() = runTest {
        var calls = 0
        val (client, engine) = mockClient { _ ->
            calls += 1
            if (calls == 1) {
                jsonResponse(
                    """{"error":"slow down","code":"rate_limit_exceeded"}""",
                    HttpStatusCode.TooManyRequests,
                    mapOf("Retry-After" to "1"),
                )
            } else {
                jsonResponse(LINK_BODY, HttpStatusCode.Created)
            }
        }
        client.links.create { longUrl = "https://example.com/" }
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun retryAfterBeyondTheCeilingSurfacesImmediately() = runTest {
        val (client, engine) = mockClient { _ ->
            jsonResponse(
                """{"error":"slow down","code":"rate_limit_exceeded"}""",
                HttpStatusCode.TooManyRequests,
                mapOf("Retry-After" to "86400"),
            )
        }
        val error = assertFailsWith<RateLimitException> { client.links.get("x") }
        assertEquals(1, engine.requestHistory.size, "must not park the caller for a day")
        assertEquals(
            86400.seconds,
            error.rateLimit.retryAfter,
            "the full mandated wait stays readable on the exception",
        )
    }

    @Test
    fun retryAfterHttpDateFormIsHonored() = runTest {
        // The date form must parse (the Python SDK once crashed here); a
        // past date means no wait and the retry proceeds immediately.
        var calls = 0
        val (client, engine) = mockClient { _ ->
            calls += 1
            if (calls == 1) {
                jsonResponse(
                    """{"error":"slow down","code":"rate_limit_exceeded"}""",
                    HttpStatusCode.TooManyRequests,
                    mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT"),
                )
            } else {
                jsonResponse("""{"id":"x","password_set":false}""")
            }
        }
        client.links.get("x")
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun envelopeErrorMapsFieldsAndRateLimit() = runTest {
        val (client, _) = mockClient { _ ->
            jsonResponse(
                """{"error":"alias is taken","code":"conflict","field":"alias"}""",
                HttpStatusCode.UnprocessableEntity,
                mapOf(
                    "X-Request-ID" to "req-123",
                    "X-RateLimit-Limit" to "50",
                    "X-RateLimit-Remaining" to "49",
                    "X-RateLimit-Reset" to "1767225599",
                ),
            )
        }
        val error = assertFailsWith<ValidationException> {
            client.links.create { longUrl = "https://example.com/"; alias = "taken" }
        }
        assertEquals("conflict", error.code)
        assertEquals("alias", error.field)
        assertEquals("req-123", error.requestId)
        assertEquals(50, error.rateLimit.limit)
        assertEquals(1767225599, error.rateLimit.reset?.epochSeconds)
        assertNull(error.rawBody, "envelope bodies are not preserved raw")
    }

    @Test
    fun nonEnvelopeBodyIsNeverTheMessage() = runTest {
        val html = "<html><body><h1>502 Bad Gateway</h1></body></html>"
        val (client, _) = mockClient { request ->
            respond(
                html,
                HttpStatusCode.BadGateway,
                headersOfJson("X-Error-Code" to "bad_gateway", "Content-Type" to "text/html"),
            )
        }
        val error = assertFailsWith<UnknownApiException> { client.links.get("x") }
        assertEquals("HTTP 502", error.message)
        assertEquals("bad_gateway", error.code, "the X-Error-Code header is the fallback")
        assertEquals(html, error.rawBody)
    }

    @Test
    fun the401TrichotomyIsTyped() = runTest {
        val (client, _) = mockClient { _ ->
            jsonResponse(
                """{"error":"password required","code":"password_required"}""",
                HttpStatusCode.Unauthorized,
                mapOf("X-Error-Code" to "password_required"),
            )
        }
        val error = assertFailsWith<AuthenticationException> { client.links.get("locked") }
        assertTrue(error.isPasswordRequired)

        val (plain, _) = mockClient { _ ->
            jsonResponse(
                """{"error":"missing token","code":"authentication_error"}""",
                HttpStatusCode.Unauthorized,
            )
        }
        val unauthorized = assertFailsWith<AuthenticationException> { plain.links.get("x") }
        assertTrue(!unauthorized.isPasswordRequired)
    }

    @Test
    fun escapeHatchReusesAuthAndErrorMapping() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals("Bearer spoo_test_key", request.headers["Authorization"])
            assertEquals("v", request.url.parameters["k"])
            jsonResponse("""{"answer":42}""")
        }

        @kotlinx.serialization.Serializable
        data class Answer(val answer: Int)

        val got: Answer = client.get("/api/v1/some/new/endpoint", listOf("k" to "v"))
        assertEquals(42, got.answer)

        val (failing, _) = mockClient { _ ->
            jsonResponse("""{"error":"nope","code":"not_found"}""", HttpStatusCode.NotFound)
        }
        val error = assertFailsWith<SpooApiException> {
            failing.post<Answer>("/api/v1/some/new/endpoint")
        }
        assertIs<NotFoundException>(error)
    }
}
