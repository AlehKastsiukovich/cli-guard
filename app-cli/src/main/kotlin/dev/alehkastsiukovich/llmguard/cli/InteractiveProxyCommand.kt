package dev.alehkastsiukovich.llmguard.cli

import dev.alehkastsiukovich.llmguard.adapter.InteractiveProviderAdapter
import dev.alehkastsiukovich.llmguard.adapter.ProviderLaunchMode
import dev.alehkastsiukovich.llmguard.adapter.StagedWorkspaceDescriptor
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

internal class InteractiveProxyCommand(
    private val providerDisplayName: String,
    private val adapter: InteractiveProviderAdapter,
    private val realExecutableEnvVar: String,
    private val realExecutableFlags: Set<String>,
    private val policyLoader: PolicyLoader,
    private val guardEngine: GuardEngine,
    private val workspaceStager: WorkspaceStager,
    private val sessionRunner: InteractiveSessionRunner,
    private val environment: Map<String, String>,
) {
    fun run(args: List<String>): Int {
        val wrapperArgs = parseInteractiveProxyArguments(
            args = args,
            realExecutableFlags = realExecutableFlags,
        ) ?: return 1
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
        val parsedProviderArguments = adapter.parseArguments(
            args = wrapperArgs.providerArguments,
            currentWorkingDirectory = currentWorkingDirectory,
        ) ?: return 1

        val promptEvaluation = parsedProviderArguments.prompt?.let { prompt ->
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

        val externalIncludeDirectories = buildList {
            addAll(parsedProviderArguments.includeDirectories.filter { !it.startsWith(projectRoot) })
            parsedProviderArguments.requestedWorkingDirectory
                ?.takeIf { !it.startsWith(projectRoot) }
                ?.let(::add)
        }.distinct()
        val externalIncludeFiles = parsedProviderArguments.includeFiles
            .filter { !it.startsWith(projectRoot) }
            .filterNot { externalFile -> externalIncludeDirectories.any(externalFile::startsWith) }

        val workspace = workspaceStager.stage(
            policy = policy,
            projectRoot = projectRoot,
            currentWorkingDirectory = currentWorkingDirectory,
            externalIncludeDirectories = externalIncludeDirectories,
            externalIncludeFiles = externalIncludeFiles,
        )

        val workspaceNeedsApproval = workspace.findings.any { it.action == RuleActionType.CONFIRM }
        if (workspaceNeedsApproval && !wrapperArgs.guardApprove) {
            printWorkspaceSummary(workspace, promptEvaluation, currentWorkingDirectory, policyPath)
            System.err.println("Workspace contains files that require approval. Re-run with --guard-approve to continue.")
            return 4
        }

        val sanitizedPrompt = promptEvaluation?.prompt?.content
        val preparedArguments = adapter.rewriteArguments(
            originalArguments = wrapperArgs.providerArguments,
            parsedArguments = parsedProviderArguments,
            sanitizedPrompt = sanitizedPrompt,
            originalWorkingDirectory = currentWorkingDirectory,
            stagedWorkspace = workspace.toDescriptor(),
        )

        printWorkspaceSummary(workspace, promptEvaluation, currentWorkingDirectory, policyPath)
        if (wrapperArgs.guardDryRun) {
            System.err.println("Dry run only. ${providerDisplayName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} was not started.")
            return 0
        }

        val executable = wrapperArgs.guardRealExecutable ?: environment[realExecutableEnvVar] ?: adapter.defaultExecutable
        val liveSyncSession = WorkspaceLiveSyncSession(
            providerDisplayName = providerDisplayName,
            policy = policy,
            workspace = workspace,
            workspaceStager = workspaceStager,
        )
        if (parsedProviderArguments.launchMode == ProviderLaunchMode.INTERACTIVE_PROXY) {
            liveSyncSession.start()
            try {
                System.err.println("Starting interactive $providerDisplayName proxy session.")
                return sessionRunner.run(
                    config = InteractiveSessionConfig(
                        executable = executable,
                        arguments = preparedArguments,
                        workingDirectory = workspace.workingDirectory,
                        environment = mapOf(
                            "LLM_GUARD_POLICY" to policyPath.toAbsolutePath().toString(),
                            "LLM_GUARD_STAGE_DIR" to workspace.root.toString(),
                        ),
                    ),
                    inputTransformer = { input ->
                        if (!adapter.shouldSanitizeInteractiveInput(input)) {
                            input
                        } else {
                            transformInteractiveInput(
                                input = input,
                                policy = policy,
                                projectRoot = projectRoot,
                                guardApprove = wrapperArgs.guardApprove,
                            )
                        }
                    },
                )
            } finally {
                liveSyncSession.close()
            }
        }

        val processBuilder = ProcessBuilder(listOf(executable) + preparedArguments)
            .directory(workspace.workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)

        val processEnvironment = processBuilder.environment()
        processEnvironment["LLM_GUARD_POLICY"] = policyPath.toAbsolutePath().toString()
        processEnvironment["LLM_GUARD_STAGE_DIR"] = workspace.root.toString()

        liveSyncSession.start()
        return try {
            processBuilder.start().waitFor()
        } finally {
            liveSyncSession.close()
        }
    }

    private fun transformInteractiveInput(
        input: String,
        policy: dev.alehkastsiukovich.llmguard.policy.LlmGuardPolicy,
        projectRoot: Path,
        guardApprove: Boolean,
    ): String? {
        val evaluation = guardEngine.evaluate(
            request = GuardRequest(
                projectRoot = projectRoot,
                prompt = GuardPrompt(
                    content = input,
                    sourceLabel = "interactive",
                ),
            ),
            policy = policy,
        )

        return when {
            evaluation.isBlocked -> {
                printPromptFindings(evaluation)
                System.err.println("[llm-guard] interactive input blocked and was not forwarded.")
                null
            }
            evaluation.requiresApproval && !guardApprove -> {
                printPromptFindings(evaluation)
                System.err.println("[llm-guard] interactive input requires --guard-approve and was not forwarded.")
                null
            }
            else -> {
                val sanitized = evaluation.prompt?.content ?: input
                if (sanitized != input) {
                    System.err.println("[llm-guard] interactive input was sanitized before forwarding.")
                }
                sanitized
            }
        }
    }

    private fun printPromptFindings(result: GuardResult) {
        if (result.findings.isEmpty()) {
            return
        }

        System.err.println("Prompt findings:")
        result.findings.forEach { finding ->
            val source = when (finding.source) {
                FindingSource.POLICY_RULE -> finding.ruleId ?: "policy"
                FindingSource.SECRET_DETECTOR -> "secret-detector"
                FindingSource.TEXT_SANITIZER_BACKEND -> finding.ruleId ?: "text-sanitizer"
            }
            System.err.println("  - [${finding.action.name.lowercase()}] via $source: ${finding.message}")
        }
    }

    private fun printWorkspaceSummary(
        workspace: StagedWorkspace,
        promptEvaluation: GuardResult?,
        currentWorkingDirectory: Path,
        policyPath: Path,
    ) {
        System.err.println("${providerDisplayName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} proxy summary:")
        System.err.println("  Policy: ${policyPath.toAbsolutePath()}")
        System.err.println("  Project root: ${workspace.projectRoot}")
        System.err.println("  Working directory: ${currentWorkingDirectory.toAbsolutePath()}")
        System.err.println("  Staged workspace: ${workspace.root}")
        System.err.println("  Files mirrored: ${workspace.mirroredFiles}")
        System.err.println("  Files reused from cache: ${workspace.cacheHits}")
        System.err.println("  Files pass-through: ${workspace.passThroughFiles}")
        System.err.println("  Files redacted: ${workspace.redactedFiles}")
        System.err.println("  Files blocked: ${workspace.blockedFiles}")
        promptEvaluation?.prompt?.let { prompt ->
            System.err.println("  Prompt: ${if (prompt.wasRedacted) "redacted" else "unchanged"}")
        }

        val notableFindings = workspace.findings
            .filter { it.action != RuleActionType.ALLOW }
            .take(10)
        if (notableFindings.isNotEmpty()) {
            System.err.println("  Findings:")
            notableFindings.forEach { finding ->
                val source = when (finding.source) {
                    FindingSource.POLICY_RULE -> finding.ruleId ?: "policy"
                    FindingSource.SECRET_DETECTOR -> "secret-detector"
                    FindingSource.TEXT_SANITIZER_BACKEND -> finding.ruleId ?: "text-sanitizer"
                }
                System.err.println("    - [${finding.action.name.lowercase()}] ${finding.target} via $source")
            }
            if (workspace.findings.size > notableFindings.size) {
                System.err.println("    - ... and ${workspace.findings.size - notableFindings.size} more")
            }
        }
    }
}

internal data class InteractiveProxyArguments(
    val guardPolicyPath: Path?,
    val guardDryRun: Boolean,
    val guardApprove: Boolean,
    val guardRealExecutable: String?,
    val providerArguments: List<String>,
)

internal fun parseInteractiveProxyArguments(
    args: List<String>,
    realExecutableFlags: Set<String> = defaultRealExecutableFlags,
): InteractiveProxyArguments? {
    var index = 0
    var guardPolicyPath: Path? = null
    var guardDryRun = false
    var guardApprove = false
    var guardRealExecutable: String? = null
    val providerArguments = mutableListOf<String>()

    while (index < args.size) {
        when (val arg = args[index]) {
            "--guard-policy" -> {
                guardPolicyPath = args.getOrNull(index + 1)?.let(::Path) ?: return interactiveProxyMissingValue(arg)
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
            in realExecutableFlags -> {
                guardRealExecutable = args.getOrNull(index + 1) ?: return interactiveProxyMissingValue(arg)
                index += 2
            }
            else -> {
                providerArguments += args.drop(index)
                break
            }
        }
    }

    return InteractiveProxyArguments(
        guardPolicyPath = guardPolicyPath,
        guardDryRun = guardDryRun,
        guardApprove = guardApprove,
        guardRealExecutable = guardRealExecutable,
        providerArguments = providerArguments,
    )
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

private fun interactiveProxyMissingValue(argument: String): InteractiveProxyArguments? {
    System.err.println("Missing value for $argument")
    return null
}

private val candidatePolicyNames = listOf(
    "llm-policy.yaml",
    "llm-policy.yml",
)

private val defaultRealExecutableFlags = setOf(
    "--guard-real-executable",
    "--guard-real-gemini",
)

private fun StagedWorkspace.toDescriptor(): StagedWorkspaceDescriptor = StagedWorkspaceDescriptor(
    root = root,
    projectRoot = projectRoot,
    workingDirectory = workingDirectory,
    stagedExternalIncludes = stagedExternalIncludes,
    stagedExternalFiles = stagedExternalFiles,
)
