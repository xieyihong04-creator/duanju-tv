package com.duanju.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.duanju.tv.data.model.Drama

/**
 * 跨页面传递选中剧集。
 *
 * 不用序列化进 NavArgument：Drama 含全部分集地址，Bundle 上限 512KB，
 * 长剧（几百集）会直接 TransactionTooLargeException。页面切换在同进程内，
 * 用一个有界登记表最稳妥。
 */
@Stable
object DramaRegistry {

    private val map = LinkedHashMap<String, Drama>()
    private const val MAX = 40

    var current: Drama? by mutableStateOf(null)
        private set

    fun put(drama: Drama): String {
        map[drama.key] = drama
        if (map.size > MAX) {
            val it = map.entries.iterator()
            while (map.size > MAX && it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        current = drama
        return drama.key
    }

    fun get(key: String?): Drama? {
        if (key == null) return null
        return map[key]?.also { current = it }
    }
}

/** 返回目标：播放器退出后是否要回详情页 */
object NavArgs {
    const val KEY = "key"
    const val EPISODE = "episode"
    const val POSITION = "position"
}
