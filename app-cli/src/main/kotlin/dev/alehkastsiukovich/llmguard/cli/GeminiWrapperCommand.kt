package dev.alehkastsiukovich.llmguard.cli

import dev.alehkastsiukovich.llmguard.guard.FindingSource
import dev.alehkastsiukovich.llmguard.guard.GuardEngine
import dev.alehkastsiukovich.llmguard.guard.GuardPrompt
import dev.alehkastsiukovich.llmguard.guard.GuardRequest
import dev.alehkastsiukovich.llmguard.guard.GuardResult
import dev.alehkastsiukovich.llmguard.guard.StagedWorkspace
import dev.alehkastsiukovich.llmguard.guard.WorkspaceStager
import dev.alehkastsiukovich.llmguard.policy.PolicyLoader
import dev.alehkastsiukovich.llmguard.policy.PolicyValidationException
import dev.alehkastsiukovich.llmguard.policy.RuleActionType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString

internal class GeminiWrapperCommand(
    private val policyLoader: PolicyLoader,
    private val guardEngine: GuardEngine,
    private val workspaceStager: WorkspaceStager,
    private val environment: Map<String, String>,
) {
    fun run(args: List<String>): Int {
        val wrapperArgs = parseGeminiWrapperArguments(args) ?: return 1
        val currentWorkingDirectory = Path("").toAbsolutePath().normalize()
        val policyPath = findPolicyPath(
            explicitPath = wrapperArgs.guardPolicyPath,
            startDirectory = currentWorkingDirectory,
            environment = environment,
        )

        if (policyPath == null) {
            System.err.println("Could not find llm-policy.yaml. Add one to the project root or set LLM_GUARD_POLICY.")
            return 2
        }

        val policy = try {
            policyLoader.load(policyPath)
        } catch (error: PolicyValidationException) {
            System.err.println("Policy validation failed: ${error.message}")
            return 2
        } catch (error: Exception) {
            System.err.println("Failed to load policy at $policyPath: ${error.message}")
            return 2
        }

        val projectRoot = policyPath.parent ?: currentWorkingDirectory
        val geminiArguments = parseGeminiCliArguments(
            args = wrapperArgs.geminiArguments,
            currentWorkingDirectory = currentWorkingDirectory,
        ) ?: return 1

        val promptEvaluation = geminiArguments.prompt?.let { prompt ->
            guardEngine.evaluate(
                policy = policy,
                request = GuardRequest(
                    projectRoot = projectRoot,
                    prompt = GuardPrompt(
                        content = prompt.value,
                        sourceLabel = prompt.flag,
                    ),
                ),
            )
        }

        if (promptEvaluation?.isBlocked == true) {
            printPromptFindings(promptEvaluation)
            System.err.println("Execution blocked by prompt policy.")
            return 3
        }

        if (promptEvaluation?.requiresApproval == true && !wrapperArgs.guardApprove) {
            printPromptFindings(promptEvaluation)
            System.err.println("Prompt requires approval. Re-run with --guard-approve to continue.")
            return 4
        }

        val workspace = workspaceStager.stage(
            policy = policy,
            projectRoot = projectRoot,
            currentWorkingDirectory = currentWorkingDirectory,
            externalIncludeDirectories = geminiArguments.includeDirectories.filter { !it.startsWith(projectRoot) },
        )

        val workspaceNeedsApproval = workspace.findings.any { it.action == RuleActionType.CONFIRM }
        if (workspaceNeedsApproval && !wrapperArgs.guardApprove) {
            printWorkspaceSummary(workspace, promptEvaluation, currentWorkingDirectory, policyPath)
            System.err.println("Workspace contains files that require approval. Re-run with --guard-approve to continue.")
            return 4
        }

        val sanitizedPrompt = promptEvaluation?.prompt?.content
        val preparedArguments = rewriteGeminiArguments(
            originalArguments = wrapperArgs.geminiArguments,
            parsedArguments = geminiArguments,
            sanitizedPrompt = sanitizedPrompt,
            originalWorkingDirectory = currentWorkingDirectory,
            stagedWorkspace = workspace,
        )

        printWorkspaceSummary(workspace, promptEvaluation, currentWorkingDirectory, policyPath)
        if (wrapperArgs.guardDryRun) {
            println("Dry run only. Gemini CLI was not started.")
            return 0
        }

        if (geminiArguments.prompt == null) {
            println("Interactive Gemini session will use the sanitized workspace. Interactive prompt text is not filtered yet.")
        }

        val executable = wrapperArgs.guardRealGemini ?: environment["LLM_GUARD_REAL_GEMINI"] ?: "gemini"
        val processBuilder = ProcessBuilder(listOf(executable) + preparedArguments)
            .directory(workspace.workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)

        val processEnvironment = processBuilder.environment()
        processEnvironment["LLM_GUARD_POLICY"] = policyPath.toAbsolutePath().toString()
        processEnvironment["LLM_GUARD_STAGE_DIR"] = workspace.root.toString()

        return processBuilder.start().waitFor()
    }

    private fun printPromptFindings(result: GuardResult) {
        if (result.findings.isEmpty()) {
            return
        }

        println("Prompt findings:")
        result.findings.forEach { finding ->
            val source = when (finding.source) {
                FindingSource.POLICY_RULE -> finding.ruleId ?: "policy"
                FindingSource.SECRET_DETECTOR -> "secret-detector"
            }
            println("  - [${finding.action.name.lowercase()}] via $source: ${finding.message}")
        }
    }

    private fun printWorkspaceSummary(
        workspace: StagedWorkspace,
        promptEvaluation: GuardResult?,
        currentWorkingDirectory: Path,
        policyPath: Path,
    ) {
        println("Gemini wrapper summary:")
        println("  Policy: ${policyPath.toAbsolutePath()}")
        println("  Project root: ${workspace.projectRoot}")
        println("  Working directory: ${currentWorkingDirectory.toAbsolutePath()}")
        println("  Staged workspace: ${workspace.root}")
        println("  Files mirrored: ${workspace.mirroredFiles}")
        println("  Files redacted: ${workspace.redactedFiles}")
        println("  Files blocked: ${workspace.blockedFiles}")
        promptEvaluation?.prompt?.let { prompt ->
            println("  Prompt: ${if (prompt.wasRedacted) "redacted" else "unchanged"}")
        }

        val notableFindings = workspace.findings
            .filter { it.action != RuleActionType.ALLOW }
            .take(10)
        if (notableFindings.isNotEmpty()) {
            println("  Findings:")
            notableFindings.forEach { finding ->
                val source = when (finding.source) {
                    FindingSource.POLICY_RULE -> finding.ruleId ?: "policy"
                    FindingSource.SECRET_DETECTOR -> "secret-detector"
                }
                println("    - [${finding.action.name.lowercase()}] ${finding.target} via $source")
            }
            if (workspace.findings.size > notableFindings.size) {
                println("    - ... and ${workspace.findings.size - notableFindings.size} more")
            }
        }
    }
}

