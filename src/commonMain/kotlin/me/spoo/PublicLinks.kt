package me.spoo

import io.ktor.http.HttpMethod
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.spoo.internal.LenientEnumSerializer
import me.spoo.internal.Transport
import me.spoo.internal.encodeSegment

/** Which generation a link belongs to. */
@Serializable(with = Generation.Companion::class)
public enum class Generation {
    /** A link from the original platform. */
    V1,

    /** A link from the current platform. */
    V2,

    /** A generation this SDK version does not know yet. */
    UNKNOWN,
    ;

    internal companion object : LenientEnumSerializer<Generation>(
        "me.spoo.Generation",
        entries,
        UNKNOWN,
        { it.name.lowercase() },
    )
}

/** Lifecycle state as the public surfaces report it. */
@Serializable(with = PublicStatus.Companion::class)
public enum class PublicStatus {
    /** Redirects are served. */
    ACTIVE,

    /** Redirects are disabled by the owner. */
    INACTIVE,

    /** The link ran out of time or clicks. */
    EXPIRED,

    /** Taken down because the destination was flagged. */
    BLOCKED,

    /** A status this SDK version does not know yet. */
    UNKNOWN,
    ;

    internal companion object : LenientEnumSerializer<PublicStatus>(
        "me.spoo.PublicStatus",
        entries,
        UNKNOWN,
        { it.name.lowercase() },
    )
}

/** Public facts about a link, shown above its charts. */
@Serializable
public data class PublicLinkFacts(
    /** Short code. */
    val alias: String,
    /** Full short URL. */
    @SerialName("short_url") val shortUrl: String,
    /** The destination; withheld while the link is not active. */
    @SerialName("long_url") val longUrl: String? = null,
    /** When the link was created. */
    @SerialName("created_at") val createdAt: Instant? = null,
    /** Lifecycle state. */
    val status: PublicStatus,
    /** Click limit, if one is set. */
    @SerialName("max_clicks") val maxClicks: Long? = null,
    /** Whether known bots are blocked. */
    @SerialName("block_bots") val blockBots: Boolean,
    /** Whether the link is password-protected. */
    @SerialName("password_protected") val passwordProtected: Boolean,
)

/**
 * A public stats page's data. [stats] is the modern stats wire shape kept
 * raw: v1 and v2 links carry different dimension sets, so the shape is
 * deliberately open.
 */
@Serializable
public data class PublicStats(
    /** Which generation the link belongs to. */
    val generation: Generation,
    /** Facts about the link. */
    val link: PublicLinkFacts,
    /** The stats body, raw. */
    val stats: JsonObject,
)

/** A destination URL split into display parts. */
@Serializable
public data class PreviewDestination(
    /** The full destination URL. */
    val url: String,
    /** Its host. */
    val domain: String,
    /** Its path. */
    val path: String,
    /** Whether it is served over https. */
    @SerialName("is_https") val isHttps: Boolean,
)

/** One geo-rule destination group: every rule listed, nothing summarized. */
@Serializable
public data class PreviewGeoDestination(
    /** The full destination URL. */
    val url: String,
    /** Its host. */
    val domain: String,
    /** Its path. */
    val path: String,
    /** Whether it is served over https. */
    @SerialName("is_https") val isHttps: Boolean,
    /** ISO 3166-1 alpha-2 codes this destination serves, sorted. */
    val countries: List<String>,
)

/**
 * Where a short link leads, without following it. [destination] and
 * [geoDestinations] are present only while the link is active and not
 * password-protected: the preview never reveals a destination the redirect
 * would refuse to serve.
 */
@Serializable
public data class Preview(
    /** Which generation the link belongs to. */
    val generation: Generation,
    /** Short code. */
    val alias: String,
    /** Full short URL. */
    @SerialName("short_url") val shortUrl: String,
    /** Lifecycle state. */
    val status: PublicStatus,
    /** When the link was created. */
    @SerialName("created_at") val createdAt: String? = null,
    /** Whether the link is password-protected. */
    @SerialName("password_protected") val passwordProtected: Boolean,
    /** The default destination. */
    val destination: PreviewDestination? = null,
    /** Per-country destinations, when geo rules exist. */
    @SerialName("geo_destinations") val geoDestinations: List<PreviewGeoDestination>? = null,
)

/** Public, unauthenticated link surfaces, from [SpooClient.publicLinks]. */
public class PublicLinks internal constructor(
    private val transport: Transport,
) {
    /**
     * A link's public stats page data. Sends a plain GET, or a POST
     * carrying [password] for password-protected links: one method, both
     * wire forms.
     */
    public suspend fun stats(shortCode: String, password: String? = null): PublicStats {
        val path = "/api/v1/public/stats/${encodeSegment(shortCode)}"
        val response = if (password == null) {
            transport.send(HttpMethod.Get, path, authenticated = false)
        } else {
            transport.send(
                HttpMethod.Post,
                path,
                body = buildJsonObject { put("password", password) },
                authenticated = false,
            )
        }
        return transport.decode(response)
    }

    /** Where a short link leads, without following it. */
    public suspend fun preview(shortCode: String): Preview {
        val response = transport.send(
            HttpMethod.Get,
            "/api/v1/public/preview/${encodeSegment(shortCode)}",
            authenticated = false,
        )
        return transport.decode(response)
    }
}
