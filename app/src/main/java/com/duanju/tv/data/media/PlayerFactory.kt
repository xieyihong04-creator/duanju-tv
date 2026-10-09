package com.duanju.tv.data.media

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import com.duanju.tv.data.remote.CmsClient
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 播放与下载共用的媒体缓存。
 * SimpleCache 会锁定目录，因此全进程只允许一个实例。
 */
object MediaCache {

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        ).also { cache = it }
    }

    /** 已用空间（字节） */
    fun usedBytes(context: Context): Long =
        File(context.applicationContext.cacheDir, "media").walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    /**
     * 清空缓存。必须先释放 SimpleCache（它持有目录锁与 SQLite），
     * 否则删除会失败或后续写入损坏。
     */
    fun clear(context: Context): Boolean {
        val app = context.applicationContext
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
        }
        return runCatching { File(app.cacheDir, "media").deleteRecursively() }.isSuccess
    }

    private const val MAX_BYTES = 1_000L * 1024 * 1024 // 1GB：短剧单集约 5-15MB，够存上百集
}

object OkHttpEngine {
    @Volatile
    private var client: OkHttpClient? = null

    fun get(): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build().also { client = it }
    }
}

/**
 * 播放器工厂。
 * - HLS 由 media3-exoplayer-hls 处理，DefaultMediaSourceFactory 按后缀自动选择
 * - 用 CacheDataSource 包一层，播放即写入缓存，看过的集数之后可离线重播
 * - 剧集地址带时效签名且需要 Referer，所以每次换集重建 factory
 */
class PlayerFactory(private val context: Context) {

    private val appContext = context.applicationContext

    fun create(headers: Map<String, String>): ExoPlayer =
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory(headers))
            .setTrackSelector(trackSelector())
            .setLoadControl(loadControl())
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }

    fun dataSourceFactory(headers: Map<String, String>): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(MediaCache.get(appContext))
            .setUpstreamDataSourceFactory(upstream(headers))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun mediaSourceFactory(headers: Map<String, String>): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory(headers))

    private fun upstream(headers: Map<String, String>): DataSource.Factory {
        val ok = OkHttpDataSource.Factory(OkHttpEngine.get())
            .setUserAgent(CmsClient.UA)
        if (headers.isNotEmpty()) {
            ok.setDefaultRequestProperties(headers)
        }
        return DefaultDataSource.Factory(appContext, ok)
    }

    private fun trackSelector(): DefaultTrackSelector =
        DefaultTrackSelector(appContext).apply {
            parameters = buildUponParameters()
                .setForceHighestSupportedBitrate(true)
                .build()
        }

    private fun loadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,  // minBuffer
                60_000,  // maxBuffer
                1_500,   // bufferForPlayback
                3_000,   // bufferForPlaybackAfterRebuffer
            )
            .build()

    companion object {
        fun mediaItem(url: String, title: String): MediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .setMimeType(if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null)
            .build()
    }
}