internal data class GeminiWrapperArguments(
    val guardPolicyPath: Path?,
    val guardDryRun: Boolean,
    val guardApprove: Boolean,
    val guardRealGemini: String?,
    val geminiArguments: List<String>,
)

internal fun parseGeminiWrapperArguments(args: List<String>): GeminiWrapperArguments? {
    var index = 0
    var guardPolicyPath: Path? = null
    var guardDryRun = false
    var guardApprove = false
    var guardRealGemini: String? = null
    val geminiArguments = mutableListOf<String>()

    while (index < args.size) {
        when (val arg = args[index]) {
            "--guard-policy" -> {
                guardPolicyPath = args.getOrNull(index + 1)?.let(::Path) ?: return guardMissingValue(arg)
                index += 2
            }
            "--guard-dry-run" -> {
                guardDryRun = true
                index += 1
            }
            "--guard-approve" -> {
                guardApprove = true
                index += 1
            }
            "--guard-real-gemini" -> {
                guardRealGemini = args.getOrNull(index + 1) ?: return guardMissingValue(arg)
                index += 2
            }
            else -> {
                geminiArguments += args.drop(index)
                break
            }
        }
    }

    return GeminiWrapperArguments(
        guardPolicyPath = guardPolicyPath,
        guardDryRun = guardDryRun,
        guardApprove = guardApprove,
        guardRealGemini = guardRealGemini,
        geminiArguments = geminiArguments,
    )
}

internal data class ParsedGeminiCliArguments(
    val prompt: PromptArgument?,
    val includeDirectories: List<Path>,
)

internal data class PromptArgument(
    val flag: String,
    val index: Int,
    val value: String,
)

