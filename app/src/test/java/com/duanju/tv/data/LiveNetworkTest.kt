package com.duanju.tv.data

import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Resolved
import com.duanju.tv.data.remote.CmsClient
import com.duanju.tv.data.remote.DefaultSources
import com.duanju.tv.data.remote.DramaRepository
import com.duanju.tv.data.remote.SharePageResolver
import com.duanju.tv.data.remote.SourceSpec
import com.duanju.tv.data.remote.buildOkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.HttpURLConnection

/**
 * 端到端真实网络验证（需要联网，离线时自动跳过）。
 * 目的：确认 CMS 分类发现、剧集解析、分享页 -> m3u8、以及 m3u8 首段真的可下载。
 */
class LiveNetworkTest {

    private val http = buildOkHttp()
    private val cms = CmsClient(http)
    private val repo = DramaRepository(cms, SharePageResolver(http))

    private fun online(): Boolean = runCatching {
        val c = java.net.Socket().apply { connect(java.net.InetSocketAddress("8.8.8.8", 53), 2000); close() }
        true
    }.getOrDefault(false)

    @Test
    fun `every builtin source yields playable m3u8`() = runBlocking {
        Assume.assumeTrue("offline", online())
        for (spec in DefaultSources.ALL) {
            val cats = repo.dramaCategoryIds(spec)
            assertTrue("${spec.id} 未发现短剧分类", cats.isNotEmpty())

            val page = repo.page(spec, 1)
            assertTrue("${spec.id} 分类列表为空", page.items.isNotEmpty())
            println("[${spec.id}] categories=$cats total=${page.total} items=${page.items.size}")

            val drama = page.items.first { it.playGroups.any { g -> g.episodes.isNotEmpty() } }
            println("  sample: ${drama.name} 线路=${drama.playGroups.map { it.name }} 集数=${drama.episodeCount}")
            val ep = drama.playGroups.maxByOrNull { it.episodes.size }!!.episodes[0]
            val res = repo.resolveEpisode(ep)
            assertTrue("${spec.id} 解析失败: ${(res as? Resolved.Failed)?.message}", res is Resolved.Direct)
            val url = (res as Resolved.Direct).url
            assertTrue("${spec.id} 未解析出流地址: $url", url.contains(".m3u8") || url.contains(".mp4"))
            println("  resolved: $url")

            // 首段清单必须真的能取到
            val code = head(url, res.headers)
            assertEquals("${spec.id} 播放地址 HTTP 异常 ($url)", 200, code)
        }
    }

    @Test
    fun `aggregate search returns results`() = runBlocking {
        Assume.assumeTrue("offline", online())
        val agg = SourceSpec(SourceSpec.AGGREGATE_ID, "全网", "")
        val r = repo.search(agg, "总裁", 1)
        println("aggregate search 总裁 -> ${r.items.size} 条, total=${r.total}")
        assertTrue("聚合搜索无结果", r.items.isNotEmpty())
    }

    private fun head(url: String, headers: Map<String, String>): Int = runCatching {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> if (k.lowercase() != "origin") b.header(k, v) }
        http.newCall(b.build()).execute().use { it.code }
    }.getOrElse { -1 }
}
