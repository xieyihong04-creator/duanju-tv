package com.duanju.tv.data

import com.duanju.tv.data.model.Resolved
import com.duanju.tv.data.remote.CmsClient
import com.duanju.tv.data.remote.DefaultSources
import com.duanju.tv.data.remote.DramaRepository
import com.duanju.tv.data.remote.HongguoAppClient
import com.duanju.tv.data.remote.HongguoClient
import com.duanju.tv.data.remote.LandpageCursor
import com.duanju.tv.data.remote.SharePageResolver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红果协议级测试：用假响应驱动真实请求构造，验证「翻页 / 取流 / 详情」在协议层的语义。
 *
 * 与 HongguoAppClientTest 的区别是这里跑完整链路（含 postWithSign 的重试与降级），
 * 不打网络；LiveNetworkTest 负责真机联网时验证接口仍然可用。
 */
class HongguoProtocolTest {

    private class Recorder : Interceptor {
        var responder: (suspend (url: String, body: String) -> Triple<Int, String, Boolean>)? = null

        data class Call(val path: String, val query: String, val body: JsonElement)
        val calls = mutableListOf<Call>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val bodyText = req.body?.let { b ->
                val sink = okio.Buffer()
                b.writeTo(sink)
                sink.readUtf8()
            } ?: ""
            synchronized(calls) {
                calls.add(Call(req.url.encodedPath, req.url.query.orEmpty(), Json.parseToJsonElement(bodyText)))
            }
            val (code, text, successful) = runBlocking { responder!!.invoke(req.url.toString(), bodyText) }
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (successful) "OK" else "ERR")
                .body(text.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }

