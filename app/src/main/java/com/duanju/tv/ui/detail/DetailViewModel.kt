package com.duanju.tv.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duanju.tv.AppGraph
import com.duanju.tv.data.media.DljDownloadManager
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Episode
import com.duanju.tv.data.model.HistoryEntry
import com.duanju.tv.data.model.PlayGroup
import com.duanju.tv.data.model.Resolved
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 详情页当前的线路与选集分页 */
data class DetailState(
    val key: String = "",
    /** 补齐分集后的剧集对象；播放器要用它，列表传进来的往往只是剧壳 */
    val drama: Drama? = null,
    val lineName: String = "",
    val page: Int = 1,
    val episodes: List<Episode> = emptyList(),
    val favorite: Boolean = false,
    val progress: HistoryEntry? = null,
    val caching: Boolean = false,
    val cacheMessage: String = "",
    val cachedCount: Int = 0,
    /** 红果这类源列表里没有分集，进详情页要再拉一次 */
    val loadingEpisodes: Boolean = false,
    val episodeError: String = "",
) {
    companion object {
        const val PAGE_SIZE = 50
    }

    fun pageCount(total: Int): Int = if (total <= 0) 1 else (total + PAGE_SIZE - 1) / PAGE_SIZE

    fun window(): List<Episode> =
        episodes.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
}

/**
 * 详情页模型：线路切换、选集分页、收藏、整剧缓存。
 *
 * 线路偏好写进 Settings，之后所有剧集都优先用这条线；
 * 缓存逐集现场解析，因为 share 页地址带时效签名，提前存的会过期。
 */
class DetailViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    /** 进入详情页或换了剧集时调用 */
    fun bind(drama: Drama) {
        apply(drama, graph.store.settings.value.preferredLine.let { pref ->
            drama.playGroups.firstOrNull { it.name == pref }
                ?: drama.playGroups.maxByOrNull { it.episodes.size }
        })
        // 列表接口只给剧壳、不给分集（红果 landpage/网页分类都是这样），
        // 不在这里补齐就永远显示「没有可播放的分集」，也没法播放或缓存。
        if (needsEpisodes(drama)) loadEpisodes(drama)
    }

    private fun needsEpisodes(drama: Drama): Boolean =
        drama.sourceId == "hongguo" && drama.playGroups.none { it.episodes.isNotEmpty() }

    private fun loadEpisodes(drama: Drama) {
        if (_state.value.loadingEpisodes) return
        _state.value = _state.value.copy(loadingEpisodes = true, episodeError = "")
        viewModelScope.launch {
            val full = runCatching { graph.repository.withEpisodes(drama) }
                .getOrElse {
                    _state.value = _state.value.copy(
                        loadingEpisodes = false,
                        episodeError = it.message ?: "分集加载失败",
                    )
                    return@launch
                }
            // 拉取期间用户可能已经切走了，只更新同一部剧
            if (_state.value.key != full.key) return@launch
            apply(full, full.bestGroup)
            _state.value = _state.value.copy(loadingEpisodes = false)
            // 播放器按 key 从登记表取剧集对象：不回填的话它拿到的还是没有分集的剧壳
            com.duanju.tv.ui.DramaRegistry.put(full)
        }
    }

    private fun apply(drama: Drama, group: PlayGroup?) {
        _state.value = _state.value.copy(
            key = drama.key,
            drama = drama,
            lineName = group?.name ?: "",
            episodes = group?.episodes ?: emptyList(),
            page = 1,
            favorite = graph.store.isFavorite(drama),
            progress = graph.store.progressOf(drama.key),
            cachedCount = DljDownloadManager.completedEpisodes(drama.key).size,
        )
    }

    fun selectLine(name: String, drama: Drama) {
        val group = drama.playGroups.firstOrNull { it.name == name } ?: return
        graph.store.updateSettings { it.copy(preferredLine = name) }
        _state.value = _state.value.copy(lineName = name, episodes = group.episodes, page = 1)
    }

    fun selectPage(page: Int, total: Int) {
        _state.value = _state.value.copy(page = page.coerceIn(1, _state.value.pageCount(total)))
    }

    fun toggleFavorite(drama: Drama) {
        val added = graph.store.toggleFavorite(drama)
        _state.value = _state.value.copy(favorite = added)
    }

    /** 从播放器返回时刷新进度与缓存数 */
    fun refresh(drama: Drama) {
        _state.value = _state.value.copy(
            favorite = graph.store.isFavorite(drama),
            progress = graph.store.progressOf(drama.key),
            cachedCount = DljDownloadManager.completedEpisodes(drama.key).size,
        )
    }

    /** 当前线路的某一集（供播放器取地址） */
    fun group(drama: Drama): PlayGroup? =
        drama.playGroups.firstOrNull { it.name == _state.value.lineName }

    fun cacheAll(context: Context, drama: Drama) {
        if (_state.value.caching) return
        val episodes = _state.value.episodes
        if (episodes.isEmpty()) {
            _state.value = _state.value.copy(cacheMessage = "这部剧没有可用剧集")
            return
        }
        _state.value = _state.value.copy(caching = true, cacheMessage = "正在解析播放地址…")
        viewModelScope.launch {
            var submitted = 0
            var failed = 0
            episodes.forEach { ep ->
                if (DljDownloadManager.completedEpisodes(drama.key).contains(ep.index)) {
                    submitted++
                } else {
                    when (val resolved = graph.repository.resolveEpisode(ep)) {
                        is Resolved.Direct -> {
                            DljDownloadManager.enqueue(context, drama, ep, resolved.url)
                            submitted++
                        }
                        is Resolved.Failed -> failed++
                    }
                }
                _state.value = _state.value.copy(
                    cacheMessage = "已提交 ${submitted + failed}/${episodes.size}",
                    cachedCount = submitted,
                )
            }
            _state.value = _state.value.copy(
                caching = false,
                cacheMessage = if (failed == 0) "已提交 $submitted 集缓存"
                else "已提交 $submitted 集，$failed 集解析失败",
            )
        }
    }

    fun cancelCache(context: Context, drama: Drama) {
        DljDownloadManager.cancelDrama(context, drama.key)
        _state.value = _state.value.copy(caching = false, cacheMessage = "已取消本剧缓存任务", cachedCount = 0)
    }
}
