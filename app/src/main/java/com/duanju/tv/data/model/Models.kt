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
) {
    val episodeCount: Int get() = playGroups.maxOfOrNull { it.episodes.size } ?: 0

    /** 同一部剧在聚合源中的唯一键 */
    val key: String get() = "$sourceId#$id"

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
        get() = rawUrl.contains(".m3u8") || rawUrl.contains(".mp4") || rawUrl.contains(".mpd")
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
) {
    val key: String get() = "$sourceId#$dramaId"
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
) {
    val key: String get() = "$sourceId#$dramaId"

    val percent: Int
        get() = if (durationMs <= 0) 0 else (positionMs * 100 / durationMs).toInt().coerceIn(0, 100)
}
