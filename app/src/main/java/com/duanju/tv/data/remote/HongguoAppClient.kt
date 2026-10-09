package com.duanju.tv.data.remote

import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Episode
import com.duanju.tv.data.model.PlayGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 红果短剧 App 通道客户端
 *
 * 对应 Go: /tmp/opencode/guoapp/native/core/provider_hongguo_app.go (hongguoAppRequest)
 *         /tmp/opencode/guoapp/native/core/provider_hongguo_catalog.go (landpage)
 *         /tmp/opencode/guoapp/native/core/provider_hongguo_detail.go (video_detail)
 *
 * 签名复用 HongguoSign.sign/stub，不重写签名逻辑。
 */
class HongguoAppClient(
    private val http: OkHttpClient,
    private val deviceId: String,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val baseUrl = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val userAgent =
        "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    // 固定 query 参数（对应 Go provider_hongguo_app.go:135-141）
    private val baseQueryParams = mapOf(
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
        "iid" to deviceId, // install_id 同 device_id
    )

    companion object {
        const val LAND_PAGE_PATH = "/reading/distribution/category/landpage/v/"
        const val VIDEO_DETAIL_PATH = "/novel/player/video_detail/v1/"
        const val MAX_RETRIES = 3
        const val TIMEOUT_SECONDS = 20L
    }

    /**
     * 列表页：POST /reading/distribution/category/landpage/v/
     *
     * @param cursor 分页游标（JSON 字符串，包含 offset/sessionId/lastId/pageSignature），null 表示首页
     * @return Pair(剧集列表, 下一页游标JSON字符串或null表示无更多)
     */
    suspend fun landpage(cursor: String?): Pair<List<Drama>, String?> = withContext(Dispatchers.IO) {
        val cursorObj = cursor?.let { json.decodeFromString<LandpageCursor>(it) }
            ?: LandpageCursor(offset = 0, initialized = false)

        val payload = buildLandpagePayload(cursorObj)
        val (response, _) = postWithSign(LAND_PAGE_PATH, payload)

        val data = response.getJsonObject("data") ?: throw IOException("landpage 响应缺少 data")
        val videoData = data.getJsonArray("video_data") ?: throw IOException("landpage 响应缺少 video_data")

        val dramas = mutableListOf<Drama>()
        val ids = mutableListOf<String>()

        for (item in videoData.toList()) {
            val drama = parseDramaFromLandpageItem(item as JsonObject)
            if (drama != null) {
                dramas.add(drama)
                ids.add(drama.backendId ?: drama.id.toString())
            }
        }

        val nextOffset = data["next_offset"]?.let { (it as JsonPrimitive).content.toIntOrNull() } ?: (cursorObj.offset + videoData.size)
        val hasMore = data["has_more"]?.let { (it as JsonPrimitive).content.toBoolean() } ?: false
        val sessionId = data["session_id"]?.let { (it as JsonPrimitive).content } ?: ""

        val nextCursor = if (hasMore) {
            LandpageCursor(
                offset = nextOffset,
                sessionId = sessionId,
                lastId = dramas.lastOrNull()?.backendId ?: "",
                pageSignature = if (ids.isNotEmpty()) computePageSignature(ids) else "",
                initialized = true,
                exhausted = false,
            )
        } else {
            LandpageCursor(
                offset = nextOffset,
                sessionId = sessionId,
                lastId = dramas.lastOrNull()?.backendId ?: "",
                pageSignature = "",
                initialized = true,
                exhausted = true,
            )
        }

        dramas to json.encodeToString(nextCursor)
    }

    /**
     * 详情页：POST /novel/player/video_detail/v1/
     *
     * @param seriesId 剧集 ID (series_id)
     * @return Drama 含完整剧集列表
     */
    suspend fun videoDetail(seriesId: String): Drama = withContext(Dispatchers.IO) {
        if (!seriesId.matches(Regex("^\\d+$"))) {
            throw IOException("红果剧集 ID 无效: $seriesId")
        }

        val payload = json.encodeToString(mapOf("series_id" to seriesId))
        val (response, _) = postWithSign(VIDEO_DETAIL_PATH, payload)

        val detail = response.getJsonObject("data")?.getJsonObject("video_data")
            ?: throw IOException("video_detail 响应缺少 data.video_data")

        val returnedId = detail.strOrNull("series_id_str") ?: detail.strOrNull("series_id")
            ?: throw IOException("video_detail 未返回 series_id")
        if (returnedId != seriesId) {
            throw IOException("红果 App 未返回所请求的剧集: 期望 $seriesId, 实际 $returnedId")
        }

        val title = detail.strOrNull("series_title") ?: detail.strOrNull("series_name") ?: detail.strOrNull("name") ?: seriesId
        val cover = detail.strOrNull("series_cover") ?: detail.strOrNull("cover") ?: ""
        val intro = detail.strOrNull("series_intro") ?: detail.strOrNull("video_desc") ?: ""
        val count = detail.strOrNull("episode_cnt") ?: ""
        val remark = detail.strOrNull("episode_right_text") ?: (if (count.isNotBlank()) "共${count}集" else "")
        val score = detail.strOrNull("score") ?: ""
        val category = detail.strOrNull("category_name") ?: detail.strOrNull("categoryName") ?: detail.strOrNull("category") ?: "短剧"

        val vidList = detail.getJsonArray("vid_list") ?: throw IOException("video_detail 缺少 vid_list")
        val episodes = mutableListOf<Episode>()
        val seenVids = mutableSetOf<String>()
        val seenIndices = mutableSetOf<Int>()

        for (item in vidList.toList()) {
            val video = item as? JsonObject ?: continue
            val vid = video.strOrNull("vid") ?: continue
            val indexStr = video.strOrNull("vid_index") ?: continue
            val index = indexStr.toIntOrNull() ?: continue

            if (index < 1 || !vid.matches(Regex("^\\d+$"))) continue
            if (vid in seenVids || index in seenIndices) continue
            if (video.strOrNull("series_id")?.let { it != seriesId } == true) continue

            seenVids.add(vid)
            seenIndices.add(index)
            episodes.add(Episode(index, "第${index}集", "hongguo://$seriesId/$vid"))
        }

        episodes.sortBy { it.index }

        val total = detail.strOrNull("episode_cnt")?.toIntOrNull() ?: episodes.size
        if (episodes.isEmpty() || total > episodes.size) {
            throw IOException("红果 App 未返回完整分集")
        }
        for (i in episodes.indices) {
            if (episodes[i].index != i + 1) {
                throw IOException("红果 App 分集列表不连续")
            }
        }

        val playGroup = PlayGroup("红果官方", episodes)

        Drama(
            id = seriesId.toIntOrNull() ?: seriesId.hashCode(),
            sourceId = "hongguo",
            name = title,
            pic = cover,
            type = category,
            typeId = 0,
            remarks = remark,
            year = "",
            area = "",
            director = "",
            actors = "",
            blurb = intro,
            detail = intro,
            tag = "",
            score = score,
            updated = "",
            playGroups = listOf(playGroup),
            backendId = seriesId,
        )
    }

    // ========== 内部方法 ==========

    /** 执行带签名的 POST 请求，返回 (JSON响应, 原始query字符串用于调试) */
    private suspend fun postWithSign(path: String, jsonBody: String): Pair<JsonObject, String> = withContext(Dispatchers.IO) {
        val bodyBytes = jsonBody.toByteArray()
        val rticket = System.currentTimeMillis().toString()

        // 构建 query（含 _rticket）
        val queryParams = baseQueryParams.toMutableMap()
        queryParams["_rticket"] = rticket
        val rawQuery = queryParams.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

        // 签名
        val (xGorgon, xKhronos, xReqTicket) = HongguoSign.sign(rawQuery, bodyBytes, (rticket.toLong() / 1000).toLong())
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            "Content-Type" to "application/json; charset=utf-8",
            "X-Gorgon" to xGorgon,
            "X-Khronos" to xKhronos,
            "X-SS-Req-Ticket" to xReqTicket,
            "X-SS-STUB" to HongguoSign.stub(bodyBytes),
        )

        var lastErr: IOException? = null
        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) {
                try { Thread.sleep(attempt * 1000L) } catch (_: InterruptedException) { throw IOException("请求被中断") }
            }

            val request = Request.Builder()
                .url("$baseUrl$path?$rawQuery")
                .headers(headers.toHeaders())
                .post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code >= 400 && resp.code < 500) {
                            throw IOException("红果 App 接口 HTTP ${resp.code}")
                        }
                        throw IOException("红果 App 接口 HTTP ${resp.code}")
                    }
                    val body = resp.body?.string() ?: throw IOException("空响应")
                    if (body.isEmpty()) throw IOException("空响应体")

                    val result = json.parseToJsonElement(body) as? JsonObject
                        ?: throw IOException("响应非 JSON 对象")

                    val code = result.strOrNull("code") ?: result.strOrNull("Code") ?: result.strOrNull("status_code")
                        ?: result.getJsonObject("BaseResp")?.strOrNull("StatusCode")
                    if (code != null && code != "0") {
                        throw IOException("红果 App 接口暂不可用（$code）")
                    }

                    return@withContext result to rawQuery
                }
            } catch (e: IOException) {
                lastErr = e
                if (attempt == MAX_RETRIES - 1) throw e
            }
        }
        throw lastErr ?: IOException("未知错误")
    }

    /** 构建 landpage 请求体（对应 Go provider_hongguo_catalog.go:109-118） */
    private fun buildLandpagePayload(cursor: LandpageCursor): String {
        val selectItems = mapOf(
            "genre" to listOf("short_play"),
            "sort" to listOf("online_time"),
            "gender" to emptyList<String>(),
            "category_dim_theme" to emptyList<String>(),
            "category_dim_role" to emptyList<String>(),
            "category_dim_epoch" to emptyList<String>(),
            "online_time" to emptyList<String>(),
            "creation_status" to emptyList<String>(),
        )

        val payload = mutableMapOf<String, Any>(
            "req_scene" to "default",
            "offset" to cursor.offset,
            "limit" to 18,
            "req_type" to "only_content",
            "need_selector_panel" to false,
            "client_req_type" to if (cursor.offset > 0) 2 else 3,
            "session_id" to cursor.sessionId,
            "filter_ids" to "",
            "select_items" to selectItems,
        )

        return json.encodeToString(payload)
    }

    /** 解析 landpage 单条条目（对应 Go hongguoDramaFromAny） */
    private fun parseDramaFromLandpageItem(item: JsonObject): Drama? {
        val vd = item.getJsonObject("video_data") ?: item

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

        // 状态
        val status = vd.strOrNull("series_status")
        val releaseStatus = when (status) {
            "1" -> "finished"
            "0" -> "ongoing"
            else -> ""
        }

        // 标签
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
            playGroups = listOf(PlayGroup("红果官方", emptyList())), // 详情页再填充剧集
            backendId = seriesId,
        )
    }

    /** 计算分页签名（对应 Go provider_hongguo_catalog.go:229-230） */
    private fun computePageSignature(ids: List<String>): String {
        val sorted = ids.sorted()
        val joined = sorted.joinToString("\n")
        // 简化：Go 用 SHA256，这里用 hashCode 作为占位，实际应用 SHA256
        // TODO: 替换为真正的 SHA256
        return joined.hashCode().toString(16)
    }

    /** 通用 JSON 数组提取 */
    private fun anyList(v: JsonElement?): List<JsonElement> {
        return when (v) {
            is JsonArray -> v.toList()
            is JsonObject -> {
                for (key in listOf("list", "items", "data")) {
                    val arr = v[key] as? JsonArray
                    if (arr != null) return arr.toList()
                }
                emptyList()
            }
            else -> emptyList()
        }
    }

    // ========== 扩展函数 ==========

    private fun JsonObject.getJsonObject(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.getJsonArray(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.strOrNull(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        val content = p.content
        return if (content == "null") null else content.takeIf { it.isNotBlank() }
    }

    private fun JsonArray.toList(): List<JsonElement> = (0 until size).map { get(it) }

    private fun Map<String, String>.toHeaders(): okhttp3.Headers {
        return okhttp3.Headers.Builder().apply {
            for ((k, v) in this@toHeaders) add(k, v)
        }.build()
    }
}

/** landpage 分页游标（对应 Go hongguoCatalogCursor） */
@kotlinx.serialization.Serializable
data class LandpageCursor(
    val offset: Int = 0,
    val sessionId: String = "",
    val lastId: String = "",
    val pageSignature: String = "",
    val initialized: Boolean = false,
    val exhausted: Boolean = false,
)