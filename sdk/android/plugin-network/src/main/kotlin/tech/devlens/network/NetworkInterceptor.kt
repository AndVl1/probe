package tech.devlens.network

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.util.UUID

internal class NetworkInterceptor(
    private val plugin: NetworkPlugin,
    private val maxBodySize: Long
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        val requestBody: String?
        val requestSizeBytes: Long

        val reqBody = request.body
        if (reqBody != null) {
            val buffer = Buffer()
            reqBody.writeTo(buffer)
            requestSizeBytes = buffer.size
            requestBody = if (requestSizeBytes <= maxBodySize) buffer.readUtf8()
            else "[body too large: $requestSizeBytes bytes]"
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
            val peeked = response.peekBody(maxBodySize)
            responseBody = peeked.string()
            responseSizeBytes = responseBody?.length?.toLong()
        }

        plugin.record(
            HttpTransaction(
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
                error = errorMessage
            )
        )

        return response ?: throw java.io.IOException(errorMessage ?: "Request failed")
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> =
        (0 until size).associate { name(it) to value(it) }
}
