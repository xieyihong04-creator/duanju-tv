package com.duanju.tv.data

import com.duanju.tv.data.model.entryKey
import com.duanju.tv.data.media.DljDownloadManager
import com.duanju.tv.data.remote.HongguoAppClient
import com.duanju.tv.data.remote.HongguoSign
import com.duanju.tv.data.remote.LandpageCursor
import com.duanju.tv.data.remote.hongguoDramaFromItem
import com.duanju.tv.data.remote.parseVideoModel
import com.duanju.tv.data.remote.parseHongguoVidList
import com.duanju.tv.data.remote.selectClearStream
import com.duanju.tv.data.remote.getJsonArray
import com.duanju.tv.data.remote.videoModelVariants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * 红果通道纯函数单测（不打网络）
 *
 * 覆盖：
 * 1. 请求体必须是可序列化的 JsonObject（历史上用 Map<String, Any> 直接把 App 通道打死）
 * 2. landpage 游标语义与分页签名
 * 3. 条目 -> Drama 的字段映射（用真实抓包形态）
 * 4. vid_list / video_list 两种分集形态
 * 5. 选流：加密流、bytevc2 过滤，明流取最高分
 * 6. key 一致性：Drama.key / 收藏键 / 下载任务 id 同构
 *
 * 签名本身在 HongguoSignTest 里有 Go 侧 golden 值，这里只做长度与 header 校验。
 */
class HongguoAppClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val deviceId = "1234567890123456789"
    private val fixedTs = 1700000000L
    private val fixedRticket = "1700000000000"

    // ========== 1. 请求体序列化 ==========

    @Test
    fun `landpage payload serializes to JSON directly`() {
        val client = newClient()
        val payload = client.buildLandpagePayload(LandpageCursor(offset = 18, sessionId = "sess_1"))

        // kotlinx 对 Map<String, Any> 会抛 "Serializer for class 'Any' is not found"，
        // 一旦这里退回 Map 形态，整个 App 通道的 POST 都会失败
        val text = json.encodeToString(JsonElement.serializer(), payload)
        assertTrue(text, text.contains("\"offset\":18"))
        assertTrue(text, text.contains("\"session_id\":\"sess_1\""))
        assertTrue(text, text.contains("\"client_req_type\":2"))
        assertTrue(text, text.contains("\"limit\":18"))
        assertTrue(text, text.contains("short_play"))
    }

    @Test
    fun `landpage payload uses client_req_type 3 then 2`() {
        val client = newClient()
        assertEquals(
            "3",
            client.buildLandpagePayload(LandpageCursor()).getJsonPrimitive("client_req_type"),
        )
        assertEquals(
            "2",
            client.buildLandpagePayload(LandpageCursor(offset = 18, sessionId = "s"))
                .getJsonPrimitive("client_req_type"),
        )
    }

    // ========== 2. 分页签名与游标 ==========

    @Test
    fun `page signature is SHA-256 of sorted ids`() {
        val client = newClient()
        // 与 Go fmt.Sprintf("%x", sha256.Sum256(join(ids,"\n"))) 一致
        assertEquals(
            "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b",
            client.computePageSignature(listOf("1")),
        )
        // 排序后再签名：顺序不同结果相同，对应 "1\n2" 的摘要
        val ab = client.computePageSignature(listOf("2", "1"))
        val ba = client.computePageSignature(listOf("1", "2"))
        assertEquals(ab, ba)
        assertEquals(
            "b598b3a62a3f7cedb17e66d1cb31d53dffeebaf5c07e2c60d5e31971936fd35e",
            ab,
        )
        assertEquals("", client.computePageSignature(emptyList()))
    }

    @Test
    fun `cursor survives encode-decode round trip`() {
        val cursor = LandpageCursor(
            offset = 18,
            sessionId = "sess_abc123",
            lastId = "987654",
            pageSignature = "abcdef123456",
            initialized = true,
            exhausted = false,
        )
        val parsed = json.decodeFromString(LandpageCursor.serializer(), json.encodeToString(LandpageCursor.serializer(), cursor))
        assertEquals(cursor, parsed)
        assertEquals(
            LandpageCursor(),
            json.decodeFromString(LandpageCursor.serializer(), json.encodeToString(LandpageCursor.serializer(), LandpageCursor())),
        )
    }

    // ========== 3. landpage 响应解析 ==========

    /** 真实 landpage 首条（2026-10 抓包，字段名与网页通道不同） */
    private val realLandpageItem = JsonObject(
        mapOf(
            "series_id" to JsonPrimitive("7693846533267016728"),
            "title" to JsonPrimitive("牌下深渊"),
            "cover" to JsonPrimitive("https://p3-reading-sign.fqnovelpic.com/novel-pic/889ecc.jpg"),
            "video_desc" to JsonPrimitive("林晴前世欠下巨额债务，重生回到悲剧发生之前。"),
            "episode_cnt" to JsonPrimitive(38),
            "score" to JsonPrimitive("8.4"),
            "vid" to JsonPrimitive("7693849422186155033"),
            "category_schema" to JsonPrimitive(
                "[{\"category_id\":5022,\"name\":\"都市\"},{\"category_id\":5069,\"name\":\"重生逆袭\"}]"
            ),
        )
    )

    @Test
    fun `real landpage item maps to Drama`() {
        val drama = hongguoDramaFromItem(realLandpageItem)
        assertNotNull(drama)
        drama!!
        assertEquals("7693846533267016728", drama.backendId)
        assertEquals("hongguo", drama.sourceId)
        assertEquals("牌下深渊", drama.name)
        assertEquals("https://p3-reading-sign.fqnovelpic.com/novel-pic/889ecc.jpg", drama.pic)
        assertEquals("林晴前世欠下巨额债务，重生回到悲剧发生之前。", drama.blurb)
        // episode_cnt 是数字，不是字符串
        assertEquals("共38集", drama.remarks)
        assertEquals("8.4", drama.score)
        // category_schema 是被转义的 JSON 字符串，要展开成标签
        assertEquals("都市,重生逆袭", drama.tag)
        assertEquals("都市", drama.type)
        assertTrue("列表条目不带分集", drama.playGroups.first().episodes.isEmpty())
        assertEquals(HONGGUO_GROUP, drama.playGroups.first().name)
    }

    @Test
    fun `web item with video_data wrapper and series_name`() {
        // 真实 category_page recommendList 首条（2026-10 抓包）
        val item = JsonObject(
            mapOf(
                "episode_cnt" to JsonPrimitive(69),
                "series_id" to JsonPrimitive("7686713195246930968"),
                "series_name" to JsonPrimitive("首辅娇娘"),
                "series_cover" to JsonPrimitive("https://p3-novel.byteimg.com/novel-pic/a2cb13.jpg"),
                "series_intro" to JsonPrimitive("现代杀手顾娇坠机穿越。"),
                "tags" to JsonArray(listOf(JsonPrimitive("爱情"), JsonPrimitive("古风爱情"))),
                "episode_right_text" to JsonPrimitive("全69集"),
                "celebrities" to JsonArray(
                    listOf(
                        JsonObject(mapOf("nickname" to JsonPrimitive("孟娜"), "sub_title" to JsonPrimitive("饰 顾娇"))),
                        JsonObject(mapOf("nickname" to JsonPrimitive("刘润铭"))),
                    )
                ),
            )
        )
        val drama = hongguoDramaFromItem(item, "真人剧")!!
        assertEquals("7686713195246930968", drama.backendId)
        assertEquals("首辅娇娘", drama.name)
        assertEquals("全69集", drama.remarks)
        assertEquals("爱情,古风爱情", drama.tag)
        assertEquals("孟娜,刘润铭", drama.actors)
        // 无 category_name 时按 Go 的优先级：首个标签 > 路由名
        assertEquals("爱情", drama.type)
    }

    @Test
    fun `non numeric series id returns null`() {
        assertNull(hongguoDramaFromItem(JsonObject(mapOf("series_id_str" to JsonPrimitive("abc")))))
        assertNull(hongguoDramaFromItem(JsonObject(mapOf("title" to JsonPrimitive("无 id")))))
    }

    @Test
    fun `parseLandpageRows rejects missing data or video_data`() {
        val client = newClient()
        assertThrows(Exception::class.java) {
            client.parseLandpageRows(JsonObject(emptyMap()))
        }
        assertThrows(Exception::class.java) {
            client.parseLandpageRows(
                JsonObject(mapOf("data" to JsonObject(mapOf("has_more" to JsonPrimitive(true)))))
            )
        }
        // 有行但全都识别不了 -> 抛错，不能静默当成「到底」
        assertThrows(Exception::class.java) {
            client.parseLandpageRows(
                JsonObject(
                    mapOf(
                        "data" to JsonObject(
                            mapOf("video_data" to JsonArray(listOf(JsonObject(mapOf("x" to JsonPrimitive(1))))))
                        )
                    )
                )
            )
        }
        val ok = client.parseLandpageRows(
            JsonObject(
                mapOf(
                    "data" to JsonObject(
                        mapOf("video_data" to JsonArray(listOf(realLandpageItem)))
                    )
                )
            )
        )
        assertEquals(1, ok.size)
    }

    // ========== 4. 分集列表 ==========

    @Test
    fun `vid_list of plain strings numbers positionally`() {
        val eps = parseHongguoVidList(
            JsonArray(listOf(JsonPrimitive("111"), JsonPrimitive("222"))),
            "7686713195246930968",
        )
        assertEquals(2, eps.size)
        assertEquals(1, eps[0].index)
        assertEquals("hongguo://7686713195246930968/111", eps[0].rawUrl)
        assertEquals("第2集", eps[1].title)
        assertTrue(eps[0].isDirectStream)
    }

    @Test
    fun `video_list objects use vid_index, sorted and deduped`() {
        val eps = parseHongguoVidList(
            JsonArray(
                listOf(
                    JsonObject(mapOf("vid" to JsonPrimitive("300"), "vid_index" to JsonPrimitive(3))),
                    JsonObject(mapOf("vid" to JsonPrimitive("100"), "vid_index" to JsonPrimitive(1))),
                    JsonObject(mapOf("vid" to JsonPrimitive("100"), "vid_index" to JsonPrimitive(1))),
                    JsonObject(mapOf("vid" to JsonPrimitive("200"), "vid_index" to JsonPrimitive(2))),
                )
            ),
            "series",
        )
        assertEquals(listOf(1, 2, 3), eps.map { it.index })
        assertEquals(listOf("100", "200", "300"), eps.map { it.rawUrl.substringAfterLast('/') })
    }

    @Test
    fun `episodes of another series are dropped`() {
        val eps = parseHongguoVidList(
            JsonArray(
                listOf(
                    JsonObject(mapOf("vid" to JsonPrimitive("1"), "series_id" to JsonPrimitive("mine"))),
                    JsonObject(mapOf("vid" to JsonPrimitive("2"), "series_id" to JsonPrimitive("other"))),
                )
            ),
            "mine",
        )
        assertEquals(1, eps.size)
        assertEquals("hongguo://mine/1", eps[0].rawUrl)
    }

    @Test
    fun `non numeric vid and invalid index are ignored`() {
        val eps = parseHongguoVidList(
            JsonArray(
                listOf(
                    JsonObject(mapOf("vid" to JsonPrimitive("abc"))),
                    JsonObject(mapOf("vid" to JsonPrimitive("9"), "vid_index" to JsonPrimitive(0))),
                    JsonPrimitive(nullString()),
                )
            ),
            "s",
        )
        assertTrue(eps.isEmpty())
    }

    @Test
    fun `object wrapped list yields episodes`() {
        val eps = parseHongguoVidList(
            JsonObject(mapOf("list" to JsonArray(listOf(JsonPrimitive("7"))))),
            "s",
        )
        assertEquals(1, eps.size)
        assertEquals("hongguo://s/7", eps[0].rawUrl)
    }

    // ========== 5. 选流 ==========

    @Test
    fun `picks the highest quality clear stream`() {
        val videoList = JsonArray(
            listOf(
                variant("h264", 720, "https://example.com/720p_h264.m3u8"),
                variant("hevc", 1080, "https://example.com/1080p_hevc.m3u8"),
                variant("avc1", 480, "https://example.com/480p_h264.m3u8"),
            )
        )
        assertEquals("https://example.com/1080p_hevc.m3u8", selectClearStream(videoList))
    }

    @Test
    fun `skips bytevc2 and encrypted variants`() {
        val videoList = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "video_meta" to JsonObject(
                            mapOf("codec_type" to JsonPrimitive("bytevc2"), "vheight" to JsonPrimitive("1080"))
                        ),
                        "main_url" to JsonPrimitive("https://example.com/bytevc2.m3u8"),
                    )
                ),
                JsonObject(
                    mapOf(
                        "video_meta" to JsonObject(mapOf("codec_type" to JsonPrimitive("h264"), "vheight" to JsonPrimitive("1080"))),
                        "encrypt_info" to JsonObject(mapOf("spade_a" to JsonPrimitive("key"))),
                        "main_url" to JsonPrimitive("https://example.com/encrypted.m3u8"),
                    )
                ),
                JsonObject(
                    mapOf(
                        "video_meta" to JsonObject(mapOf("codec_type" to JsonPrimitive("h264"), "vheight" to JsonPrimitive("720"))),
                        "encrypt_info" to JsonObject(mapOf("encrypt" to JsonPrimitive(true))),
                        "main_url" to JsonPrimitive("https://example.com/encrypted2.m3u8"),
                    )
                ),
                JsonObject(
                    mapOf(
                        "video_meta" to JsonObject(mapOf("codec_type" to JsonPrimitive("h264"), "vheight" to JsonPrimitive("480"))),
                        "encrypt_info" to JsonObject(mapOf("encryption_method" to JsonPrimitive("cenc-aes-ctr"))),
                        "main_url" to JsonPrimitive("https://example.com/cenc.m3u8"),
                    )
                ),
                JsonObject(
                    mapOf(
                        "video_meta" to JsonObject(mapOf("codec_type" to JsonPrimitive("h264"), "vheight" to JsonPrimitive("1080"))),
                        "gear_des_key" to JsonPrimitive("bytevc2_x"),
                        "main_url" to JsonPrimitive("https://example.com/gear.m3u8"),
                    )
                ),
                variant("h264", 720, "https://example.com/clear.m3u8"),
            )
        )
        assertEquals("https://example.com/clear.m3u8", selectClearStream(videoList))
    }

    @Test
    fun `returns null when no clear stream`() {
        val videoList = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "video_meta" to JsonObject(mapOf("codec_type" to JsonPrimitive("h264"))),
                        "encrypt_info" to JsonObject(mapOf("spade_a" to JsonPrimitive("key"))),
                        "main_url" to JsonPrimitive("https://example.com/encrypted.m3u8"),
                    )
                ),
            )
        )
        assertNull(selectClearStream(videoList))
        assertNull(selectClearStream(JsonArray(emptyList())))
    }

    @Test
    fun `falls back to backup_url and decodes base64`() {
        val videoList = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "video_meta" to JsonObject(mapOf("codec_type" to JsonPrimitive("h264"), "vheight" to JsonPrimitive("720"))),
                        "main_url" to JsonPrimitive(""),
                        "backup_url" to JsonPrimitive("https://example.com/backup.m3u8"),
                    )
                ),
            )
        )
        assertEquals("https://example.com/backup.m3u8", selectClearStream(videoList))

        // 网页通道的 main_url 是 base64
        val encoded = "https://v.example.com/1/index.m3u8".toByteArray().let { java.util.Base64.getEncoder().encodeToString(it) }
        val b64 = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "video_meta" to JsonObject(mapOf("codec_type" to JsonPrimitive("h264"), "vheight" to JsonPrimitive("720"))),
                        "main_url" to JsonPrimitive(encoded),
                    )
                ),
                // 无填充形态也要能解
                JsonObject(
                    mapOf(
                        "video_meta" to JsonObject(mapOf("codec_type" to JsonPrimitive("h264"), "vheight" to JsonPrimitive("540"))),
                        "main_url" to JsonPrimitive(encoded.trimEnd('=')),
                    )
                ),
            )
        )
        assertEquals("https://v.example.com/1/index.m3u8", selectClearStream(b64))
    }

    @Test
    fun `definition wins over vheight`() {
        val videoList = JsonArray(
            listOf(
                variant("h264", 720, "https://example.com/def_1080.m3u8", definition = "1080p"),
                variant("h264", 1080, "https://example.com/vheight_1080.m3u8", definition = "720p"),
            )
        )
        assertEquals("https://example.com/def_1080.m3u8", selectClearStream(videoList))
    }

    @Test
    fun `object shaped video_list expands sorted by key`() {
        val model = JsonObject(
            mapOf(
                "video_list" to JsonObject(
                    mapOf(
                        "3" to variant("h264", 720, "https://example.com/low.m3u8"),
                        "5" to variant("h264", 1080, "https://example.com/high.m3u8"),
                    )
                )
            )
        )
        assertEquals(2, videoModelVariants(model).size)
        assertEquals("https://example.com/high.m3u8", selectClearStream(videoModelVariants(model)))
    }

    @Test
    fun `parseVideoModel accepts object and JSON string`() {
        val obj = JsonObject(mapOf("video_model" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        assertNotNull(parseVideoModel(obj))

        val str = JsonObject(
            mapOf("video_model" to JsonPrimitive("""{"video_list":[{"main_url":"https://example.com/s.m3u8"}]}"""))
        )
        val model = parseVideoModel(str)
        assertNotNull(model)
        assertEquals(1, model!!.getJsonArray("video_list")!!.size)

        assertNull(parseVideoModel(JsonObject(mapOf("other" to JsonPrimitive("v")))))
        assertNull(parseVideoModel(JsonObject(mapOf("video_model" to JsonPrimitive("null")))))
    }

    // ========== 6. key 一致性 ==========

    @Test
    fun `Drama key, favorite key and download id share one shape`() {
        val drama = hongguoDramaFromItem(realLandpageItem)!!
        val seriesId = "7693846533267016728"

        // 19 位 series_id 放不进 Int，必须走掩码散列，且同一条 id 稳定
        assertEquals(drama.id, hongguoDramaFromItem(realLandpageItem)!!.id)
        assertEquals(seriesId, drama.backendId)

        // Drama.key 与只存了元信息的收藏键必须一致，否则「在看/已收藏」匹配不上
        assertEquals("hongguo#$seriesId", drama.key)
        assertEquals(drama.key, entryKey(drama.sourceId, drama.id, drama.backendId))
        assertNotEquals("hongguo#${drama.id}", drama.key)

        // 下载任务 id 以 key 打头，substringBeforeLast('#') 才能还原回 drama.key
        val id = DljDownloadManager.downloadId(drama.key, 7)
        assertEquals("hongguo#$seriesId#7", id)
        assertEquals(drama.key, id.substringBeforeLast('#'))
    }

    @Test
    fun `CMS key falls back to id without backendId`() {
        assertEquals("ffzy#12345", entryKey("ffzy", 12345, null))
        assertEquals("ffzy#12345", entryKey("ffzy", 12345, ""))
    }

    // ========== 7. 签名与 header 完整性 ==========

    @Test
    fun `signature length and prefix follow the protocol`() {
        val query = "aid=8662&device_id=$deviceId&_rticket=$fixedRticket"
        val body = """{"offset":0}""".toByteArray()
        val (gorgon, khronos, _) = HongguoSign.sign(query, body, fixedTs)
        assertEquals(52, gorgon.length)
        assertTrue(gorgon, gorgon.startsWith("8404401c0000"))
        assertEquals(fixedTs.toString(), khronos)
        assertEquals(32, HongguoSign.stub(body).length)
        assertTrue(HongguoSign.stub(body).all { it.isUpperCase() || it.isDigit() })
    }

    private fun newClient(): HongguoAppClient = HongguoAppClient(okhttp3.OkHttpClient(), deviceId)

    private fun variant(codec: String, height: Int, url: String, definition: String? = null): JsonObject =
        JsonObject(
            mapOf(
                "video_meta" to JsonObject(
                    buildMap {
                        put("codec_type", JsonPrimitive(codec))
                        put("vheight", JsonPrimitive(height))
                        definition?.let { put("definition", JsonPrimitive(it)) }
                    }
                ),
                "main_url" to JsonPrimitive(url),
            )
        )

    private fun JsonObject.getJsonPrimitive(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun nullString(): String = "null"

    private companion object {
        const val HONGGUO_GROUP = "红果官方"
    }
}
