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
import java.security.MessageDigest

/**
 * 红果短剧 App 通道客户端
 *
 * 对应 Go: guoapp/native/core/provider_hongguo_app.go (hongguoAppRequest)
 *         guoapp/native/core/provider_hongguo_catalog.go (landpage)
 *         guoapp/native/core/provider_hongguo_detail.go (video_detail)
 *         guoapp/native/core/provider_hongguo_native_media.go (video_model)
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

        /** Go 侧的分页前进上界（provider_hongguo_catalog.go:232） */
        const val MAX_OFFSET = 1_000_000
    }

    /**
     * 列表页：POST /reading/distribution/category/landpage/v/
     *
     * 分页是游标制（offset + session_id），不是页码：接口只认上一页返回的游标，
     * 传裸页码无效。调用方按顺序持有 [LandpageCursor]，首页传 null。
     *
     * @return Pair(剧集列表, 下一页游标；null 表示没有更多)
     */
    suspend fun landpage(cursor: LandpageCursor?): Pair<List<Drama>, LandpageCursor?> =
        withContext(Dispatchers.IO) {
            val current = cursor ?: LandpageCursor(offset = 0, initialized = false)
            val (response, _) = postWithSign(LAND_PAGE_PATH, buildLandpagePayload(current))

            val dramas = parseLandpageRows(response)

            val data = response.getJsonObject("data") ?: JsonObject(emptyMap())
            val rows = data.getJsonArray("video_data")?.toList().orEmpty()
            val nextOffset = data.strOrNull("next_offset")?.toIntOrNull() ?: (current.offset + rows.size)
            val hasMore = data.strOrNull("has_more")?.toBooleanStrictOrNull() ?: false
            val sessionId = data.strOrNull("session_id").orEmpty()

            if (!hasMore || dramas.isEmpty()) {
                // 没有下一页：返回 null 游标，让上层知道已经到底
                return@withContext dramas to null
            }
            if (nextOffset <= current.offset || nextOffset > MAX_OFFSET) {
                throw IOException("红果 App 分页未前进（offset ${current.offset} -> $nextOffset）")
            }
            // session_id 下一页要回写进请求体，长度和控制字符先挡一下（对应 Go 的同名校验）
            if (sessionId.length > 4096 || sessionId.any { it == '\r' || it == '\n' || it.code == 0 }) {
                throw IOException("红果 App 分页会话无效")
            }
            val ids = dramas.mapNotNull { it.backendId }
            val signature = computePageSignature(ids)
            if (signature.isNotEmpty() && signature == current.pageSignature) {
                // has_more 说还有货，但页面内容和上一页一模一样——游标没生效，
                // 继续翻页只会拿同一批，交给上层降级
                throw IOException("红果 App 分页未更新（offset ${current.offset} -> $nextOffset）")
            }
            dramas to LandpageCursor(
                offset = nextOffset,
                sessionId = sessionId,
                lastId = ids.lastOrNull().orEmpty(),
                pageSignature = signature,
                initialized = true,
                exhausted = false,
            )
        }

    /** 解析 landpage 响应行（独立出来便于单测：缺 data/video_data、整页不可识别都抛错） */
    internal fun parseLandpageRows(response: JsonObject): List<Drama> {
        val data = response.getJsonObject("data") ?: throw IOException("landpage 响应缺少 data")
        val videoData = data.getJsonArray("video_data") ?: throw IOException("landpage 响应缺少 video_data")
        val out = ArrayList<Drama>(videoData.size)
        for (item in videoData.toList()) {
            hongguoDramaFromItem(item as? JsonObject ?: continue)?.let { out.add(it) }
        }
        if (videoData.size > 0 && out.isEmpty()) throw IOException("红果 App 分类未返回可识别的剧集")
        return out
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

        val payload = JsonObject(mapOf("series_id" to JsonPrimitive(seriesId)))
        val (response, _) = postWithSign(VIDEO_DETAIL_PATH, payload)

        val detail = response.getJsonObject("data")?.getJsonObject("video_data")
            ?: throw IOException("video_detail 响应缺少 data.video_data")

        val returnedId = detail.strOrNull("series_id_str") ?: detail.strOrNull("series_id")
            ?: throw IOException("video_detail 未返回 series_id")
        if (returnedId != seriesId) {
            throw IOException("红果 App 未返回所请求的剧集: 期望 $seriesId, 实际 $returnedId")
        }

        val episodes = parseDetailEpisodes(detail, seriesId)
        if (episodes.isEmpty()) throw IOException("红果 App 未返回分集")
        val total = detail.strOrNull("episode_cnt")?.toIntOrNull() ?: episodes.size
        if (total > episodes.size) throw IOException("红果 App 未返回完整分集")
        for (i in episodes.indices) {
            if (episodes[i].index != i + 1) throw IOException("红果 App 分集列表不连续")
        }

        val base = hongguoDramaFromItem(detail) ?: throw IOException("红果 App 详情缺少剧集字段")
        base.copy(
            playGroups = listOf(PlayGroup(HONGGUO_GROUP_NAME, episodes)),
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

        // 必须用 JsonObject：Map<String, Any> 走 kotlinx 会抛
        // "Serializer for class 'Any' is not found"（已实测），整个 App 通道因此不可用
        val payload = JsonObject(
            mapOf(
                "video_id" to JsonPrimitive(vid),
                "content_type" to JsonPrimitive(1),
                "biz_param" to JsonObject(
                    mapOf(
                        "need_all_video_definition" to JsonPrimitive(true),
                        "video_platform" to JsonPrimitive(3),
                    )
                ),
            )
        )
        val (response, _) = postWithSign(VIDEO_MODEL_PATH, payload)

        val data = response.getJsonObject("data") ?: throw IOException("video_model 响应缺少 data")
        val videoModel = parseVideoModel(data) ?: throw IOException("video_model 响应缺少 video_model")

        val variants = videoModelVariants(videoModel)
        val clearUrl = selectClearStream(variants)
            ?: throw IOException("红果 App 未返回兼容的明流，已跳过加密/不支持编码")

        Resolved.Direct(
            clearUrl,
            mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://hongguoduanju.com/",
                "Origin" to "https://hongguoduanju.com",
            ),
        )
    }

    // ========== 内部方法 ==========

    /** 执行带签名的 POST 请求，返回 (JSON响应, 原始query字符串用于调试) */
    private suspend fun postWithSign(path: String, body: JsonObject): Pair<JsonObject, String> =
        withContext(Dispatchers.IO) {
            val bodyBytes = body.toString().toByteArray()
            val rticket = System.currentTimeMillis().toString()

            // 构建 query（含 _rticket）
            val queryParams = baseQueryParams.toMutableMap()
            queryParams["_rticket"] = rticket
            val rawQuery = queryParams.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }

            // 签名
            val (xGorgon, xKhronos, xReqTicket) =
                HongguoSign.sign(rawQuery, bodyBytes, (rticket.toLong() / 1000))
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
                    try {
                        Thread.sleep(attempt * 1000L)
                    } catch (_: InterruptedException) {
                        throw IOException("请求被中断")
                    }
                }

                val request = Request.Builder()
                    .url("$baseUrl$path?$rawQuery")
                    .headers(headers.toHeaders())
                    .post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                try {
                    http.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            // 4xx 是请求本身被拒（签名/参数问题），换 IP 或重试都不会变
                            if (resp.code in 400..499) {
                                throw PermanentAppError("红果 App 接口 HTTP ${resp.code}")
                            }
                            throw IOException("红果 App 接口 HTTP ${resp.code}")
                        }
                        val body = resp.body?.string()
                        if (body.isNullOrEmpty()) {
                            // 200 空 body 是这台设备未注册时 video_detail/video_model
                            // 的固定返回形态，重试不会变，直接判死
                            throw PermanentAppError("红果 App 接口返回空响应")
                        }

                        val result = json.parseToJsonElement(body) as? JsonObject
                            ?: throw IOException("响应非 JSON 对象")

                        val code = result.strOrNull("code") ?: result.strOrNull("Code")
                            ?: result.strOrNull("status_code")
                            ?: result.getJsonObject("BaseResp")?.strOrNull("StatusCode")
                        if (code != null && code != "0") {
                            // 业务码（如 110001 未知异常、未登录设备取不到流）重试无意义，
                            // 立刻让上层回退网页通道，别把三次退避耗在详情页打开上
                            throw PermanentAppError("红果 App 接口暂不可用（$code）")
                        }

                        return@withContext result to rawQuery
                    }
                } catch (e: PermanentAppError) {
                    throw e
                } catch (e: IOException) {
                    lastErr = e
                    if (attempt == MAX_RETRIES - 1) throw e
                }
            }
            throw lastErr ?: IOException("未知错误")
        }

    /** 构建 landpage 请求体（对应 Go provider_hongguo_catalog.go:109-121） */
    internal fun buildLandpagePayload(cursor: LandpageCursor): JsonObject {
        val selectItems = JsonObject(
            mapOf(
                "genre" to JsonArray(listOf(JsonPrimitive("short_play"))),
                "sort" to JsonArray(listOf(JsonPrimitive("online_time"))),
                "gender" to JsonArray(emptyList()),
                "category_dim_theme" to JsonArray(emptyList()),
                "category_dim_role" to JsonArray(emptyList()),
                "category_dim_epoch" to JsonArray(emptyList()),
                "online_time" to JsonArray(emptyList()),
                "creation_status" to JsonArray(emptyList()),
            )
        )
        return JsonObject(
            mapOf(
                "req_scene" to JsonPrimitive("default"),
                "offset" to JsonPrimitive(cursor.offset),
                "limit" to JsonPrimitive(18),
                "req_type" to JsonPrimitive("only_content"),
                "need_selector_panel" to JsonPrimitive(false),
                "client_req_type" to JsonPrimitive(if (cursor.offset > 0) 2 else 3),
                "session_id" to JsonPrimitive(cursor.sessionId),
                "filter_ids" to JsonPrimitive(""),
                "select_items" to selectItems,
            )
        )
    }

    /** 分页签名：页内 series_id 排序后换行拼接取 SHA-256（对应 Go provider_hongguo_catalog.go:229-230） */
    internal fun computePageSignature(ids: List<String>): String {
        if (ids.isEmpty()) return ""
        val joined = ids.sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 详情分集：App 用 video_list（对象带 vid/vid_index），网页用 vid_list（纯字符串数组）。
     * 两种形态都兼容，见 [parseHongguoVidList]。
     */
    internal fun parseDetailEpisodes(detail: JsonObject, seriesId: String): List<Episode> =
        parseHongguoVidList(
            detail.getJsonArray("video_list")?.takeIf { it.isNotEmpty() } ?: detail["vid_list"],
            seriesId,
        )
}

