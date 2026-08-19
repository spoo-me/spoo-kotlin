package me.spoo

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import me.spoo.internal.LenientEnumSerializer
import me.spoo.internal.Transport
import me.spoo.internal.contentDispositionFilename
import me.spoo.internal.encodeSegment

/** A grouping dimension for statistics breakdowns. */
public enum class Dimension(internal val wire: String) {
    /** Time buckets (day/week/month, auto-selected from the range). */
    TIME("time"),

    /** Browser name. */
    BROWSER("browser"),

    /** Operating system. */
    OS("os"),

    /** Device type: mobile, tablet, desktop, unknown. */
    DEVICE("device"),

    /** Country. */
    COUNTRY("country"),

    /** City. */
    CITY("city"),

    /** Referrer URL. */
    REFERRER("referrer"),

    /** URL alias. */
    SHORT_CODE("short_code"),

    /** `utm_source` tag; untagged clicks appear as `(none)`. */
    UTM_SOURCE("utm_source"),

    /** `utm_medium` tag. */
    UTM_MEDIUM("utm_medium"),

    /** `utm_campaign` tag. */
    UTM_CAMPAIGN("utm_campaign"),
}

/** A metric to include in the breakdown. Defaults to both when unset. */
public enum class Metric(internal val wire: String) {
    /** Total click count. */
    CLICKS("clicks"),

    /** Unique visitor count. */
    UNIQUE_CLICKS("unique_clicks"),
}

/**
 * A dimension clicks can be filtered by. Values are case-sensitive, exact
 * as stored. The aggregate-only short-code/url-id slicers live on
 * [AccountStatsRequest] instead, so they cannot be sent where the endpoint
 * rejects them.
 */
public enum class FilterDimension(internal val wire: String) {
    /** Browser name (Chrome, Firefox, Safari, ...). */
    BROWSER("browser"),

    /** Operating system (Windows, macOS, iOS, ...). */
    OS("os"),

    /** Device type: mobile, tablet, desktop, unknown. */
    DEVICE("device"),

    /** Country name. */
    COUNTRY("country"),

    /** City name. */
    CITY("city"),

    /** Referrer URL. */
    REFERRER("referrer"),

    /** `utm_source` tag; `(none)` matches untagged clicks. */
    UTM_SOURCE("utm_source"),

    /** `utm_medium` tag. */
    UTM_MEDIUM("utm_medium"),

    /** `utm_campaign` tag. */
    UTM_CAMPAIGN("utm_campaign"),
}

/** Response scope marker. */
@Serializable(with = StatsScope.Companion::class)
public enum class StatsScope {
    /** The owner's aggregate. */
    ALL,

    /** The public stats page's frozen contract for anonymous links. */
    ANON,

    /** A scope this SDK version does not know yet. */
    UNKNOWN,
    ;

    internal companion object : LenientEnumSerializer<StatsScope>(
        "me.spoo.StatsScope",
        entries,
        UNKNOWN,
        { it.name.lowercase() },
    )
}

/** Summary block of a statistics response. */
@Serializable
public data class StatsSummary(
    /** Total clicks in the range. */
    @SerialName("total_clicks") val totalClicks: Long,
    /** Unique visitors in the range. */
    @SerialName("unique_clicks") val uniqueClicks: Long,
    /** First click in the range. */
    @SerialName("first_click") val firstClick: Instant? = null,
    /** Most recent click in the range. */
    @SerialName("last_click") val lastClick: Instant? = null,
    /** Average redirection latency, milliseconds. */
    @SerialName("avg_redirection_time") val avgRedirectionTime: Double? = null,
)

/** The time range a response covers. */
@Serializable
public data class TimeRange(
    /** Range start. */
    @SerialName("start_date") val startDate: Instant? = null,
    /** Range end. */
    @SerialName("end_date") val endDate: Instant? = null,
)

/** Time bucketing metadata, present when grouping by time. */
@Serializable
public data class TimeBucketInfo(
    /** Bucketing strategy the server chose. */
    val strategy: String,
    /** The server's bucket format string. */
    @SerialName("mongo_format") val mongoFormat: String,
    /** Suggested display format. */
    @SerialName("display_format") val displayFormat: String,
    /** Timezone the buckets are aligned to. */
    val timezone: String,
    /** Bucket width in minutes, for sub-daily strategies. */
    @SerialName("interval_minutes") val intervalMinutes: Long? = null,
)

