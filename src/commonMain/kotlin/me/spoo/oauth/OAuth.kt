package me.spoo.oauth

import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import kotlin.io.encoding.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.spoo.AuthenticationException
import me.spoo.SessionExpiredException
import me.spoo.User
import me.spoo.ValidationException
import me.spoo.internal.Transport
import me.spoo.internal.secureRandomBytes
import me.spoo.internal.sha256

private const val VERIFIER_CHARS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

internal val UrlSafeBase64: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** A PKCE verifier and its S256 challenge. */
public class PkcePair internal constructor(
    /** The secret half: goes into the token exchange. */
    public val verifier: String,
    /** The public half: goes into the authorization URL. */
    public val challenge: String,
) {
    override fun toString(): String = "PkcePair(challenge=$challenge, verifier=[redacted])"
}

/**
 * Generate a PKCE pair (RFC 7636, S256). The verifier is 64 characters
 * from the unreserved set, from a cryptographically secure source.
 */
public fun generatePkcePair(): PkcePair {
    val bytes = secureRandomBytes(64)
    val verifier = buildString(64) {
        bytes.forEach { append(VERIFIER_CHARS[(it.toInt() and 0xFF) % VERIFIER_CHARS.length]) }
    }
    val challenge = UrlSafeBase64.encode(sha256(verifier.encodeToByteArray()))
    return PkcePair(verifier, challenge)
}

/** Generate a CSRF-binding `state` value for the authorization URL. */
public fun generateState(): String {
    val bytes = secureRandomBytes(32)
    return buildString(32) {
        bytes.forEach { append(VERIFIER_CHARS[(it.toInt() and 0xFF) % VERIFIER_CHARS.length]) }
    }
}

/**
 * An access/refresh token pair. Refresh tokens rotate: after a refresh the
 * pair you held before is dead. [toString] redacts both tokens: a refresh
 * token in a log line is a long-lived credential leak.
 */
@Serializable
public data class TokenPair(
    /** JWT access token. */
    @SerialName("access_token") val accessToken: String,
    /** JWT refresh token. */
    @SerialName("refresh_token") val refreshToken: String,
) {
    override fun toString(): String =
        "TokenPair(accessToken=[redacted], refreshToken=[redacted])"
}

/**
 * The result of a device-code exchange: tokens plus the signed-in user.
 * [toString] redacts both tokens.
 */
@Serializable
public data class DeviceTokens(
    /** JWT access token. */
    @SerialName("access_token") val accessToken: String,
    /** JWT refresh token. */
    @SerialName("refresh_token") val refreshToken: String,
    /** The signed-in user's profile. */
    val user: User,
) {
    /** The token pair, for building a [Session]. */
    public fun tokens(): TokenPair = TokenPair(accessToken, refreshToken)

    override fun toString(): String =
        "DeviceTokens(accessToken=[redacted], refreshToken=[redacted], user=$user)"
}

/**
 * The client half of Sign in with Spoo (authorization-code + PKCE), from
 * [me.spoo.SpooClient.oauth]. The SDK never opens browsers, renders consent,
 * or stores secrets; it provides the protocol pieces and the self-refreshing
 * [Session] credential.
 */
public class OAuth internal constructor(
    private val transport: Transport,
    private val baseUrl: String,
) {
    /**
     * Build the consent-page URL your app opens in a browser. S256 is
     * mandatory. The callback carries `code` and `state`: verify the echoed
     * state matches [state] BEFORE exchanging the code, and reject the flow
     * on a mismatch (CSRF protection).
     *
     * [redirectUri] must exactly match a redirect URI registered for the
     * app; omit to use the app's registered default.
     */
    public fun authorizationUrl(
        appId: String,
        state: String,
        codeChallenge: String,
        redirectUri: String? = null,
    ): String {
        val builder = URLBuilder("$baseUrl/auth/device/login")
        builder.parameters.append("app_id", appId)
        redirectUri?.let { builder.parameters.append("redirect_uri", it) }
        builder.parameters.append("state", state)
        builder.parameters.append("code_challenge", codeChallenge)
        builder.parameters.append("code_challenge_method", "S256")
        return builder.buildString()
    }

    /**
     * Exchange the one-time code from the callback for tokens. The code and
     * verifier are the credentials; no auth header is involved.
     */
    public suspend fun exchangeCode(code: String, codeVerifier: String): DeviceTokens {
        val response = transport.send(
            HttpMethod.Post,
            "/auth/device/token",
            body = buildJsonObject {
                put("code", code)
                put("code_verifier", codeVerifier)
            },
            authenticated = false,
        )
        return transport.decode(response)
    }

    /**
     * Trade a refresh token for a fresh pair. The pair you sent is invalid
     * afterwards. Prefer a [Session], which handles rotation, persistence
     * and retry for you.
     */
    public suspend fun refreshTokens(refreshToken: String): TokenPair =
        refreshCall(transport, refreshToken)
}

/**
 * The refresh endpoint answers 400 or 401 when the refresh token is dead:
 * both mean the session is over.
 */
internal suspend fun refreshCall(transport: Transport, refreshToken: String): TokenPair {
    val response = try {
        transport.send(
            HttpMethod.Post,
            "/auth/device/refresh",
            body = buildJsonObject { put("refresh_token", refreshToken) },
            authenticated = false,
        )
    } catch (cause: AuthenticationException) {
        throw SessionExpiredException(cause)
    } catch (cause: ValidationException) {
        if (cause.status == 400) throw SessionExpiredException(cause) else throw cause
    }
    return transport.decode(response)
}

/**
 * Best-effort read of a JWT's exp claim. Null disables proactive refresh;
 * the 401-retry path still works.
 */
internal fun decodeJwtExp(jwt: String): Long? {
    val payload = jwt.split('.').getOrNull(1) ?: return null
    val bytes = try {
        UrlSafeBase64.decode(payload)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val json = try {
        me.spoo.internal.WireJson.parseToJsonElement(bytes.decodeToString())
    } catch (_: Throwable) {
        return null
    }
    return (json as? kotlinx.serialization.json.JsonObject)
        ?.get("exp")
        ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
        ?.content
        ?.toDoubleOrNull()
        ?.toLong()
}
