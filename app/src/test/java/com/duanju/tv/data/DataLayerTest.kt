package com.duanju.tv.data

import com.duanju.tv.data.remote.CmsClient
import com.duanju.tv.data.remote.SharePageResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 数据层单元测试：覆盖 CmsClient 的 parseDrama / parseEpisodes 与 SharePageResolver 的 absolutize。
 *
 * 使用真实数据形态：
 * - ffzy：vod_play_from 为空，vod_play_url 是 share 页地址
 * - bfzy：vod_play_from = "bfzym3u8"，vod_play_url 是直出 m3u8
 * - 多线路：vod_play_from="a$$$b"，vod_play_url 用 $$$ 分隔
 * - 剧集排序：第10集必须排在第2集之后
 */
class DataLayerTest {

    private lateinit var cmsClient: CmsClient
    private lateinit var sharePageResolver: SharePageResolver

    @Before
    fun setUp() {
        val httpClient = OkHttpClient()
        cmsClient = CmsClient(httpClient)
        sharePageResolver = SharePageResolver(httpClient)
    }

    // ========== parseEpisodes 测试 ==========

    @Test
    fun `parseEpisodes - ffzy 格式 share 页地址`() {
        // ffzy 的 vod_play_url 格式：标题$share页URL#标题$share页URL
        val raw = "第01集\$https://vip.ffzy-play9.com/share/6916222172eb021e5d5f0043453078b9#第02集\$https://vip.ffzy-play9.com/share/2439abcdef"
        val episodes = cmsClient.parseEpisodes(raw)

        assertEquals(2, episodes.size)
        assertEquals("第01集", episodes[0].title)
        assertEquals("https://vip.ffzy-play9.com/share/6916222172eb021e5d5f0043453078b9", episodes[0].rawUrl)
        assertEquals("第02集", episodes[1].title)
        assertEquals("https://vip.ffzy-play9.com/share/2439abcdef", episodes[1].rawUrl)
    }

    @Test
    fun `parseEpisodes - bfzy 格式直出 m3u8`() {
        // bfzy 的 vod_play_url 格式：标题$直出m3u8#标题$直出m3u8
        val raw = "第1集\$https://v.fengbao8.com/video/x/第1集/index.m3u8#第2集\$https://v.fengbao8.com/video/x/第2集/index.m3u8"
        val episodes = cmsClient.parseEpisodes(raw)

        assertEquals(2, episodes.size)
        assertEquals("第1集", episodes[0].title)
        assertTrue(episodes[0].rawUrl.endsWith(".m3u8"))
        assertEquals("第2集", episodes[1].title)
        assertTrue(episodes[1].rawUrl.endsWith(".m3u8"))
    }

    @Test
    fun `parseEpisodes - bfzy 单集 全集`() {
        val raw = "全集\$https://s3.bfllvip.com/video/chuntangyuzui_32824b/0531b270f1f6/index.m3u8"
        val episodes = cmsClient.parseEpisodes(raw)

        assertEquals(1, episodes.size)
        assertEquals("全集", episodes[0].title)
        assertEquals("https://s3.bfllvip.com/video/chuntangyuzui_32824b/0531b270f1f6/index.m3u8", episodes[0].rawUrl)
    }

    @Test
    fun `parseEpisodes - 剧集排序 第10集在 第2集之后`() {
        // 故意打乱顺序：第10集、第2集、第1集
        val raw = "第10集\$https://a.com/10.m3u8#第2集\$https://a.com/2.m3u8#第1集\$https://a.com/1.m3u8"
        val episodes = cmsClient.parseEpisodes(raw)

        assertEquals(3, episodes.size)
        // 排序后应该是：第1集、第2集、第10集
        assertEquals("第1集", episodes[0].title)
        assertEquals("第2集", episodes[1].title)
        assertEquals("第10集", episodes[2].title)
    }

    @Test
    fun `parseEpisodes - 空字符串返回空列表`() {
        val episodes = cmsClient.parseEpisodes("")
        assertTrue(episodes.isEmpty())
    }