/** 红果线路在详情页展示的名字 */
const val HONGGUO_GROUP_NAME = "红果官方"

/**
 * App 通道的「重试也不会好」类错误：4xx、空响应、业务码非 0。
 * 上层据此把 App 通道标记为冷却，直接走网页通道，避免每次进详情都白等。
 */
internal class PermanentAppError(message: String) : IOException(message)

internal val NUMERIC_ID = Regex("^\\d+$")

/**
 * 红果分集列表 -> Episode。
 *
 * 三种落点都要兼容：
 * - App video_detail 的 `video_list`：对象数组，带 vid / vid_index
 * - 网页 detail 的 `vid_list`：纯 vid 字符串数组
 * - 网页 detail 的 `vid_list`：对象数组（同样带 vid / vid_index）
 *
 * 缺 vid_index 时按出现顺序编号；只收数字 vid，vid 与序号各自去重后按序号排序。
 */
internal fun parseHongguoVidList(element: JsonElement?, seriesId: String): List<Episode> {
    val rows = when (element) {
        is JsonArray -> element.toList()
        is JsonObject -> jsonList(element)
        else -> emptyList()
    }
    val episodes = ArrayList<Episode>(rows.size)
    val seenVids = HashSet<String>()
    val seenIndices = HashSet<Int>()
    var positional = 0
    for (row in rows) {
        val vid: String?
        val index: Int?
        when (row) {
            is JsonObject -> {
                vid = row.strOrNull("vid") ?: row.strOrNull("video_id")
                index = row.strOrNull("vid_index")?.toIntOrNull()
            }
            is JsonPrimitive -> {
                vid = row.contentOrNullSafe()
                index = null
            }
            else -> { vid = null; index = null }
        }
        if (vid == null || !vid.matches(NUMERIC_ID)) continue
        positional++
        val number = index ?: positional
        if (number < 1) continue
        if (row is JsonObject && row.strOrNull("series_id")?.let { it != seriesId } == true) continue
        if (!seenVids.add(vid) || !seenIndices.add(number)) continue
        episodes.add(Episode(number, "第${number}集", "hongguo://$seriesId/$vid"))
    }
    return episodes.sortedBy { it.index }
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

/**
 * 红果条目 -> Drama（对应 Go hongguoDramaFromAny）。
 *
 * App landpage 与网页 category 的字段名不一致（title/series_title、cover/series_cover、
 * video_desc/series_intro），这里一次兼容两边；tags 既可能是逗号字符串也可能是数组，
 * category_schema 是 JSON 字符串，都要展开。
 */
internal fun hongguoDramaFromItem(item: JsonObject, categoryFallback: String = ""): Drama? {
    val vd = item.getJsonObject("video_data") ?: item

    val seriesId = vd.strOrNull("series_id_str") ?: vd.strOrNull("series_id")
        ?: item.strOrNull("series_id_str") ?: item.strOrNull("series_id")
        ?: vd.strOrNull("keyword") ?: item.strOrNull("keyword")
        ?: return null
    if (!seriesId.matches(NUMERIC_ID)) return null

    val title = vd.strOrNull("series_title") ?: vd.strOrNull("series_name") ?: vd.strOrNull("title")
        ?: item.strOrNull("series_name") ?: item.strOrNull("title") ?: item.strOrNull("name") ?: seriesId
    val cover = vd.strOrNull("series_cover") ?: vd.strOrNull("cover")
        ?: item.strOrNull("series_cover") ?: item.strOrNull("cover") ?: ""
    val intro = vd.strOrNull("series_intro") ?: vd.strOrNull("video_desc")
        ?: item.strOrNull("series_intro") ?: item.strOrNull("video_desc") ?: ""
    val count = vd.strOrNull("episode_cnt") ?: item.strOrNull("episode_cnt") ?: ""
    val remark = vd.strOrNull("episode_right_text") ?: item.strOrNull("episode_right_text")
        ?: if (count.isNotBlank()) "共${count}集" else ""
    val score = vd.strOrNull("score") ?: ""

    val tags = ArrayList<String>()
    fun addTag(name: String?) {
        val t = name?.trim()
        if (!t.isNullOrEmpty() && t !in tags) tags.add(t)
    }
    collectStrings(vd["tags"]).forEach(::addTag)
    collectStrings(item["tags"]).forEach(::addTag)
    for (cat in jsonList(vd["category_list"])) addTag((cat as? JsonObject)?.strOrNull("name"))
    // category_schema 是被转义的 JSON 字符串：[{"name":"都市",...}]
    vd.strOrNull("category_schema")?.let { schema ->
        (runCatching { HONGGUO_JSON.parseToJsonElement(schema) }.getOrNull() as? JsonArray)
            ?.forEach { addTag((it as? JsonObject)?.strOrNull("name")) }
    }

    // 归类优先级与 Go 一致：分类字段 -> 首个标签 -> 列表页路由名
    val genre = vd.strOrNull("category_name") ?: vd.strOrNull("categoryName") ?: vd.strOrNull("category")
        ?: item.strOrNull("category_name")
        ?: tags.firstOrNull() ?: categoryFallback.ifBlank { "短剧" }

    return Drama(
        id = seriesId.toLongOrNull()?.let { (it and 0x7FFFFFFFL).toInt() } ?: seriesId.hashCode(),
        sourceId = "hongguo",
        name = title,
        pic = cover,
        type = genre,
        typeId = 0,
        remarks = remark,
        year = "",
        area = "",
        director = "",
        actors = celebrityNames(vd["celebrities"]).joinToString(","),
        blurb = intro,
        detail = intro,
        tag = tags.joinToString(","),
        score = score,
        updated = "",
        playGroups = listOf(PlayGroup(HONGGUO_GROUP_NAME, emptyList())), // 详情页再填充剧集
        backendId = seriesId,
    )
}

/** 演员列表：元素是 {"nickname":"张三", ...} 或纯字符串 */
internal fun celebrityNames(v: JsonElement?): List<String> {
    val out = ArrayList<String>()
    for (e in jsonList(v)) {
        val name = when (e) {
            is JsonObject -> e.strOrNull("nickname") ?: e.strOrNull("name")
            is JsonPrimitive -> e.content.takeIf { it.isNotBlank() && it != "null" }
            else -> null
        }
        if (!name.isNullOrBlank() && name !in out) out.add(name)
    }
    return out
}

private val HONGGUO_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/** 取出字段里的字符串：支持字符串、字符串数组、对象数组（带 name/content） */
internal fun collectStrings(v: JsonElement?): List<String> {
    val out = ArrayList<String>()
    fun walk(e: JsonElement?) {
        when (e) {
            is JsonArray -> e.forEach(::walk)
            is JsonObject -> {
                val name = e.strOrNull("name") ?: e.strOrNull("content") ?: e.strOrNull("text")
                if (name != null) out.add(name) else e.values.forEach(::walk)
            }
            is JsonPrimitive -> e.contentOrNullSafe()?.let { s ->
                // 逗号分隔的字符串标签也拆开
                s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
            }
            else -> {}
        }
    }
    walk(v)
    return out
}

private fun JsonPrimitive.contentOrNullSafe(): String? {
    val c = content
    return if (c == "null" || c.isBlank()) null else c
}

/** 解析 video_model 响应，支持直接对象或 JSON 字符串（对应 Go provider_hongguo_native_media.go:27-35） */
fun parseVideoModel(data: JsonObject): JsonObject? {
    val videoModelElement = data["video_model"]
    return when (videoModelElement) {
        is JsonObject -> videoModelElement
        is JsonPrimitive -> {
            val str = videoModelElement.content
            if (str.isNotBlank() && str != "null") {
                try {
                    HONGGUO_JSON.parseToJsonElement(str) as? JsonObject
                } catch (_: Exception) {
                    null
                }
            } else null
        }
        else -> null
    }
}

/**
 * video_model.video_list 可能是数组，也可能是以清晰度为 key 的对象
 * （对应 Go provider_hongguo_native_media.go:46-56）
 */
fun videoModelVariants(model: JsonObject): JsonArray {
    (model["video_list"] as? JsonArray)?.let { return it }
    val map = model["video_list"] as? JsonObject ?: return JsonArray(emptyList())
    return JsonArray(map.keys.sorted().mapNotNull { map[it] })
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
            val encrypt = encryptInfo["encrypt"]?.let { (it as JsonPrimitive).content.toBooleanStrictOrNull() } ?: false
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
        val width = meta.strOrNull("vwidth")?.toIntOrNull() ?: 0
        val finalHeight = when {
            definition > 0 -> definition
            height > 0 -> height
            width > 0 -> width
            else -> 0
        }

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
internal fun extractMediaAddresses(variant: JsonObject): List<String> {
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

/**
 * JSON 数组取值；对象形态时按 list/items/data 递归取内层数组
 * （对应 Go anyList：Go 版本会递归，之前这里只取一层会漏数据）
 */
internal fun jsonList(v: JsonElement?): List<JsonElement> = when (v) {
    is JsonArray -> v.toList()
    is JsonObject ->
        listOf("list", "items", "data")
            .map { jsonList(v[it]) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    else -> emptyList()
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
