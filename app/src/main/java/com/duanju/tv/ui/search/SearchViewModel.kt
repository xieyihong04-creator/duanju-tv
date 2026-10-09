package com.duanju.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duanju.tv.AppGraph
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.remote.DefaultSources
import com.duanju.tv.ui.category.PagedUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索模型。
 *
 * 关键词提交后走聚合搜索（各源 wd 参数），空结果时仓库内部会用分类列表做包含匹配兜底。
 * 支持继续翻页，翻页沿用同一个关键词。
 */
class SearchViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(PagedUiState())
    val state: StateFlow<PagedUiState> = _state.asStateFlow()

    private var keyword: String = ""
    private var spec = DefaultSources.AGGREGATE

    fun search(word: String) {
        val w = word.trim()
        if (w.isEmpty()) {
            _state.value = PagedUiState(error = "")
            return
        }
        keyword = w
        spec = DefaultSources.spec(graph.store.settings.value.sourceId)
        _state.value = PagedUiState(loading = true)
        viewModelScope.launch { run(w, 1) }
    }

    fun loadMore() {
        val s = _state.value
        if (keyword.isEmpty() || s.loading || s.loadingMore || !s.hasMore) return
        _state.value = s.copy(loadingMore = true)
        viewModelScope.launch { run(keyword, s.page + 1) }
    }

    private suspend fun run(word: String, page: Int) {
        val merged = ArrayList(_state.value.items)
        val result = runCatching { graph.repository.search(spec, word, page) }
        val found = result.getOrNull()
        if (found == null && merged.isEmpty()) {
            _state.value = PagedUiState(error = "搜索失败，请检查网络后重试")
            return
        }
        val keys = HashSet<String>()
        val list = ArrayList<Drama>()
        merged.forEach { if (keys.add(it.key)) list += it }
        found?.items?.forEach { if (keys.add(it.key)) list += it }
        _state.value = PagedUiState(
            items = list,
            page = found?.page ?: page,
            pageCount = found?.pageCount ?: 1,
            total = found?.total ?: list.size,
        )
    }
}
