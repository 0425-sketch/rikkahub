package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity

// A rate may only compare cache reads and input tokens from the same reported samples.
private const val KNOWN_CACHE_SAMPLE =
    "prompt_tokens_reported = 1 AND cached_tokens_reported = 1 AND prompt_tokens >= cached_tokens AND cached_tokens >= 0"
private const val CACHE_USAGE_PROJECTION =
    "COUNT(*) AS requestCount, " +
        "COALESCE(SUM(CASE WHEN prompt_tokens_reported = 1 THEN 1 ELSE 0 END), 0) AS promptReportedRequestCount, " +
        "COALESCE(SUM(CASE WHEN cached_tokens_reported = 1 THEN 1 ELSE 0 END), 0) AS cacheReportedRequestCount, " +
        "COALESCE(SUM(CASE WHEN " + KNOWN_CACHE_SAMPLE + " THEN 1 ELSE 0 END), 0) AS cacheRateRequestCount, " +
        "COALESCE(SUM(CASE WHEN " + KNOWN_CACHE_SAMPLE + " THEN prompt_tokens ELSE 0 END), 0) AS cacheRatePromptTokens, " +
        "COALESCE(SUM(CASE WHEN " + KNOWN_CACHE_SAMPLE + " THEN cached_tokens ELSE 0 END), 0) AS cacheRateCachedTokens, " +
        "SUM(cache_write_tokens) AS cacheWriteTokens, " +
        "COALESCE(SUM(CASE WHEN cache_write_tokens IS NOT NULL THEN 1 ELSE 0 END), 0) AS cacheWriteReportedRequestCount "

@Dao
interface UsageRecordDAO {
    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Query(
        "SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens, " + CACHE_USAGE_PROJECTION +
            "FROM usage_record WHERE created_at >= :since"
    )
    suspend fun getTotals(since: Long = 0): UsageTotals

    /** 按本地时间分桶汇总，[format] 是 strftime 格式，比如按天用 `%Y-%m-%d`、按月用 `%Y-%m` */
    @Query(
        "SELECT strftime(:format, created_at / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens, " + CACHE_USAGE_PROJECTION +
            "FROM usage_record WHERE created_at >= :since GROUP BY bucket ORDER BY bucket"
    )
    suspend fun getTotalsByBucket(format: String, since: Long): List<UsageBucket>

    /** 每天（本地日期 `yyyy-MM-dd`）某类请求的次数 */
    @Query(
        "SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count " +
            "FROM usage_record WHERE source = :source AND created_at >= :since GROUP BY day"
    )
    suspend fun getRequestCountPerDay(source: String, since: Long): List<DayCount>

    // 回填的旧记录可能只有 model_id 没有 model_name，按 model_id 归并，名称取任意一条非空的
    @Query(
        "SELECT model_id AS modelId, MAX(model_name) AS modelName, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens, " + CACHE_USAGE_PROJECTION +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY COALESCE(model_id, model_name) " +
            "ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsByModel(since: Long): List<ModelUsage>

    @Query(
        "SELECT source, SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens, " + CACHE_USAGE_PROJECTION +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY source ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsBySource(since: Long): List<SourceUsage>
}

data class UsageTotals(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    @Embedded val cache: CacheUsageSummary = CacheUsageSummary(),
)

data class DayCount(
    val day: String,
    val count: Int,
)

data class UsageBucket(
    val bucket: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    @Embedded val cache: CacheUsageSummary = CacheUsageSummary(),
)

data class ModelUsage(
    val modelId: String?,
    val modelName: String?,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    @Embedded val cache: CacheUsageSummary = CacheUsageSummary(),
)

data class SourceUsage(
    val source: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    @Embedded val cache: CacheUsageSummary = CacheUsageSummary(),
)

/** Coverage is kept next to the counts so missing cache fields never become a zero-percent claim. */
data class CacheUsageSummary(
    val requestCount: Long = 0,
    val promptReportedRequestCount: Long = 0,
    val cacheReportedRequestCount: Long = 0,
    val cacheRateRequestCount: Long = 0,
    val cacheRatePromptTokens: Long = 0,
    val cacheRateCachedTokens: Long = 0,
    val cacheWriteTokens: Long? = null,
    val cacheWriteReportedRequestCount: Long = 0,
) {
    val hitRatePercent: Double?
        get() = if (cacheRateRequestCount > 0 && cacheRatePromptTokens > 0 &&
            cacheRateCachedTokens in 0..cacheRatePromptTokens
        ) cacheRateCachedTokens * 100.0 / cacheRatePromptTokens else null

    val cacheReadIsPartial: Boolean
        get() = cacheReportedRequestCount < requestCount

    val cacheWriteIsPartial: Boolean
        get() = cacheWriteReportedRequestCount < requestCount
}
