package com.duanju.tv.data.local

import android.content.Context
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.FavoriteEntry
import com.duanju.tv.data.model.HistoryEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/** 用户偏好，全部有合理默认值，新增字段不影响旧文件读取 */
@Serializable
data class Settings(
    /** 首页/详情页信息流使用的数据源 id，或 [com.duanju.tv.data.remote.SourceSpec.AGGREGATE_ID] */
    val sourceId: String = "aggregate",
    /** 播放时优先使用的线路名 */
    val preferredLine: String = "",
    val autoPlayNext: Boolean = true,
    val resumePlayback: Boolean = true,
    val defaultSpeed: Float = 1.0f,
    /** 跳过片头/片尾秒数 */
    val skipIntroSeconds: Int = 0,
    val skipEndingSeconds: Int = 0,
    val uiScale: Float = 1.0f,
    val immersiveDetail: Boolean = true,
    val dimBackground: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showSourceBadge: Boolean = true,
    /** 最近搜索词，最多 8 条 */
    val recentKeywords: List<String> = emptyList(),
)

/**
 * 收藏 / 历史 / 偏好设置。
 *
 * 读进内存后用 StateFlow 驱动 UI，写盘走单线程 executor，避免并发写坏文件。
 */
class LocalStore private constructor(context: Context) {

    private val dir = File(context.filesDir, "store").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor()

    private val _favorites = MutableStateFlowOf(emptyList<FavoriteEntry>())
    val favorites = _favorites.flow

    private val _history = MutableStateFlowOf(emptyList<HistoryEntry>())
    val history = _history.flow

    private val _settings = MutableStateFlowOf(Settings())
    val settings = _settings.flow

    init {
        io.execute {
            _favorites.set(readJson<List<FavoriteEntry>>(FAV) ?: emptyList())
            _history.set(readJson<List<HistoryEntry>>(HIS) ?: emptyList())
            _settings.set(readJson<Settings>(SET) ?: Settings())
        }
    }

    fun isFavorite(drama: Drama): Boolean = _favorites.value.any { it.key == drama.key }

    fun toggleFavorite(drama: Drama): Boolean {
        val exists = isFavorite(drama)
        val list = if (exists) {
            _favorites.value.filterNot { it.key == drama.key }
        } else {
            listOf(
                FavoriteEntry(
                    sourceId = drama.sourceId,
                    dramaId = drama.id,
                    name = drama.name,
                    pic = drama.pic,
                    remarks = drama.remarks,
                    addedAt = System.currentTimeMillis(),
                    drama = drama,
                ),
            ) + _favorites.value
        }
        _favorites.set(list)
        writeJson(FAV, list)
        return !exists
    }

    fun removeFavorite(key: String) {
        _favorites.set(_favorites.value.filterNot { it.key == key })
        writeJson(FAV, _favorites.value)
    }

    /** 观看进度：写入历史并置顶 */
    fun saveProgress(
        drama: Drama,
        episodeIndex: Int,
        episodeTitle: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        val entry = HistoryEntry(
            sourceId = drama.sourceId,
            dramaId = drama.id,
            name = drama.name,
            pic = drama.pic,
            remarks = drama.remarks,
            episodeIndex = episodeIndex,
            episodeTitle = episodeTitle,
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0),
            updatedAt = System.currentTimeMillis(),
            drama = drama,
        )
        val list = (listOf(entry) + _history.value.filterNot { it.key == drama.key }).take(MAX_HISTORY)
        _history.set(list)
        writeJson(HIS, list)
    }

    fun progressOf(key: String): HistoryEntry? = _history.value.firstOrNull { it.key == key }

    fun removeHistory(key: String) {
        _history.set(_history.value.filterNot { it.key == key })
        writeJson(HIS, _history.value)
    }

    fun clearHistory() {
        _history.set(emptyList())
        writeJson(HIS, emptyList<HistoryEntry>())
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.set(next)
        writeJson(SET, next)
    }

    /** 记录搜索词，最近 8 条，新的置顶去重 */
    fun rememberKeyword(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        updateSettings { s -> s.copy(recentKeywords = (listOf(w) + s.recentKeywords.filter { it != w }).take(8)) }
    }

    private inline fun <reified T> readJson(name: String): T? = try {
        val f = File(dir, name)
        if (f.exists() && f.length() > 0) json.decodeFromString<T>(f.readText()) else null
    } catch (_: Exception) {
        null
    }

    private inline fun <reified T> writeJson(name: String, value: T) {
        val text = try {
            json.encodeToString(value)
        } catch (_: Exception) {
            return
        }
        io.execute {
            try {
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(dir, name))
            } catch (_: Exception) {
            }
        }
    }

    /** 包一层，让 set() 与 value 读写意图清晰 */
    private class MutableStateFlowOf<T>(initial: T) {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
        val flow: kotlinx.coroutines.flow.StateFlow<T> = state
        val value: T get() = state.value
        fun set(v: T) {
            state.value = v
        }
    }

    companion object {
        private const val FAV = "favorites.json"
        private const val HIS = "history.json"
        private const val SET = "settings.json"
        private const val MAX_HISTORY = 200

        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Volatile
        private var instance: LocalStore? = null

        fun get(context: Context): LocalStore =
            instance ?: synchronized(this) {
                instance ?: LocalStore(context.applicationContext).also { instance = it }
            }
    }
}
