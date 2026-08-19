package me.spoo

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Rate-limit state parsed from the `X-RateLimit-*` and `Retry-After`
 * response headers. The backend reports the shortest rate-limit window that
 * applies to the endpoint.
 */
public class RateLimitInfo internal constructor(
    /** Request budget of the reported window. */
    public val limit: Long?,
    /** How much of the budget is left. */
    public val remaining: Long?,
    /** When the reported window resets. */
    public val reset: Instant?,
    /** The server-mandated wait, sent on 429 responses. */
    public val retryAfter: Duration?,
) {
    override fun toString(): String =
        "RateLimitInfo(limit=$limit, remaining=$remaining, reset=$reset, retryAfter=$retryAfter)"
}

/**
 * Root of every failure this SDK throws. Callers who wrap calls in
 * `catch (e: SpooException)` never see anything else escape; coroutine
 * cancellation is always rethrown untouched.
 */
public sealed class SpooException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The request never produced a usable response: connection failures,
 * timeouts, TLS errors.
 */
public class SpooIOException(
    message: String,
    cause: Throwable? = null,
) : SpooException(message, cause)

/** The client was misconfigured; no request was sent. */
public class SpooConfigException(message: String) : SpooException(message)

/**
 * A 2xx response body did not decode into the expected shape. Usually means
 * the SDK is behind the server: check for a newer release.
 */
public class SpooDecodeException(
    message: String,
    cause: Throwable? = null,
) : SpooException(message, cause)

/**
 * The API answered with an error status. Carries the parsed error envelope
 * plus the response metadata that matters for handling it programmatically.
 *
 * When the response body is not the JSON error envelope (a proxy-composed
 * HTML page, say), [message] is `HTTP {status}` and the raw text is
 * preserved in [rawBody]: a web page is never an error message.
 */
public sealed class SpooApiException(
    /** HTTP status of the response. */
    public val status: Int,
    /**
     * The backend's machine-readable error code: an open string enum in
     * lowercase snake_case such as `conflict`, `not_found`,
     * `rate_limit_exceeded`, `blocked` (the one uppercase outlier is
     * `EMAIL_NOT_VERIFIED`). Read from the body, with the `X-Error-Code`
     * header as fallback for edge-composed responses.
     */
    public val code: String,
    message: String,
    /** Names the offending request field on validation errors. */
    public val field: String?,
    /** The `X-Request-ID` header, for support correlation. */
    public val requestId: String?,
    /** Parsed rate-limit headers. */
    public val rateLimit: RateLimitInfo,
    /** Raw response text, preserved when the body was not the envelope. */
    public val rawBody: String?,
) : SpooException(message)

/**
 * 429: the client has already retried, so this surfacing means the budget
 * is truly gone. [RateLimitInfo.retryAfter] carries the full server-mandated
 * wait, even when it exceeded the retry ceiling.
 */
public class RateLimitException internal constructor(
    status: Int,
    code: String,
    message: String,
    field: String?,
    requestId: String?,
    rateLimit: RateLimitInfo,
    rawBody: String?,
) : SpooApiException(status, code, message, field, requestId, rateLimit, rawBody)

/**
 * 401. [isPasswordRequired] distinguishes a property of the link (its stats
 * need the link password) from a property of the caller's credentials.
 */
public class AuthenticationException internal constructor(
    status: Int,
    code: String,
    message: String,
    field: String?,
    requestId: String?,
    rateLimit: RateLimitInfo,
    rawBody: String?,
) : SpooApiException(status, code, message, field, requestId, rateLimit, rawBody) {
    /** Whether the failure is the link's password gate, not dead credentials. */
    public val isPasswordRequired: Boolean
        get() = code == "password_required" || code == "invalid_password"
}

/** 403. */
public class PermissionException internal constructor(
    status: Int,
    code: String,
    message: String,
    field: String?,
    requestId: String?,
    rateLimit: RateLimitInfo,
    rawBody: String?,
) : SpooApiException(status, code, message, field, requestId, rateLimit, rawBody)

/** 404: no such resource, or not yours (deliberately indistinguishable). */
public class NotFoundException internal constructor(
    status: Int,
    code: String,
    message: String,
    field: String?,
    requestId: String?,
    rateLimit: RateLimitInfo,
    rawBody: String?,
) : SpooApiException(status, code, message, field, requestId, rateLimit, rawBody)

/**
 * 451: the link was taken down because its destination was flagged. A
 * verdict on the link, not a transient failure.
 */
public class ContentBlockedException internal constructor(
    status: Int,
    code: String,
    message: String,
    field: String?,
    requestId: String?,
    rateLimit: RateLimitInfo,
    rawBody: String?,
) : SpooApiException(status, code, message, field, requestId, rateLimit, rawBody)

/** 413. */
public class PayloadTooLargeException internal constructor(
    status: Int,
    code: String,
    message: String,
    field: String?,
    requestId: String?,
    rateLimit: RateLimitInfo,
    rawBody: String?,
) : SpooApiException(status, code, message, field, requestId, rateLimit, rawBody)

/** 400, 409, 422: the server rejected the request's content. */
public class ValidationException internal constructor(
    status: Int,
    code: String,
    message: String,
    field: String?,
    requestId: String?,
    rateLimit: RateLimitInfo,
    rawBody: String?,
) : SpooApiException(status, code, message, field, requestId, rateLimit, rawBody)

/**
 * A status this SDK version does not know yet. An explicit forward-compat
 * leaf: the hierarchy stays sealed and additive server changes still map
 * somewhere typed.
 */
public class UnknownApiException internal constructor(
    status: Int,
    code: String,
    message: String,
    field: String?,
    requestId: String?,
    rateLimit: RateLimitInfo,
    rawBody: String?,
) : SpooApiException(status, code, message, field, requestId, rateLimit, rawBody)

/**
 * A refreshing session's refresh token no longer works. The only recovery
 * is a fresh sign-in.
 */
public class SessionExpiredException internal constructor(
    cause: Throwable? = null,
) : SpooException("session expired: refresh token was rejected", cause)
