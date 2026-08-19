package me.spoo

import io.ktor.http.HttpMethod
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.spoo.internal.EpochSecondsSerializer
import me.spoo.internal.LenientEnumSerializer
import me.spoo.internal.Transport
import me.spoo.internal.WireJson
import me.spoo.internal.encodeSegment

/**
 * Lifecycle state of a link. [EXPIRED] and [BLOCKED] are derived or
 * system-set; callers can only set [ACTIVE] and [INACTIVE].
 */
@Serializable(with = LinkStatus.Companion::class)
public enum class LinkStatus {
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

    internal companion object : LenientEnumSerializer<LinkStatus>(
        "me.spoo.LinkStatus",
        entries,
        UNKNOWN,
        { it.name },
    )
}

/** The two statuses a caller may set. */
@Serializable
public enum class SettableStatus {
    @SerialName("ACTIVE")
    ACTIVE,

    @SerialName("INACTIVE")
    INACTIVE,
}

/** Alias style to auto-generate when no explicit alias is given. */
@Serializable
public enum class AliasKind {
    @SerialName("alphanumeric")
    ALPHANUMERIC,

    @SerialName("emoji")
    EMOJI,
}

/**
 * A custom social preview (og:title, og:description, og:image, theme-color)
 * served to link-preview crawlers.
 */
@Serializable
public data class MetaTags(
    /** Preview headline (og:title). Required. */
    val title: String,
    /** og:description. Roughly 200 characters render on most platforms. */
    val description: String? = null,
    /**
     * og:image: an https URL, or a `data:image/...;base64,` URI stored on
     * spoo's CDN. 1200x630 recommended, under 300KB.
     */
    val image: String? = null,
    /** Accent color for Discord embeds, `#RRGGBB`. */
    val color: String? = null,
)

/** Custom social-preview settings stored on a link. */
@Serializable
public data class MetaTagsInfo(
    /** og:title. */
    val title: String,
    /** og:description. */
    val description: String? = null,
    /** og:image URL. */
    val image: String? = null,
    /** Discord embed accent color. */
    val color: String? = null,
    /** Non-fatal quality notes, e.g. an image WhatsApp may drop. */
    val warnings: List<String>? = null,
)

/** A freshly created link (`POST /api/v1/shorten`). */
@Serializable
public data class Link(
    /** The identifier the management endpoints address this link by. */
    val id: String,
    /** Short code. */
    val alias: String,
    /** Full shortened URL, ready to share. */
    @SerialName("short_url") val shortUrl: String,
    /** The destination. */
    @SerialName("long_url") val longUrl: String,
    /** Owning user, null for anonymous creates. */
    @SerialName("owner_id") val ownerId: String? = null,
    /** When the link was created. */
    @SerialName("created_at")
    @Serializable(with = EpochSecondsSerializer::class)
    val createdAt: Instant,
    /** Lifecycle state. */
    val status: LinkStatus,
    /** Whether statistics are owner-only. */
    @SerialName("private_stats") val privateStats: Boolean? = null,
    /** Per-country destination overrides (ISO alpha-2 code to URL). */
    @SerialName("geo_rules") val geoRules: Map<String, String>? = null,
    /** Custom social preview, if configured. */
    @SerialName("meta_tags") val metaTags: MetaTagsInfo? = null,
    /**
     * One-time bearer proof of creation, present only on anonymous creates
     * and shown exactly once. Store it to later attach the link to an
     * account via [Links.claim].
     */
    @SerialName("claim_token") val claimToken: String? = null,
)

