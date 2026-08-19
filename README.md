# spoo.me Kotlin SDK

The official Kotlin Multiplatform SDK for the [spoo.me](https://spoo.me)
link management API. Android and JVM today; the KMP structure keeps iOS and
JS additive.

```kotlin
val spoo = SpooClient(apiKey = "spoo_your_api_key")

val link = spoo.links.create {
    longUrl = "https://example.com/launch"
    alias = "launch" // or emoji: "🚀🔥"
    maxClicks = 10_000
}
println(link.shortUrl) // https://spoo.me/launch
```

- Coroutines-first: suspend functions everywhere, `Flow` pagination
- Typed sealed errors, automatic retries, streaming exports
- Timestamps in and out as `kotlin.time.Instant`, whatever the wire format
- Anonymous, API key, and Sign in with Spoo authentication
- Thin tree: Ktor client and kotlinx.serialization, nothing else

## Install

```kotlin
dependencies {
    implementation("me.spoo:spoo:0.1.0")
}
```

Requires Kotlin 2.4+. On Android the minimum SDK is 21; consumer R8 rules
ship in the artifact. The snippets on this page also use
`kotlinx-coroutines` (already a transitive dependency) and, where named,
`kotlinx.serialization` for your own types.

## Authentication

Create an API key from your [spoo.me dashboard](https://spoo.me) and pass
it explicitly:

```kotlin
val spoo = SpooClient(apiKey = "spoo_...")
```

`SpooClient()` with no credentials works too: anonymous shortening and the
public endpoints (stats, previews, the emoji set) need no account.

Self-hosting spoo.me, injecting an engine, or tagging your app:

```kotlin
val spoo = SpooClient(SpooConfig(
    apiKey = "spoo_...",
    baseUrl = "https://links.example.com",
    engine = OkHttp.create(),          // shared pools, proxies, tests
    clientTag = "my-app/1.0",          // X-Spoo-Client override
))
```

The client is safe to share across coroutines. `close()` releases the owned
engine; injected engines stay yours to manage.

## Shorten links

```kotlin
val link = spoo.links.create {
    longUrl = "https://example.com/launch"
    password = "secure@123"
    expireAfter = Clock.System.now() + 30.days
}
```

Anonymous creations return a one-time `claimToken`. Store it and the link
can be claimed into an account later:

```kotlin
spoo.links.claim(listOf(ClaimRequest(urlId = link.id, token = link.claimToken!!)))
```

## Manage links

```kotlin
// Paginated listing with typed filters.
val page = spoo.links.list(ListLinksRequest(
    pageSize = 50,
    sortBy = SortBy.TOTAL_CLICKS,
    status = SettableStatus.ACTIVE,
    search = "promo",
))

// Or walk everything lazily.
spoo.links.listPaginated().items().collect { println(it.id) }

// Updates only touch what you set; remove* clears a setting explicitly.
spoo.links.update(link.id) {
    longUrl("https://example.com/v2")
    removePassword()
}

// Bulk operations report per-item outcomes instead of failing the batch.
val outcome = spoo.links.bulkSetStatus(ids, SettableStatus.INACTIVE)
outcome.results.filterNot { it.ok }.forEach { println("${it.id}: ${it.errorCode}") }
```

## Statistics and exports

```kotlin
val report = spoo.stats.account(AccountStatsRequest(
    query = StatsQuery(
        groupBy = listOf(Dimension.TIME, Dimension.COUNTRY),
        filters = mapOf(FilterDimension.BROWSER to listOf("Chrome")),
    ),
))
println("${report.summary.totalClicks} clicks")

val perLink = spoo.stats.forLink(link.id)

// Exports stream; filenames from the server are reduced to a bare name
// (no separators or dot-segments), so joining one into a directory cannot
// traverse out of it. Choosing a safe directory remains your job.
val export = spoo.stats.exportLink(link.id, ExportFormat.CSV)
File(downloads, export.filename).writeBytes(export.bytes())
```

Account-wide downloads come from `stats.export()`; per-link downloads with
per-link filenames come from `stats.exportLink(id)`.

## Public endpoints

```kotlin
val anon = SpooClient()
val stats = anon.publicLinks.stats("launch")
val locked = anon.publicLinks.stats("locked", password = "hunter@22")
val preview = anon.publicLinks.preview("launch") // never reveals what the redirect refuses
val emoji = anon.emoji.set()                     // ETag-cached on the client
```

## Errors

Every failure is a `SpooException`, and coroutine cancellation is always
rethrown untouched. API failures are a sealed hierarchy carrying the
backend's machine-readable code, the offending field, request id and
rate-limit state:

```kotlin
try {
    spoo.links.get("gone")
} catch (e: NotFoundException) {
    println("no such link")
} catch (e: RateLimitException) {
    println("wait ${e.rateLimit.retryAfter}")
} catch (e: ContentBlockedException) {
    println("taken down")
} catch (e: AuthenticationException) {
    if (e.isPasswordRequired) promptForLinkPassword()
}
```

Transient failures (408, 429, 5xx) retry twice with jittered exponential
backoff capped at 8 seconds, honoring both legal `Retry-After` forms with a
60 second ceiling: a longer mandated wait surfaces immediately with the
full wait readable on the exception. Requests that could duplicate work on
replay (POST, PATCH) retry only where the server provably did nothing
(429, 503). Default timeout is 30 seconds.

## Sign in with Spoo

The client half of the connected-apps flow: PKCE, the code exchange, and a
self-refreshing session.

```kotlin
val anon = SpooClient()
val pkce = generatePkcePair()
val state = generateState()
val url = anon.oauth.authorizationUrl(
    appId = "your_app_id",
    state = state,
    codeChallenge = pkce.challenge,
    redirectUri = "https://your.app/callback",
)
// Open url in a browser (Custom Tabs on Android); the callback carries
// code and state. Verify the echoed state matches BEFORE exchanging the
// code, and reject the flow on a mismatch.

val tokens = anon.oauth.exchangeCode(code, pkce.verifier)

val session = Session(tokens.tokens(), onRefresh = { pair ->
    // Persist the rotated pair: the previous refresh token is dead.
})
val spoo = SpooClient(session = session)
val me = spoo.auth.me()
```

Sessions refresh proactively before the access token expires and once more
after an unexpected 401. Refreshes are single-flight across coroutines, and
a dead refresh token surfaces as `SessionExpiredException`. Token pairs
redact themselves in `toString()`, so they never leak into logs.

## Scope

This SDK covers the v1 data plane: shortening (including emoji aliases),
link management, bulk operations, claiming, statistics, exports, public
link surfaces, the emoji catalogue, identity read, and Sign in with Spoo.
Account administration (API key management, profile editing), service
endpoints (health, contact), and the legacy v0 API are deliberately out of
scope.

| Area | Methods |
|---|---|
| Shorten | `links.create`, `links.checkAlias` |
| Manage | `links.list`, `get`, `getByAddress`, `update`, `setStatus`, `delete`, `deleteAllOnDomain` |
| Bulk | `bulkDelete`, `bulkSetStatus`, `bulkSetExpiry`, `bulkMoveDomain` |
| Claim | `links.claim` |
| Stats | `stats.account`, `stats.forLink` |
| Export | `stats.export`, `stats.exportLink` |
| Public | `publicLinks.stats`, `publicLinks.preview` |
| Emoji | `emoji.set` |
| Identity | `auth.me` |
| Sign in with Spoo | `oauth.authorizationUrl`, `exchangeCode`, `refreshTokens`, `Session` |

## Raw requests

For v1 endpoints the SDK does not cover yet, typed passthroughs reuse the
client's auth, retries, timeout and error mapping:

```kotlin
@Serializable data class Whatever(val ok: Boolean)
val value: Whatever = spoo.get("/api/v1/new-endpoint", listOf("k" to "v"))
```

These are a supported pressure valve. If you need one, the surface has a
gap worth an issue on this repo.

## License

MIT
