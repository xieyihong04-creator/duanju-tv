package com.duanju.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.duanju.tv.AppGraph
import com.duanju.tv.appGraph

/**
 * 依赖注入：全局只有 5 个对象，用一个 CompositionLocal 传就够了，
 * 不引入 Hilt/Koin 这类框架（APK 体积与构建复杂度不划算）。
 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph?> { null }

val LocalDramaRepository = staticCompositionLocalOf<Any?> { null }

@Composable
fun AppGraphProvider(graph: AppGraph, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppGraph provides graph) { content() }
}

/** 在 DuanjuApp 根部取一次全局容器 */
@Composable
fun rememberAppGraph(): AppGraph {
    val injected = LocalAppGraph.current
    if (injected != null) return injected
    return LocalContext.current.appGraph()
}

/** 取不到就抛说明接线漏了，比静默 null 更好排查 */
@Composable
fun requireGraph(): AppGraph = rememberAppGraph()

/**
 * 带全局依赖的 ViewModel。
 * key 保证同一宿主内只建一个实例，切页返回时状态仍在。
 */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(
    key: String,
    noinline create: (AppGraph) -> VM,
): VM {
    val graph = rememberAppGraph()
    return viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = create(graph) as T
        },
    )
}
