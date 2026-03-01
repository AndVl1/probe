package dev.probe.network

data class HttpTransaction(
    val id: String,
    val timestamp: Long,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val requestSizeBytes: Long,
    val responseCode: Int?,
    val responseMessage: String?,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    val responseSizeBytes: Long?,
    val durationMs: Long?,
    val error: String?
)

internal fun HttpTransaction.toPayload(): Map<String, Any?> = mapOf(
    "id" to id,
    "timestamp" to timestamp,
    "method" to method,
    "url" to url,
    "requestHeaders" to requestHeaders,
    "requestBody" to requestBody,
    "requestSizeBytes" to requestSizeBytes,
    "responseCode" to responseCode,
    "responseMessage" to responseMessage,
    "responseHeaders" to responseHeaders,
    "responseBody" to responseBody,
    "responseSizeBytes" to responseSizeBytes,
    "durationMs" to durationMs,
    "error" to error
)
