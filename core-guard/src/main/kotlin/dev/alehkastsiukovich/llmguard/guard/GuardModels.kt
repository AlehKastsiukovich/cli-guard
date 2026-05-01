package dev.alehkastsiukovich.llmguard.guard

import dev.alehkastsiukovich.llmguard.policy.RuleActionType
import java.nio.file.Path

data class GuardRequest(
    val projectRoot: Path,
    val prompt: GuardPrompt? = null,
    val attachments: List<Path> = emptyList(),
)

data class GuardPrompt(
    val content: String,
    val sourceLabel: String = "inline",
)

data class GuardResult(
    val prompt: PreparedPrompt?,
    val attachments: List<PreparedAttachment>,
    val findings: List<GuardFinding>,
    val isBlocked: Boolean,
    val requiresApproval: Boolean,
)

data class PreparedPrompt(
    val content: String,
    val wasRedacted: Boolean,
)

data class PreparedAttachment(
    val originalPath: Path,
    val relativePath: Path,
    val disposition: AttachmentDisposition,
    val sanitizedText: String? = null,
)

enum class AttachmentDisposition {
    COPY_ORIGINAL,
    WRITE_SANITIZED_TEXT,
    BLOCKED,
}

data class GuardFinding(
    val target: String,
    val action: RuleActionType,
    val source: FindingSource,
    val message: String,
    val ruleId: String? = null,
)

enum class FindingSource {
    POLICY_RULE,
    SECRET_DETECTOR,
    TEXT_SANITIZER_BACKEND,
}
