package com.duanju.tv.data.remote

import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Episode
import com.duanju.tv.data.model.PlayGroup
import com.duanju.tv.data.model.Resolved
import com.duanju.tv.data.model.SearchPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * 红果短剧网页通道客户端
 *
 * 从 window._ROUTER_DATA 正则抽取 JSON，解析：
 * - category_page.recommendList -> 列表页
 * - detail_page.seriesDetail/vid_list -> 详情页
 * - player_page.video_player_info -> 播放页
 *
 * Episode.rawUrl 编为 `hongguo://series/vid`，明流优先，Referer=https://hongguoduanju.com/
 * 失败抛 IOException 让上层降级
 */
class HongguoClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val routerDataPattern = Pattern.compile("(?s)(?:window\\.)?_ROUTER_DATA\\s*=\\s*")
    private val baseUrl = "https://hongguoduanju.com"
    private val referer = "$baseUrl/"

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"

        /** 红果分类路由 */
        val CATEGORY_ROUTES = listOf(
            "real-drama" to "真人剧",
            "comic-drama" to "漫剧",
            "ai-drama" to "AI剧",
            "comic" to "动漫",
        )
    }

    /** 列表页：GET /category/{route}?page=N */
    suspend fun page(page: Int): SearchPage = withContext(Dispatchers.IO) {
        val allDramas = mutableListOf<Drama>()
        var totalPages = 1
        var totalItems = 0

        for ((route, category) in CATEGORY_ROUTES) {
            try {
                val (dramas, pages) = fetchCategoryPage(route, category, page)
                allDramas.addAll(dramas)
                totalPages = maxOf(totalPages, pages)
                totalItems += dramas.size
            } catch (e: Exception) {
                // 单个分类失败不影响整体，记录错误继续
            }
        }

        // 去重：按 backendId (series_id) 去重
        val uniqueDramas = allDramas.distinctBy { it.backendId ?: it.id.toString() }

        SearchPage(
            items = uniqueDramas,
            page = page,
            pageCount = totalPages,
            total = totalItems,
        )
    }

    /** 搜索：GET /search/{keyword}?page=N */
    suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.IO) {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val url = "$baseUrl/search/$encodedKeyword?page=$page"
        val body = fetchHtml(url)
        val data = parseRouterData(body)
        val pageData = routerLoaderMap(data, "search_page", "search_")

        if (pageData == null || pageData.booleanOrNull("isSuccess") == false) {
            throw IOException("红果搜索数据不可用")
        }

        val items = anyList(pageData["recommendList"])
        val dramas = items.mapNotNull { parseDramaFromCategoryItem(it) }
        val pages = pageData["pagination"]?.let { (it as JsonObject).intOrNull("totalPages") } ?: 1

        SearchPage(
            items = dramas,
            page = page,
            pageCount = pages,
            total = items.size,
        )
    }

    /** 详情页：GET /detail?series_id= */
    suspend fun detail(seriesId: String): Drama = withContext(Dispatchers.IO) {
        val url = "$baseUrl/detail?series_id=$seriesId"
        val body = fetchHtml(url)
        val data = parseRouterData(body)
        val pageData = routerLoaderMap(data, "detail_page", "detail_")

        val detail = pageData?.getJsonObject("seriesDetail")
            ?: throw IOException("红果详情数据为空")

        val seriesIdStr = detail.strOrNull("series_id_str") ?: detail.strOrNull("series_id") ?: seriesId
        val title = detail.strOrNull("series_title") ?: detail.strOrNull("series_name") ?: detail.strOrNull("name") ?: seriesIdStr
        val cover = detail.strOrNull("series_cover") ?: detail.strOrNull("cover") ?: ""
        val intro = detail.strOrNull("series_intro") ?: detail.strOrNull("video_desc") ?: ""
        val count = detail.strOrNull("episode_cnt") ?: ""
        val remark = detail.strOrNull("episode_right_text") ?: (if (count.isNotBlank()) "共${count}集" else "")
        val score = detail.strOrNull("score") ?: ""
        val playCount = detail.strOrNull("series_play_cnt") ?: detail.strOrNull("play_cnt") ?: ""

        val vidList = anyList(detail["vid_list"])
        val episodes = vidList.mapIndexed { idx, v ->
            val vid = (v as? JsonPrimitive)?.content?.trim() ?: v.toString().trim()
            if (vid.isEmpty() || vid == "<nil>" || !vid.matches(Regex("^\\d+$"))) return@mapIndexed null
            Episode(idx + 1, "第${idx + 1}集", "hongguo://$seriesIdStr/$vid")
        }.filterNotNull()

        val playGroup = PlayGroup("红果官方", episodes)

        Drama(
            id = seriesIdStr.toIntOrNull() ?: seriesIdStr.hashCode(),
            sourceId = "hongguo",
            name = title,
            pic = cover,
            type = detail.strOrNull("category_name") ?: detail.strOrNull("categoryName") ?: detail.strOrNull("category") ?: "短剧",
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
            backendId = seriesIdStr,
        )
    }

    /** 播放页：GET /player/{series}/{vid} -> 解析出直链 */
    suspend fun resolve(seriesId: String, vid: String): Resolved = withContext(Dispatchers.IO) {
        val pageUrl = "$baseUrl/player/$seriesId/$vid"
        val body = fetchHtml(pageUrl)
        val data = parseRouterData(body)
        val pageData = routerLoaderMap(data, "player_", "player_page")

        if (pageData == null) {
            throw IOException("红果播放页数据为空")
        }

        // 校验 vid/series_id 一致性
        val returnedVid = pageData.strOrNull("vid")
        val returnedSeriesId = pageData.strOrNull("series_id")
        if (returnedVid != vid || returnedSeriesId != seriesId) {
            throw IOException("红果未返回所请求的剧集，可能仅允许网页试看")
        }

        val info = pageData.getJsonObject("video_player_info")
            ?: throw IOException("红果该集未提供公开播放地址")

        val addresses = extractMediaAddresses(info)
        if (addresses.isEmpty()) {
            throw IOException("红果该集未提供公开播放地址，可能需要登录或 App 授权")
        }

        val duration = info.doubleOrNull("duration") ?: 0.0
        val mediaUrl = addresses[0]
        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to referer,
            "Origin" to baseUrl,
        )

        Resolved.Direct(mediaUrl, headers)
    }

    // ========== 内部解析方法 ==========

    private suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", referer)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("空响应")
        }
    }

    private fun parseRouterData(raw: String): JsonObject? {
        val matcher = routerDataPattern.matcher(raw)
        if (!matcher.find()) return null
        val start = matcher.end()
        // 找到第一个 { 开始
        var braceStart = raw.indexOf('{', start)
        if (braceStart == -1) return null
        // 大括号配平计数（处理字符串/转义）
        var depth = 0
        var inString = false
        var escape = false
        var end = -1
        for (i in braceStart until raw.length) {
            val c = raw[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                c == '{' && !inString -> depth++
                c == '}' && !inString -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end == -1) return null
        val jsonStr = raw.substring(braceStart, end + 1)
        return try {
            json.parseToJsonElement(jsonStr) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun routerLoaderMap(data: JsonObject?, vararg names: String): JsonObject? {
        val loader = data?.getJsonObject("loaderData") ?: return null
        for (name in names) {
            val page = loader.getJsonObject(name)
            if (page != null && page.isNotEmpty()) return page
        }
        // 模糊匹配：去掉 $ 后缀后做前缀匹配，如 category_$ -> category_page, player_ -> player_page-xxx
        for (key in loader.keys) {
            for (name in names) {
                val prefix = name.removeSuffix("$")
                if (prefix.isNotEmpty() && key.startsWith(prefix)) {
                    val page = loader.getJsonObject(key)
                    if (page != null && page.isNotEmpty()) return page
                }
            }
        }
        // 兜底：遍历所有 loader value，找包含 video_player_info 的那个
        for (value in loader.values) {
            val obj = value as? JsonObject ?: continue
            if (obj.containsKey("video_player_info") && obj.isNotEmpty()) return obj
        }
        return null
    }

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

    private suspend fun fetchCategoryPage(route: String, category: String, page: Int): Pair<List<Drama>, Int> {
        val url = "$baseUrl/category/$route?page=$page"
        val body = fetchHtml(url)
        val data = parseRouterData(body)
        val pageData = routerLoaderMap(data, "category_page", "category_$")

        if (pageData == null || pageData.booleanOrNull("isSuccess") == false) {
            throw IOException("红果分类数据不可用")
        }

        val items = anyList(pageData["recommendList"])
        val dramas = items.mapNotNull { parseDramaFromCategoryItem(it, category) }
        val pages = pageData["pagination"]?.let { (it as JsonObject).intOrNull("totalPages") } ?: 1

        return dramas to pages
    }

    private fun parseDramaFromCategoryItem(item: JsonElement, category: String = ""): Drama? {
        val m = item as? JsonObject ?: return null
        val vd = m.getJsonObject("video_data") ?: m

        val seriesId = vd.strOrNull("series_id_str") ?: vd.strOrNull("series_id")
            ?: m.strOrNull("series_id_str") ?: m.strOrNull("series_id")
            ?: vd.strOrNull("keyword") ?: m.strOrNull("keyword")
            ?: return null

        if (!seriesId.matches(Regex("^\\d+$"))) return null

        val title = vd.strOrNull("series_title") ?: vd.strOrNull("series_name") ?: vd.strOrNull("title")
            ?: m.strOrNull("series_name") ?: m.strOrNull("name") ?: seriesId
        val cover = vd.strOrNull("series_cover") ?: vd.strOrNull("cover")
            ?: m.strOrNull("series_cover") ?: ""
        val intro = vd.strOrNull("series_intro") ?: vd.strOrNull("video_desc")
            ?: m.strOrNull("series_intro") ?: ""
        val count = vd.strOrNull("episode_cnt") ?: m.strOrNull("episode_cnt") ?: ""
        val remark = vd.strOrNull("episode_right_text") ?: m.strOrNull("episode_right_text")
            ?: (if (count.isNotBlank()) "共${count}集" else "")
        val score = vd.strOrNull("score") ?: ""
        val playCount = vd.strOrNull("series_play_cnt") ?: vd.strOrNull("play_cnt") ?: ""

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

        val genre = vd.strOrNull("category_name") ?: vd.strOrNull("categoryName") ?: vd.strOrNull("category") ?: category

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

    private fun extractMediaAddresses(info: JsonObject): List<String> {
        val addresses = mutableSetOf<String>()
        val candidates = setOf(
            "main_url", "url", "play_url", "video_url", "m3u8_url",
            "hls_url", "dash_url", "mp4_url"
        )

        fun collectUrls(element: JsonElement?) {
            when (element) {
                is JsonObject -> {
                    for (key in element.keys) {
                        if (key in candidates) {
                            val v = element.strOrNull(key)
                            if (v?.isNotBlank() == true && v.startsWith("http")) addresses.add(v)
                        }
                        collectUrls(element[key])
                    }
                }
                is JsonArray -> {
                    for (item in element) collectUrls(item)
                }
                else -> {}
            }
        }

        collectUrls(info)
        return addresses.toList()
    }

    private fun JsonObject.strOrNull(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        val content = p.content
        return if (content == "null") null else content.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val p = this[key] as? JsonPrimitive ?: return null
        val content = p.content.lowercase()
        return when (content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val p = this[key] as? JsonPrimitive ?: return null
        return runCatching { p.int }.getOrNull()
            ?: p.content.toIntOrNull()
    }

    private fun JsonObject.doubleOrNull(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        return runCatching { p.content.toDouble() }.getOrNull()
    }

    private fun JsonObject.getJsonObject(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.isNotEmpty(): Boolean = keys.isNotEmpty()

    private fun JsonArray.toList(): List<JsonElement> = (0 until size).map { get(it) }
}