        fun bodies(): List<JsonObject> = synchronized(calls) { calls.map { it.body as? JsonObject ?: JsonObject(emptyMap()) } }
    }

    private fun client(rec: Recorder): HongguoAppClient =
        HongguoAppClient(OkHttpClient.Builder().addInterceptor(rec).build(), "7412345678901234567")

    private fun landpageJson(offset: Int, session: String, vararg seriesIds: String, hasMore: Boolean = true): String {
        val rows = seriesIds.map { id ->
            JsonObject(mapOf("series_id" to JsonPrimitive(id), "title" to JsonPrimitive("剧$id")))
        }
        val data = JsonObject(
            mapOf(
                "video_data" to JsonArray(rows),
                "next_offset" to JsonPrimitive(offset + rows.size),
                "has_more" to JsonPrimitive(hasMore),
                "session_id" to JsonPrimitive(session),
            )
        )
        return JsonObject(mapOf("code" to JsonPrimitive(0), "data" to data)).toString()
    }

    // ========== 1. 游标翻页 ==========

    @Test
    fun `first page uses offset 0 and client_req_type 3`() = runBlocking {
        val rec = Recorder()
        rec.responder = { _, _ -> Triple(200, landpageJson(0, "sess-A", "101", "102"), true) }
        val (dramas, next) = client(rec).landpage(null)

        assertEquals(2, dramas.size)
        assertEquals(1, rec.calls.size)
        val body = rec.bodies().first()
        assertEquals("0", (body["offset"] as JsonPrimitive).content)
        assertEquals("3", (body["client_req_type"] as JsonPrimitive).content)
        assertEquals("", (body["session_id"] as JsonPrimitive).content)

        // 下一页游标必须带上接口返回的 offset/session，而不是页码
        assertEquals(2, next!!.offset)
        assertEquals("sess-A", next.sessionId)
        assertEquals("102", next.lastId)
        assertTrue(next.initialized)
        assertEquals(64, next.pageSignature.length)
    }

    @Test
    fun `next page reuses cursor with client_req_type 2`() = runBlocking {
        val rec = Recorder()
        rec.responder = { _, _ -> Triple(200, landpageJson(2, "sess-B", "103", "104"), true) }
        val cursor = LandpageCursor(offset = 2, sessionId = "sess-A", pageSignature = "prev")
        val (dramas, next) = client(rec).landpage(cursor)

        assertEquals(listOf("103", "104"), dramas.map { it.backendId })
        val body = rec.bodies().first()
        assertEquals("2", (body["offset"] as JsonPrimitive).content)
        assertEquals("2", (body["client_req_type"] as JsonPrimitive).content)
        assertEquals("sess-A", (body["session_id"] as JsonPrimitive).content)
        assertEquals(4, next!!.offset)
    }

    @Test
    fun `has_more false yields null cursor`() = runBlocking {
        val rec = Recorder()
        rec.responder = { _, _ -> Triple(200, landpageJson(0, "sess-A", "101", hasMore = false), true) }
        val (dramas, next) = client(rec).landpage(null)
        assertEquals(1, dramas.size)
        assertNull("到底应返回 null 游标", next)
    }

    @Test
    fun `stale offset throws instead of repeating the same page`() = runBlocking {
        val rec = Recorder()
        // 接口说还有，但 next_offset 没动 —— 继续翻只会拿到同一批
        rec.responder = { _, _ ->
            val data = JsonObject(
                mapOf(
                    "video_data" to JsonArray(listOf(JsonObject(mapOf("series_id" to JsonPrimitive("1"), "title" to JsonPrimitive("x"))))),
                    "next_offset" to JsonPrimitive(0),
                    "has_more" to JsonPrimitive(true),
                    "session_id" to JsonPrimitive("s"),
                )
            )
            Triple(200, JsonObject(mapOf("code" to JsonPrimitive(0), "data" to data)).toString(), true)
        }
        val err = runCatching { client(rec).landpage(LandpageCursor(offset = 0)) }.exceptionOrNull()
        assertTrue("${err?.message}", err is java.io.IOException)
        assertTrue("${err?.message}", err!!.message!!.contains("分页未前进"))
    }

    @Test
    fun `identical page content is detected as stalled cursor`() = runBlocking {
        val rec = Recorder()
        rec.responder = { _, _ -> Triple(200, landpageJson(18, "sess-A", "101", "102"), true) }
        val c = client(rec)
        val (_, first) = c.landpage(null)
        // 上一页签名直接塞进游标：新一页即使 offset 前进了，内容相同也要判定失效
        val same = first!!.copy(offset = 18, pageSignature = first.pageSignature)
        val err = runCatching { c.landpage(same) }.exceptionOrNull()
        assertTrue("${err?.message}", err!!.message!!.contains("分页未更新"))
    }

    // ========== 2. 失败与降级 ==========

    @Test
    fun `non-zero business code fails fast without retry`() = runBlocking {
        val rec = Recorder()
        var n = 0
        rec.responder = { _, _ ->
            n++
            Triple(200, """{"Code":110001,"Message":"未知异常","BaseResp":{"StatusCode":"110001"}}""", true)
        }
        val err = runCatching { client(rec).landpage(null) }.exceptionOrNull()
        assertTrue("$n 次调用", n == 1)
        assertTrue("应为不可重试错误", err is java.io.IOException)
        assertTrue("${err?.message}", err!!.message!!.contains("110001"))
    }

    @Test
    fun `empty response fails fast without retry`() = runBlocking {
        val rec = Recorder()
        var n = 0
        rec.responder = { _, _ -> n++; Triple(200, "", true) }
        val err = runCatching { client(rec).videoDetail("123456") }.exceptionOrNull()
        assertEquals(1, n)
        assertTrue("${err?.message}", err!!.message!!.contains("空响应"))
    }

    @Test
    fun `5xx retries, 4xx does not`() = runBlocking {
        val rec = Recorder()
        var n = 0
        rec.responder = { _, _ -> n++; Triple(503, "service", false) }
        runCatching { client(rec).landpage(null) }
        assertTrue("5xx 应重试多次: $n", n > 1)

        val rec4 = Recorder()
        var m = 0
        rec4.responder = { _, _ -> m++; Triple(403, "forbidden", false) }
        runCatching { client(rec4).landpage(null) }
        assertEquals("4xx 不该重试", 1, m)
    }

    @Test
    fun `videoDetail validates returned series id`() = runBlocking {
        val rec = Recorder()
        val payload = JsonObject(
            mapOf(
                "data" to JsonObject(
                    mapOf(
                        "video_data" to JsonObject(
                            mapOf(
                                "series_id" to JsonPrimitive("999999"),
                                "video_list" to JsonArray(listOf()),
                            )
                        )
                    )
                )
            )
        ).toString()
        rec.responder = { _, _ -> Triple(200, payload, true) }
        val err = runCatching { client(rec).videoDetail("123456") }.exceptionOrNull()
        assertTrue("${err?.message}", err!!.message!!.contains("未返回所请求的剧集"))
        assertEquals("123456", (rec.bodies().first()["series_id"] as JsonPrimitive).content)
    }

    // ========== 3. 仓库层降级 ==========

    private fun repoWith(app: HongguoAppClient, web: HongguoClient): DramaRepository =
        DramaRepository(CmsClient(OkHttpClient()), SharePageResolver(OkHttpClient()), web, app)

    private class WebRecorder : Interceptor {
        val urls = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            synchronized(urls) { urls.add(req.url.toString()) }
            return Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(404).message("nope")
                .body("".toResponseBody("text/html".toMediaType()))
                .build()
        }
    }

    @Test
    fun `repository pages through app cursor when available`() = runBlocking {
        val rec = Recorder()
        rec.responder = { _, body ->
            val offset = Regex("\"offset\":(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val ids = (1..2).map { offset * 10 + it }
            Triple(200, landpageJson(offset, "sess", *ids.map { it.toString() }.toTypedArray()), true)
        }
        val webRec = WebRecorder()
        val repo = repoWith(client(rec), HongguoClient(OkHttpClient.Builder().addInterceptor(webRec).build()))
        val spec = DefaultSources.byId("hongguo")!!

        val p1 = repo.page(spec, 1)
        val p2 = repo.page(spec, 2)
        assertEquals("app", repo.lastHongguoListChannel)
        assertEquals(2, p1.items.size)
        assertEquals(2, p2.items.size)
        val overlap = p2.items.map { it.backendId } intersect p1.items.map { it.backendId }
        assertTrue("翻页不应重复", overlap.isEmpty())
        assertTrue("网页通道不应被调用", webRec.urls.isEmpty())

        // 回到第 1 页 = 重新开一个会话
        repo.page(spec, 1)
        val offsets = rec.bodies().map { (it["offset"] as JsonPrimitive).content }
        assertEquals(listOf("0", "2", "0"), offsets)
    }

    @Test
    fun `repository falls back to web channel and resets cursor`() = runBlocking {
        val rec = Recorder()
        rec.responder = { _, _ -> Triple(200, """{"Code":110001}""", true) }
        val webRec = WebRecorder()
        val repo = repoWith(client(rec), HongguoClient(OkHttpClient.Builder().addInterceptor(webRec).build()))
        val spec = DefaultSources.byId("hongguo")!!

        val p = repo.page(spec, 1)
        assertTrue("网页通道应被调用", webRec.urls.isNotEmpty())
        assertTrue("降级后列表为空但不抛异常", p.items.isEmpty())
        assertEquals("web", repo.lastHongguoListChannel)
    }

    @Test
    fun `dead app detail channel cools down after first failure`() = runBlocking {
        val rec = Recorder()
        rec.responder = { _, _ -> Triple(200, "", true) } // 空 body = PermanentAppError
        val webRec = WebRecorder()
        val repo = repoWith(client(rec), HongguoClient(OkHttpClient.Builder().addInterceptor(webRec).build()))

        val listItem = com.duanju.tv.data.remote.hongguoDramaFromItem(
            JsonObject(mapOf("series_id" to JsonPrimitive("123456"), "title" to JsonPrimitive("剧")))
        )!!
        assertEquals("列表条目本身没有分集", 0, listItem.episodeCount)

        val one = repo.withEpisodes(listItem)
        assertEquals(1, rec.calls.size)
        val webCallsBefore = webRec.urls.size
        // 网页详情也拿不到（这里用 404 假响应），至少不能崩，且 key 不变
        assertEquals(listItem.key, one.key)

        repo.withEpisodes(listItem)
        assertEquals("冷却期内不应再打 App 详情接口", 1, rec.calls.size)
        assertTrue("网页详情仍应被尝试", webRec.urls.size > webCallsBefore)
    }

    @Test
    fun `resolve failure returns Failed instead of throwing`() = runBlocking {
        val rec = Recorder()
        rec.responder = { _, _ -> Triple(200, """{"Code":110001}""", true) }
        val webRec = WebRecorder()
        val repo = repoWith(client(rec), HongguoClient(OkHttpClient.Builder().addInterceptor(webRec).build()))

        val ep = com.duanju.tv.data.model.Episode(1, "第1集", "hongguo://123456/654321")
        val res = repo.resolveEpisode(ep)
        assertTrue("应返回 Failed: $res", res is Resolved.Failed)

        val bad = repo.resolveEpisode(com.duanju.tv.data.model.Episode(1, "x", "hongguo://onlyseries"))
        assertTrue(bad is Resolved.Failed)
    }

    @Test
    fun `hongguo source is not treated as macCMS category source`() {
        val spec = DefaultSources.byId("hongguo")!!
        assertNotEquals(spec, DefaultSources.byId("ffzy"))
        assertTrue(!spec.hasMacCategories)
        assertTrue(DefaultSources.byId("ffzy")!!.hasMacCategories)
    }
}
