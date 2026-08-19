package me.spoo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import me.spoo.internal.contentDispositionFilename
import me.spoo.internal.encodeSegment
import me.spoo.internal.parseRetryAfter
import me.spoo.internal.sanitizeFilename

class WireHelpersTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun retryAfterParsesBothLegalFormsAndNeverThrows() {
        assertEquals(120.seconds, parseRetryAfter("120", now))
        assertEquals(30.seconds, parseRetryAfter("Thu, 01 Jan 2026 00:00:30 GMT", now))
        assertNull(parseRetryAfter("Wed, 31 Dec 2025 23:59:00 GMT", now), "past date = no wait")
        assertNull(parseRetryAfter("not-a-value", now))
        assertNull(parseRetryAfter("", now))
        assertNull(parseRetryAfter(null, now))
        assertNull(parseRetryAfter("-5", now))
    }

    @Test
    fun hostileFilenamesFallBack() {
        val fallback = "spoo-export.json"
        assertEquals("evil.json", sanitizeFilename("../../../evil.json", fallback))
        assertEquals(fallback, sanitizeFilename("/tmp/absolute-evil.json", fallback))
        assertEquals("evil.json", sanitizeFilename("..\\..\\evil.json", fallback))
        assertEquals(fallback, sanitizeFilename("..", fallback))
        assertEquals(fallback, sanitizeFilename(".", fallback))
        assertEquals(fallback, sanitizeFilename("", fallback))
        assertEquals("report.csv", sanitizeFilename("report.csv", fallback))
    }

    @Test
    fun contentDispositionAllForms() {
        val fallback = "spoo-export.json"
        assertEquals(
            "stats.json",
            contentDispositionFilename("""attachment; filename="stats.json"""", fallback),
        )
        assertEquals(
            "esc.json",
            contentDispositionFilename(
                "attachment; filename*=utf-8''%2e%2e%2f%2e%2e%2fesc.json",
                fallback,
            ),
        )
        assertEquals(
            "evil.json",
            contentDispositionFilename("""attachment; filename="../../../evil.json"""", fallback),
        )
        assertEquals(fallback, contentDispositionFilename(null, fallback))
    }

    @Test
    fun pathSegmentsEncodeTargetRewritingCharacters() {
        assertEquals("abc-DEF_1.2~", encodeSegment("abc-DEF_1.2~"))
        assertEquals("a%2Fb", encodeSegment("a/b"))
        assertEquals("a%3Fx%3D1", encodeSegment("a?x=1"))
        assertEquals("a%23b", encodeSegment("a#b"))
        assertEquals("..%2Fx", encodeSegment("../x"))
        assertEquals("%F0%9F%9A%80", encodeSegment("🚀"))
    }
}
