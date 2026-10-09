package com.duanju.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.duanju.tv.data.local.LocalStore
import com.duanju.tv.data.remote.BrowserHeadersInterceptor
import com.duanju.tv.ui.DuanjuApp
import com.duanju.tv.ui.common.AppGraphProvider
import com.duanju.tv.ui.theme.DuanjuTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV 上必须真正沉浸式，否则左右各留 10% 安全区，海报墙会变小
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        keepScreenOnIfNeeded()

        val graph = appGraph()
        setContent {
            // 海报 CDN 常拒绝非浏览器 UA（403）：全局给图片请求补浏览器头
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components { add(BrowserHeadersInterceptor) }
                    .build()
            }
            val settings by graph.store.settings.collectAsState()
            DuanjuTheme(dark = true, uiScale = settings.uiScale) {
                AppGraphProvider(graph) {
                    DuanjuApp(store = graph.store)
                }
            }
        }
    }

    private fun keepScreenOnIfNeeded() {
        val keep = runCatching { LocalStore.get(this).settings.value.keepScreenOn }.getOrDefault(true)
        if (keep) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }
}
