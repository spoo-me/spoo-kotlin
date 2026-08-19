package me.spoo.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Parse a `Retry-After` header value. RFC 9110 allows two legal forms:
 * delay-seconds and an HTTP-date (IMF-fixdate). Never throws: an
 * unparseable value is null. The Python SDK once crashed on the date form;
 * this parser is why that class of bug cannot recur here.
 */
internal fun parseRetryAfter(value: String?, now: Instant): Duration? {
    val trimmed = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    trimmed.toLongOrNull()?.let { seconds ->
        return if (seconds >= 0) seconds.seconds else null
    }
    val when_ = parseHttpDate(trimmed) ?: return null
    val delta = when_ - now
    return if (delta.isPositive()) delta else null
}

/**
 * Minimal IMF-fixdate parser ("Sun, 06 Nov 1994 08:49:37 GMT"). Returns
 * null on anything else; the caller falls back to computed backoff.
 */
internal fun parseHttpDate(value: String): Instant? {
    // "Sun, 06 Nov 1994 08:49:37 GMT"
    val parts = value.split(' ').filter { it.isNotEmpty() }
    if (parts.size != 6 || !parts[5].equals("GMT", ignoreCase = true)) return null
    val day = parts[1].toIntOrNull() ?: return null
    val month = MONTHS.indexOfFirst { it.equals(parts[2], ignoreCase = true) } + 1
    if (month == 0) return null
    val year = parts[3].toIntOrNull() ?: return null
    val time = parts[4].split(':')
    if (time.size != 3) return null
    val hour = time[0].toIntOrNull() ?: return null
    val minute = time[1].toIntOrNull() ?: return null
    val second = time[2].toIntOrNull() ?: return null
    if (day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    // Days since epoch via the standard civil-date algorithm.
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097L + doe - 719468L
    return Instant.fromEpochSeconds(days * 86400 + hour * 3600 + minute * 60 + second)
}

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * Reduce a server-suggested filename to a safe bare filename. Wire-supplied
 * paths are untrusted: consumers hand this value to file APIs, so anything
 * that could escape a directory is rejected and the fallback used instead.
 * Pure string logic on purpose: it must behave identically on every target.
 */
internal fun sanitizeFilename(raw: String, fallback: String): String {
    val candidate = raw.trim().trim('"')
    val absolute = candidate.startsWith("/") || candidate.startsWith("\\")
    val base = candidate.substringAfterLast('/').substringAfterLast('\\').trim()
    return if (absolute || base.isEmpty() || base == "." || base == "..") fallback else base
}

/**
 * Extract and sanitize the filename from a `Content-Disposition` header,
 * preferring the RFC 5987 `filename*` form (sanitized AFTER decoding).
 */
internal fun contentDispositionFilename(header: String?, fallback: String): String {
    if (header == null) return fallback
    var plain: String? = null
    var extended: String? = null
    for (part in header.split(';')) {
        val piece = part.trim()
        when {
            piece.startsWith("filename*=") -> {
                val encoded = piece.removePrefix("filename*=").substringAfterLast('\'')
                extended = percentDecode(encoded)
            }
            piece.startsWith("filename=") -> {
                plain = piece.removePrefix("filename=").trim('"')
            }
        }
    }
    val name = extended ?: plain ?: return fallback
    return sanitizeFilename(name, fallback)
}

internal fun percentDecode(input: String): String {
    val out = StringBuilder(input.length)
    var i = 0
    val bytes = ArrayList<Byte>()
    fun flushBytes() {
        if (bytes.isNotEmpty()) {
            out.append(bytes.toByteArray().decodeToString())
            bytes.clear()
        }
    }
    while (i < input.length) {
        val c = input[i]
        if (c == '%' && i + 2 < input.length + 1 && i + 2 <= input.length - 1 + 1) {
            val hex = input.substring(i + 1, minOf(i + 3, input.length))
            val byte = if (hex.length == 2) hex.toIntOrNull(16) else null
            if (byte != null) {
                bytes.add(byte.toByte())
                i += 3
                continue
            }
        }
        flushBytes()
        out.append(c)
        i += 1
    }
    flushBytes()
    return out.toString()
}

/**
 * Percent-encode a caller-supplied path segment (the RFC 3986 unreserved
 * set stays literal). Keeps ids, aliases and domains from rewriting the
 * request target with `/`, `?` or `#`.
 */
internal fun encodeSegment(segment: String): String {
    val out = StringBuilder(segment.length)
    for (byte in segment.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
            out.append(c)
        } else {
            out.append('%')
            val hex = (byte.toInt() and 0xFF).toString(16).uppercase()
            if (hex.length == 1) out.append('0')
            out.append(hex)
        }
    }
    return out.toString()
}
