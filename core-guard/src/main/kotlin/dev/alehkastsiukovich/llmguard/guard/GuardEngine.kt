package dev.alehkastsiukovich.llmguard.guard

import dev.alehkastsiukovich.llmguard.policy.KotlinSymbolCriteria
import dev.alehkastsiukovich.llmguard.policy.LlmGuardPolicy
import dev.alehkastsiukovich.llmguard.policy.MatchCriteria
import dev.alehkastsiukovich.llmguard.policy.PolicyRule
import dev.alehkastsiukovich.llmguard.policy.RuleActionType
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class GuardEngine internal constructor(
    private val secretDetector: SecretDetector,
    private val kotlinSymbolRedactor: KotlinSymbolRedactor,
    private val textSanitizerBackends: List<TextSanitizerBackend>,
) {
    constructor() : this(
        secretDetector = SecretDetector(),
        kotlinSymbolRedactor = KotlinSymbolRedactor(),
        textSanitizerBackends = emptyList(),
    )

    companion object {
        fun withDefaultTextSanitizers(environment: Map<String, String>): GuardEngine = GuardEngine(
            secretDetector = SecretDetector(),
            kotlinSymbolRedactor = KotlinSymbolRedactor(),
            textSanitizerBackends = listOf(
                GitleaksTextSanitizerBackend(environment),
                PrivacyFilterTextSanitizerBackend(environment),
            ),
        )
    }

    fun evaluate(
        policy: LlmGuardPolicy,
        request: GuardRequest,
    ): GuardResult {
        val promptResult = request.prompt?.let { evaluatePrompt(policy, it) }
        val attachmentResults = request.attachments.map { attachment ->
            evaluateAttachment(
                policy = policy,
                projectRoot = request.projectRoot.normalize(),
                attachmentPath = attachment,
            )
        }

        val findings = buildList {
            promptResult?.findings?.let(::addAll)
            attachmentResults.flatMapTo(this) { it.findings }
        }

        return GuardResult(
            prompt = promptResult?.preparedPrompt,
            attachments = attachmentResults.map { it.preparedAttachment },
            findings = findings,
            isBlocked = findings.any { it.action == RuleActionType.BLOCK },
            requiresApproval = findings.any { it.action == RuleActionType.CONFIRM },
        )
    }

    private fun evaluatePrompt(
        policy: LlmGuardPolicy,
        prompt: GuardPrompt,
    ): PromptEvaluationResult {
        val evaluation = evaluateTextContent(
            target = "prompt:${prompt.sourceLabel}",
            content = prompt.content,
            policy = policy,
            path = null,
            allowKotlinSymbolRules = false,
        )

        return PromptEvaluationResult(
            preparedPrompt = PreparedPrompt(
                content = evaluation.content,
                wasRedacted = evaluation.content != prompt.content,
            ),
            findings = evaluation.findings,
        )
    }

    private fun evaluateAttachment(
        policy: LlmGuardPolicy,
        projectRoot: Path,
        attachmentPath: Path,
    ): AttachmentEvaluationResult {
        val absolutePath = resolveAttachmentPath(projectRoot, attachmentPath)
        require(absolutePath.isRegularFile()) { "Attachment is not a regular file: $attachmentPath" }

        val relativePath = relativeToRoot(projectRoot, absolutePath)
        val target = relativePath.invariantSeparatorsPathString
        val textContent = readUtf8OrNull(absolutePath)

        val pathFindings = evaluatePathRules(policy, target, textContent)
        if (pathFindings.any { it.action == RuleActionType.BLOCK }) {
            return AttachmentEvaluationResult(
                preparedAttachment = PreparedAttachment(
                    originalPath = absolutePath,
                    relativePath = relativePath,
                    disposition = AttachmentDisposition.BLOCKED,
                ),
                findings = pathFindings,
            )
        }

        if (textContent == null) {
            return AttachmentEvaluationResult(
                preparedAttachment = PreparedAttachment(
                    originalPath = absolutePath,
                    relativePath = relativePath,
                    disposition = AttachmentDisposition.COPY_ORIGINAL,
                ),
                findings = pathFindings,
            )
        }

        val textEvaluation = evaluateTextContent(
            target = target,
            content = textContent,
            policy = policy,
            path = relativePath,
            allowKotlinSymbolRules = absolutePath.extension == "kt",
        )

        val allFindings = pathFindings + textEvaluation.findings
        if (allFindings.any { it.action == RuleActionType.BLOCK }) {
            return AttachmentEvaluationResult(
                preparedAttachment = PreparedAttachment(
                    originalPath = absolutePath,
                    relativePath = relativePath,
                    disposition = AttachmentDisposition.BLOCKED,
                ),
                findings = allFindings,
            )
        }

        val disposition = if (textEvaluation.content == textContent) {
            AttachmentDisposition.COPY_ORIGINAL
        } else {
            AttachmentDisposition.WRITE_SANITIZED_TEXT
        }

        return AttachmentEvaluationResult(
            preparedAttachment = PreparedAttachment(
                originalPath = absolutePath,
                relativePath = relativePath,
                disposition = disposition,
                sanitizedText = textEvaluation.content.takeIf { disposition == AttachmentDisposition.WRITE_SANITIZED_TEXT },
            ),
            findings = allFindings,
        )
    }

    private fun evaluatePathRules(
        policy: LlmGuardPolicy,
        relativePath: String,
        textContent: String?,
    ): List<GuardFinding> = policy.rules
        .filter { rule ->
            (rule.match.paths.isNotEmpty() || rule.match.pathRegex.isNotEmpty()) &&
                rule.match.contentRegex.isEmpty() &&
                rule.match.kotlin == null
        }
        .mapNotNull { rule ->
        val matches = matchesRule(
            rule = rule,
            relativePath = relativePath,
            content = textContent,
            kotlinInspection = null,
        )

        if (!matches.matched) {
            null
        } else if (rule.action.type == RuleActionType.BLOCK || rule.action.type == RuleActionType.CONFIRM) {
            GuardFinding(
                target = relativePath,
                action = rule.action.type,
                source = FindingSource.POLICY_RULE,
                message = "Matched rule ${rule.id}",
                ruleId = rule.id,
            )
        } else {
            null
        }
    }

    private fun evaluateTextContent(
        target: String,
        content: String,
        policy: LlmGuardPolicy,
        path: Path?,
        allowKotlinSymbolRules: Boolean,
    ): TextEvaluation {
        var current = content
        val findings = mutableListOf<GuardFinding>()

        policy.rules.forEach { rule ->
            val kotlinCriteria = rule.match.kotlin
            val kotlinInspection = if (allowKotlinSymbolRules && kotlinCriteria != null) {
                kotlinSymbolRedactor.inspect(current, kotlinCriteria)
            } else {
                null
            }

            val matches = matchesRule(
                rule = rule,
                relativePath = path?.invariantSeparatorsPathString,
                content = current,
                kotlinInspection = kotlinInspection,
            )

            if (!matches.matched) {
                return@forEach
            }

            when (rule.action.type) {
                RuleActionType.ALLOW -> Unit
                RuleActionType.BLOCK,
                RuleActionType.CONFIRM,
                -> findings += GuardFinding(
                    target = target,
                    action = rule.action.type,
                    source = FindingSource.POLICY_RULE,
                    message = "Matched rule ${rule.id}",
                    ruleId = rule.id,
                )
                RuleActionType.REDACT -> {
                    val replacement = rule.action.replacement ?: policy.defaults.redactReplacement
                    val redacted = applyRedaction(rule.match, current, replacement, kotlinInspection)
                    if (redacted != current) {
                        findings += GuardFinding(
                            target = target,
                            action = RuleActionType.REDACT,
                            source = FindingSource.POLICY_RULE,
                            message = "Redacted content using rule ${rule.id}",
                            ruleId = rule.id,
                        )
                        current = redacted
                    }
                }
                RuleActionType.SUMMARIZE -> {
                    current = "<summarized by ${rule.id}>"
                    findings += GuardFinding(
                        target = target,
                        action = RuleActionType.SUMMARIZE,
                        source = FindingSource.POLICY_RULE,
                        message = "Summarized content using rule ${rule.id}",
                        ruleId = rule.id,
                    )
                }
            }
        }

        if (policy.detectors.secrets.enabled) {
            val secretMatches = secretDetector.find(current)
            if (secretMatches.isNotEmpty()) {
                when (policy.detectors.secrets.action) {
                    RuleActionType.ALLOW -> Unit
                    RuleActionType.BLOCK,
                    RuleActionType.CONFIRM,
                    -> findings += GuardFinding(
                        target = target,
                        action = policy.detectors.secrets.action,
                        source = FindingSource.SECRET_DETECTOR,
                        message = "Detected ${secretMatches.size} secret-like value(s)",
                    )
                    RuleActionType.REDACT -> {
                        current = replaceRanges(
                            content = current,
                            replacements = secretMatches.map { match ->
                                match.range to policy.defaults.redactReplacement
                            },
                        )
                        findings += GuardFinding(
                            target = target,
                            action = RuleActionType.REDACT,
                            source = FindingSource.SECRET_DETECTOR,
                            message = "Redacted ${secretMatches.size} secret-like value(s)",
                        )
                    }
                    RuleActionType.SUMMARIZE -> {
                        current = "<summarized:secret-content>"
                        findings += GuardFinding(
                            target = target,
                            action = RuleActionType.SUMMARIZE,
                            source = FindingSource.SECRET_DETECTOR,
                            message = "Summarized content after secret detection",
                        )
                    }
                }
            }
        }

        textSanitizerBackends.forEach { backend ->
            val detectorConfig = backend.config(policy.detectors)
            if (!detectorConfig.enabled) {
                return@forEach
            }

            val backendResult = backend.sanitize(
                TextSanitizerRequest(
                    target = target,
                    content = current,
                ),
            ) ?: return@forEach

            val hasMatches = backendResult.redactedText != null || backendResult.matchedValues.isNotEmpty()
            if (!hasMatches) {
                return@forEach
            }

            when (detectorConfig.action) {
                RuleActionType.ALLOW -> Unit
                RuleActionType.BLOCK,
                RuleActionType.CONFIRM,
                -> findings += GuardFinding(
                    target = target,
                    action = detectorConfig.action,
                    source = FindingSource.TEXT_SANITIZER_BACKEND,
                    message = backendResult.summary ?: "Detected sensitive text via ${backend.id}",
                    ruleId = backend.id,
                )
                RuleActionType.REDACT -> {
                    val redacted = backendResult.redactedText ?: redactMatchedValues(
                        content = current,
                        matchedValues = backendResult.matchedValues,
                        replacement = policy.defaults.redactReplacement,
                    )
                    if (redacted != current) {
                        current = redacted
                        findings += GuardFinding(
                            target = target,
                            action = RuleActionType.REDACT,
                            source = FindingSource.TEXT_SANITIZER_BACKEND,
                            message = backendResult.summary ?: "Redacted sensitive text via ${backend.id}",
                            ruleId = backend.id,
                        )
                    }
                }
                RuleActionType.SUMMARIZE -> {
                    current = "<summarized:${backend.id}>"
                    findings += GuardFinding(
                        target = target,
                        action = RuleActionType.SUMMARIZE,
                        source = FindingSource.TEXT_SANITIZER_BACKEND,
                        message = backendResult.summary ?: "Summarized content via ${backend.id}",
                        ruleId = backend.id,
                    )
                }
            }
        }

        return TextEvaluation(current, findings)
    }

    private fun redactMatchedValues(
        content: String,
        matchedValues: List<String>,
        replacement: String,
    ): String {
        if (matchedValues.isEmpty()) {
            return content
        }

        var current = content
        matchedValues
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { matchedValue ->
                current = current.replace(matchedValue, replacement)
            }
        return current
    }

    private fun matchesRule(
        rule: PolicyRule,
        relativePath: String?,
        content: String?,
        kotlinInspection: KotlinInspection?,
    ): RuleMatch {
        val checks = mutableListOf<Boolean>()

        if (rule.match.paths.isNotEmpty()) {
            checks += relativePath != null && rule.match.paths.any { pattern -> matchesGlob(pattern, relativePath) }
        }
        if (rule.match.pathRegex.isNotEmpty()) {
            checks += relativePath != null && rule.match.pathRegex.any { regex -> Regex(regex).containsMatchIn(relativePath) }
        }
        if (rule.match.contentRegex.isNotEmpty()) {
            checks += content != null && rule.match.contentRegex.any { regex -> Regex(regex).containsMatchIn(content) }
        }
        if (rule.match.kotlin != null) {
            checks += kotlinInspection?.matched == true
        }

        return RuleMatch(checks.isNotEmpty() && checks.all { it })
    }

    private fun applyRedaction(
        criteria: MatchCriteria,
        content: String,
        replacement: String,
        kotlinInspection: KotlinInspection?,
    ): String {
        criteria.kotlin?.let {
            if (kotlinInspection?.matched == true && kotlinInspection.matches.isNotEmpty()) {
                return kotlinSymbolRedactor.redact(content, kotlinInspection.matches, replacement)
            }
        }

        if (criteria.contentRegex.isNotEmpty()) {
            var updated = content
            criteria.contentRegex.forEach { pattern ->
                updated = Regex(pattern).replace(updated, replacement)
            }
            return updated
        }

        return replacement
    }

    private fun matchesGlob(pattern: String, relativePath: String): Boolean =
        FileSystems.getDefault()
            .getPathMatcher("glob:$pattern")
            .matches(Path.of(relativePath))

    private fun resolveAttachmentPath(projectRoot: Path, attachmentPath: Path): Path =
        if (attachmentPath.isAbsolute) {
            attachmentPath.normalize()
        } else {
            projectRoot.resolve(attachmentPath).normalize()
        }

    private fun relativeToRoot(projectRoot: Path, absolutePath: Path): Path {
        return try {
            if (!absolutePath.startsWith(projectRoot)) {
                Path.of("_external", absolutePath.name)
            } else {
                val relative = projectRoot.relativize(absolutePath).normalize()
                if (relative.startsWith("..")) {
                    Path.of("_external", absolutePath.name)
                } else {
                    relative
                }
            }
        } catch (_: IllegalArgumentException) {
            Path.of("_external", absolutePath.name)
        }
    }

    private fun readUtf8OrNull(path: Path): String? = try {
        Files.readString(path, StandardCharsets.UTF_8)
    } catch (_: MalformedInputException) {
        null
    }
}

private data class PromptEvaluationResult(
    val preparedPrompt: PreparedPrompt,
    val findings: List<GuardFinding>,
)

private data class AttachmentEvaluationResult(
    val preparedAttachment: PreparedAttachment,
    val findings: List<GuardFinding>,
)

private data class TextEvaluation(
    val content: String,
    val findings: List<GuardFinding>,
)

private data class RuleMatch(
    val matched: Boolean,
)

internal fun replaceRanges(
    content: String,
    replacements: List<Pair<IntRange, String>>,
): String {
    if (replacements.isEmpty()) {
        return content
    }

    val builder = StringBuilder(content)
    replacements
        .sortedByDescending { it.first.first }
        .forEach { (range, replacement) ->
            builder.replace(range.first, range.last + 1, replacement)
        }
    return builder.toString()
}
