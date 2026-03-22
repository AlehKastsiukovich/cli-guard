package dev.alehkastsiukovich.llmguard.policy

import com.fasterxml.jackson.annotation.JsonCreator

data class LlmGuardPolicy(
    val version: Int,
    val defaults: PolicyDefaults = PolicyDefaults(),
    val detectors: DetectorConfig = DetectorConfig(),
    val rules: List<PolicyRule> = emptyList(),
)

data class PolicyDefaults(
    val onUnmatched: RuleActionType = RuleActionType.ALLOW,
    val redactReplacement: String = "<redacted>",
)

data class DetectorConfig(
    val secrets: DetectorToggle = DetectorToggle(),
    val kotlinSymbols: DetectorToggle = DetectorToggle(enabled = false),
)

data class DetectorToggle(
    val enabled: Boolean = true,
    val action: RuleActionType = RuleActionType.REDACT,
)

data class PolicyRule(
    val id: String,
    val description: String? = null,
    val match: MatchCriteria,
    val action: RuleAction,
)

data class MatchCriteria(
    val paths: List<String> = emptyList(),
    val pathRegex: List<String> = emptyList(),
    val contentRegex: List<String> = emptyList(),
    val kotlin: KotlinSymbolCriteria? = null,
)

data class KotlinSymbolCriteria(
    val packages: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    val functions: List<String> = emptyList(),
    val annotations: List<String> = emptyList(),
)

data class RuleAction(
    val type: RuleActionType,
    val replacement: String? = null,
)

enum class RuleActionType {
    ALLOW,
    BLOCK,
    REDACT,
    CONFIRM,
    SUMMARIZE,
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromRaw(value: String): RuleActionType = valueOf(value.trim().uppercase())
    }
}