/** A link as returned by the list, get and get-by-address endpoints. */
@Serializable
public data class LinkItem(
    /** The identifier the management endpoints address this link by. */
    val id: String,
    /** Short code. */
    val alias: String? = null,
    /** The destination. */
    @SerialName("long_url") val longUrl: String? = null,
    /** Lifecycle state. */
    val status: LinkStatus? = null,
    /** When the link was created. */
    @SerialName("created_at") val createdAt: Instant? = null,
    /** When the link expires, if an expiry is set. */
    @SerialName("expire_after")
    @Serializable(with = EpochSecondsSerializer::class)
    val expireAfter: Instant? = null,
    /** Click limit, if one is set. */
    @SerialName("max_clicks") val maxClicks: Long? = null,
    /** Whether statistics are owner-only. */
    @SerialName("private_stats") val privateStats: Boolean? = null,
    /** Whether known bots are blocked. */
    @SerialName("block_bots") val blockBots: Boolean? = null,
    /** Whether the link is password-protected. */
    @SerialName("password_set") val passwordSet: Boolean,
    /** Lifetime click count. */
    @SerialName("total_clicks") val totalClicks: Long? = null,
    /** Most recent click. */
    @SerialName("last_click") val lastClick: Instant? = null,
    /** Custom domain the link lives on, null for the default namespace. */
    val domain: String? = null,
    /** Per-country destination overrides. */
    @SerialName("geo_rules") val geoRules: Map<String, String>? = null,
    /** Custom social preview, if configured. */
    @SerialName("meta_tags") val metaTags: MetaTagsInfo? = null,
)

/** The link's state after an update (`PATCH /api/v1/urls/{url_id}`). */
@Serializable
public data class UpdatedLink(
    /** The link's identifier. */
    val id: String,
    /** Short code. */
    val alias: String? = null,
    /** The destination. */
    @SerialName("long_url") val longUrl: String? = null,
    /** Lifecycle state. */
    val status: LinkStatus? = null,
    /** Whether the link is password-protected. */
    @SerialName("password_set") val passwordSet: Boolean,
    /** Click limit, if one is set. */
    @SerialName("max_clicks") val maxClicks: Long? = null,
    /** When the link expires, if an expiry is set. */
    @SerialName("expire_after")
    @Serializable(with = EpochSecondsSerializer::class)
    val expireAfter: Instant? = null,
    /** Whether known bots are blocked. */
    @SerialName("block_bots") val blockBots: Boolean? = null,
    /** Whether statistics are owner-only. */
    @SerialName("private_stats") val privateStats: Boolean? = null,
    /** Custom domain the link lives on, null for the default namespace. */
    val domain: String? = null,
    /** Per-country destination overrides. */
    @SerialName("geo_rules") val geoRules: Map<String, String>? = null,
    /** When the update was applied. */
    @SerialName("updated_at")
    @Serializable(with = EpochSecondsSerializer::class)
    val updatedAt: Instant,
    /** Custom social preview, if configured. */
    @SerialName("meta_tags") val metaTags: MetaTagsInfo? = null,
)

/** Confirmation of a deletion. */
@Serializable
public data class DeletedLink(
    /** Confirmation message. */
    val message: String,
    /** The deleted link's identifier. */
    val id: String,
)

/** Why an alias is unavailable. */
@Serializable(with = AliasIssue.Companion::class)
public enum class AliasIssue {
    /** Too short or too long. */
    LENGTH,

    /** Contains characters outside the accepted set. */
    FORMAT,

    /** Reserved by the platform. */
    RESERVED,

    /** Already in use. */
    TAKEN,

    /** Emoji alias contains sequences outside the accepted set. */
    EMOJI_POLICY,

    /** A reason this SDK version does not know yet. */
    UNKNOWN,
    ;

    internal companion object : LenientEnumSerializer<AliasIssue>(
        "me.spoo.AliasIssue",
        entries,
        UNKNOWN,
        { it.name.lowercase() },
    )
}

/** Result of an alias availability check. */
@Serializable
public data class AliasCheck(
    /** Whether the alias passes validation AND is not taken. */
    val available: Boolean,
    /** When unavailable, why. */
    val reason: AliasIssue? = null,
)

/** Counts derived from a bulk operation's result rows. */
@Serializable
public data class BulkSummary(
    /** Unique ids in the request, after deduplication. */
    val total: Long,
    /** Rows that succeeded. */
    val succeeded: Long,
    /** Rows that failed. */
    val failed: Long,
)

