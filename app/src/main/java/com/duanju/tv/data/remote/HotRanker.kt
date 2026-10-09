package com.duanju.tv.data.remote

import com.duanju.tv.data.model.Drama
import kotlin.math.ln
import kotlin.math.max

/**
 * 热度排序。
 *
 * CMS 的 by=hits/by=score 参数实测不生效（仍按更新时间返回），
 * vod_hits 也常年为 0，因此用「新鲜度 + 集数 + 完结」自建热度分，
 * 这比原样返回更贴近「大家都在看」的观感。
 */
object HotRanker {

    /** 时间分：按更新时间衰减，半衰期约 5 天 */
    fun timeScore(epochSeconds: Long, nowSeconds: Long): Double {
        val ageDays = max(0.0, (nowSeconds - epochSeconds) / 86_400.0)
        return 1.0 / (1.0 + ageDays / 5.0)
    }

    fun volumeScore(episodes: Int): Double = if (episodes <= 1) 0.0 else ln(episodes.toDouble()) / ln(120.0)

    fun completedBonus(remarks: String): Double =
        if (remarks.contains("完结") || remarks.contains("全集") || remarks.contains("全")) 0.15 else 0.0

    /**
     * 综合热度分。
     * 权重：时间 0.55、集数 0.30、完结 0.15。
     */
    fun score(drama: Drama, nowSeconds: Long): Double {
        val epoch = parseTime(drama.updated) ?: (drama.id % 1_000_000L) // 缺时间时退化用 id 造序
        return 0.55 * timeScore(epoch, nowSeconds) +
            0.30 * volumeScore(drama.episodeCount) +
            completedBonus(drama.remarks)
    }

    fun rank(items: List<Drama>, nowSeconds: Long = System.currentTimeMillis() / 1000): List<Drama> =
        items.sortedByDescending { score(it, nowSeconds) }

    /** 取前 n 部，用于首页推荐位 */
    fun top(items: List<Drama>, n: Int, nowSeconds: Long = System.currentTimeMillis() / 1000): List<Drama> =
        rank(items, nowSeconds).take(n)

    private val timeRegex = Regex("""(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?""")

    fun parseTime(v: String): Long? {
        val m = timeRegex.find(v.trim()) ?: return null
        val g = m.groupValues
        return runCatching {
            val cal = java.util.Calendar.getInstance()
            cal.clear()
            cal.set(
                g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
                g.getOrNull(4)?.toInt() ?: 0, g.getOrNull(5)?.toInt() ?: 0, g.getOrNull(6)?.toInt() ?: 0,
            )
            cal.timeInMillis / 1000
        }.getOrNull()
    }
}
