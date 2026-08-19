package me.spoo.oauth

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.spoo.internal.Transport

/**
 * A self-refreshing Sign in with Spoo credential, passed to
 * [me.spoo.SpooConfig.session].
 *
 * The client refreshes proactively shortly before the access token's exp,
 * retries once after an unexpected 401, and rotations are single-flight:
 * concurrent requests share one refresh, so a rotated pair is never
 * persisted twice. Every rotation is reported through [onRefresh]; persist
 * the pair there, because the previous refresh token is dead the moment it
 * fires. The hook runs outside the session lock but on the caller's
 * coroutine, so keep it quick; move slow writes onto another dispatcher.
 */
public class Session(
    tokens: TokenPair,
    /** How long before the access token's exp to refresh proactively. */
    private val expirySkew: Duration = 30.seconds,
    /** Called after every successful refresh with the rotated pair. */
    private val onRefresh: ((TokenPair) -> Unit)? = null,
) {
    private val lock = Mutex()
    private var tokens: TokenPair = tokens
    private var generation: Long = 0
    private var expiresAtEpochSeconds: Long? = decodeJwtExp(tokens.accessToken)

    /** Mark the current access token stale, so the next request refreshes. */
    public suspend fun invalidate() {
        lock.withLock { expiresAtEpochSeconds = 0 }
    }

    /**
     * A fresh access token and the rotation generation it belongs to.
     * Refreshes first when the token is at or past exp minus the skew.
     */
    internal suspend fun freshToken(transport: Transport): Pair<String, Long> {
        var rotated: TokenPair? = null
        val result = lock.withLock {
            val now = Clock.System.now().epochSeconds
            val stale = expiresAtEpochSeconds?.let { now + expirySkew.inWholeSeconds >= it } == true
            if (stale) rotated = rotateLocked(transport)
            tokens.accessToken to generation
        }
        rotated?.let { onRefresh?.invoke(it) }
        return result
    }

    /**
     * Refresh after a 401, unless another caller already rotated past the
     * generation this request was sent with.
     */
    internal suspend fun refreshStale(transport: Transport, seenGeneration: Long) {
        var rotated: TokenPair? = null
        lock.withLock {
            if (generation != seenGeneration) return
            rotated = rotateLocked(transport)
        }
        rotated?.let { onRefresh?.invoke(it) }
    }

    private suspend fun rotateLocked(transport: Transport): TokenPair {
        val pair = refreshCall(transport, tokens.refreshToken)
        tokens = pair
        generation += 1
        expiresAtEpochSeconds = decodeJwtExp(pair.accessToken)
        return pair
    }
}