/** Machine-readable cause of a per-item bulk failure. */
@Serializable(with = BulkErrorCode.Companion::class)
public enum class BulkErrorCode {
    /** No such link in your account. */
    NOT_FOUND,

    /** The link is blocked. */
    FORBIDDEN,

    /** The operation conflicts with the link's current state. */
    CONFLICT,

    /** The value failed validation for this link. */
    VALIDATION_ERROR,

    /** Unexpected per-item failure, logged server-side. */
    INTERNAL,

    /** Processing aborted before this item. */
    NOT_ATTEMPTED,

    /** A code this SDK version does not know yet. */
    UNKNOWN,
    ;

    internal companion object : LenientEnumSerializer<BulkErrorCode>(
        "me.spoo.BulkErrorCode",
        entries,
        UNKNOWN,
        { it.name.lowercase() },
    )
}

/** Per-item verdict of a bulk operation. */
@Serializable
public data class BulkResult(
    /** The requested link id. */
    val id: String,
    /** Echoed when the id resolved to a link you own. */
    val alias: String? = null,
    /** Whether the operation succeeded for this id. */
    val ok: Boolean,
    /** Failure cause to branch on; null when ok. */
    @SerialName("error_code") val errorCode: BulkErrorCode? = null,
    /** Display-safe failure message; not stable, branch on [errorCode]. */
    val error: String? = null,
)

/**
 * Envelope of every bulk link operation. Partial success is data, not an
 * exception: inspect [results] row by row.
 */
@Serializable
public data class BulkOutcome(
    /** Aggregate counts. */
    val summary: BulkSummary,
    /** One row per unique requested id, in request order. */
    val results: List<BulkResult>,
)

/** Confirmation of a whole-domain bulk delete. */
@Serializable
public data class DomainPurge(
    /** Confirmation message. */
    val message: String,
    /** Number of links deleted. */
    val count: Long,
    /** Domain whose links were deleted. */
    val domain: String,
)

/** One (link id, claim token) pair to claim. */
@Serializable
public data class ClaimRequest(
    /** ObjectId of the link, from the shorten response. */
    @SerialName("url_id") val urlId: String,
    /** The one-time claim token the anonymous create returned. */
    val token: String,
)

/** Per-item outcome of a claim batch. */
@Serializable(with = ClaimStatus.Companion::class)
public enum class ClaimStatus {
    /** Ownership transferred; the token is burned. */
    CLAIMED,

    /** You already own this link (idempotent repeat). */
    ALREADY_YOURS,

    /** Unknown id, wrong token, or a link that is not claimable. */
    INVALID,

    /** A status this SDK version does not know yet. */
    UNKNOWN,
    ;

    internal companion object : LenientEnumSerializer<ClaimStatus>(
        "me.spoo.ClaimStatus",
        entries,
        UNKNOWN,
        { it.name.lowercase() },
    )
}

/** One row of a claim batch's outcome. */
@Serializable
public data class ClaimResult(
    /** The link id from the request item. */
    @SerialName("url_id") val urlId: String,
    /** What happened. */
    val status: ClaimStatus,
)

/**
 * Outcome of a claim batch. The batch never hard-fails: every submitted
 * item gets a result, in request order.
 */
@Serializable
public data class ClaimOutcome(
    /** One outcome per submitted item. */
    val results: List<ClaimResult>,
    /** Convenience count of claimed results. */
    val claimed: Long,
)

