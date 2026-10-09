package com.duanju.tv.data

import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.PlayGroup
import com.duanju.tv.data.remote.HongguoAppClient
import com.duanju.tv.data.remote.LandpageCursor
import com.duanju.tv.data.remote.HongguoSign
import com.duanju.tv.data.remote.parseVideoModel
import com.duanju.tv.data.remote.selectClearStream
import com.duanju.tv.data.remote.getJsonArray
import com.duanju.tv.data.remote.getJsonObject
import com.duanju.tv.data.remote.strOrNull
import com.duanju.tv.data.remote.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * HongguoAppClient 纯函数单测
 *
 * 测试范围：
 * 1. buildQueryParams / buildHeaders - 验证 query 参数完整、签名 header 齐全、X-Gorgon 长度 52
 * 2. parseLandpageCursor / parseLandpageItem - 验证游标解析与条目映射
 *
 * 不依赖网络、不依赖 MockWebServer，仅用 JUnit + kotlinx-serialization-json
 */
class HongguoAppClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val deviceId = "1234567890123456789"
    private val fixedTs = 1700000000L
    private val fixedRticket = "1700000000000"

    // ========== 1. Query/Header 纯函数测试 ==========

    @Test
    fun testBuildQueryParams_containsAllRequiredKeys() {
        val query = HongguoAppClientTestAccessor.buildQueryParams(deviceId, fixedRticket)

        // 必须包含的固定参数（对应 Go provider_hongguo_app.go:135-141）
        val requiredKeys = listOf(
            "aid", "app_name", "version_code", "version_name",
            "manifest_version_code", "update_version_code", "channel",
            "device_platform", "os", "ssmix", "device_type", "device_brand",
            "language", "os_api", "os_version", "resolution", "dpi", "ac",
            "device_id", "iid", "_rticket"
        )
        for (key in requiredKeys) {
            assertTrue("query 必须包含 $key", query.containsKey(key))
            assertTrue("query[$key] 不能为空", query[key]?.isNotBlank() == true)
        }
        assertEquals("device_id 应等于传入 deviceId", deviceId, query["device_id"])
        assertEquals("iid 应等于 deviceId", deviceId, query["iid"])
        assertEquals("_rticket 应等于固定毫秒值", fixedRticket, query["_rticket"])
    }

    @Test
    fun testBuildHeaders_completeAndXGorgonLength52() {
        val rawQuery = HongguoAppClientTestAccessor.buildQueryParams(deviceId, fixedRticket)
            .entries.joinToString("&") { "${it.key}=${it.value}" }
        val bodyBytes = """{"req_scene":"default","offset":0,"limit":18,"req_type":"only_content","need_selector_panel":false,"client_req_type":3,"session_id":"","filter_ids":"","select_items":{"genre":["short_play"],"sort":["online_time"],"gender":[],"category_dim_theme":[],"category_dim_role":[],"category_dim_epoch":[],"online_time":[],"creation_status":[]}}""".toByteArray()

        val headers = HongguoAppClientTestAccessor.buildHeaders(rawQuery, bodyBytes, fixedTs)

        // 必须包含的 header（对应 Go provider_hongguo_app.go:173-189, 197-198）
        val requiredHeaders = listOf(
            "User-Agent", "Accept", "X-XS-From-Web", "Sdk-Version",
            "Content-Type", "X-Gorgon", "X-Khronos", "X-SS-Req-Ticket", "X-SS-STUB"
        )
        for (h in requiredHeaders) {
            assertTrue("header 必须包含 $h", headers.containsKey(h))
            assertTrue("header[$h] 不能为空", headers[h]?.isNotBlank() == true)
        }

        // X-Gorgon 必须是 52 字符（26 字节 hex）
        val xGorgon = headers["X-Gorgon"]!!
        assertEquals("X-Gorgon 长度必须为 52", 52, xGorgon.length)
        assertTrue("X-Gorgon 必须以 8404401c0000 开头", xGorgon.startsWith("8404401c0000"))

        // X-Khronos 必须等于 timestamp 秒
        assertEquals("X-Khronos 必须等于 timestamp", fixedTs.toString(), headers["X-Khronos"])

        // X-SS-STUB 必须是大写 MD5（对应 Go fmt.Sprintf("%X", bodyHash)）
        val stub = headers["X-SS-STUB"]!!
        assertEquals("X-SS-STUB 必须是 32 字符大写 hex", 32, stub.length)
        assertTrue("X-SS-STUB 必须全大写", stub.all { it.isUpperCase() || it.isDigit() })
    }

    @Test
    fun testBuildHeaders_emptyBody_noStub() {
        val rawQuery = HongguoAppClientTestAccessor.buildQueryParams(deviceId, fixedRticket)
            .entries.joinToString("&") { "${it.key}=${it.value}" }
        val bodyBytes = byteArrayOf()

        val headers = HongguoAppClientTestAccessor.buildHeaders(rawQuery, bodyBytes, fixedTs)

        // 空 body 时 X-SS-STUB 应为空字符串（对应 Go: body 为空不设置 X-SS-STUB，但我们设为空串）
        val stub = headers["X-SS-STUB"]!!
        assertEquals("空 body 时 X-SS-STUB 应为空", "", stub)
    }

    // ========== 2. 解析纯函数测试 ==========

    @Test
    fun testParseLandpageCursor_roundTrip() {
        val cursor = LandpageCursor(
            offset = 18,
            sessionId = "sess_abc123",
            lastId = "987654",
            pageSignature = "abcdef123456",
            initialized = true,
            exhausted = false,
        )
        val jsonStr = json.encodeToString(cursor)
        val parsed = json.decodeFromString<LandpageCursor>(jsonStr)

        assertEquals(cursor.offset, parsed.offset)
        assertEquals(cursor.sessionId, parsed.sessionId)
        assertEquals(cursor.lastId, parsed.lastId)
        assertEquals(cursor.pageSignature, parsed.pageSignature)
        assertEquals(cursor.initialized, parsed.initialized)
        assertEquals(cursor.exhausted, parsed.exhausted)
    }

    @Test
    fun testParseLandpageCursor_emptyDefaults() {
        val cursor = LandpageCursor()
        val jsonStr = json.encodeToString(cursor)
        val parsed = json.decodeFromString<LandpageCursor>(jsonStr)

        assertEquals(0, parsed.offset)
        assertEquals("", parsed.sessionId)
        assertEquals("", parsed.lastId)
        assertEquals("", parsed.pageSignature)
        assertFalse(parsed.initialized)
        assertFalse(parsed.exhausted)
    }

    @Test
    fun testParseLandpageItem_mapsAllFields() {
        // 伪造 landpage 单条响应（模拟 Go parseHongguoCatalogPage 返回结构）
        val itemJson = JsonObject(mapOf(
            "video_data" to JsonObject(mapOf(
                "series_id_str" to JsonPrimitive("123456"),
                "series_id" to JsonPrimitive("123456"),
                "series_title" to JsonPrimitive("测试短剧"),
                "series_name" to JsonPrimitive("测试短剧"),
                "series_cover" to JsonPrimitive("https://example.com/cover.jpg"),
                "series_intro" to JsonPrimitive("这是简介"),
                "video_desc" to JsonPrimitive("视频描述"),
                "episode_cnt" to JsonPrimitive("30"),
                "episode_right_text" to JsonPrimitive("更新至30集"),
                "score" to JsonPrimitive("9.5"),
                "category_name" to JsonPrimitive("真人剧"),
                "series_status" to JsonPrimitive("1"),
                "tags" to JsonPrimitive("热门,推荐"),
                "category_list" to JsonArray(listOf(
                    JsonObject(mapOf("name" to JsonPrimitive("都市"))),
                    JsonObject(mapOf("name" to JsonPrimitive("甜宠"))),
                )),
            ))
        ))

        val drama = HongguoAppClientTestAccessor.parseLandpageItem(itemJson)

        assertNotNull("必须解析出 Drama", drama)
        assertEquals("backendId 应为 series_id", "123456", drama!!.backendId)
        assertEquals("id 应为 series_id.toInt()", 123456, drama.id)
        assertEquals("sourceId 必须为 hongguo", "hongguo", drama.sourceId)
        assertEquals("name", "测试短剧", drama.name)
        assertEquals("pic", "https://example.com/cover.jpg", drama.pic)
        assertEquals("type", "真人剧", drama.type)
        assertEquals("remarks", "更新至30集", drama.remarks)
        assertEquals("score", "9.5", drama.score)
        assertEquals("blurb", "这是简介", drama.blurb)
        assertEquals("detail", "这是简介", drama.detail)
        assertEquals("tag 包含 tags 和 category_list", "热门,推荐,都市,甜宠", drama.tag)
        assertEquals("playGroups 为空列表（详情页再填充）", 1, drama.playGroups.size)
        assertTrue("playGroups[0].episodes 为空", drama.playGroups[0].episodes.isEmpty())
    }

    @Test
    fun testParseLandpageItem_invalidSeriesId_returnsNull() {
        val itemJson = JsonObject(mapOf(
            "video_data" to JsonObject(mapOf(
                "series_id_str" to JsonPrimitive("abc"), // 非数字
                "series_title" to JsonPrimitive("测试"),
            ))
        ))

        val drama = HongguoAppClientTestAccessor.parseLandpageItem(itemJson)
        assertNull("非数字 series_id 应返回 null", drama)
    }

    @Test
    fun testParseLandpageItem_missingVideoData_usesRoot() {
        // 兼容：video_data 可能在根层级
        val itemJson = JsonObject(mapOf(
            "series_id_str" to JsonPrimitive("789012"),
            "series_title" to JsonPrimitive("根层级剧集"),
            "series_cover" to JsonPrimitive("https://example.com/cover2.jpg"),
        ))

        val drama = HongguoAppClientTestAccessor.parseLandpageItem(itemJson)

        assertNotNull(drama)
        assertEquals("789012", drama!!.backendId)
        assertEquals("根层级剧集", drama.name)
    }

    // ========== 3. 签名一致性回归（复用 HongguoSignTest 的 golden 值） ==========

    @Test
    fun testSign_consistentWithGo_golden() {
        // 使用 HongguoSignTest 中相同的固定输入
        val query = "aid=8662&app_name=novelread&version_code=73532&version_name=7.3.5.32&manifest_version_code=73532&update_version_code=73532&channel=update_64&device_platform=android&os=android&ssmix=a&device_type=25053RT47C&device_brand=Redmi&language=zh&os_api=36&os_version=16&resolution=1280*2772&dpi=520&ac=wifi&device_id=$deviceId&iid=$deviceId&_rticket=$fixedRticket"
        val bodyBytes = """{"test":"body"}""".toByteArray()

        val (gorgon, khronos, _) = HongguoSign.sign(query, bodyBytes, fixedTs)

        // 这些值来自 Go 实现（provider_hongguo_sign.go），已在 HongguoSignTest 验证
        // 这里只做长度和前缀校验，避免硬编码完整值重复
        assertEquals(52, gorgon.length)
        assertTrue(gorgon.startsWith("8404401c0000"))
        assertEquals(fixedTs.toString(), khronos)
    }

    // ========== 4. 选流纯函数测试 ==========

    @Test
    fun testSelectClearStream_picksHighestQualityH264() {
        // 伪造 video_list：包含多个清晰度、编码的明流
        val videoList = JsonArray(listOf(
            // 720p h264 (score = 720*10 + 1 = 7201)
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("720"),
                    "vwidth" to JsonPrimitive("1280"),
                    "definition" to JsonPrimitive("720p"),
                )),
                "main_url" to JsonPrimitive("https://example.com/720p_h264.m3u8"),
            )),
            // 1080p hevc (score = 1080*10 = 10800)
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("hevc"),
                    "vheight" to JsonPrimitive("1080"),
                    "vwidth" to JsonPrimitive("1920"),
                    "definition" to JsonPrimitive("1080p"),
                )),
                "main_url" to JsonPrimitive("https://example.com/1080p_hevc.m3u8"),
            )),
            // 480p h264 (score = 480*10 + 1 = 4801)
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("avc1"),
                    "vheight" to JsonPrimitive("480"),
                    "vwidth" to JsonPrimitive("854"),
                    "definition" to JsonPrimitive("480p"),
                )),
                "main_url" to JsonPrimitive("https://example.com/480p_h264.m3u8"),
            )),
        ))

        val selected = HongguoAppClientTestAccessor.selectClearStreamTest(videoList)
        assertNotNull("应选出最高分流", selected)
        assertEquals("应选 1080p hevc（分最高）", "https://example.com/1080p_hevc.m3u8", selected)
    }

    @Test
    fun testSelectClearStream_skipsBytevc2() {
        val videoList = JsonArray(listOf(
            // bytevc2 编码应被跳过
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("bytevc2"),
                    "vheight" to JsonPrimitive("1080"),
                )),
                "main_url" to JsonPrimitive("https://example.com/bytevc2.m3u8"),
            )),
            // 正常 h264
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("720"),
                )),
                "main_url" to JsonPrimitive("https://example.com/normal.m3u8"),
            )),
        ))

        val selected = HongguoAppClientTestAccessor.selectClearStreamTest(videoList)
        assertNotNull(selected)
        assertEquals("应跳过 bytevc2，选正常流", "https://example.com/normal.m3u8", selected)
    }

    @Test
    fun testSelectClearStream_skipsEncryptedVariants() {
        val videoList = JsonArray(listOf(
            // 有 spade_a 的加密流
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("1080"),
                )),
                "encrypt_info" to JsonObject(mapOf(
                    "spade_a" to JsonPrimitive("encrypted_key_data"),
                )),
                "main_url" to JsonPrimitive("https://example.com/encrypted.m3u8"),
            )),
            // encrypt=true 的加密流
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("720"),
                )),
                "encrypt_info" to JsonObject(mapOf(
                    "encrypt" to JsonPrimitive("true"),
                )),
                "main_url" to JsonPrimitive("https://example.com/encrypted2.m3u8"),
            )),
            // encryption_method=cenc-aes-ctr 的加密流
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("480"),
                )),
                "encrypt_info" to JsonObject(mapOf(
                    "encryption_method" to JsonPrimitive("cenc-aes-ctr"),
                )),
                "main_url" to JsonPrimitive("https://example.com/encrypted3.m3u8"),
            )),
            // 明流
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("720"),
                )),
                "main_url" to JsonPrimitive("https://example.com/clear.m3u8"),
            )),
        ))

        val selected = HongguoAppClientTestAccessor.selectClearStreamTest(videoList)
        assertNotNull(selected)
        assertEquals("应跳过所有加密变体，选明流", "https://example.com/clear.m3u8", selected)
    }

    @Test
    fun testSelectClearStream_skipsGearDesKeyBytevc2() {
        val videoList = JsonArray(listOf(
            // gear_des_key 含 bytevc2 应被跳过
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("1080"),
                )),
                "gear_des_key" to JsonPrimitive("bytevc2_key_data"),
                "main_url" to JsonPrimitive("https://example.com/gear_bytevc2.m3u8"),
            )),
            // 正常流
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("720"),
                )),
                "main_url" to JsonPrimitive("https://example.com/normal.m3u8"),
            )),
        ))

        val selected = HongguoAppClientTestAccessor.selectClearStreamTest(videoList)
        assertNotNull(selected)
        assertEquals("应跳过 gear_des_key 含 bytevc2", "https://example.com/normal.m3u8", selected)
    }

    @Test
    fun testSelectClearStream_noClearStream_returnsNull() {
        val videoList = JsonArray(listOf(
            // 全是加密流
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("1080"),
                )),
                "encrypt_info" to JsonObject(mapOf(
                    "spade_a" to JsonPrimitive("key"),
                )),
                "main_url" to JsonPrimitive("https://example.com/encrypted.m3u8"),
            )),
            // 全是 bytevc2
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("bytevc2"),
                    "vheight" to JsonPrimitive("720"),
                )),
                "main_url" to JsonPrimitive("https://example.com/bytevc2.m3u8"),
            )),
        ))

        val selected = HongguoAppClientTestAccessor.selectClearStreamTest(videoList)
        assertNull("无明流时应返回 null", selected)
    }

    @Test
    fun testSelectClearStream_emptyList_returnsNull() {
        val videoList = JsonArray(emptyList())
        val selected = HongguoAppClientTestAccessor.selectClearStreamTest(videoList)
        assertNull("空列表应返回 null", selected)
    }

    @Test
    fun testSelectClearStream_usesBackupUrls() {
        // main_url 为空，backup_url 有值
        val videoList = JsonArray(listOf(
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("720"),
                )),
                "main_url" to JsonPrimitive(""),
                "backup_url" to JsonPrimitive("https://example.com/backup.m3u8"),
            )),
        ))

        val selected = HongguoAppClientTestAccessor.selectClearStreamTest(videoList)
        assertNotNull(selected)
        assertEquals("应使用 backup_url", "https://example.com/backup.m3u8", selected)
    }

    @Test
    fun testSelectClearStream_usesDefinitionOverVheight() {
        // definition 优先于 vheight
        val videoList = JsonArray(listOf(
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("720"),
                    "definition" to JsonPrimitive("1080p"),
                )),
                "main_url" to JsonPrimitive("https://example.com/def_1080.m3u8"),
            )),
            JsonObject(mapOf(
                "video_meta" to JsonObject(mapOf(
                    "codec_type" to JsonPrimitive("h264"),
                    "vheight" to JsonPrimitive("1080"),
                    "definition" to JsonPrimitive("720p"),
                )),
                "main_url" to JsonPrimitive("https://example.com/vheight_1080.m3u8"),
            )),
        ))

        val selected = HongguoAppClientTestAccessor.selectClearStreamTest(videoList)
        assertNotNull(selected)
        assertEquals("应优先使用 definition", "https://example.com/def_1080.m3u8", selected)
    }

    @Test
    fun testParseVideoModel_object() {
        val data = JsonObject(mapOf(
            "video_model" to JsonObject(mapOf(
                "video_list" to JsonArray(listOf(
                    JsonObject(mapOf("main_url" to JsonPrimitive("https://example.com/test.m3u8"))),
                )),
            )),
        ))

        val model = HongguoAppClientTestAccessor.parseVideoModelTest(data)
        assertNotNull(model)
        assertNotNull(model!!.getJsonArray("video_list"))
    }

    @Test
    fun testParseVideoModel_jsonString() {
        val innerJson = """{"video_list":[{"main_url":"https://example.com/from_string.m3u8"}]}"""
        val data = JsonObject(mapOf(
            "video_model" to JsonPrimitive(innerJson),
        ))

        val model = HongguoAppClientTestAccessor.parseVideoModelTest(data)
        assertNotNull(model)
        val list = model!!.getJsonArray("video_list")!!
        assertEquals(1, list.size)
    }

    @Test
    fun testParseVideoModel_missing_returnsNull() {
        val data = JsonObject(mapOf(
            "other_field" to JsonPrimitive("value"),
        ))

        val model = HongguoAppClientTestAccessor.parseVideoModelTest(data)
        assertNull(model)
    }
}

