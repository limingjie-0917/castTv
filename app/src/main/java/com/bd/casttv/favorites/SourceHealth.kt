package com.bd.casttv.favorites

import kotlin.math.roundToInt

/**
 * 自定义 Tab「源健康」模型：用于展示与主源自动推荐，不参与第一期 playable 语义判断。
 */
enum class HealthStatus {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
}

/**
 * 单条源的健康检测结果。
 *
 * 注意：
 * - failCount/successCount 为“累计计数”。在 [com.bd.casttv.health.SourceHealthDetector]
 *   中，本次检测只会返回 0/1 计数；由调用方在写入 [com.bd.casttv.settings.SourceHealthStore]
 *   时与旧值累加。
 * - score 采用“滑动/累加式”最终分：写入缓存时由 [SourceHealthScorer.blendedScore] 把本次快照分
 *   与历史成功率融合，避免单次抖动主导排序（详见该方法）。
 */
data class SourceHealthRecord(
    val status: HealthStatus,
    val score: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val startupMs: Long,
    val reason: String,
    val lastCheckMs: Long,
    val failCount: Int,
    val successCount: Int,
) {
    companion object {
        /** 默认未知值：UNKNOWN 视为中等分，避免未检测源总被排到最后。 */
        val UNKNOWN = SourceHealthRecord(
            status = HealthStatus.UNKNOWN,
            score = 50,
            hasVideo = false,
            hasAudio = false,
            startupMs = -1L,
            reason = "",
            lastCheckMs = 0L,
            failCount = 0,
            successCount = 0,
        )
    }
}

data class SourceHealthScoreResult(
    val status: HealthStatus,
    val score: Int,
    val reason: String,
)

object SourceHealthScorer {

    /**
     * 评分规则（建议实现，保持可解释性）：
     * - 不可连接 / 超时 / 无视频 → UNHEALTHY
     * - 可连接但无音频 → DEGRADED
     * - 可连接但启播慢（>4s）→ DEGRADED
     * - 其余 → HEALTHY
     */
    fun score(
        connectable: Boolean,
        hasVideo: Boolean,
        hasAudio: Boolean,
        startupMs: Long,
        reasonOverride: String? = null,
    ): SourceHealthScoreResult {
        if (!connectable) {
            return SourceHealthScoreResult(
                status = HealthStatus.UNHEALTHY,
                score = 0,
                reason = reasonOverride ?: "无法连接",
            )
        }
        if (!hasVideo) {
            // 能连接但没有视频轨道：按 UNHEALTHY 处理，且分数压低，避免被错误推荐。
            return SourceHealthScoreResult(
                status = HealthStatus.UNHEALTHY,
                score = 10,
                reason = reasonOverride ?: "无视频轨道",
            )
        }

        var score = 0
        score += 40 // connectable
        score += 20 // video
        if (hasAudio) score += 20

        val bonus = when {
            startupMs in 0..1_499L -> 20
            startupMs in 0..2_999L -> 12
            startupMs in 0..4_999L -> 5
            else -> 0
        }
        score += bonus

        return when {
            !hasAudio -> SourceHealthScoreResult(HealthStatus.DEGRADED, score, "无声音")
            startupMs > 4_000L -> SourceHealthScoreResult(HealthStatus.DEGRADED, score, "启播慢/卡顿")
            else -> SourceHealthScoreResult(HealthStatus.HEALTHY, score, "稳定")
        }
    }

    /**
     * 滑动/累加式最终分：让历史成功率参与最终分，避免单次偶发抖动主导主源推荐与排序。
     *
     * 最终分 = 当次快照分 × 0.6 + 历史成功率(0..100) × 0.4
     *
     * - successCount/failCount 为写入缓存时已累加的累计值；
     * - 累计样本为空（首次检测）时直接返回当次分，避免用空历史稀释首检结果。
     */
    fun blendedScore(currentScore: Int, successCount: Int, failCount: Int): Int {
        val total = successCount + failCount
        if (total <= 0) return currentScore.coerceIn(0, 100)
        val historyRate = successCount * 100.0 / total
        return (currentScore * 0.6 + historyRate * 0.4).roundToInt().coerceIn(0, 100)
    }
}
