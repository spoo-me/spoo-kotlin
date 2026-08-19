package me.spoo.internal

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.spoo.AuthenticationException
import me.spoo.ContentBlockedException
import me.spoo.NotFoundException
import me.spoo.PayloadTooLargeException
import me.spoo.PermissionException
import me.spoo.RateLimitException
import me.spoo.RateLimitInfo
import me.spoo.SpooApiException
import me.spoo.SpooConfig
import me.spoo.SpooDecodeException
import me.spoo.SpooIOException
import me.spoo.UnknownApiException
import me.spoo.ValidationException

/**
 * The longest server-mandated wait the client will sit through. A
 * Retry-After beyond this is not worth blocking a caller for: the 429/503
 * surfaces instead, with the full wait readable on the exception.
 */
internal val MAX_RETRY_AFTER: Duration = 60.seconds

@PublishedApi
internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

@PublishedApi
internal class Transport(
    private val config: SpooConfig,
    engine: io.ktor.client.engine.HttpClientEngine?,
) : AutoCloseable {
    private val ownsEngine = engine == null

    internal val http: HttpClient = HttpClient(engine ?: defaultEngine()) {
        expectSuccess = false
        install(ContentNegotiation) { json(WireJson) }
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeout.inWholeMilliseconds
        }
        install(HttpRequestRetry) {
            maxRetries = config.maxRetries
            retryIf(config.maxRetries) { request, response ->
                if (!retryableStatus(request.method, response.status.value)) {
                    false
                } else {
                    // A server-mandated wait beyond the ceiling is not worth
                    // blocking for: surface the response instead.
                    val mandated = parseRetryAfter(
                        response.headers["Retry-After"],
                        Clock.System.now(),
                    )
                    mandated == null || mandated <= MAX_RETRY_AFTER
                }
            }
            retryOnExceptionIf(config.maxRetries) { request, cause ->
                cause !is CancellationException && idempotent(request.method)
            }
            delayMillis(respectRetryAfterHeader = false) { attempt ->
                val mandated = response?.headers?.get("Retry-After")?.let {
                    parseRetryAfter(it, Clock.System.now())
                }
                (mandated ?: computedBackoff(attempt)).inWholeMilliseconds
            }
        }
    }

    private val clientTag: String = config.clientTag ?: "sdk-kotlin/$SPOO_SDK_VERSION"
    private val base: String = config.baseUrl.trimEnd('/')

    /**
     * Execute one request with auth, the client tag, retries and error
     * mapping. A 401 on a refreshing session gets one rotation and an
     * immediate replay, outside the retry budget.
     */
    @PublishedApi
    internal suspend fun send(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        headers: List<Pair<String, String>> = emptyList(),
        authenticated: Boolean = true,
    ): HttpResponse {
        var refreshed = false
        while (true) {
            val session = config.session
            val generation: Long?
            val bearer: String?
            when {
                !authenticated -> {
                    bearer = null
                    generation = null
                }
                session != null -> {
                    val fresh = session.freshToken(this)
                    bearer = fresh.first
                    generation = fresh.second
                }
                else -> {
                    bearer = config.apiKey
                    generation = null
                }
            }
            val response = executeRaw(method, path, query, body, headers, bearer)
            if (response.status.value == 401 && session != null && authenticated && !refreshed) {
                session.refreshStale(this, generation ?: 0)
                refreshed = true
                continue
            }
            if (response.status.isSuccess() || response.status.value == 304) {
                return response
            }
            throw mapError(response)
        }
    }

    private suspend fun executeRaw(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>>,
        body: JsonElement?,
        extraHeaders: List<Pair<String, String>>,
        bearer: String?,
    ): HttpResponse {
        val builder = HttpRequestBuilder().apply {
            this.method = method
            url("$base$path")
            query.forEach { (key, value) -> url.parameters.append(key, value) }
            header("X-Spoo-Client", clientTag)
            extraHeaders.forEach { (name, value) -> header(name, value) }
            bearer?.let { bearerAuth(it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return try {
            http.request(builder)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Throwable) {
            throw SpooIOException(cause.message ?: "request failed", cause)
        }
    }

    /** Decode a JSON body, mapping decode failures to [SpooDecodeException]. */
    @PublishedApi
    internal suspend inline fun <reified T> decode(response: HttpResponse): T {
        val text = response.bodyAsText()
        return try {
            WireJson.decodeFromString<T>(text)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Throwable) {
            throw SpooDecodeException("failed to decode response body", cause)
        }
    }

    override fun close() {
        if (ownsEngine) http.close()
    }
}

internal suspend fun mapError(response: HttpResponse): SpooApiException {
    val status = response.status.value
    val headerCode = response.headers["X-Error-Code"]
    val requestId = response.headers["X-Request-ID"]
    val rateLimit = RateLimitInfo(
        limit = response.headers["X-RateLimit-Limit"]?.toLongOrNull(),
        remaining = response.headers["X-RateLimit-Remaining"]?.toLongOrNull(),
        reset = response.headers["X-RateLimit-Reset"]?.toLongOrNull()
            ?.let(kotlin.time.Instant::fromEpochSeconds),
        retryAfter = parseRetryAfter(response.headers["Retry-After"], Clock.System.now()),
    )
    val text = try {
        response.bodyAsText()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        ""
    }
    val envelope = runCatching { WireJson.decodeFromString<ErrorEnvelope>(text) }.getOrNull()
    val code = envelope?.code?.takeIf { it.isNotEmpty() } ?: headerCode.orEmpty()
    // A non-envelope body is never the message: proxy-composed HTML stays
    // on rawBody where callers can inspect it without rendering it.
    val message = envelope?.error ?: "HTTP $status"
    val rawBody = if (envelope == null && text.isNotEmpty()) text else null
    val field = envelope?.field
    return when (status) {
        429 -> RateLimitException(status, code, message, field, requestId, rateLimit, rawBody)
        401 -> AuthenticationException(status, code, message, field, requestId, rateLimit, rawBody)
        403 -> PermissionException(status, code, message, field, requestId, rateLimit, rawBody)
        404 -> NotFoundException(status, code, message, field, requestId, rateLimit, rawBody)
        451 -> ContentBlockedException(status, code, message, field, requestId, rateLimit, rawBody)
        413 -> PayloadTooLargeException(status, code, message, field, requestId, rateLimit, rawBody)
        400, 409, 422 -> ValidationException(status, code, message, field, requestId, rateLimit, rawBody)
        else -> UnknownApiException(status, code, message, field, requestId, rateLimit, rawBody)
    }
}

@kotlinx.serialization.Serializable
internal class ErrorEnvelope(
    val error: String,
    val code: String = "",
    val field: String? = null,
)

/**
 * Whether a failed status is worth another attempt. Idempotent methods
 * retry the full transient set; POST and PATCH retry only where the server
 * provably did no work, so a replay can never duplicate a link.
 */
internal fun retryableStatus(method: HttpMethod, status: Int): Boolean {
    val transient = status == 408 || status == 429 || status == 500 ||
        status == 502 || status == 503 || status == 504
    return if (idempotent(method)) transient else status == 429 || status == 503
}

internal fun idempotent(method: HttpMethod): Boolean =
    method == HttpMethod.Get || method == HttpMethod.Put ||
        method == HttpMethod.Delete || method == HttpMethod.Head

/** Jittered exponential backoff: 0.5s, 1s, 2s ... capped at 8s, 50-100%. */
internal fun computedBackoff(attempt: Int): Duration {
    val exp = (attempt - 1).coerceIn(0, 16)
    val baseMs = (500L shl exp).coerceAtMost(8_000L)
    val jittered = baseMs / 2 + Random.nextLong(baseMs / 2 + 1)
    return jittered.milliseconds
}
