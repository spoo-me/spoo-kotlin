package me.spoo

import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import me.spoo.oauth.Session
import me.spoo.oauth.TokenPair
import me.spoo.oauth.generatePkcePair
import me.spoo.oauth.generateState
import me.spoo.internal.sha256

private val JwtBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

private fun jwtWithExp(exp: Long): String {
    val payload = JwtBase64.encode("""{"exp":$exp}""".encodeToByteArray())
    return "header.$payload.sig"
}

private fun farFuture(): Long = Clock.System.now().epochSeconds + 3600

class OAuthTest {
    @Test
    fun pkcePairMatchesRfc7636S256() {
        val pair = generatePkcePair()
        assertEquals(64, pair.verifier.length)
        assertTrue(pair.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        val expected = JwtBase64.encode(sha256(pair.verifier.encodeToByteArray()))
        assertEquals(expected, pair.challenge)
        assertFalse(pair.challenge.contains('='))
        assertFalse(pair.toString().contains(pair.verifier), "toString must redact the verifier")
        assertTrue(generateState().length == 32)
    }

    @Test
    fun authorizationUrlCarriesThePkceContract() {
        val client = SpooClient()
        val url = client.oauth.authorizationUrl(
            appId = "app_123",
            state = "state_abc",
            codeChallenge = "challenge_xyz",
            redirectUri = "http://127.0.0.1:8000/callback",
        )
        assertTrue(url.startsWith("https://spoo.me/auth/device/login?"))
        assertTrue("app_id=app_123" in url)
        assertTrue("state=state_abc" in url)
        assertTrue("code_challenge=challenge_xyz" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A8000%2Fcallback" in url)
        client.close()
    }

    @Test
    fun exchangeCodeSendsVerifierAndRedactsTokens() = runTest {
        val (client, _) = mockClient { request ->
            assertEquals(
                """{"code":"one-time-code","code_verifier":"the-verifier"}""",
                request.body.toByteArray().decodeToString(),
            )
            jsonResponse(
                """{"access_token":"${jwtWithExp(farFuture())}","refresh_token":"refresh_1",""" +
                    """"user":{"id":"u1","email_verified":true,"plan":"free","password_set":false}}""",
            )
        }
        val tokens = client.oauth.exchangeCode("one-time-code", "the-verifier")
        assertEquals("refresh_1", tokens.refreshToken)
        assertEquals("u1", tokens.user.id)
        assertFalse(tokens.toString().contains("refresh_1"), "toString must redact tokens")
    }

    @Test
    fun sessionRefreshesOnceOn401AndReplays() = runTest {
        val access1 = jwtWithExp(farFuture())
        val access2 = jwtWithExp(farFuture() + 1)
        val rotations = mutableListOf<String>()
        val session = Session(
            TokenPair(access1, "refresh_1"),
            onRefresh = { rotations.add(it.refreshToken) },
        )
        val (client, engine) = mockClient(SpooConfig(session = session)) { request ->
            when {
                request.url.encodedPath == "/auth/device/refresh" -> {
                    assertEquals(
                        """{"refresh_token":"refresh_1"}""",
                        request.body.toByteArray().decodeToString(),
                    )
                    jsonResponse(
                        """{"access_token":"$access2","refresh_token":"refresh_2"}""",
                    )
                }
                request.headers["Authorization"] == "Bearer $access1" ->
                    jsonResponse(
                        """{"error":"token revoked","code":"authentication_error"}""",
                        HttpStatusCode.Unauthorized,
                    )
                else -> jsonResponse("""{"id":"x","password_set":false}""")
            }
        }
        client.links.get("x")
        assertEquals(listOf("refresh_2"), rotations, "rotation persisted exactly once")
        assertEquals(3, engine.requestHistory.size)
    }

    @Test
    fun concurrentRequestsShareOneRefresh() = runTest {
        // An expired access token forces every request through the
        // proactive refresh path at once.
        val expired = jwtWithExp(Clock.System.now().epochSeconds - 10)
        var refreshes = 0
        val session = Session(TokenPair(expired, "refresh_1"))
        val (client, _) = mockClient(SpooConfig(session = session)) { request ->
            if (request.url.encodedPath == "/auth/device/refresh") {
                refreshes += 1
                jsonResponse(
                    """{"access_token":"${jwtWithExp(farFuture())}","refresh_token":"refresh_2"}""",
                )
            } else {
                jsonResponse("""{"id":"x","password_set":false}""")
            }
        }
        coroutineScope {
            (1..8).map { async { client.links.get("x") } }.forEach { it.await() }
        }
        assertEquals(1, refreshes, "a stampede must share a single rotation")
    }

    @Test
    fun deadRefreshTokenIsSessionExpired() = runTest {
        val expired = jwtWithExp(Clock.System.now().epochSeconds - 10)
        val session = Session(TokenPair(expired, "refresh_dead"))
        val (client, _) = mockClient(SpooConfig(session = session)) { request ->
            assertEquals("/auth/device/refresh", request.url.encodedPath)
            jsonResponse(
                """{"error":"refresh token revoked","code":"authentication_error"}""",
                HttpStatusCode.Unauthorized,
            )
        }
        assertFailsWith<SessionExpiredException> { client.links.get("x") }
    }

    @Test
    fun invalidateForcesARefreshBeforeTheNextRequest() = runTest {
        val valid = jwtWithExp(farFuture())
        var refreshes = 0
        val session = Session(TokenPair(valid, "refresh_1"))
        val (client, _) = mockClient(SpooConfig(session = session)) { request ->
            if (request.url.encodedPath == "/auth/device/refresh") {
                refreshes += 1
                jsonResponse(
                    """{"access_token":"${jwtWithExp(farFuture())}","refresh_token":"refresh_2"}""",
                )
            } else {
                jsonResponse("""{"id":"x","password_set":false}""")
            }
        }
        session.invalidate()
        client.links.get("x")
        assertEquals(1, refreshes)
    }
}
