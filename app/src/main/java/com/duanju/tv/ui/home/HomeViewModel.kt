package com.duanju.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duanju.tv.AppGraph
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.remote.DefaultSources
import com.duanju.tv.data.remote.HotRanker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 首页状态 */
data class HomeUiState(
    val loading: Boolean = true,
    val error: String = "",
    val hero: List<Drama> = emptyList(),
    val hot: List<Drama> = emptyList(),
    val latest: List<Drama> = emptyList(),
    val finished: List<Drama> = emptyList(),
)

/**
 * 首页信息流。
 *
 * CMS 没有可用的热度字段（by=hits 实测不生效、vod_hits 恒为 0），
 * 因此抓几页列表后本地算热度（[HotRanker]），再拆成「热推 / 最新 / 完结」。
 * 第 1 页返回即出首屏，剩余页在同一个协程里补齐。
 */
class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val pool = ArrayList<Drama>()
    private val seen = HashSet<String>()
    private var running = false

    fun refresh(force: Boolean = false) {
        if (running) return
        if (force) {
            pool.clear()
            seen.clear()
        } else if (pool.isNotEmpty()) {
            publish()
            return
        }
        running = true
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = "")
            val spec = DefaultSources.spec(graph.store.settings.value.sourceId)
            var failure = ""
            for (p in 1..PAGES) {
                runCatching { graph.repository.page(spec, p) }
                    .onSuccess { page ->
                        page.items.forEach { if (seen.add(it.key)) pool += it }
                        publish()
                    }
                    .onFailure { failure = it.message ?: "加载失败" }
            }
            _state.value = if (pool.isEmpty()) {
                _state.value.copy(loading = false, error = failure.ifBlank { "暂无内容" })
            } else {
                _state.value.copy(loading = false, error = "")
            }
            running = false
        }
    }

    /** 切换数据源后重算 */
    fun reloadForSource() {
        pool.clear()
        seen.clear()
        refresh(force = true)
    }

    private fun publish() {
        val now = System.currentTimeMillis() / 1000
        val ranked = HotRanker.rank(pool, now)
        _state.value = _state.value.copy(
            hero = ranked.take(HERO),
            hot = ranked.take(ROW),
            latest = pool.sortedByDescending { it.updated }.take(ROW),
            finished = pool.filter { it.remarks.contains("完结") || it.remarks.contains("全集") }
                .let { HotRanker.top(it, ROW, now) },
        )
    }

    private companion object {
        const val PAGES = 3
        const val HERO = 8
        const val ROW = 30
    }
}
