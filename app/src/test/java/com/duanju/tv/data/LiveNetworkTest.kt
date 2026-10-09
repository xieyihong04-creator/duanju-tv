package com.duanju.tv.data

import com.duanju.tv.data.model.Episode
import com.duanju.tv.data.model.Resolved
import com.duanju.tv.data.remote.CmsClient
import com.duanju.tv.data.remote.DefaultSources
import com.duanju.tv.data.remote.DramaRepository
import com.duanju.tv.data.remote.HongguoAppClient
import com.duanju.tv.data.remote.HongguoClient
import com.duanju.tv.data.remote.HongguoSign
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

/**
 * 端到端真实网络验证（需要联网，离线时自动跳过）。
 * 目的：确认 CMS 分类发现、剧集解析、分享页 -> m3u8、以及 m3u8 首段真的可下载。
 */
class LiveNetworkTest {

    private val http = buildOkHttp()
    private val cms = CmsClient(http)
    private val repo = DramaRepository(
        cms,
        SharePageResolver(http),
        HongguoClient(http),
        HongguoAppClient(http, HongguoSign.newDeviceId()),
    )

    private fun online(): Boolean = runCatching {
        val c = java.net.Socket().apply { connect(java.net.InetSocketAddress("8.8.8.8", 53), 2000); close() }
        true
    }.getOrDefault(false)

    @Test
    fun `cms source yields playable m3u8`() = runBlocking {
        Assume.assumeTrue("offline", online())
        for (spec in DefaultSources.ALL.filter { it.hasMacCategories }) {
            val cats = repo.dramaCategoryIds(spec)
            assertTrue("${spec.id} 未发现短剧分类", cats.isNotEmpty())

            val page = repo.page(spec, 1)
            assertTrue("${spec.id} 分类列表为空", page.items.isNotEmpty())
            println("[${spec.id}] categories=$cats total=${page.total} items=${page.items.size}")

            val drama = page.items.first { it.playGroups.any { g -> g.episodes.isNotEmpty() } }
            println("  sample: ${drama.name} 线路=${drama.playGroups.map { it.name }} 集数=${drama.episodeCount}")
            val ep = drama.playGroups.maxByOrNull { it.episodes.size }!!.episodes[0]
            assertPlayable(spec, ep)
        }
    }

    /**
     * 红果端到端：列表页只有剧壳，分集要进详情时补齐；取流走 hongguo:// 协议，
     * App 通道失败会静默回退网页通道，所以这里断言的是「最终能不能播」。
     */
    @Test
    fun `hongguo list hydrates episodes and resolves a playable stream`() = runBlocking {
        Assume.assumeTrue("offline", online())
        val spec = DefaultSources.byId("hongguo")!!

        // 红果没有 macCMS 的 ac=list，这里必须是空而不是硬打一次接口
        assertTrue("红果不该有 CMS 分类", repo.dramaCategoryIds(spec).isEmpty())

        val first = repo.page(spec, 1)
        assertTrue("红果首页为空", first.items.isNotEmpty())
        println("[hongguo] page1 items=${first.items.size} pageCount=${first.pageCount} channel=${repo.lastHongguoListChannel}")
        val probe = first.items.first()
        assertTrue("列表条目应缺失分集（详情再补）", probe.episodeCount == 0)
        assertNotNull("列表条目应带 backendId", probe.backendId)

        // 游标翻页：第二页必须能拿到内容，且不与首页重复
        val second = repo.page(spec, 2)
        val overlapIds = second.items.mapNotNull { it.backendId }.toSet() intersect
            first.items.mapNotNull { it.backendId }.toSet()
        println("[hongguo] page2 items=${second.items.size} overlap=${overlapIds.size} channel=${repo.lastHongguoListChannel}")
        assertTrue("红果第二页为空", second.items.isNotEmpty())
        assertTrue("红果翻页与首页重复 ${overlapIds.size} 条", overlapIds.size < second.items.size)

        val full = repo.withEpisodes(probe)
        assertTrue("红果详情未补齐分集", full.episodeCount > 0)
        println("  sample: ${full.name} 集数=${full.episodeCount}")

        // key 一致性：补完分集后 key 不变，收藏/进度才认得
        assertEquals(probe.key, full.key)

        val ep = full.bestGroup!!.episodes[0]
        assertTrue("分集应为 hongguo:// 协议", ep.rawUrl.startsWith("hongguo://"))
        assertPlayable(spec, ep)
    }

    @Test
    fun `hongguo web channel search returns items`() = runBlocking {
        Assume.assumeTrue("offline", online())
        val spec = DefaultSources.byId("hongguo")!!
        val r = repo.search(spec, "总裁", 1)
        println("[hongguo] 搜索 总裁 -> ${r.items.size} 条 total=${r.total}")
        assertTrue("红果搜索无结果", r.items.isNotEmpty())
    }

    private suspend fun assertPlayable(spec: SourceSpec, ep: Episode) {
        val res = repo.resolveEpisode(ep)
        assertTrue("${spec.id} 解析失败: ${(res as? Resolved.Failed)?.message}", res is Resolved.Direct)
        val url = (res as Resolved.Direct).url
        // 红果 App 通道给的是无扩展名的 mp4（mime_type=video_mp4 在 query 里），
        // 所以只要求 http 直链，具体能不能播由下面的真实请求验证
        assertTrue("${spec.id} 未解析出流地址: $url", url.startsWith("http"))
        println("  resolved: ${url.take(120)}")

        // 清单/视频首段必须真的能取到
        val code = head(url, res.headers)
        assertTrue("${spec.id} 播放地址 HTTP 异常 ($code, $url)", code == 200 || code == 206)
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
