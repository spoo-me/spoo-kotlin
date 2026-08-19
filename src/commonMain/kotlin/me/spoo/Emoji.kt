package me.spoo

import io.ktor.http.HttpMethod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.spoo.internal.Transport

/** One accepted emoji, enriched for client-side search. */
@Serializable
public data class EmojiEntry(
    /**
     * Raw canonical emoji character (no U+FE0F variation selector),
     * matching how aliases are stored and echoed.
     */
    @SerialName("c") val character: String,
    /** Human-readable name, lowercased with spaces: the primary search key. */
    @SerialName("n") val name: String,
    /**
     * Canonical Unicode category display name, for picker tabs. Entries
     * arrive sorted by category and within-category order.
     */
    @SerialName("g") val group: String,
    /** Whether this emoji is in the auto-generation pool. */
    @SerialName("gen") val generates: Boolean,
    /** Extra search aliases, when the source lists any. */
    @SerialName("k") val keywords: List<String>? = null,
)

/** The accepted emoji catalogue and its policy caps. */
@Serializable
public data class EmojiSet(
    /** Newest Unicode emoji version a custom alias may use. */
    @SerialName("accept_max_version") val acceptMaxVersion: Double,
    /** Cap for auto-generated aliases (lower, for older platform coverage). */
    @SerialName("generate_max_version") val generateMaxVersion: Double,
    /** Maximum emoji graphemes in one alias. */
    @SerialName("max_graphemes") val maxGraphemes: Int,
    /**
     * Every single-codepoint emoji a user may choose. Skin-tone variants
     * are not enumerated: the base emoji suffices.
     */
    val emoji: List<EmojiEntry>,
)

/** The emoji catalogue, from [SpooClient.emoji]. */
public class Emoji internal constructor(
    private val transport: Transport,
) {
    private val cacheLock = Mutex()
    private var cachedEtag: String? = null
    private var cachedSet: EmojiSet? = null

    /**
     * Fetch the catalogue. The set changes rarely, so the client caches it
     * with the server's ETag and revalidates with If-None-Match: a 304
     * answers from cache without re-downloading the list.
     */
    public suspend fun set(): EmojiSet {
        val (etag, cached) = cacheLock.withLock { cachedEtag to cachedSet }
        val headers = if (etag != null && cached != null) {
            listOf("If-None-Match" to etag)
        } else {
            emptyList()
        }
        val response = transport.send(
            HttpMethod.Get,
            "/api/v1/emoji-set",
            headers = headers,
            authenticated = false,
        )
        if (response.status.value == 304) {
            cacheLock.withLock { cachedSet }?.let { return it }
            // The cache vanished between requests: a 304 has no body, so
            // refetch unconditionally.
            val fresh = transport.send(HttpMethod.Get, "/api/v1/emoji-set", authenticated = false)
            return store(fresh)
        }
        return store(response)
    }

    private suspend fun store(response: io.ktor.client.statement.HttpResponse): EmojiSet {
        val etag = response.headers["ETag"]
        val set = transport.decode<EmojiSet>(response)
        if (etag != null) {
            cacheLock.withLock {
                cachedEtag = etag
                cachedSet = set
            }
        }
        return set
    }
}
