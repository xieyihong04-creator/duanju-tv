package com.duanju.tv.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Episode
import com.duanju.tv.data.remote.CmsClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 离线缓存（下载）管理。
 *
 * 用 Media3 的 [DownloadManager] 在进程内驱动：它自带线程池与重试，
 * 下载内容写入与播放共用的 [MediaCache]，因此下载完可直接离线播。
 * 不放进前台 Service，是为了避开 Android 12+ 对后台启动与通知的额外限制；
 * 代价是应用被完全杀死后任务会停，重新打开会自动续传（缓存分片仍在）。
 */
object DljDownloadManager {

    @Volatile
    private var manager: DownloadManager? = null

    private val executor = Executors.newFixedThreadPool(3)

    /** downloadId -> 展示信息，用于把 Media3 的 Download 还原成「哪部剧第几集」 */
    private val labels = java.util.concurrent.ConcurrentHashMap<String, DownloadLabel>()

    data class DownloadLabel(
        val dramaKey: String,
        val dramaName: String,
        val episodeTitle: String,
        val episodeIndex: Int,
        val sourceId: String,
        val dramaId: Int,
        val rawEpisodeUrl: String,
    )

    fun get(context: Context): DownloadManager = manager ?: synchronized(this) {
        manager ?: build(context.applicationContext).also { manager = it }
    }

    private fun build(appContext: Context): DownloadManager {
        val dbProvider: DatabaseProvider = StandaloneDatabaseProvider(appContext)
        val upstream = DefaultDataSource.Factory(
            appContext,
            OkHttpDataSource.Factory(OkHttpEngine.get()).setUserAgent(CmsClient.UA),
        )
        val cache = CacheDataSource.Factory()
            .setCache(MediaCache.get(appContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DownloadManager(appContext, dbProvider, MediaCache.get(appContext), upstream, executor).apply {
            maxParallelDownloads = 2
            minRetryCount = 3
            requirements = DownloadManager.DEFAULT_REQUIREMENTS
        }.also { dm ->
            // 恢复历史任务的标签（Media3 把 Download 存在自己的数据库里，重启后仍需展示）
            runCatching {
                dm.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) restoreLabel(cursor.getDownload())
                }
            }
        }
    }

    /** 标签信息编码进 DownloadRequest.data，进程重启后仍可还原 */
    private fun restoreLabel(download: Download) {
        val data = download.request.data
        if (data == null || data.isEmpty()) return
        val text = String(data, Charsets.UTF_8)
        val parts = text.split("\u0001")
        if (parts.size >= 7) {
            labels[download.request.id] = DownloadLabel(
                dramaKey = parts[0],
                dramaName = parts[1],
                episodeTitle = parts[2],
                episodeIndex = parts[3].toIntOrNull() ?: 0,
                sourceId = parts[4],
                dramaId = parts[5].toIntOrNull() ?: 0,
                rawEpisodeUrl = parts[6],
            )
        }
    }

    private fun encodeLabel(l: DownloadLabel): ByteArray =
        listOf(
            l.dramaKey, l.dramaName, l.episodeTitle, l.episodeIndex.toString(),
            l.sourceId, l.dramaId.toString(), l.rawEpisodeUrl,
        ).joinToString("\u0001").toByteArray(Charsets.UTF_8)

    /**
     * 任务 id 直接用 [com.duanju.tv.data.model.Drama.key] 拼集数。
     *
     * 之前用 drama.id：红果的 id 是 series_id 的散列值，而取消/进度统计按
     * drama.key（含 backendId）比对，两边永远对不上，取消任务与「已缓存几集」都会失效。
     */
    fun downloadId(dramaKey: String, episodeIndex: Int): String = "$dramaKey#$episodeIndex"

