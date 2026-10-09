package com.duanju.tv.data.remote

import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Episode
import com.duanju.tv.data.model.PlayGroup
import com.duanju.tv.data.model.Resolved
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
 *         /tmp/opencode/guoapp/native/core/provider_hongguo_native_media.go (video_model)
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
        const val VIDEO_MODEL_PATH = "/novel/player/video_model/v1/"
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

    /**
     * 取流：POST /novel/player/video_model/v1/
     *
     * @param seriesId 剧集 ID (series_id)
     * @param vid 视频 ID
     * @return Resolved.Direct 含明流 URL 与请求头
     * @throws IOException 无明流或请求失败
     */
    suspend fun videoModel(seriesId: String, vid: String): Resolved.Direct = withContext(Dispatchers.IO) {
        if (!seriesId.matches(Regex("^\\d+$")) || !vid.matches(Regex("^\\d+$"))) {
            throw IOException("红果剧集/视频 ID 无效: seriesId=$seriesId, vid=$vid")
        }

        val payload = json.encodeToString(mapOf(
            "video_id" to vid,
            "content_type" to 1,
            "biz_param" to mapOf(
                "need_all_video_definition" to true,
                "video_platform" to 3,
            ),
        ))
        val (response, _) = postWithSign(VIDEO_MODEL_PATH, payload)

        val data = response.getJsonObject("data") ?: throw IOException("video_model 响应缺少 data")
        val videoModel = parseVideoModel(data) ?: throw IOException("video_model 响应缺少 video_model")

        val videoList = videoModel.getJsonArray("video_list") ?: throw IOException("video_model 缺少 video_list")
        val clearUrl = selectClearStream(videoList)
            ?: throw IOException("红果 App 未返回兼容的明流，已跳过加密/不支持编码")

        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "https://hongguoduanju.com/",
            "Origin" to "https://hongguoduanju.com",
        )
        return@withContext Resolved.Direct(clearUrl, headers)
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

/** 解析 video_model 响应，支持直接对象或 JSON 字符串（对应 Go provider_hongguo_native_media.go:27-35） */
fun parseVideoModel(data: JsonObject): JsonObject? {
    val videoModelElement = data["video_model"]
    return when (videoModelElement) {
        is JsonObject -> videoModelElement
        is JsonPrimitive -> {
            val str = videoModelElement.content
            if (str.isNotBlank() && str != "null") {
                try {
                    Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(str) as? JsonObject
                } catch (_: Exception) {
                    null
                }
            } else null
        }
        else -> null
    }
}

/**
 * 选流：从 video_list 中选择最高质量的明流（对应 Go selectHongguoAppMedia/hongguoMediaAddresses）
 * MVP 只返回明流，跳过加密变体、bytevc2 编码
 *
 * @return 明流 URL，无可用明流返回 null
 */
