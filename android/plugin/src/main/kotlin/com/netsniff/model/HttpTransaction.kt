package com.netsniff.model

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
    val appId: String?,
    val error: String?
)
