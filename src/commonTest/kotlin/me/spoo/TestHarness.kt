package me.spoo

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

internal fun mockClient(
    config: SpooConfig? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<SpooClient, MockEngine> {
    val engine = MockEngine { request -> handler(request) }
    val base = config ?: SpooConfig(apiKey = "spoo_test_key")
    val client = SpooClient(
        SpooConfig(
            apiKey = base.apiKey,
            session = base.session,
            baseUrl = base.baseUrl,
            engine = engine,
            maxRetries = base.maxRetries,
            timeout = base.timeout,
            clientTag = base.clientTag,
        ),
    )
    return client to engine
}

internal fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    extraHeaders: Map<String, String> = emptyMap(),
): HttpResponseData {
    val headers = io.ktor.http.headers {
        append(HttpHeaders.ContentType, "application/json")
        extraHeaders.forEach { (name, value) -> append(name, value) }
    }
    return respond(ByteReadChannel(body), status, headers)
}

internal val LINK_BODY: String = """
    {
      "id": "665f0c2f9e7a4b1d2c3d4e5f",
      "alias": "launch",
      "short_url": "https://spoo.me/launch",
      "long_url": "https://example.com/launch",
      "owner_id": null,
      "created_at": 1704067200,
      "status": "ACTIVE",
      "claim_token": "tok_once"
    }
""".trimIndent()

internal val LINK_ITEM_BODY: String = """
    {
      "id": "665f0c2f9e7a4b1d2c3d4e5f",
      "alias": "launch",
      "created_at": "2026-01-01T00:00:00Z",
      "expire_after": 1767225599,
      "password_set": false
    }
""".trimIndent()

internal fun headersOfJson(vararg pairs: Pair<String, String>) =
    headersOf(*pairs.map { (k, v) -> k to listOf(v) }.toTypedArray())