/** Derived rates the server computes over the range. */
@Serializable
public data class ComputedMetrics(
    /** uniqueClicks / totalClicks. */
    @SerialName("unique_click_rate") val uniqueClickRate: Double,
    /** 1 - uniqueClickRate. */
    @SerialName("repeat_click_rate") val repeatClickRate: Double,
    /** totalClicks / unique visitors. */
    @SerialName("average_clicks_per_visitor") val averageClicksPerVisitor: Double,
)

/**
 * A statistics breakdown (`GET /api/v1/stats`).
 *
 * [metrics] is keyed `{metric}_by_{dimension}` (for example
 * `clicks_by_browser`); each value is a list of data points whose keys are
 * the dimension name, the metric name and `{metric}_percentage`. The keys
 * are dynamic by design, so data points stay raw [JsonObject]s.
 */
@Serializable
public data class StatsReport(
    /** Response scope marker. */
    val scope: StatsScope,
    /** The filters the server applied. */
    val filters: Map<String, List<String>> = emptyMap(),
    /** The grouping dimensions applied. */
    @SerialName("group_by") val groupBy: List<String> = emptyList(),
    /** Timezone of the response. */
    val timezone: String,
    /** The covered range. */
    @SerialName("time_range") val timeRange: TimeRange,
    /** Totals over the range. */
    val summary: StatsSummary,
    /** Breakdown series, keyed `{metric}_by_{dimension}`. */
    val metrics: Map<String, List<JsonObject>> = emptyMap(),
    /** When the server produced the response. */
    @SerialName("generated_at") val generatedAt: Instant? = null,
    /** Alias echo, present when sliced to one link. */
    @SerialName("short_code") val shortCode: String? = null,
    /** Time bucketing metadata, when grouping by time. */
    @SerialName("time_bucket_info") val timeBucketInfo: TimeBucketInfo? = null,
    /** Derived rates, when the server includes them. */
    @SerialName("computed_metrics") val computedMetrics: ComputedMetrics? = null,
)

/**
 * A single link's statistics (`GET /api/v1/stats/links/{url_id}`): the
 * standard wire plus the identity of the selected link.
 */
@Serializable
public data class LinkStatsReport(
    /** Response scope marker. */
    val scope: StatsScope,
    /** The filters the server applied. */
    val filters: Map<String, List<String>> = emptyMap(),
    /** The grouping dimensions applied. */
    @SerialName("group_by") val groupBy: List<String> = emptyList(),
    /** Timezone of the response. */
    val timezone: String,
    /** The covered range. */
    @SerialName("time_range") val timeRange: TimeRange,
    /** Totals over the range. */
    val summary: StatsSummary,
    /** Breakdown series, keyed `{metric}_by_{dimension}`. */
    val metrics: Map<String, List<JsonObject>> = emptyMap(),
    /** When the server produced the response. */
    @SerialName("generated_at") val generatedAt: Instant? = null,
    /** Alias echo. */
    @SerialName("short_code") val shortCode: String? = null,
    /** Time bucketing metadata, when grouping by time. */
    @SerialName("time_bucket_info") val timeBucketInfo: TimeBucketInfo? = null,
    /** Derived rates, when the server includes them. */
    @SerialName("computed_metrics") val computedMetrics: ComputedMetrics? = null,
    /** The selected link's id. */
    @SerialName("url_id") val urlId: String,
    /** The selected link's alias. */
    val alias: String,
)

/** Shared statistics query options. */
public data class StatsQuery(
    /** Start of the time range. Defaults to 7 days before the end. */
    val startDate: Instant? = null,
    /** End of the time range. Defaults to now. */
    val endDate: Instant? = null,
    /** Grouping dimensions. Defaults to time. */
    val groupBy: List<Dimension> = emptyList(),
    /** Metrics to include. Defaults to both. */
    val metrics: List<Metric> = emptyList(),
    /** IANA timezone for bucketing and display. Defaults to UTC. */
    val timezone: String? = null,
    /** Per-dimension click filters. Values are case-sensitive. */
    val filters: Map<FilterDimension, List<String>> = emptyMap(),
)

/** Query for [Stats.account]: [StatsQuery] plus the aggregate-only slicers. */
public data class AccountStatsRequest(
    /** The shared query options. */
    val query: StatsQuery = StatsQuery(),
    /** Slice the aggregate to these aliases. */
    val shortCodes: List<String> = emptyList(),
    /** Slice to these link ids; ids you do not own match nothing. */
    val urlIds: List<String> = emptyList(),
)

