package tech.devlens.network

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
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

        // ── Mock short-circuit ───────────────────────────────────────────────
        // A matching rule skips the real network call entirely: a response mock
        // synthesizes an okhttp3.Response WITHOUT chain.proceed(); an error mock
        // throws IOException. Either way the transaction is recorded with
        // mocked = true. Non-matching requests fall through to the normal path
        // (mocked = false). Matching is method + URL substring, lock-free over a
        // snapshot — never blocks the dispatcher thread.
        val hit = matchRules(plugin.snapshotMockRules(), request.method, request.url.toString())
        if (hit != null) {
            return applyMock(chain, hit, startTime, requestHeaders, requestBody, requestSizeBytes)
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
                error = errorMessage,
                mocked = false
            )
        )

        return response ?: throw IOException(errorMessage ?: "Request failed")
    }

    /**
     * Applies a matched [MockRule] without hitting the network.
     *
     * - Error mock ([MockRule.error] != null): records a transaction with
     *   `mocked = true`, `error = MockRule.error`, `responseCode = null`, then
     *   throws [IOException] so the caller observes the failure exactly as if
     *   the real call had failed.
     * - Response mock: builds a synthetic [Response] from the rule's status /
     *   headers / body, records the transaction with `mocked = true`, and
     *   returns it — `chain.proceed()` is never called.
     */
    private fun applyMock(
        chain: Interceptor.Chain,
        hit: MockRule,
        startTime: Long,
        requestHeaders: Map<String, String>,
        requestBody: String?,
        requestSizeBytes: Long
    ): Response {
        val request = chain.request()

        if (hit.error != null) {
            val durationMs = System.currentTimeMillis() - startTime
            plugin.record(
                HttpTransaction(
                    id = UUID.randomUUID().toString(),
                    timestamp = startTime,
                    method = request.method,
                    url = request.url.toString(),
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    requestSizeBytes = requestSizeBytes,
                    responseCode = null,
                    responseMessage = null,
                    responseHeaders = emptyMap(),
                    responseBody = null,
                    responseSizeBytes = null,
                    durationMs = durationMs,
                    error = hit.error,
                    mocked = true
                )
            )
            throw IOException(hit.error)
        }

        val status = hit.status ?: 200
        val reason = hit.reason ?: defaultReasonPhrase(status)
        val body = (hit.body ?: "").toResponseBody(mediaTypeFrom(hit.headers))
        val headers = Headers.Builder().apply {
            hit.headers.forEach { (k, v) -> add(k, v) }
        }.build()
        val now = System.currentTimeMillis()

        val response = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(reason)
            .headers(headers)
            .body(body)
            .sentRequestAtMillis(startTime)
            .receivedResponseAtMillis(now)
            .build()

        val durationMs = now - startTime
        plugin.record(
            HttpTransaction(
                id = UUID.randomUUID().toString(),
                timestamp = startTime,
                method = request.method,
                url = request.url.toString(),
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                requestSizeBytes = requestSizeBytes,
                responseCode = status,
                responseMessage = reason,
                responseHeaders = hit.headers,
                responseBody = hit.body,
                responseSizeBytes = (hit.body?.length ?: 0).toLong(),
                durationMs = durationMs,
                error = null,
                mocked = true
            )
        )
        return response
    }

    /** Derives a [okhttp3.MediaType] from a `Content-Type` header in [headers], if any. */
    private fun mediaTypeFrom(headers: Map<String, String>) =
        headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()

    /** Minimal HTTP reason-phrase table for the codes tests exercise; `OK` fallback. */
    private fun defaultReasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        418 -> "I'm a teapot"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "OK"
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> =
        (0 until size).associate { name(it) to value(it) }
}