    @Test
    fun `parseEpisodes - 非 http 地址被过滤`() {
        val raw = "第1集\$ftp://a.com/1.m3u8#第2集\$https://a.com/2.m3u8"
        val episodes = cmsClient.parseEpisodes(raw)

        assertEquals(1, episodes.size)
        assertEquals("第2集", episodes[0].title)
    }

    @Test
    fun `parseEpisodes - 去重相同 URL`() {
        val raw = "第1集\$https://a.com/1.m3u8#第2集\$https://a.com/1.m3u8"
        val episodes = cmsClient.parseEpisodes(raw)

        assertEquals(1, episodes.size)
        assertEquals("第1集", episodes[0].title)
    }

    // ========== parseDrama 测试 ==========

    @Test
    fun `parseDrama - ffzy 格式 vod_play_from 为空`() {
        val json = buildJsonObject {
            put("vod_id", 12345)
            put("vod_name", "测试短剧")
            put("vod_play_from", "") // ffzy 通常为空
            put("vod_play_url", "第01集\$https://vip.ffzy-play9.com/share/abc#第02集\$https://vip.ffzy-play9.com/share/def")
            put("vod_pic", "https://img.example.com/pic.jpg")
            put("type_name", "短剧")
            put("type_id", 36)
            put("vod_remarks", "全集")
            put("vod_year", "2024")
            put("vod_area", "大陆")
            put("vod_director", "张三")
            put("vod_actor", "李四,王五")
            put("vod_blurb", "简介内容")
            put("vod_content", "<p>详细内容</p>")
            put("vod_class", "都市")
            put("vod_score", "8.5")
            put("vod_time", "2024-01-01")
        }

        val drama = cmsClient.parseDrama(json, "ffzy")

        assertNotNull(drama)
        assertEquals(12345, drama!!.id)
        assertEquals("ffzy", drama.sourceId)
        assertEquals("测试短剧", drama.name)
        assertEquals(1, drama.playGroups.size)
        assertEquals("线路1", drama.playGroups[0].name) // from 为空时用默认名
        assertEquals(2, drama.playGroups[0].episodes.size)
        assertEquals("https://img.example.com/pic.jpg", drama.pic)
        assertEquals("短剧", drama.type)
        assertEquals(36, drama.typeId)
        assertEquals("2024", drama.year)
        assertEquals("大陆", drama.area)
        assertEquals("8.5", drama.score)
        // HTML 标签应被去除
        assertEquals("详细内容", drama.detail)
    }

    @Test
    fun `parseDrama - bfzy 格式 vod_play_from 有值`() {
        val json = buildJsonObject {
            put("vod_id", 67890)
            put("vod_name", "暴风短剧")
            put("vod_play_from", "bfzym3u8")
            put("vod_play_url", "第1集\$https://v.fengbao8.com/video/1.m3u8#第2集\$https://v.fengbao8.com/video/2.m3u8")
            put("vod_pic", "")
            put("type_name", "短剧")
            put("type_id", 58)
            put("vod_remarks", "")
            put("vod_year", "")
            put("vod_area", "")
            put("vod_director", "")
            put("vod_actor", "")
            put("vod_blurb", "")
            put("vod_content", "")
            put("vod_class", "")
            put("vod_score", "")
            put("vod_time", "")
        }

        val drama = cmsClient.parseDrama(json, "bfzy")

        assertNotNull(drama)
        assertEquals(67890, drama!!.id)
        assertEquals("bfzy", drama.sourceId)
        assertEquals(1, drama.playGroups.size)
        assertEquals("bfzym3u8", drama.playGroups[0].name) // 使用实际的 from 名
        assertEquals(2, drama.playGroups[0].episodes.size)
    }