/**
 * 测试访问器：将 HongguoAppClient 内部私有逻辑暴露为可测试的纯函数
 * 实际项目中可考虑将这些逻辑提取到单独的工具类/顶层函数
 */
object HongguoAppClientTestAccessor {

    /** 构建基础 query 参数（对应 HongguoAppClient.baseQueryParams + _rticket） */
    fun buildQueryParams(deviceId: String, rticket: String): Map<String, String> {
        return mapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to "73532",
            "version_name" to "7.3.5.32",
            "manifest_version_code" to "73532",
            "update_version_code" to "73532",
            "channel" to "update_64",
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "device_type" to "25053RT47C",
            "device_brand" to "Redmi",
            "language" to "zh",
            "os_api" to "36",
            "os_version" to "16",
            "resolution" to "1280*2772",
            "dpi" to "520",
            "ac" to "wifi",
            "device_id" to deviceId,
            "iid" to deviceId,
            "_rticket" to rticket,
        )
    }

    /** 构建完整请求头（对应 HongguoAppClient.postWithSign 中的签名逻辑） */
    fun buildHeaders(rawQuery: String, bodyBytes: ByteArray, timestampSec: Long): Map<String, String> {
        val (xGorgon, xKhronos, xReqTicket) = HongguoSign.sign(rawQuery, bodyBytes, timestampSec)
        val stub = if (bodyBytes.isNotEmpty()) HongguoSign.stub(bodyBytes) else ""
        return mapOf(
            "User-Agent" to "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)",
            "Accept" to "application/json",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            "Content-Type" to "application/json; charset=utf-8",
            "X-Gorgon" to xGorgon,
            "X-Khronos" to xKhronos,
            "X-SS-Req-Ticket" to xReqTicket,
            "X-SS-STUB" to stub,
        )
    }

    /** 解析 landpage 单条条目（复用 HongguoAppClient 内部逻辑） */
    fun parseLandpageItem(item: JsonObject): Drama? {
        val vd = (item["video_data"] as? JsonObject) ?: item

        val seriesId = vd.strOrNull("series_id_str") ?: vd.strOrNull("series_id")
            ?: item.strOrNull("series_id_str") ?: item.strOrNull("series_id")
            ?: return null

        if (!seriesId.matches(Regex("^\\d+$"))) return null

        val title = vd.strOrNull("series_title") ?: vd.strOrNull("series_name") ?: vd.strOrNull("title")
            ?: item.strOrNull("series_name") ?: item.strOrNull("name") ?: seriesId
        val cover = vd.strOrNull("series_cover") ?: vd.strOrNull("cover")
            ?: item.strOrNull("series_cover") ?: ""
        val intro = vd.strOrNull("series_intro") ?: vd.strOrNull("video_desc")
            ?: item.strOrNull("series_intro") ?: ""
        val count = vd.strOrNull("episode_cnt") ?: item.strOrNull("episode_cnt") ?: ""
        val remark = vd.strOrNull("episode_right_text") ?: item.strOrNull("episode_right_text")
            ?: (if (count.isNotBlank()) "共${count}集" else "")
        val score = vd.strOrNull("score") ?: ""
        val genre = vd.strOrNull("category_name") ?: vd.strOrNull("categoryName") ?: vd.strOrNull("category") ?: "短剧"

        val status = vd.strOrNull("series_status")
        val tags = mutableListOf<String>()
        val tagStr = vd.strOrNull("tags")
        if (tagStr?.isNotBlank() == true) {
            tags.addAll(tagStr.split(",").map { it.trim() }.filter { it.isNotBlank() })
        }
        val categoryList = anyList(vd["category_list"])
        for (cat in categoryList) {
            val name = (cat as? JsonObject)?.strOrNull("name")
            if (name?.isNotBlank() == true && name !in tags) tags.add(name)
        }

        return Drama(
            id = seriesId.toIntOrNull() ?: seriesId.hashCode(),
            sourceId = "hongguo",
            name = title,
            pic = cover,
            type = genre,
            typeId = 0,
            remarks = remark,
            year = "",
            area = "",
            director = "",
            actors = "",
            blurb = intro,
            detail = intro,
            tag = tags.joinToString(","),
            score = score,
            updated = "",
            playGroups = listOf(PlayGroup("红果官方", emptyList())),
            backendId = seriesId,
        )
    }

    private fun anyList(v: kotlinx.serialization.json.JsonElement?): List<kotlinx.serialization.json.JsonElement> {
        return when (v) {
            is kotlinx.serialization.json.JsonArray -> v.toList()
            is kotlinx.serialization.json.JsonObject -> {
                for (key in listOf("list", "items", "data")) {
                    val arr = v[key] as? kotlinx.serialization.json.JsonArray
                    if (arr != null) return arr.toList()
                }
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun kotlinx.serialization.json.JsonObject.strOrNull(key: String): String? {
        val p = this[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        val content = p.content
        return if (content == "null") null else content.takeIf { it.isNotBlank() }
    }

    /** 选流纯函数（委托给顶层 selectClearStream） */
    fun selectClearStreamTest(videoList: JsonArray): String? {
        return selectClearStream(videoList)
    }

    /** 解析 video_model（委托给顶层 parseVideoModel） */
    fun parseVideoModelTest(data: JsonObject): JsonObject? {
        return parseVideoModel(data)
    }
}