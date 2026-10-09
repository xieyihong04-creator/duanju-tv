package com.duanju.tv.data.remote

import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.PlayGroup
import com.duanju.tv.data.model.Resolved
import com.duanju.tv.data.model.SearchPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

        // 搜索页字段和分类页不同名：结果在 searchList，总数在 totalCount（无 pagination）
        val items = jsonList(pageData["searchList"] ?: pageData["recommendList"])
        val dramas = items.mapNotNull { hongguoDramaFromItem(it as? JsonObject ?: return@mapNotNull null) }
            .distinctBy { it.backendId ?: it.id }
        if (items.isNotEmpty() && dramas.isEmpty()) throw IOException("红果搜索结果中没有可识别的剧集")
        val total = pageData.intOrNull("totalCount") ?: items.size

        SearchPage(
            items = dramas,
            page = page,
            pageCount = if (total > dramas.size) page + 1 else page,
            total = maxOf(total, dramas.size),
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

        // 剧壳字段与分类页、App 通道同构，共用一个映射，避免三处各写一遍字段表
        val base = hongguoDramaFromItem(detail, categoryFallback = "短剧")
            ?: throw IOException("红果详情缺少 series_id")

        // vid_list 两种形态都见过：纯 vid 字符串数组，或带 vid/vid_index 的对象数组
        val episodes = parseHongguoVidList(detail["vid_list"], base.backendId ?: seriesId)
        if (episodes.isEmpty()) throw IOException("红果详情没有返回剧集 ID")

        base.copy(
            playGroups = listOf(PlayGroup(HONGGUO_GROUP_NAME, episodes)),
            remarks = base.remarks.ifBlank {
                val count = detail.strOrNull("episode_cnt")
                if (count != null) "共${count}集" else ""
            },
            updated = "",
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

        // 与 App 通道共用取址逻辑：网页给的是 base64 形态的 main_url，且要过滤加密流
        val clear = extractClearAddresses(info)
        if (clear.isEmpty()) {
            throw IOException("红果该集未提供公开播放地址，可能需要登录或 App 授权")
        }

        Resolved.Direct(
            clear[0],
            mapOf(
                "User-Agent" to UA,
                "Referer" to referer,
                "Origin" to baseUrl,
            ),
        )
    }

    /**
     * video_player_info 结构比 App 通道扁平（直接是 main_url/duration），
     * 但地址同样是 base64，也可能带 encrypt_info。走一遍明流筛选。
     */
    private fun extractClearAddresses(info: JsonObject): List<String> {
        if (info.getJsonArray("video_list") != null || info.getJsonObject("video_model") != null) {
            val model = parseVideoModel(info) ?: info
            val url = selectClearStream(videoModelVariants(model))
            return listOfNotNull(url)
        }
        val enc = info.getJsonObject("encrypt_info")
        if (enc != null && (enc.strOrNull("spade_a")?.isNotBlank() == true ||
                enc.strOrNull("encryption_method") == "cenc-aes-ctr")) {
            return emptyList()
        }
        return extractMediaAddresses(info)
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

    private suspend fun fetchCategoryPage(route: String, category: String, page: Int): Pair<List<Drama>, Int> {
        val url = "$baseUrl/category/$route?page=$page"
        val body = fetchHtml(url)
        val data = parseRouterData(body)
        val pageData = routerLoaderMap(data, "category_page", "category_$")

        if (pageData == null || pageData.booleanOrNull("isSuccess") == false) {
            throw IOException("红果分类数据不可用")
        }

        val items = jsonList(pageData["recommendList"])
        // 与 App 通道共用一套字段映射，网页条目多出 video_data 包裹层也能识别
        val dramas = items.mapNotNull { hongguoDramaFromItem(it as? JsonObject ?: return@mapNotNull null, category) }
        val pages = pageData["pagination"]?.let { (it as JsonObject).intOrNull("totalPages") } ?: 1

        return dramas to pages
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

    private fun JsonObject.isNotEmpty(): Boolean = keys.isNotEmpty()
}