package com.duanju.tv

import android.app.Application
import android.content.Context
import com.duanju.tv.data.local.LocalStore
import com.duanju.tv.data.media.PlayerFactory
import com.duanju.tv.data.remote.CmsClient
import com.duanju.tv.data.remote.DramaRepository
import com.duanju.tv.data.remote.HongguoAppClient
import com.duanju.tv.data.remote.HongguoClient
import com.duanju.tv.data.remote.HongguoSign
import com.duanju.tv.data.remote.SharePageResolver
import com.duanju.tv.data.remote.buildOkHttp
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

/**
 * 全局依赖容器。
 * 对象数量少、生命周期与进程一致，用手写容器即可，不引入 DI 框架。
 */
class App : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(context: Context) {

    val store: LocalStore = LocalStore.get(context)

    /** 接口与分享页共用：带磁盘缓存，短剧列表允许短暂复用 */
    val http: OkHttpClient = buildOkHttp().newBuilder()
        .cache(Cache(File(context.cacheDir, "http"), 20L * 1024 * 1024))
        .build()

    val cms: CmsClient = CmsClient(http)

    val resolver: SharePageResolver = SharePageResolver(http)

    val hongguo: HongguoClient = HongguoClient(http)

    /** 红果 App 通道：优先使用，失败回退网页版 */
    val hongguoApp: HongguoAppClient = HongguoAppClient(http, getOrCreateDeviceId())

    val repository: DramaRepository = DramaRepository(cms, resolver, hongguo, hongguoApp)

    val playerFactory: PlayerFactory = PlayerFactory(context)

    /** 从 LocalStore 读取或生成并持久化红果 device_id */
    private fun getOrCreateDeviceId(): String {
        val current = store.currentSettings.hongguoDeviceId
        if (current.isNotBlank()) return current
        val newId = HongguoSign.newDeviceId()
        store.updateSettings { it.copy(hongguoDeviceId = newId) }
        return newId
    }
}

fun Context.appGraph(): AppGraph = (applicationContext as App).graph
