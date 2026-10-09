package com.duanju.tv.data.remote

import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageResult

/**
 * 海报 CDN 普遍拒绝默认 OkHttp UA（403），这里给所有图片请求补上浏览器 UA。
 * 已有 User-Agent 的请求（比如带了 Referer 的站点图）不覆盖。
 */
object BrowserHeadersInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.httpHeaders["User-Agent"] != null) return chain.proceed()
        val headers: NetworkHeaders = request.httpHeaders.newBuilder()
            .set("User-Agent", CmsClient.UA)
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        return chain.withRequest(request.newBuilder().httpHeaders(headers).build()).proceed()
    }
}
