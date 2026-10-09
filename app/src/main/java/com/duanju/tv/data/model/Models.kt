package com.duanju.tv.data.model

import kotlinx.serialization.Serializable

/**
 * 一部短剧（来自 CMS/macCMS 聚合接口的 vod 记录）。
 */
@Serializable
data class Drama(
    val id: Int,
    val sourceId: String,
    val name: String,
    val pic: String,
    val type: String,
    val typeId: Int,
    val remarks: String,
    val year: String,
    val area: String,
    val director: String,
    val actors: String,
    val blurb: String,
    val detail: String,
    val tag: String,
    val score: String,
    val updated: String,
    /** 线路 -> 剧集列表，保留多线路以便切换源 */
    val playGroups: List<PlayGroup>,
    /** 后端真实 ID（如红果 series_id），用于区分同一 sourceId 下的不同 ID 空间；默认 null 兼容旧收藏 */
    val backendId: String? = null,
) {
    val episodeCount: Int get() = playGroups.maxOfOrNull { it.episodes.size } ?: 0

    /** 同一部剧在聚合源中的唯一键：优先用 backendId，回落到 id */
    val key: String get() = entryKey(sourceId, id, backendId)

    val bestGroup: PlayGroup? get() = playGroups.maxByOrNull { it.episodes.size }

    val infoLine: String
        get() = listOf(year, area, type).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        val EMPTY = Drama(
            id = 0, sourceId = "", name = "", pic = "", type = "", typeId = 0,
            remarks = "", year = "", area = "", director = "", actors = "",
            blurb = "", detail = "", tag = "", score = "", updated = "", playGroups = emptyList(),
        )
    }
}

@Serializable
data class PlayGroup(
    val name: String,
    val episodes: List<Episode>,
)

@Serializable
data class Episode(
    val index: Int,
    val title: String,
    /** 原始地址，可能是 m3u8，也可能是播放器分享页 */
    val rawUrl: String,
) {
    val isDirectStream: Boolean
        get() = rawUrl.startsWith("hongguo://") ||
            rawUrl.contains(".m3u8") || rawUrl.contains(".mp4") || rawUrl.contains(".mpd")
}

/** 播放解析结果 */
sealed class Resolved {
    data class Direct(val url: String, val headers: Map<String, String> = emptyMap()) : Resolved()
    data class Failed(val message: String) : Resolved()
}

@Serializable
data class Category(val id: Int, val name: String)

data class SearchPage(
    val items: List<Drama>,
    val page: Int,
    val pageCount: Int,
    val total: Int,
) {
    val hasMore: Boolean get() = page < pageCount
}

@Serializable
data class FavoriteEntry(
    val sourceId: String,
    val dramaId: Int,
    val name: String,
    val pic: String,
    val remarks: String,
    val addedAt: Long,
    /** 缓存完整详情，收藏列表点击后无需重新请求 */
    val drama: Drama? = null,
    /** 与 [Drama.backendId] 对应；红果这类源的 id 是散列值，必须靠它对齐 key */
    val backendId: String? = null,
) {
    /** 有缓存的 Drama 就以它的键为准，兼容 v1.1.0 没写 backendId 的旧收藏 */
    val key: String get() = drama?.key ?: entryKey(sourceId, dramaId, backendId)
}

@Serializable
data class HistoryEntry(
    val sourceId: String,
    val dramaId: Int,
    val name: String,
    val pic: String,
    val remarks: String,
    val episodeIndex: Int,
    val episodeTitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val drama: Drama? = null,
    val backendId: String? = null,
) {
    val key: String get() = drama?.key ?: entryKey(sourceId, dramaId, backendId)

    val percent: Int
        get() = if (durationMs <= 0) 0 else (positionMs * 100 / durationMs).toInt().coerceIn(0, 100)
}

/**
 * 追剧条目的唯一键，必须与 [Drama.key] 同构。
 *
 * 红果这类源的 [Drama.id] 由 series_id 散列得到，只用 id 会和详情页的
 * backendId 键对不上，收藏/进度/缓存就再也找不回来。
 */
fun entryKey(sourceId: String, dramaId: Int, backendId: String?): String =
    "$sourceId#${backendId?.takeIf { it.isNotBlank() } ?: dramaId}"
