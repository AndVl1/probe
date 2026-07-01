package tech.devlens.network

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
    val error: String?,
    /**
     * `true` when this transaction was produced by a network mock rule
     * (the interceptor short-circuited without calling `chain.proceed()`),
     * `false` for a real network round-trip. Emitted on the payload so the CLI
     * can flag mocked traffic; absent/`false` on older SDKs is back-compatible.
     */
    val mocked: Boolean = false
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
    "error" to error,
    "mocked" to mocked
)

/**
 * Returns a copy of this transaction with sensitive values replaced by masked placeholders.
 *
 * Each match is replaced with `[LABEL-hex]` where `hex` is the lowercase hexadecimal
 * representation of the matched string's hash code — deterministic for the same input.
 *
 * Non-maskable fields ([id], [method], [timestamp], [durationMs], [responseCode],
 * [requestSizeBytes], [responseSizeBytes]) are never modified.
 *
 * Returns the same instance when [rules] is empty (zero allocation path).
 */
internal fun HttpTransaction.sanitized(rules: List<SanitizeRule>): HttpTransaction {
    if (rules.isEmpty()) return this

    fun String.mask(): String = rules.fold(this) { text, rule ->
        rule.regex.replace(text) { match ->
            "[${rule.label}-${match.value.hashCode().toHexString()}]"
        }
    }

    fun Map<String, String>.maskValues(): Map<String, String> =
        mapValues { (_, v) -> v.mask() }

    return copy(
        url = url.mask(),
        requestHeaders = requestHeaders.maskValues(),
        requestBody = requestBody?.mask(),
        responseHeaders = responseHeaders.maskValues(),
        responseBody = responseBody?.mask(),
        responseMessage = responseMessage?.mask(),
        error = error?.mask()
    )
}