/** Request for [Links.create]. All fields beyond [longUrl] are optional. */
@Serializable
public data class CreateLinkRequest(
    /** The destination URL to shorten. */
    @SerialName("long_url") val longUrl: String,
    /** Custom short code: alphanumeric (3-16 chars) or emoji-only (1-15). */
    val alias: String? = null,
    /** Alias style to auto-generate when [alias] is omitted. */
    @SerialName("alias_type") val aliasType: AliasKind? = null,
    /** Password-protect the link (min 8 chars; the server validates). */
    val password: String? = null,
    /** Block known bot user agents. */
    @SerialName("block_bots") val blockBots: Boolean? = null,
    /** Expire the link after this many clicks. */
    @SerialName("max_clicks") val maxClicks: Long? = null,
    /** Expire the link at a point in time. */
    @SerialName("expire_after") val expireAfter: Instant? = null,
    /** Make statistics owner-only. Requires authentication. */
    @SerialName("private_stats") val privateStats: Boolean? = null,
    /** Scope the link under a custom domain you own (must be ACTIVE). */
    val domain: String? = null,
    /** Per-country destination overrides (ISO alpha-2 code to URL). */
    @SerialName("geo_rules") val geoRules: Map<String, String>? = null,
    /** Custom social preview. */
    @SerialName("meta_tags") val metaTags: MetaTags? = null,
)

/** DSL builder for [Links.create]. */
public class CreateLinkBuilder internal constructor() {
    /** The destination URL to shorten. Required. */
    public var longUrl: String? = null

    /** Custom short code. */
    public var alias: String? = null

    /** Alias style to auto-generate when [alias] is omitted. */
    public var aliasType: AliasKind? = null

    /** Password-protect the link. */
    public var password: String? = null

    /** Block known bot user agents. */
    public var blockBots: Boolean? = null

    /** Expire the link after this many clicks. */
    public var maxClicks: Long? = null

    /** Expire the link at a point in time. */
    public var expireAfter: Instant? = null

    /** Make statistics owner-only. */
    public var privateStats: Boolean? = null

    /** Scope the link under a custom domain you own. */
    public var domain: String? = null

    /** Per-country destination overrides. */
    public var geoRules: Map<String, String>? = null

    /** Custom social preview. */
    public var metaTags: MetaTags? = null

    internal fun build(): CreateLinkRequest = CreateLinkRequest(
        longUrl = requireNotNull(longUrl) { "longUrl is required" },
        alias = alias,
        aliasType = aliasType,
        password = password,
        blockBots = blockBots,
        maxClicks = maxClicks,
        expireAfter = expireAfter,
        privateStats = privateStats,
        domain = domain,
        geoRules = geoRules,
        metaTags = metaTags,
    )
}

/**
 * An update to apply with [Links.update]. Fields you do not touch keep
 * their stored values; the `remove*` methods clear a setting explicitly.
 */
public class UpdateLinkBuilder internal constructor() {
    private var longUrl: Patch<String> = Patch.Keep
    private var alias: Patch<String> = Patch.Keep
    private var password: Patch<String> = Patch.Keep
    private var blockBots: Patch<Boolean> = Patch.Keep
    private var maxClicks: Patch<Long> = Patch.Keep
    private var expireAfter: Patch<Instant> = Patch.Keep
    private var privateStats: Patch<Boolean> = Patch.Keep
    private var status: Patch<SettableStatus> = Patch.Keep
    private var domain: Patch<String> = Patch.Keep
    private var geoRules: Patch<Map<String, String>> = Patch.Keep
    private var metaTags: Patch<MetaTags> = Patch.Keep

    /** Point the link at a new destination. */
    public fun longUrl(value: String): UpdateLinkBuilder = apply { longUrl = Patch.Set(value) }

    /** Rename the short code. Must be available. */
    public fun alias(value: String): UpdateLinkBuilder = apply { alias = Patch.Set(value) }

    /** Set a new password. */
    public fun password(value: String): UpdateLinkBuilder = apply { password = Patch.Set(value) }

    /** Remove password protection. */
    public fun removePassword(): UpdateLinkBuilder = apply { password = Patch.Null }

    /** Enable or disable bot blocking. */
    public fun blockBots(value: Boolean): UpdateLinkBuilder = apply { blockBots = Patch.Set(value) }

    /** Set a new click limit. */
    public fun maxClicks(value: Long): UpdateLinkBuilder = apply { maxClicks = Patch.Set(value) }

    /** Remove the click limit. */
    public fun removeMaxClicks(): UpdateLinkBuilder = apply { maxClicks = Patch.Null }

