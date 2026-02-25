package com.netsniff

import com.netsniff.model.HttpTransaction
import com.netsniff.transport.NetSniffTransport
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.util.UUID

/**
 * OkHttp interceptor that captures HTTP transactions and forwards them
 * to the configured [NetSniffTransport].
 *
 * Usage:
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(NetSniff.interceptor())
 *     .build()
 * ```
 */
class NetSniffInterceptor internal constructor(
    private val transport: NetSniffTransport,
    private val appId: String?,
    private val maxBodySize: Long = 1024 * 1024L // 1MB
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        val requestBody: String?
        val requestSizeBytes: Long

        if (request.body != null) {
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            requestSizeBytes = buffer.size
            requestBody = if (requestSizeBytes <= maxBodySize) {
                buffer.readUtf8()
            } else {
                "[body too large: $requestSizeBytes bytes]"
            }
        } else {
            requestBody = null
            requestSizeBytes = 0L
        }

        var responseCode: Int? = null
        var responseMessage: String? = null
        var responseHeaders: Map<String, String> = emptyMap()
        var responseBody: String? = null
        var responseSizeBytes: Long? = null
        var errorMessage: String? = null

        val response: Response? = try {
            chain.proceed(request)
        } catch (e: Exception) {
            errorMessage = e.message ?: e.javaClass.simpleName
            null
        }

        val durationMs = System.currentTimeMillis() - startTime

        if (response != null) {
            responseCode = response.code
            responseMessage = response.message
            responseHeaders = response.headers.toMap()
            // peekBody() reads without consuming the stream - response is still usable by caller
            val peeked = response.peekBody(maxBodySize)
            responseBody = peeked.string()
            responseSizeBytes = responseBody?.length?.toLong()
        }

        val transaction = HttpTransaction(
            id = UUID.randomUUID().toString(),
            timestamp = startTime,
            method = request.method,
            url = request.url.toString(),
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            requestSizeBytes = requestSizeBytes,
            responseCode = responseCode,
            responseMessage = responseMessage,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
            responseSizeBytes = responseSizeBytes,
            durationMs = durationMs,
            appId = appId,
            error = errorMessage
        )

        transport.send(transaction)

        return response ?: throw java.io.IOException(errorMessage ?: "Request failed")
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> =
        (0 until size).associate { name(it) to value(it) }
}