/** Export file format. */
public enum class ExportFormat(internal val wire: String, internal val extension: String) {
    /** JSON document. */
    JSON("json", "json"),

    /** CSV files, zipped together. */
    CSV("csv", "zip"),

    /** Excel workbook. */
    XLSX("xlsx", "xlsx"),

    /** XML document. */
    XML("xml", "xml"),
}

/**
 * A downloaded export. [filename] is reduced to a safe bare name before you
 * see it, so joining it into a directory cannot traverse out of it;
 * choosing a safe directory remains your job.
 */
public class SpooExport internal constructor(
    /** Server-suggested filename, sanitized to a bare name. */
    public val filename: String,
    /** The response content type. */
    public val contentType: String,
    private val response: HttpResponse,
) {
    /** Buffer the whole body. Fine for typical exports. */
    public suspend fun bytes(): ByteArray = response.bodyAsBytes()

    /** Stream the body without buffering it. */
    public suspend fun bodyChannel(): ByteReadChannel = response.bodyAsChannel()
}

/** Statistics and exports, from [SpooClient.stats]. */
public class Stats internal constructor(
    private val transport: Transport,
) {
    /** Account-wide statistics across all your links. */
    public suspend fun account(request: AccountStatsRequest = AccountStatsRequest()): StatsReport {
        val query = request.query.toQuery(
            shortCodes = request.shortCodes,
            urlIds = request.urlIds,
        )
        val response = transport.send(HttpMethod.Get, "/api/v1/stats", query)
        return transport.decode(response)
    }

    /** One link's statistics, addressed by id. */
    public suspend fun forLink(urlId: String, query: StatsQuery = StatsQuery()): LinkStatsReport {
        val response = transport.send(
            HttpMethod.Get,
            "/api/v1/stats/links/${encodeSegment(urlId)}",
            query.toQuery(),
        )
        return transport.decode(response)
    }

    /**
     * Download the account-wide export. Its filenames are constants server
     * side; per-link downloads named after the link come from [exportLink].
     */
    public suspend fun export(
        format: ExportFormat? = null,
        query: StatsQuery = StatsQuery(),
    ): SpooExport = download("/api/v1/export", format, query)

    /** Download one link's export, named after the link by the server. */
    public suspend fun exportLink(
        urlId: String,
        format: ExportFormat? = null,
        query: StatsQuery = StatsQuery(),
    ): SpooExport = download("/api/v1/export/links/${encodeSegment(urlId)}", format, query)

    private suspend fun download(
        path: String,
        format: ExportFormat?,
        query: StatsQuery,
    ): SpooExport {
        val params = query.toQuery().toMutableList()
        format?.let { params.add(0, "format" to it.wire) }
        val response = transport.send(HttpMethod.Get, path, params)
        val fallback = "spoo-export.${format?.extension ?: "json"}"
        return SpooExport(
            filename = contentDispositionFilename(response.headers["Content-Disposition"], fallback),
            contentType = response.headers["Content-Type"] ?: "application/octet-stream",
            response = response,
        )
    }
}

internal fun StatsQuery.toQuery(
    shortCodes: List<String> = emptyList(),
    urlIds: List<String> = emptyList(),
): List<Pair<String, String>> {
    val query = mutableListOf<Pair<String, String>>()
    startDate?.let { query.add("start_date" to it.toString()) }
    endDate?.let { query.add("end_date" to it.toString()) }
    if (groupBy.isNotEmpty()) query.add("group_by" to groupBy.joinToString(",") { it.wire })
    if (metrics.isNotEmpty()) query.add("metrics" to metrics.joinToString(",") { it.wire })
    timezone?.let { query.add("timezone" to it) }
    val allFilters = buildMap<String, List<String>> {
        filters.forEach { (dimension, values) -> put(dimension.wire, values) }
        if (shortCodes.isNotEmpty()) put("short_code", shortCodes)
        if (urlIds.isNotEmpty()) put("url_id", urlIds)
    }
    if (allFilters.isNotEmpty()) {
        val ordered = buildJsonObject {
            allFilters.toList().sortedBy { it.first }.forEach { (key, values) ->
                putJsonArray(key) { values.forEach { add(JsonPrimitive(it)) } }
            }
        }
        query.add(
            "filters" to me.spoo.internal.WireJson.encodeToString(JsonObject.serializer(), ordered),
        )
    }
    return query
}
