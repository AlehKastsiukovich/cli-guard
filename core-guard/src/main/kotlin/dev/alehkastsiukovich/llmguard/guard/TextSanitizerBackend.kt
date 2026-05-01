package dev.alehkastsiukovich.llmguard.guard

import dev.alehkastsiukovich.llmguard.policy.DetectorConfig

internal interface TextSanitizerBackend {
    val id: String

    fun config(detectors: DetectorConfig): dev.alehkastsiukovich.llmguard.policy.DetectorToggle

    fun sanitize(request: TextSanitizerRequest): TextSanitizerResult?
}

internal data class TextSanitizerRequest(
    val target: String,
    val content: String,
)

internal data class TextSanitizerResult(
    val redactedText: String? = null,
    val matchedValues: List<String> = emptyList(),
    val summary: String? = null,
)