    /** Set a new expiry time. */
    public fun expireAfter(value: Instant): UpdateLinkBuilder = apply { expireAfter = Patch.Set(value) }

    /** Remove the expiry. */
    public fun removeExpiry(): UpdateLinkBuilder = apply { expireAfter = Patch.Null }

    /** Make statistics owner-only, or public again. */
    public fun privateStats(value: Boolean): UpdateLinkBuilder = apply { privateStats = Patch.Set(value) }

    /** Enable or disable redirects. */
    public fun status(value: SettableStatus): UpdateLinkBuilder = apply { status = Patch.Set(value) }

    /** Move the link to a custom domain you own. */
    public fun domain(value: String): UpdateLinkBuilder = apply { domain = Patch.Set(value) }

    /** Move the link back to the default namespace. */
    public fun systemDomain(): UpdateLinkBuilder = apply { domain = Patch.Null }

    /** Replace all per-country destination overrides. */
    public fun geoRules(value: Map<String, String>): UpdateLinkBuilder = apply { geoRules = Patch.Set(value) }

    /** Remove all per-country destination overrides. */
    public fun clearGeoRules(): UpdateLinkBuilder = apply { geoRules = Patch.Null }

    /** Replace the custom social preview. */
    public fun metaTags(value: MetaTags): UpdateLinkBuilder = apply { metaTags = Patch.Set(value) }

    /** Remove the custom social preview. */
    public fun removeMetaTags(): UpdateLinkBuilder = apply { metaTags = Patch.Null }

    internal fun toWire(): JsonElement = buildJsonObject {
        fun field(name: String, patch: Patch<*>, encode: (Any?) -> JsonElement) {
            when (patch) {
                is Patch.Keep -> Unit
                is Patch.Null -> put(name, JsonNull)
                is Patch.Set -> put(name, encode(patch.value))
            }
        }
        field("long_url", longUrl) { JsonPrimitive(it as String) }
        field("alias", alias) { JsonPrimitive(it as String) }
        field("password", password) { JsonPrimitive(it as String) }
        field("block_bots", blockBots) { JsonPrimitive(it as Boolean) }
        field("max_clicks", maxClicks) { JsonPrimitive(it as Long) }
        field("expire_after", expireAfter) { JsonPrimitive((it as Instant).toString()) }
        field("private_stats", privateStats) { JsonPrimitive(it as Boolean) }
        field("status", status) { JsonPrimitive((it as SettableStatus).name) }
        field("domain", domain) { JsonPrimitive(it as String) }
        field("geo_rules", geoRules) { value ->
            @Suppress("UNCHECKED_CAST")
            val rules = value as Map<String, String>
            buildJsonObject { rules.toSortedMap().forEach { (k, v) -> put(k, v) } }
        }
        field("meta_tags", metaTags) { WireJson.encodeToJsonElement(MetaTags.serializer(), it as MetaTags) }
    }
}

/** Sort key for [Links.list]. */
public enum class SortBy(internal val wire: String) {
    /** Creation time (the default). */
    CREATED_AT("created_at"),

    /** Most recent click. */
    LAST_CLICK("last_click"),

    /** Lifetime click count. */
    TOTAL_CLICKS("total_clicks"),
}

/** Sort direction for [Links.list]. */
public enum class SortOrder(internal val wire: String) {
    /** Smallest or oldest first. */
    ASCENDING("asc"),

    /** Largest or newest first (the default). */
    DESCENDING("desc"),
}

/** Query for [Links.list]. Filter fields are typed, not stringly. */
public data class ListLinksRequest(
    /** 1-based page number. */
    val page: Int? = null,
    /** Items per page, 1 to 100. */
    val pageSize: Int? = null,
    /** Sort key. */
    val sortBy: SortBy? = null,
    /** Sort direction. */
    val sortOrder: SortOrder? = null,
    /** Only links on this custom domain. */
    val domain: String? = null,
    /** Only links with this status. */
    val status: SettableStatus? = null,
    /** Only links created after this time. */
    val createdAfter: Instant? = null,
    /** Only links created before this time. */
    val createdBefore: Instant? = null,
    /** Only links with (or without) a password. */
    val passwordSet: Boolean? = null,
    /** Only links with (or without) a click limit. */
    val maxClicksSet: Boolean? = null,
    /** Case-insensitive search in alias and destination URL. */
    val search: String? = null,
)

