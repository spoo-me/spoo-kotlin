package me.spoo

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import me.spoo.internal.Transport
import me.spoo.oauth.OAuth
import me.spoo.oauth.Session

/**
 * The spoo.me API client.
 *
 * ```kotlin
 * val spoo = SpooClient(apiKey = "spoo_your_api_key")
 * val link = spoo.links.create { longUrl = "https://example.com/launch" }
 * println(link.shortUrl)
 * ```
 *
 * Safe to share across coroutines. Closing the client releases the owned
 * engine; injected engines stay the caller's to manage.
 */
public class SpooClient(
    private val config: SpooConfig,
) : AutoCloseable {
    /**
     * Convenience constructor for the common cases:
     * `SpooClient(apiKey = "spoo_...")`, `SpooClient()` for anonymous use,
     * or `SpooClient(session = session)` for Sign in with Spoo.
     */
    public constructor(
        apiKey: String? = null,
        session: Session? = null,
        baseUrl: String = DEFAULT_BASE_URL,
    ) : this(SpooConfig(apiKey = apiKey, session = session, baseUrl = baseUrl))

    init {
        require(config.baseUrl.startsWith("http://") || config.baseUrl.startsWith("https://")) {
            "baseUrl must be an http or https URL, got ${config.baseUrl}"
        }
    }

    @PublishedApi
    internal val transport: Transport = Transport(config, config.engine)

    /** Link management: create, list, update, delete, bulk, claiming. */
    public val links: Links = Links(transport)

    /** Click statistics and exports. */
    public val stats: Stats = Stats(transport)

    /** Public, unauthenticated link surfaces: stats pages and previews. */
    public val publicLinks: PublicLinks = PublicLinks(transport)

    /** The emoji-alias catalogue and its policy caps. */
    public val emoji: Emoji = Emoji(transport)

    /** Identity: who this client is signed in as. */
    public val auth: Auth = Auth(transport)

    /** Sign in with Spoo: PKCE, device-code exchange, refreshing sessions. */
    public val oauth: OAuth = OAuth(transport, config.baseUrl.trimEnd('/'))

    /**
     * Raw typed GET with the client's auth, retries, timeout and error
     * mapping applied: the supported pressure valve for endpoints this SDK
     * does not cover yet. Needing it is a signal worth an issue on the SDK.
     */
    public suspend inline fun <reified T> get(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): T {
        val response = transport.send(HttpMethod.Get, path, query)
        return transport.decode(response)
    }

    /** Raw typed POST. See [get]. */
    public suspend inline fun <reified T> post(path: String, body: JsonElement? = null): T {
        val response = transport.send(HttpMethod.Post, path, body = body)
        return transport.decode(response)
    }

    /** Raw typed PATCH. See [get]. */
    public suspend inline fun <reified T> patch(path: String, body: JsonElement? = null): T {
        val response = transport.send(HttpMethod.Patch, path, body = body)
        return transport.decode(response)
    }

    /** Raw typed DELETE. See [get]. */
    public suspend inline fun <reified T> delete(path: String): T {
        val response = transport.send(HttpMethod.Delete, path)
        return transport.decode(response)
    }

    override fun close() {
        transport.close()
    }
}
