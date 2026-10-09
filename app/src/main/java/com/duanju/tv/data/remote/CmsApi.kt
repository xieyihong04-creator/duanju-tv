package com.duanju.tv.data.remote

import com.duanju.tv.data.model.Category
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Episode
import com.duanju.tv.data.model.PlayGroup
import com.duanju.tv.data.model.Resolved
import com.duanju.tv.data.model.SearchPage
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 数据源类型：区分 macCMS 风格与红果网页通道
 */
enum class SourceKind {
    MAC_CMS,
    HONGGUO
}

/**
 * 一个 CMS(macCMS 风格) 聚合站点，或红果这类需要专用客户端的源。
 *
 * 短剧在各 CMS 站点都是一个独立分类（如 ffzy 的 type_id=36、bfzy 的 58），
 * 这里通过 ac=list 自动发现分类，而不是写死 id，避免站点调整后失效。
 */
data class SourceSpec(
    val id: String,
    val name: String,
    val listApi: String,
    /** 可作为该源短剧主分类的候选名称关键字 */
    val dramaKeywords: List<String> = listOf("短剧"),
    val enabled: Boolean = true,
    /** 数据源类型，决定解析逻辑 */
    val kind: SourceKind = SourceKind.MAC_CMS,
) {
    val isAggregate: Boolean get() = id == AGGREGATE_ID

    /** 只有 macCMS 站点有「分类 id」概念；红果走 App/网页接口，ac=list 永远为空 */
    val hasMacCategories: Boolean get() = kind == SourceKind.MAC_CMS && listApi.isNotBlank()

    companion object {
        const val AGGREGATE_ID = "aggregate"
    }
}

object DefaultSources {
    val ALL = listOf(
        SourceSpec(
            id = "ffzy",
            name = "非凡·短剧",
            listApi = "https://cj.ffzyapi.com/api.php/provide/vod/",
        ),
        SourceSpec(
            id = "bfzy",
            name = "暴风·短剧",
            listApi = "https://bfzyapi.com/api.php/provide/vod/",
        ),
        SourceSpec(
            id = "hongguo",
            name = "红果·短剧",
            listApi = "https://hongguoduanju.com",
            kind = SourceKind.HONGGUO,
        ),
    )

    /** 聚合虚拟源：listApi 留空，仓库内部按 ALL 逐源请求 */
    val AGGREGATE = SourceSpec(id = SourceSpec.AGGREGATE_ID, name = "全网聚合", listApi = "")

    fun byId(id: String): SourceSpec? = ALL.firstOrNull { it.id == id }

    /** 设置里的 sourceId -> SourceSpec，未知 id 回落到聚合 */
    fun spec(id: String): SourceSpec = when (id) {
        SourceSpec.AGGREGATE_ID -> AGGREGATE
        else -> byId(id) ?: AGGREGATE
    }

    /** 供设置页展示的可选项 */
    val CHOICES: List<SourceSpec> = listOf(AGGREGATE) + ALL
}

/**
 * 构建 OkHttpClient 实例，配置超时参数
 */
internal fun buildOkHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

class CmsClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun getJson(url: String): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("空响应")
            json.parseToJsonElement(body).jsonObject
        }
    }

    /** ac=list 返回 class 分类表，用它发现短剧分类 id */
    suspend fun categories(spec: SourceSpec): List<Category> = try {
        val obj = getJson(buildString { append(spec.listApi); append("?ac=list") })
        obj.arrOrNull("class").orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val tid = o.intOrNull("type_id") ?: return@mapNotNull null
            Category(tid, o.str("type_name"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 解析出的短剧分类 id（可能有多个，如「短剧大全」「新短剧」） */
    suspend fun dramaCategoryIds(spec: SourceSpec): List<Int> {
        val cats = categories(spec)
        val hit = cats.filter { c -> spec.dramaKeywords.any { k -> c.name.contains(k, ignoreCase = true) } }
            .map { it.id }
        return hit.distinct()
    }

    suspend fun detail(spec: SourceSpec, typeId: Int, page: Int): SearchPage {
        val url = buildString {
            append(spec.listApi); append("?ac=detail&t="); append(typeId); append("&pg="); append(page)
        }
        return parsePage(getJson(url), spec, page)
    }

    suspend fun search(spec: SourceSpec, keyword: String, page: Int): SearchPage {
        val url = buildString {
            append(spec.listApi)
            append("?ac=detail&wd=")
            append(urlEncode(keyword))
            append("&pg=")
            append(page)
        }
        return parsePage(getJson(url), spec, page)
    }

    suspend fun latest(spec: SourceSpec, page: Int): SearchPage {
        val url = buildString { append(spec.listApi); append("?ac=detail&pg="); append(page) }
        return parsePage(getJson(url), spec, page)
    }

    /** 只取列表：轻量，用于首页信息流（vod 列表项字段比 detail 少） */
    suspend fun listOnly(spec: SourceSpec, typeId: Int, page: Int): List<Drama> {
        val url = buildString {
            append(spec.listApi); append("?ac=videolist&t="); append(typeId); append("&pg="); append(page)
        }
        return try {
            parsePage(getJson(url), spec, page).items
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePage(obj: JsonObject, spec: SourceSpec, page: Int): SearchPage {
        val list = obj.arrOrNull("list").orEmpty()
        val items = list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            parseDrama(o, spec.id)
        }
        return SearchPage(
            items = items,
            page = obj.intOrNull("page") ?: page,
            pageCount = obj.intOrNull("pagecount") ?: 1,
            total = obj.intOrNull("total") ?: items.size,
        )
    }

    /**
     * 多线路解析：`vod_play_from` 用 `$$$` 分隔线路，`vod_play_url` 对应每段的剧集，
     * 剧集之间用 `#`，标题与地址用 `$`。
     */
    internal fun parseDrama(o: JsonObject, sourceId: String): Drama? {
        val id = o.intOrNull("vod_id") ?: return null
        val name = o.str("vod_name").trim()
        if (name.isEmpty()) return null

        val froms = o.str("vod_play_from").split("$$$").filter { it.isNotBlank() }
        val urls = o.str("vod_play_url").split("$$$")

        val groups = ArrayList<PlayGroup>()
        for (i in urls.indices) {
            val eps = parseEpisodes(urls[i])
            if (eps.isEmpty()) continue
            val label = froms.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "线路${groups.size + 1}"
            groups += PlayGroup(label, eps)
        }
        // 兼容只给出单一 url 且 from 为空的源
        if (groups.isEmpty()) {
            parseEpisodes(o.str("vod_down_url")).takeIf { it.isNotEmpty() }
                ?.let { groups += PlayGroup("下载", it) }
        }
        if (groups.isEmpty()) return null

        return Drama(
            id = id,
            sourceId = sourceId,
            name = name,
            pic = o.str("vod_pic").trim(),
            type = o.str("type_name").trim(),
            typeId = o.intOrNull("type_id") ?: 0,
            remarks = o.str("vod_remarks").trim(),
            year = o.str("vod_year").trim(),
            area = o.str("vod_area").trim(),
            director = o.str("vod_director").trim(),
            actors = o.str("vod_actor").trim(),
            blurb = stripHtml(o.str("vod_blurb")),
            detail = stripHtml(o.str("vod_content")),
            tag = o.str("vod_class").trim(),
            score = formatScore(o.str("vod_score")),
            updated = o.str("vod_time").trim(),
            playGroups = groups.sortedByDescending { it.episodes.size },
        )
    }

    internal fun parseEpisodes(raw: String): List<Episode> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Episode>()
        val seen = HashSet<String>()
        var idx = 0
        for (piece in raw.split("#")) {
            val seg = piece.trim()
            if (seg.isEmpty()) continue
            val parts = seg.split("$")
            val title = parts.getOrElse(0) { "" }.trim()
            val url = parts.getOrElse(1) { "" }.trim()
            if (url.isEmpty()) continue
            val cleanUrl = url.removePrefix("[]").trim()
            if (!cleanUrl.startsWith("http")) continue
            if (!seen.add(cleanUrl)) continue
            idx++
            out += Episode(idx, title.ifEmpty { "第${idx}集" }, cleanUrl)
        }
        return out.sortedWith(episodeComparator())
    }

    /** 分集标题按数值排序，避免「第10集」排在「第2集」前面 */
    private fun episodeComparator(): Comparator<Episode> = Comparator { a, b ->
        val na = episodeNumber(a.title); val nb = episodeNumber(b.title)
        when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> a.index.compareTo(b.index)
        }
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"

        fun episodeNumber(title: String): Int? {
            val m = Regex("(\\d+)").find(title) ?: return null
            return m.value.toIntOrNull()
        }

        fun formatScore(v: String): String {
            val d = v.toDoubleOrNull() ?: return ""
            if (d <= 0 || d.isNaN()) return ""
            return String.format("%.1f", d)
        }

        fun stripHtml(v: String): String =
            v.replace(Regex("<[^>]*>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace(Regex("\\s{2,}"), " ")
                .trim()

        fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8")

        private fun JsonObject.str(key: String): String =
            (this[key] as? JsonPrimitive)?.contentOrNullSafe() ?: ""

        private fun JsonObject.intOrNull(key: String): Int? {
            val p = this[key] as? JsonPrimitive ?: return null
            p.contentOrNullSafe()?.let { c -> c.toIntOrNull()?.let { return it } }
            return runCatching { p.int }.getOrNull()
        }

        private fun JsonObject.arrOrNull(key: String): JsonArray? = this[key] as? JsonArray

        private fun JsonPrimitive.contentOrNullSafe(): String? {
            if (isString) return content
            val t = content
            return if (t == "null") null else t
        }

        @Suppress("unused")
        private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
    }
}

/**
 * 播放器地址解析。
 *
 * 部分 CMS 给的 `vod_play_url` 是 `.../share/<id>` 这类网页分享地址，
 * 页面内联 `const url = "/20260918/49391_69162221/index.m3u8?sign=..."`，
 * 需要取其绝对化后的结果（逻辑对应 short-drama-downloader 的 share 页解析）。
 */
class SharePageResolver(private val http: OkHttpClient) {

    private val constUrl = Regex("""url\s*=\s*"([^"]+)"""")

    suspend fun resolve(episode: Episode): Resolved = withContext(Dispatchers.IO) {
        val raw = episode.rawUrl
        if (raw.contains(".m3u8") || raw.contains(".mp4")) {
            return@withContext Resolved.Direct(raw, mapOf("User-Agent" to CmsClient.UA, "Referer" to refererOf(raw)))
        }
        try {
            val req = Request.Builder().url(raw)
                .header("User-Agent", CmsClient.UA)
                .header("Referer", raw.substringBeforeLast("/"))
                .build()
            val html = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.string() ?: throw IOException("空页面")
            }
            val rel = constUrl.find(html)?.groupValues?.get(1)
                ?: throw IOException("未在分享页中找到播放地址")
            val abs = absolutize(raw, rel)
            Resolved.Direct(
                abs,
                mapOf(
                    "User-Agent" to CmsClient.UA,
                    "Referer" to raw.substringBeforeLast("/"),
                    "Origin" to raw.substringBeforeLast("/"),
                ),
            )
        } catch (e: Exception) {
            Resolved.Failed(e.message ?: "解析失败")
        }
    }

    private fun refererOf(url: String): String = url.substringBeforeLast("/")

    internal fun absolutize(pageUrl: String, ref: String): String {
        val u = java.net.URI(pageUrl)
        val base = "${u.scheme}://${u.host}" + if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""
        return when {
            ref.startsWith("http") -> ref
            ref.startsWith("//") -> "${u.scheme}:$ref"
            ref.startsWith("/") -> base + ref
            else -> pageUrl.substringBeforeLast("/") + "/" + ref
        }
    }
}

/** 多源聚合仓库 */
class DramaRepository(
    private val cms: CmsClient,
    private val resolver: SharePageResolver,
    private val hongguo: HongguoClient,
    private val hongguoApp: HongguoAppClient,
) {

    private companion object {
        const val PER_PAGE = 50
        const val PAGES_TO_SCAN = 6

        /** App 通道详情/取流判死后，多久之内不再尝试 */
        const val APP_MEDIA_COOLDOWN_MS = 30 * 60 * 1000L
    }

    /** 源 id -> 短剧分类 id 列表，进程内缓存 */
    private val catCache = java.util.concurrent.ConcurrentHashMap<String, List<Int>>()

    /**
     * 红果 landpage 是游标制分页，接口不认页码。
     * 这里按源记住「下一次要用的游标」，调用方仍然是按 page=1,2,3… 顺序取页。
     * page=1 视为重新开始（首页刷新、切源都回到第一页）。
     */
    private val hongguoCursor = java.util.concurrent.ConcurrentHashMap<String, LandpageCursor?>()

    /** 红果剧集详情缓存（series_id -> Drama 含分集），避免每次进详情都打两次接口 */
    private val hongguoDetailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Drama, Long>>()

    /**
     * 红果 App 通道的详情/取流冷却截止时间。
     *
     * landpage 能正常返回，但 video_detail / video_model 对未注册过的 device_id
     * 会给 200 空 body 或业务码 110001（实测）。这类失败重试无用，
     * 判死一段时间，详情页和播放直接走网页通道，不再每次白等三轮退避。
     */
    @Volatile
    private var hongguoAppMediaCooldownUntil: Long = 0L

    /**
     * 最近一次红果列表实际走的通道（app / web）。
     * 「翻页静默回退网页」是这里最难发现的问题，留个口子给测试和日志确认。
     */
    internal var lastHongguoListChannel: String = "none"
        private set

    suspend fun dramaCategoryIds(spec: SourceSpec): List<Int> {
        // 红果没有 macCMS 的 ac=list，硬调只会拿到空表并把它缓存住
        if (!spec.hasMacCategories) return emptyList()
        return catCache.getOrPut(spec.id) {
            runCatching { cms.dramaCategoryIds(spec) }.getOrDefault(emptyList())
        }
    }

    suspend fun page(spec: SourceSpec, page: Int): SearchPage = withContext(Dispatchers.IO) {
        if (spec.isAggregate) aggregatePage(page) else singlePage(spec, page)
    }

    private suspend fun singlePage(spec: SourceSpec, page: Int): SearchPage {
        return when (spec.kind) {
            SourceKind.HONGGUO -> hongguoPage(spec, page)
            SourceKind.MAC_CMS -> {
                val cats = dramaCategoryIds(spec)
                if (cats.isEmpty()) cms.latest(spec, page)
                else {
                    val results = coroutineScope {
                        cats.take(2).map { c -> async { runCatching { cms.detail(spec, c, page) }.getOrNull() } }
                            .mapNotNull { it.await() }
                    }
                    if (results.isEmpty()) cms.latest(spec, page)
                    else {
                        val first = results.first()
                        val items = results.flatMap { it.items }.distinctBy { it.sourceId to it.id }
                        SearchPage(items, page, first.pageCount, results.sumOf { it.total })
                    }
                }
            }
        }
    }

    /**
     * 红果列表：App 通道优先，失败/到底回退网页通道。
     *
     * 网页通道是分页号语义、App 通道是游标语义，两者混在一个源上：
     * 只要还有可用游标就走 App，没有就走网页的分页号。
     */
    private suspend fun hongguoPage(spec: SourceSpec, page: Int): SearchPage {
        if (page <= 1) hongguoCursor.remove(spec.id)
        val cursor = if (page <= 1) null else hongguoCursor[spec.id]
        if (page > 1 && cursor == null) {
            // App 通道不可用或已经到底，交给网页通道按分页号继续
            lastHongguoListChannel = "web"
            return hongguo.page(page)
        }

        try {
            val (dramas, next) = hongguoApp.landpage(cursor)
            if (next != null) hongguoCursor[spec.id] = next else hongguoCursor.remove(spec.id)
            if (dramas.isNotEmpty()) {
                lastHongguoListChannel = "app"
                return SearchPage(
                    items = dramas,
                    page = page,
                    pageCount = if (next != null) page + 1 else page,
                    total = page * dramas.size,
                )
            }
            Log.w(TAG, "红果 App landpage 第 $page 页为空，回退网页版")
        } catch (e: Exception) {
            Log.w(TAG, "红果 App landpage 失败(${e.message})，回退网页版 page=$page")
            hongguoCursor.remove(spec.id)
        }
        val web = hongguo.page(page)
        lastHongguoListChannel = "web"
        return web
    }

    /**
     * 列表条目只有元信息、没有分集（红果 landpage / 网页 category 都只给剧壳）。
     * 进详情时补齐：App 通道优先，失败回退网页通道。
     *
     * App 通道只有 landpage 是公开可用的；video_detail / video_model 在没有注册过的
     * device_id 上固定返回 200 空 body 或业务码 110001（实测）。这种失败重试不会变好，
     * 所以用 [hongguoAppMediaCooldownUntil] 冷却一段时间，直接走网页通道。
     */
    suspend fun withEpisodes(drama: Drama): Drama {
        if (drama.playGroups.any { it.episodes.isNotEmpty() }) return drama
        val seriesId = drama.backendId ?: return drama
        if (drama.sourceId != "hongguo") return drama

        cachedDetail(seriesId)?.let { return mergeDetail(drama, it) }

        val app = if (appMediaCoolingDown()) {
            Result.failure(IOException("红果 App 详情通道冷却中"))
        } else {
            runCatching { hongguoApp.videoDetail(seriesId) }.onSuccess { hongguoAppMediaCooldownUntil = 0L }
        }
        val fetched = app.getOrElse { e ->
            if (e is PermanentAppError) openAppMediaCooldown()
            Log.w(TAG, "红果 App 详情失败(${e.message})，回退网页版 series=$seriesId")
            runCatching { hongguo.detail(seriesId) }.getOrNull()
        }
            ?: return drama
        hongguoDetailCache[seriesId] = fetched to System.currentTimeMillis() + DETAIL_TTL_MS
        return mergeDetail(drama, fetched)
    }

    private fun cachedDetail(seriesId: String): Drama? {
        val (drama, expiresAt) = hongguoDetailCache[seriesId] ?: return null
        if (System.currentTimeMillis() > expiresAt) {
            hongguoDetailCache.remove(seriesId)
            return null
        }
        return drama
    }

    private fun appMediaCoolingDown(): Boolean = System.currentTimeMillis() < hongguoAppMediaCooldownUntil

    private fun openAppMediaCooldown() {
        hongguoAppMediaCooldownUntil = System.currentTimeMillis() + APP_MEDIA_COOLDOWN_MS
    }

    /** 列表项的元信息更丰富（tags/演员等），补进来的只有分集，所以保留原条目、只换 playGroups */
    private fun mergeDetail(listItem: Drama, detail: Drama): Drama =
        if (detail.playGroups.any { it.episodes.isNotEmpty() })
            listItem.copy(playGroups = detail.playGroups, remarks = listItem.remarks.ifBlank { detail.remarks })
        else listItem

    private suspend fun aggregatePage(page: Int): SearchPage = coroutineScope {
        val jobs = DefaultSources.ALL.filter { it.enabled }.map { spec ->
            async { runCatching { singlePage(spec, page) }.getOrNull() }
        }
        val pages = jobs.mapNotNull { it.await() }
        SearchPage(
            items = interleave(pages.flatMap { it.items }).distinctBy { it.key },
            page = page,
            pageCount = if (pages.isEmpty()) 1 else pages.maxOf { it.pageCount },
            total = pages.sumOf { it.total },
        )
    }

    /** 多源结果交错，避免整屏都是同一个站点 */
    private fun interleave(lists: List<Drama>): List<Drama> {
        val buckets = lists.groupBy { it.sourceId }
        val order = buckets.keys.toList()
        val out = ArrayList<Drama>()
        var i = 0
        while (out.size < lists.size) {
            var added = false
            for (k in order) {
                buckets.getValue(k).getOrNull(i)?.let { out += it; added = true }
            }
            if (!added) break
            i++
        }
        return out
    }

    suspend fun search(spec: SourceSpec, keyword: String, page: Int): SearchPage =
        withContext(Dispatchers.IO) {
            if (!spec.isAggregate) {
                return@withContext when (spec.kind) {
                    SourceKind.HONGGUO -> {
                        // App 通道没有搜索接口，走网页版
                        runCatching { hongguo.search(keyword, page) }.getOrElse {
                            Log.w(TAG, "红果网页搜索失败(${it.message})")
                            SearchPage(emptyList(), page, 1, 0)
                        }
                    }
                    SourceKind.MAC_CMS -> {
                        val cats = dramaCategoryIds(spec)
                        val remote = runCatching { cms.search(spec, keyword, page) }.getOrNull()
                        if (cats.isNotEmpty() && (remote == null || remote.items.isEmpty())) {
                            // 搜索接口对短剧分类覆盖不全时，用分类列表做包含匹配兜底
                            fallbackSearch(spec, cats, keyword, page)
                        } else {
                            remote ?: SearchPage(emptyList(), page, 1, 0)
                        }
                    }
                }
            }
            val jobs = DefaultSources.ALL.filter { it.enabled }.map { s ->
                async { runCatching { search(s, keyword, page) }.getOrNull() }
            }
            val pages = jobs.mapNotNull { it.await() }
            SearchPage(
                pages.flatMap { it.items }.distinctBy { it.key },
                page,
                if (pages.isEmpty()) 1 else pages.maxOf { it.pageCount },
                pages.sumOf { it.total },
            )
        }

    /**
     * CMS 的 wd 检索对短剧分类覆盖不全时，扫描短剧分类首页列表做包含匹配兜底。
     */
    private suspend fun fallbackSearch(
        spec: SourceSpec,
        cats: List<Int>,
        keyword: String,
        page: Int,
    ): SearchPage {
        val name = keyword.trim()
        if (name.isEmpty()) return SearchPage(emptyList(), page, 1, 0)
        val scanned = coroutineScope {
            cats.take(2).flatMap { c ->
                (1..PAGES_TO_SCAN).map { p ->
                    async { runCatching { cms.detail(spec, c, p) }.getOrNull() }
                }
            }.mapNotNull { it.await() }
        }
        val matched = scanned
            .flatMap { it.items }
            .distinctBy { it.sourceId to it.id }
            .filter { it.name.contains(name, ignoreCase = true) }
            .sortedByDescending { it.id }
        val from = (page - 1) * PER_PAGE
        return SearchPage(
            items = matched.drop(from).take(PER_PAGE),
            page = page,
            pageCount = ((matched.size + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1),
            total = matched.size,
        )
    }

    suspend fun resolveEpisode(episode: Episode): Resolved = withContext(Dispatchers.IO) {
        // hongguo://series/vid 格式：App 通道优先，失败回退网页版
        if (episode.rawUrl.startsWith("hongguo://")) {
            val parts = episode.rawUrl.removePrefix("hongguo://").split("/")
            if (parts.size == 2) {
                val seriesId = parts[0]
                val vid = parts[1]
                // App 通道的失败形态不只 IOException（签名/解析都可能抛别的），
                // 只 catch IOException 会把异常直接抛给播放器协程
                val app = if (appMediaCoolingDown()) {
                    Result.failure(IOException("红果 App 取流通道冷却中"))
                } else {
                    runCatching { hongguoApp.videoModel(seriesId, vid) }
                        .onSuccess { hongguoAppMediaCooldownUntil = 0L }
                }
                if (app.isSuccess) return@withContext app.getOrThrow()
                if (app.exceptionOrNull() is PermanentAppError) openAppMediaCooldown()
                Log.w(TAG, "红果 App 取流失败(${app.exceptionOrNull()?.message})，回退网页版 $seriesId/$vid")
                return@withContext runCatching { hongguo.resolve(seriesId, vid) }
                    .getOrElse { Resolved.Failed(it.message ?: "红果解析失败") }
            }
            return@withContext Resolved.Failed("红果剧集地址格式异常: ${episode.rawUrl}")
        }
        resolver.resolve(episode)
    }

    /** 依次尝试多条线路，返回第一个可用的剧集地址 */
    suspend fun resolveWithFallback(drama: Drama, episodeIndex: Int): Pair<PlayGroup?, Resolved> {
        val sorted = drama.playGroups.sortedByDescending { it.episodes.size }
        var lastMessage = "无可用线路"
        for (g in sorted) {
            val ep = g.episodes.getOrNull(episodeIndex - 1) ?: continue
            when (val r = resolveEpisode(ep)) {
                is Resolved.Direct -> return g to r
                is Resolved.Failed -> lastMessage = r.message
            }
        }
        return sorted.firstOrNull() to Resolved.Failed(lastMessage)
    }
}

private const val TAG = "DramaRepository"
private const val DETAIL_TTL_MS = 5 * 60 * 1000L