/** One page of a listing. Walk manually or lazily via [Links.listPaginated]. */
@Serializable
public data class LinkPage(
    /** The items on this page. */
    val items: List<LinkItem>,
    /** 1-based page number. */
    val page: Int,
    /** Page size the server applied. */
    @SerialName("pageSize") val pageSize: Int,
    /** Total items across all pages. */
    val total: Long,
    /** Whether another page exists. */
    @SerialName("hasNext") val hasNext: Boolean,
)

/** Link management, from [SpooClient.links]. */
public class Links internal constructor(
    private val transport: Transport,
) {
    /** Shorten a URL. */
    public suspend fun create(request: CreateLinkRequest): Link {
        val response = transport.send(
            HttpMethod.Post,
            "/api/v1/shorten",
            body = WireJson.encodeToJsonElement(CreateLinkRequest.serializer(), request),
        )
        return transport.decode(response)
    }

    /** Shorten a URL, DSL form: `links.create { longUrl = "..." }`. */
    public suspend fun create(block: CreateLinkBuilder.() -> Unit): Link =
        create(CreateLinkBuilder().apply(block).build())

    /** Check whether an alias is available before trying to create it. */
    public suspend fun checkAlias(alias: String, domain: String? = null): AliasCheck {
        val query = buildList {
            add("alias" to alias)
            domain?.let { add("domain" to it) }
        }
        val response = transport.send(HttpMethod.Get, "/api/v1/shorten/check-alias", query)
        return transport.decode(response)
    }

    /** List your links, one page. */
    public suspend fun list(request: ListLinksRequest = ListLinksRequest()): LinkPage {
        val response = transport.send(HttpMethod.Get, "/api/v1/urls", request.toQuery())
        return transport.decode(response)
    }

    /**
     * Walk every page lazily. The flow fetches a page only when the
     * previous one is exhausted; collect with `.items()` to flatten.
     */
    public fun listPaginated(request: ListLinksRequest = ListLinksRequest()): Flow<LinkPage> = flow {
        var current = request.copy(page = request.page ?: 1)
        while (true) {
            val page = list(current)
            emit(page)
            if (!page.hasNext) break
            current = current.copy(page = page.page + 1)
        }
    }

    /** Fetch one link by its id. */
    public suspend fun get(id: String): LinkItem {
        val response = transport.send(HttpMethod.Get, "/api/v1/urls/${encodeSegment(id)}")
        return transport.decode(response)
    }

    /**
     * Fetch one link by where it lives: domain plus alias. Pass the system
     * domain (`spoo.me`) for default-namespace links.
     */
    public suspend fun getByAddress(domain: String, alias: String): LinkItem {
        val response = transport.send(
            HttpMethod.Get,
            "/api/v1/urls/${encodeSegment(domain)}/${encodeSegment(alias)}",
        )
        return transport.decode(response)
    }

    /** Update a link: `links.update(id) { longUrl("..."); removePassword() }`. */
    public suspend fun update(id: String, block: UpdateLinkBuilder.() -> Unit): UpdatedLink {
        val body = UpdateLinkBuilder().apply(block).toWire()
        val response = transport.send(
            HttpMethod.Patch,
            "/api/v1/urls/${encodeSegment(id)}",
            body = body,
        )
        return transport.decode(response)
    }

    /** Enable or disable a link's redirects. */
    public suspend fun setStatus(id: String, status: SettableStatus): UpdatedLink {
        val response = transport.send(
            HttpMethod.Patch,
            "/api/v1/urls/${encodeSegment(id)}/status",
            body = buildJsonObject { put("status", status.name) },
        )
        return transport.decode(response)
    }

    /** Delete a link permanently. */
    public suspend fun delete(id: String): DeletedLink {
        val response = transport.send(HttpMethod.Delete, "/api/v1/urls/${encodeSegment(id)}")
        return transport.decode(response)
    }

    /**
     * Delete every link on one of your custom domains. Irreversible and
     * whole-domain: there is deliberately no filter.
     */
    public suspend fun deleteAllOnDomain(domain: String): DomainPurge {
        val response = transport.send(
            HttpMethod.Delete,
            "/api/v1/urls",
            listOf("domain" to domain),
        )
        return transport.decode(response)
    }

    /** Delete up to 100 links by id. Partial success is data. */
    public suspend fun bulkDelete(ids: List<String>): BulkOutcome =
        bulk("/api/v1/urls/bulk/delete", ids) {}

    /** Set the status of up to 100 links at once. */
    public suspend fun bulkSetStatus(ids: List<String>, status: SettableStatus): BulkOutcome =
        bulk("/api/v1/urls/bulk/status", ids) { put("status", status.name) }

    /** Set or clear the expiry of up to 100 links at once. Null clears. */
    public suspend fun bulkSetExpiry(ids: List<String>, expireAfter: Instant?): BulkOutcome =
        bulk("/api/v1/urls/bulk/expiry", ids) {
            put("expire_after", expireAfter?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        }

    /**
     * Move up to 100 links to a custom domain you own, or back to the
     * default namespace with null.
     */
    public suspend fun bulkMoveDomain(ids: List<String>, domain: String?): BulkOutcome =
        bulk("/api/v1/urls/bulk/domain", ids) {
            put("domain", domain?.let { JsonPrimitive(it) } ?: JsonNull)
        }

    private suspend fun bulk(
        path: String,
        ids: List<String>,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): BulkOutcome {
        val body = buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
            extra()
        }
        val response = transport.send(HttpMethod.Post, path, body = body)
        return transport.decode(response)
    }

    /**
     * Attach anonymously created links to this account, up to 16 per call.
     * Each item resolves independently; the batch never hard-fails.
     */
    public suspend fun claim(claims: List<ClaimRequest>): ClaimOutcome {
        val body = buildJsonObject {
            putJsonArray("claims") {
                claims.forEach {
                    add(WireJson.encodeToJsonElement(ClaimRequest.serializer(), it))
                }
            }
        }
        val response = transport.send(HttpMethod.Post, "/api/v1/urls/claim", body = body)
        return transport.decode(response)
    }
}