fun selectClearStream(videoList: JsonArray): String? {
    var bestUrl: String? = null
    var bestScore = -1

    for (item in videoList.toList()) {
        val variant = item as? JsonObject ?: continue

        // 跳过 bytevc2 编码（对应 Go: codec == "bytevc2" 或 gear_des_key 含 bytevc2）
        val meta = variant.getJsonObject("video_meta") ?: continue
        val codec = meta.strOrNull("codec_type")?.lowercase() ?: ""
        if (codec == "bytevc2") continue
        val gearDesKey = variant.strOrNull("gear_des_key")?.lowercase() ?: ""
        if (gearDesKey.contains("bytevc2")) continue

        // 跳过加密变体（对应 Go: encrypt_info.spade_a 或 encrypt=true 或 encryption_method=cenc-aes-ctr）
        val encryptInfo = variant.getJsonObject("encrypt_info")
        if (encryptInfo != null) {
            val spadeA = encryptInfo.strOrNull("spade_a") ?: ""
            val encrypt = encryptInfo["encrypt"]?.let { (it as JsonPrimitive).content.toBoolean() } ?: false
            val encryptionMethod = encryptInfo.strOrNull("encryption_method") ?: ""
            if (spadeA.isNotBlank() || encrypt || encryptionMethod == "cenc-aes-ctr") {
                continue // MVP 只处理明流
            }
        }

        // 提取地址（对应 Go hongguoMediaAddresses: main_url, backup_url, backup_url_1, backup_url_2, backup_urls, url_list）
        val addresses = extractMediaAddresses(variant)
        if (addresses.isEmpty()) continue

        // 计算质量分（对应 Go: height * 10 + (h264/avc1 ? 1 : 0)）
        val height = meta.strOrNull("vheight")?.toIntOrNull() ?: 0
        val definitionStr = meta.strOrNull("definition") ?: ""
        val definition = Regex("[0-9]+").find(definitionStr)?.value?.toIntOrNull() ?: 0
        val effectiveHeight = if (definition > 0) definition else height
        val width = meta.strOrNull("vwidth")?.toIntOrNull() ?: 0
        val finalHeight = if (effectiveHeight > 0) effectiveHeight else if (width > 0) width else 0

        var score = finalHeight * 10
        if (codec == "h264" || codec == "avc1") score++

        for (url in addresses) {
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
    }

    return bestUrl
}

/** 从 variant 中提取媒体地址（对应 Go hongguoMediaAddresses） */
private fun extractMediaAddresses(variant: JsonObject): List<String> {
    val addresses = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    fun addAddress(value: JsonElement?) {
        when (value) {
            is JsonPrimitive -> {
                val str = value.content.trim()
                if (str.isNotEmpty() && str.length <= 8192) {
                    val url = if (isHttpMediaUrl(str)) str else decodeBase64IfNeeded(str)
                    if (isHttpMediaUrl(url) && seen.add(url)) addresses.add(url)
                }
            }
            is JsonArray -> value.forEach { addAddress(it) }
            else -> {}
        }
    }

    for (key in listOf("main_url", "backup_url", "backup_url_1", "backup_url_2", "backup_urls", "url_list")) {
        addAddress(variant[key])
    }
    return addresses
}

/** 判断是否为 HTTP 媒体 URL */
private fun isHttpMediaUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}

/** 尝试 Base64 解码（对应 Go decodeHongguoBase64：先标准、再无填充；失败返回原文由调用方过滤）
 *  纯 Kotlin 实现：不依赖 android.util.Base64（纯 JVM 单测无此 API）也不依赖 java.util.Base64（需 API 26，minSdk 23 不可用）。 */
private fun decodeBase64IfNeeded(str: String): String {
    val bytes = decodeBase64Strict(str) ?: return str
    return String(bytes, Charsets.UTF_8).trim()
}

private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** 严格 Base64 解码：非法字符/`=`位置不对/长度非法返回 null；接受带填充与无填充两种形态。 */
private fun decodeBase64Strict(s: String): ByteArray? {
    var t = s.trim()
    if (t.isEmpty() || t.length % 4 == 1) return null
    t = t.padEnd(((t.length + 3) / 4) * 4, '=')
    val pad = t.takeLastWhile { it == '=' }.length
    if (pad > 2) return null
    val body = t.dropLast(pad)
    if (body.any { it !in B64_ALPHABET }) return null
    val out = ByteArray(t.length / 4 * 3 - pad)
    var o = 0
    fun b64val(c: Char): Int = if (c == '=') 0 else B64_ALPHABET.indexOf(c)
    for (i in t.indices step 4) {
        val n = (b64val(t[i]) shl 18) or (b64val(t[i + 1]) shl 12) or
            (b64val(t[i + 2]) shl 6) or b64val(t[i + 3])
        out[o++] = (n shr 16).toByte()
        if (o < out.size) out[o++] = (n shr 8).toByte()
        if (o < out.size) out[o++] = n.toByte()
    }
    return out
}

// ========== 顶层扩展函数（供顶层函数使用） ==========

fun JsonObject.getJsonObject(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.getJsonArray(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.strOrNull(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    val content = p.content
    return if (content == "null") null else content.takeIf { it.isNotBlank() }
}

fun JsonArray.toList(): List<JsonElement> = (0 until size).map { get(it) }

fun Map<String, String>.toHeaders(): okhttp3.Headers {
    return okhttp3.Headers.Builder().apply {
        for ((k, v) in this@toHeaders) add(k, v)
    }.build()
}