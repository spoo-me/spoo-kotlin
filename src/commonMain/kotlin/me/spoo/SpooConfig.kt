package me.spoo

import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import me.spoo.oauth.Session

/** Default production endpoint. */
public const val DEFAULT_BASE_URL: String = "https://spoo.me"

/**
 * Client configuration. Every knob has a sensible default; construct via
 * named arguments or pass a prebuilt instance to [SpooClient].
 */
public class SpooConfig(
    /** API key (`spoo_...`). Omit for anonymous access to public endpoints. */
    public val apiKey: String? = null,
    /**
     * A refreshing Sign in with Spoo session. Mutually exclusive with
     * [apiKey]; when both are set the session wins.
     */
    public val session: Session? = null,
    /** Point at a self-hosted instance instead of `https://spoo.me`. */
    public val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * Inject a Ktor engine (shared pools, proxies, tests). When omitted the
     * platform default is used (OkHttp on Android and the JVM).
     */
    public val engine: HttpClientEngine? = null,
    /** Retries after the first attempt. Default 2. */
    public val maxRetries: Int = 2,
    /** Per-request timeout. Default 30 seconds. */
    public val timeout: Duration = 30.seconds,
    /**
     * Override the `X-Spoo-Client` identification header, e.g.
     * `"app-android/1.0"`. Defaults to this SDK's own tag.
     */
    public val clientTag: String? = null,
)
