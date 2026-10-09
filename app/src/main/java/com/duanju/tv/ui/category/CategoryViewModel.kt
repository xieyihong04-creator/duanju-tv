package com.duanju.tv.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duanju.tv.AppGraph
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.remote.DefaultSources
import com.duanju.tv.data.remote.SourceSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 分页状态。分类页与「更多」共用。
 */
data class PagedUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String = "",
    val items: List<Drama> = emptyList(),
    val page: Int = 0,
    val pageCount: Int = 1,
    val total: Int = 0,
) {
    val hasMore: Boolean get() = page < pageCount
    val isEmpty: Boolean get() = !loading && items.isEmpty()
}

/**
 * 分类/榜单分页模型。
 *
 * 分类 id 由 CMS 的 ac=list 自动发现（不写死），tag 过滤在本地做：
 * 服务端没有可用的 tag 参数，客户端按已抓到的数据筛，翻页时再补抓。
 */
class CategoryViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(PagedUiState())
    val state: StateFlow<PagedUiState> = _state.asStateFlow()

    private val seen = HashSet<String>()
    private var currentTag: String? = null
    private var selectedSource: String = SourceSpec.AGGREGATE_ID

    fun load(reset: Boolean) {
        if (_state.value.loading || _state.value.loadingMore) return
        if (reset) {
            currentTag = null
            _state.value = PagedUiState(loading = true)
            selectedSource = graph.store.settings.value.sourceId
        }
        val spec = DefaultSources.spec(selectedSource)
        val nextPage = if (reset) 1 else _state.value.page + 1
        _state.value = _state.value.copy(loading = !reset, loadingMore = !reset, error = "")
        viewModelScope.launch {
            runCatching { graph.repository.page(spec, nextPage) }
                .onSuccess { page -> append(page.items, page.page, page.pageCount, page.total, reset) }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        loadingMore = false,
                        error = e.message ?: "加载失败",
                    )
                }
            _state.value = _state.value.copy(loading = false, loadingMore = false)
        }
    }

    fun loadMore() {
        if (_state.value.hasMore && !_state.value.loading && !_state.value.loadingMore) load(reset = false)
    }

    fun selectedTag(): String? = currentTag

    /** 已抓取数据里的标签分布，供筛选条展示 */
    fun tagUniverse(): List<Pair<String, Int>> {
        val map = LinkedHashMap<String, Int>()
        pool.forEach { d ->
            d.tag.split(",", "，", "、", "/").map { it.trim() }
                .filter { it.length in 2..8 }
                .forEach { map[it] = (map[it] ?: 0) + 1 }
        }
        return map.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** 本地标签过滤：命中不足时自动补抓，避免过滤后一屏空 */
    fun selectTag(tag: String?) {
        if (currentTag == tag) return
        currentTag = tag
        val cached = if (tag == null) pool.toList() else pool.filter { matches(it, tag) }
        if (cached.isNotEmpty()) {
            _state.value = _state.value.copy(items = cached, total = if (tag == null) _state.value.total else cached.size)
        }
        if (cached.size >= MIN_TAG_HITS || _state.value.page >= _state.value.pageCount) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val spec = DefaultSources.spec(selectedSource)
            var guard = 0
            while (guard < MAX_REFETCH && _state.value.page < _state.value.pageCount) {
                guard++
                val next = _state.value.page + 1
                val result = runCatching { graph.repository.page(spec, next) }
                val page = result.getOrNull() ?: break
                append(page.items, page.page, page.pageCount, page.total, false)
                val hit = if (tag == null) pool.size else pool.count { matches(it, tag) }
                if (hit >= MIN_TAG_HITS) break
            }
            _state.value = _state.value.copy(loading = false, loadingMore = false)
        }
    }

    /** 已抓到的全量（含被 tag 过滤掉的），切换标签时无需重新请求 */
    private val pool = ArrayList<Drama>()

    private fun append(items: List<Drama>, page: Int, pageCount: Int, total: Int, reset: Boolean) {
        if (reset) {
            pool.clear()
            seen.clear()
        }
        items.forEach { if (seen.add(it.key)) pool += it }
        val tag = currentTag
        val visible = if (tag == null) pool.toList() else pool.filter { matches(it, tag) }
        _state.value = _state.value.copy(
            items = visible,
            page = page,
            pageCount = pageCount,
            total = if (tag == null) total else visible.size,
        )
    }

    private fun matches(drama: Drama, tag: String): Boolean =
        drama.tag.contains(tag, ignoreCase = true) ||
            drama.name.contains(tag, ignoreCase = true) ||
            drama.type.contains(tag, ignoreCase = true)

    companion object {
        private const val MAX_REFETCH = 4
        private const val MIN_TAG_HITS = 24
    }
}