internal fun parseGeminiCliArguments(
    args: List<String>,
    currentWorkingDirectory: Path,
): ParsedGeminiCliArguments? {
    var index = 0
    var prompt: PromptArgument? = null
    val includeDirectories = mutableListOf<Path>()

    while (index < args.size) {
        when (val arg = args[index]) {
            "-p",
            "--prompt",
            -> {
                val value = args.getOrNull(index + 1) ?: return geminiMissingValue(arg)
                prompt = PromptArgument(flag = arg, index = index + 1, value = value)
                index += 2
            }
            "--include-directories" -> {
                val value = args.getOrNull(index + 1) ?: return geminiMissingValue(arg)
                includeDirectories += parseIncludeDirectories(value, currentWorkingDirectory)
                index += 2
            }
            else -> {
                when {
                    arg.startsWith("--prompt=") -> {
                        prompt = PromptArgument(flag = "--prompt", index = index, value = arg.substringAfter("="))
                    }
                    arg.startsWith("--include-directories=") -> {
                        includeDirectories += parseIncludeDirectories(
                            arg.substringAfter("="),
                            currentWorkingDirectory,
                        )
                    }
                }
                index += 1
            }
        }
    }

    return ParsedGeminiCliArguments(
        prompt = prompt,
        includeDirectories = includeDirectories.distinct(),
    )
}

internal fun rewriteGeminiArguments(
    originalArguments: List<String>,
    parsedArguments: ParsedGeminiCliArguments,
    sanitizedPrompt: String?,
    originalWorkingDirectory: Path,
    stagedWorkspace: StagedWorkspace,
): List<String> {
    if (originalArguments.isEmpty()) {
        return emptyList()
    }

    val rewritten = originalArguments.toMutableList()
    parsedArguments.prompt?.let { prompt ->
        rewritten[prompt.index] = sanitizedPrompt ?: prompt.value
    }

    var index = 0
    while (index < rewritten.size) {
        val arg = rewritten[index]
        when {
            arg == "--include-directories" -> {
                val rawValue = rewritten.getOrNull(index + 1) ?: break
                rewritten[index + 1] = rewriteIncludeDirectories(
                    rawValue = rawValue,
                    originalWorkingDirectory = originalWorkingDirectory,
                    stagedWorkspace = stagedWorkspace,
                )
                index += 2
            }
            arg.startsWith("--include-directories=") -> {
                val rawValue = arg.substringAfter("=")
                rewritten[index] = "--include-directories=" + rewriteIncludeDirectories(
                    rawValue = rawValue,
                    originalWorkingDirectory = originalWorkingDirectory,
                    stagedWorkspace = stagedWorkspace,
                )
                index += 1
            }
            else -> index += 1
        }
    }

    return rewritten
}

private fun rewriteIncludeDirectories(
    rawValue: String,
    originalWorkingDirectory: Path,
    stagedWorkspace: StagedWorkspace,
): String {
    val originalPaths = rawValue.split(',').filter { it.isNotBlank() }.map { rawPath ->
        val path = Path(rawPath)
        if (path.isAbsolute) path.normalize() else originalWorkingDirectory.resolve(path).normalize()
    }
    return originalPaths.joinToString(",") { originalPath ->
        val mapped = when {
            originalPath.startsWith(stagedWorkspace.projectRoot) ->
                stagedWorkspace.root.resolve(stagedWorkspace.projectRoot.relativize(originalPath))
            else -> stagedWorkspace.stagedExternalIncludes[originalPath]
                ?: stagedWorkspace.root.resolve("_external").resolve(
                    originalPath.fileName?.toString() ?: originalPath.invariantSeparatorsPathString,
                )
        }
        stagedWorkspace.workingDirectory.relativize(mapped).invariantSeparatorsPathString
    }
}

internal fun findPolicyPath(
    explicitPath: Path?,
    startDirectory: Path,
    environment: Map<String, String>,
): Path? {
    explicitPath?.let { return it.toAbsolutePath().normalize() }
    environment["LLM_GUARD_POLICY"]?.let { return Path(it).toAbsolutePath().normalize() }

    var directory: Path? = startDirectory.toAbsolutePath().normalize()
    while (directory != null) {
        candidatePolicyNames
            .map(directory::resolve)
            .firstOrNull(Files::exists)
            ?.let { return it }
        directory = directory.parent
    }

    return null
}

private fun parseIncludeDirectories(
    rawValue: String,
    currentWorkingDirectory: Path,
): List<Path> = rawValue
    .split(',')
    .filter { it.isNotBlank() }
    .map { rawPath ->
        val path = Path(rawPath)
        if (path.isAbsolute) path.normalize() else currentWorkingDirectory.resolve(path).normalize()
    }

private fun guardMissingValue(argument: String): GeminiWrapperArguments? {
    System.err.println("Missing value for $argument")
    return null
}

private fun geminiMissingValue(argument: String): ParsedGeminiCliArguments? {
    System.err.println("Missing value for Gemini argument $argument")
    return null
}

private val candidatePolicyNames = listOf(
    "llm-policy.yaml",
    "llm-policy.yml",
)