/** Flatten a [Links.listPaginated] flow into its items. */
public fun Flow<LinkPage>.items(): Flow<LinkItem> = transform { page ->
    page.items.forEach { emit(it) }
}

internal fun ListLinksRequest.toQuery(): List<Pair<String, String>> {
    val query = mutableListOf<Pair<String, String>>()
    page?.let { query.add("page" to it.toString()) }
    pageSize?.let { query.add("pageSize" to it.toString()) }
    sortBy?.let { query.add("sortBy" to it.wire) }
    sortOrder?.let { query.add("sortOrder" to it.wire) }
    domain?.let { query.add("domain" to it) }
    // The filter travels as one JSON object; keys are inserted in sorted
    // order so the wire bytes are deterministic.
    val filter = buildMap<String, JsonElement> {
        status?.let { put("status", JsonPrimitive(it.name)) }
        createdAfter?.let { put("createdAfter", JsonPrimitive(it.toString())) }
        createdBefore?.let { put("createdBefore", JsonPrimitive(it.toString())) }
        passwordSet?.let { put("passwordSet", JsonPrimitive(it)) }
        maxClicksSet?.let { put("maxClicksSet", JsonPrimitive(it)) }
        search?.let { put("search", JsonPrimitive(it)) }
    }
    if (filter.isNotEmpty()) {
        val ordered = buildJsonObject {
            filter.toList().sortedBy { it.first }.forEach { (k, v) -> put(k, v) }
        }
        query.add("filter" to WireJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), ordered))
    }
    return query
}