    /**
     * 加入缓存任务。
     * @param playUrl 已解析好的 m3u8 地址
     */
    fun enqueue(
        context: Context,
        drama: Drama,
        episode: Episode,
        playUrl: String,
    ): String {
        val id = downloadId(drama.key, episode.index)
        val label = DownloadLabel(
            dramaKey = drama.key,
            dramaName = drama.name,
            episodeTitle = episode.title,
            episodeIndex = episode.index,
            sourceId = drama.sourceId,
            dramaId = drama.id,
            rawEpisodeUrl = episode.rawUrl,
        )
        labels[id] = label
        val request = DownloadRequest.Builder(id, Uri.parse(playUrl))
            .setMimeType(if (playUrl.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)
            .setData(encodeLabel(label))
            .build()
        get(context).addDownload(request)
        return id
    }

    fun cancel(context: Context, id: String) {
        get(context).removeDownload(id)
        labels.remove(id)
    }

    fun cancelDrama(context: Context, dramaKey: String) {
        get(context).getCurrentDownloads().forEach { d ->
            if (d.request.id.substringBeforeLast('#') == dramaKey) {
                get(context).removeDownload(d.request.id)
                labels.remove(d.request.id)
            }
        }
    }

    /**
     * 在清理媒体缓存【之前】调用。
     *
     * MediaCache.clear() 会 release SimpleCache，旧 DownloadManager 仍持有它，
     * 之后再用会抛 IllegalStateException。所以先停掉任务并丢弃单例，
     * 下次 get() 基于新的 SimpleCache 重建，任务从下载索引数据库自动恢复。
     */
    fun reset() {
        val dm = manager ?: return
        runCatching { dm.pauseDownloads() }
        manager = null
        labels.clear()
    }

    fun pause(context: Context) {
        get(context).pauseDownloads()
    }

    fun resume(context: Context) {
        get(context).resumeDownloads()
    }

    fun labelOf(id: String): DownloadLabel? = labels[id]

    /** 某部剧已缓存完成的集数索引 */
    fun completedEpisodes(dramaKey: String): Set<Int> =
        labels.filterValues { it.dramaKey == dramaKey }.keys.mapNotNull { id ->
            id.substringAfterLast('#').toIntOrNull()
        }.toSet()

    fun snapshot(context: Context): List<Download> = get(context).getCurrentDownloads()

    /**
     * 任务列表变化推送。
     * DownloadManager 的回调在其自身线程，这里转成冷流并 conflate，避免高频刷新卡顿。
     */
    fun downloadsFlow(context: Context): Flow<List<Download>> = callbackFlow {
        val dm = get(context)
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                trySend(dm.getCurrentDownloads())
            }

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download,
            ) {
                labels.remove(download.request.id)
                trySend(dm.getCurrentDownloads())
            }
        }
        dm.addListener(listener)
        trySend(dm.getCurrentDownloads())
        awaitClose { dm.removeListener(listener) }
    }.conflate()

    fun stateOf(download: Download): DljState = when (download.state) {
        Download.STATE_QUEUED -> DljState.QUEUED
        Download.STATE_STOPPED -> DljState.PAUSED
        Download.STATE_DOWNLOADING -> DljState.DOWNLOADING
        Download.STATE_COMPLETED -> DljState.DONE
        Download.STATE_FAILED -> DljState.FAILED
        Download.STATE_REMOVING, Download.STATE_RESTARTING -> DljState.DOWNLOADING
        else -> DljState.QUEUED
    }

    enum class DljState { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED }

    fun progressOf(download: Download): Int {
        if (download.state == Download.STATE_COMPLETED) return 100
        return (download.getPercentDownloaded() * 100).toInt().coerceIn(0, 99)
    }

    /** 已下载字节 */
    fun bytesOf(download: Download): Long = download.getBytesDownloaded()

    /** 总字节，未知返回 -1 */
    fun sizeOf(download: Download): Long = download.contentLength

    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024; i++
        }
        return if (i == 0) "${bytes} B" else String.format("%.1f %s", v, units[i])
    }
}
