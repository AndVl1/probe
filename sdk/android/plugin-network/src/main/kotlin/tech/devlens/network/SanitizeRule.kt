package tech.devlens.network

/**
 * A rule that replaces matching text in HTTP transaction fields with a masked placeholder.
 *
 * Not a data class because [Regex] does not implement value-based equality.
 * Two rules are equal when their pattern, options, and label are equal.
 *
 * ## Built-in presets
 * Use [SanitizeRule.PHONE], [SanitizeRule.EMAIL], or [SanitizeRule.BEARER_TOKEN]
 * for common masking needs.
 *
 * ## Custom rule
 * ```kotlin
 * val creditCard = SanitizeRule(
 *     regex = Regex("""\b\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{4}\b"""),
 *     label = "CARD"
 * )
 * ```
 */
class SanitizeRule(
    val regex: Regex,
    val label: String
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SanitizeRule) return false
        return regex.pattern == other.regex.pattern &&
            regex.options == other.regex.options &&
            label == other.label
    }

    override fun hashCode(): Int {
        var result = regex.pattern.hashCode()
        result = 31 * result + regex.options.hashCode()
        result = 31 * result + label.hashCode()
        return result
    }

    override fun toString(): String = "SanitizeRule(label=$label, pattern=${regex.pattern})"

    companion object {

        /**
         * Masks phone numbers such as +7 (999) 123-45-67 or 1-800-555-1234.
         *
         * **Note:** this pattern is intentionally broad and may match non-phone numeric strings
         * (ISO dates, numeric IDs with dashes). Prefer precision over this preset when false
         * positives are unacceptable.
         */
        val PHONE = SanitizeRule(
            regex = Regex("""\+?\d[\d\-\s()]{6,}\d"""),
            label = "PHONE"
        )

        /** Masks e-mail addresses such as user@example.com. */
        val EMAIL = SanitizeRule(
            regex = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""),
            label = "EMAIL"
        )

        /** Masks Bearer token values from Authorization headers. */
        val BEARER_TOKEN = SanitizeRule(
            regex = Regex("""Bearer\s+\S+"""),
            label = "BEARER_TOKEN"
        )
    }
}