    @Test
    fun `parseDrama - 多线路 vod_play_from 用  分隔`() {
        val json = buildJsonObject {
            put("vod_id", 11111)
            put("vod_name", "多线路短剧")
            put("vod_play_from", "线路A\$\$\$线路B")
            put("vod_play_url", "第1集\$https://a.com/1.m3u8\$\$\$第1集\$https://b.com/1.m3u8")
            put("vod_pic", "")
            put("type_name", "短剧")
            put("type_id", 1)
            put("vod_remarks", "")
            put("vod_year", "")
            put("vod_area", "")
            put("vod_director", "")
            put("vod_actor", "")
            put("vod_blurb", "")
            put("vod_content", "")
            put("vod_class", "")
            put("vod_score", "")
            put("vod_time", "")
        }

        val drama = cmsClient.parseDrama(json, "test")

        assertNotNull(drama)
        assertEquals(2, drama!!.playGroups.size)
        assertEquals("线路A", drama.playGroups[0].name)
        assertEquals("https://a.com/1.m3u8", drama.playGroups[0].episodes[0].rawUrl)
        assertEquals("线路B", drama.playGroups[1].name)
        assertEquals("https://b.com/1.m3u8", drama.playGroups[1].episodes[0].rawUrl)
    }

    @Test
    fun `parseDrama - 无有效剧集返回 null`() {
        val json = buildJsonObject {
            put("vod_id", 99999)
            put("vod_name", "无效短剧")
            put("vod_play_from", "")
            put("vod_play_url", "") // 无剧集
            put("vod_pic", "")
            put("type_name", "")
            put("type_id", 0)
            put("vod_remarks", "")
            put("vod_year", "")
            put("vod_area", "")
            put("vod_director", "")
            put("vod_actor", "")
            put("vod_blurb", "")
            put("vod_content", "")
            put("vod_class", "")
            put("vod_score", "")
            put("vod_time", "")
        }

        val drama = cmsClient.parseDrama(json, "test")
        assertNull(drama)
    }

    @Test
    fun `parseDrama - 缺少 vod_id 返回 null`() {
        val json = buildJsonObject {
            // 缺少 vod_id
            put("vod_name", "无ID短剧")
            put("vod_play_from", "")
            put("vod_play_url", "第1集\$https://a.com/1.m3u8")
        }

        val drama = cmsClient.parseDrama(json, "test")
        assertNull(drama)
    }

    // ========== absolutize 测试 ==========

    @Test
    fun `absolutize - 相对路径 斜杠开头`() {
        val pageUrl = "https://vip.ffzy-play9.com/share/6916222172eb021e5d5f0043453078b9"
        val ref = "/20260918/49391_69162221/index.m3u8?sign=abc"

        val result = sharePageResolver.absolutize(pageUrl, ref)

        assertEquals("https://vip.ffzy-play9.com/20260918/49391_69162221/index.m3u8?sign=abc", result)
    }

    @Test
    fun `absolutize - 相对路径无斜杠`() {
        val pageUrl = "https://example.com/path/page.html"
        val ref = "video.m3u8"

        val result = sharePageResolver.absolutize(pageUrl, ref)

        assertEquals("https://example.com/path/video.m3u8", result)
    }

    @Test
    fun `absolutize - 绝对 URL 直接返回`() {
        val pageUrl = "https://example.com/page.html"
        val ref = "https://cdn.example.com/video.m3u8"

        val result = sharePageResolver.absolutize(pageUrl, ref)

        assertEquals("https://cdn.example.com/video.m3u8", result)
    }

    @Test
    fun `absolutize - 协议相对 URL 双斜杠`() {
        val pageUrl = "https://example.com/page.html"
        val ref = "//cdn.example.com/video.m3u8"

        val result = sharePageResolver.absolutize(pageUrl, ref)

        assertEquals("https://cdn.example.com/video.m3u8", result)
    }

    @Test
    fun `absolutize - 非标准端口保留`() {
        val pageUrl = "https://example.com:8080/page.html"
        val ref = "/video.m3u8"

        val result = sharePageResolver.absolutize(pageUrl, ref)

        assertEquals("https://example.com:8080/video.m3u8", result)
    }

    @Test
    fun `absolutize - 标准端口 80 443 不保留`() {
        val pageUrl1 = "https://example.com:443/page.html"
        val ref1 = "/video.m3u8"
        val result1 = sharePageResolver.absolutize(pageUrl1, ref1)
        assertEquals("https://example.com/video.m3u8", result1)

        val pageUrl2 = "http://example.com:80/page.html"
        val ref2 = "/video.m3u8"
        val result2 = sharePageResolver.absolutize(pageUrl2, ref2)
        assertEquals("http://example.com/video.m3u8", result2)
    }
}
