package dev.alehkastsiukovich.llmguard.cli

import dev.alehkastsiukovich.llmguard.adapter.InvocationRequest
import dev.alehkastsiukovich.llmguard.adapter.PreparedInvocation
import dev.alehkastsiukovich.llmguard.adapter.PromptOrigin
import dev.alehkastsiukovich.llmguard.adapter.PromptPayload
import dev.alehkastsiukovich.llmguard.adapter.ProviderAdapter
import dev.alehkastsiukovich.llmguard.guard.AttachmentDisposition
import dev.alehkastsiukovich.llmguard.guard.FindingSource
import dev.alehkastsiukovich.llmguard.guard.GuardEngine
import dev.alehkastsiukovich.llmguard.guard.GuardPrompt
import dev.alehkastsiukovich.llmguard.guard.GuardRequest
import dev.alehkastsiukovich.llmguard.guard.GuardResult
import dev.alehkastsiukovich.llmguard.policy.PolicyLoader
import dev.alehkastsiukovich.llmguard.policy.PolicyValidationException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString

fun main(args: Array<String>) {
    val exitCode = AppBootstrap.create().run(args.toList())
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}

internal class CliApplication(
    private val policyLoader: PolicyLoader,
    private val guardEngine: GuardEngine,
    private val adapters: Map<String, ProviderAdapter>,
    private val geminiWrapperCommand: InteractiveProxyCommand,
) {
    fun run(args: List<String>): Int {
        if (args.isEmpty()) {
            printUsage()
            return 1
        }

        return when (args.first()) {
            "gemini" -> geminiWrapperCommand.run(args.drop(1))
            "policy" -> runPolicyCommand(args.drop(1))
            "run" -> runProviderCommand(args.drop(1))
            else -> {
                System.err.println("Unknown command: ${args.first()}")
                printUsage()
                1
            }
        }
    }

    private fun runPolicyCommand(args: List<String>): Int {
        if (args.isEmpty()) {
            printPolicyUsage()
            return 1
        }

        return when (args.first()) {
            "validate" -> validatePolicy(args.drop(1))
            "print-example" -> {
                print(policyExample)
                0
            }
            else -> {
                System.err.println("Unknown policy command: ${args.first()}")
                printPolicyUsage()
                1
            }
        }
    }

    private fun validatePolicy(args: List<String>): Int {
        val rawPath = args.firstOrNull() ?: "llm-policy.yaml"
        val path = Path(rawPath)

        return try {
            val policy = policyLoader.load(path)
            println("Policy is valid")
            println("Version: ${policy.version}")
            println("Rules: ${policy.rules.size}")
            println("Secret detector enabled: ${policy.detectors.secrets.enabled}")
            0
        } catch (error: PolicyValidationException) {
            System.err.println("Policy validation failed: ${error.message}")
            2
        } catch (error: Exception) {
            System.err.println("Failed to load policy at $path: ${error.message}")
            2
        }
    }

    private fun printUsage() {
        println(
            """
            Usage:
              llm-guard gemini [--guard-* options] [gemini args]
              llm-guard policy validate [path]
              llm-guard policy print-example
              llm-guard run [options] -- <provider command>
            """.trimIndent(),
        )
    }

    private fun printPolicyUsage() {
        println(
            """
            Policy commands:
              validate [path]   Validate a YAML policy file
              print-example     Print a sample policy file
            """.trimIndent(),
        )
    }

    private fun runProviderCommand(args: List<String>): Int {
        val parsed = parseRunArguments(args) ?: return 1
        val policyPath = parsed.policyPath ?: Path("llm-policy.yaml")

        if (!policyPath.exists()) {
            System.err.println("Policy file does not exist: $policyPath")
            return 2
        }

        val policy = try {
            policyLoader.load(policyPath)
        } catch (error: PolicyValidationException) {
            System.err.println("Policy validation failed: ${error.message}")
            return 2
        }

        val prompt = when {
            parsed.prompt != null -> GuardPrompt(parsed.prompt)
            parsed.promptFile != null -> GuardPrompt(Files.readString(parsed.promptFile), parsed.promptFile.fileName.toString())
            else -> null
        }

        val result = guardEngine.evaluate(
            policy = policy,
            request = GuardRequest(
                projectRoot = Path("").toAbsolutePath(),
                prompt = prompt,
                attachments = parsed.attachments,
            ),
        )

        printGuardSummary(result)

        if (result.isBlocked) {
            System.err.println("Execution blocked by policy.")
            return 3
        }

        if (result.requiresApproval && !parsed.approve) {
            System.err.println("Execution requires approval. Re-run with --approve to continue.")
            return 4
        }

        val stage = stageSanitizedContext(result)
        System.err.println("Staged prompt and attachments in ${stage.root}")

        if (parsed.dryRun) {
            return 0
        }

        val invocation = buildInvocation(parsed, stage) ?: return 1
        return executeInvocation(invocation, policyPath, stage)
    }

    private fun buildInvocation(
        parsed: RunCommandOptions,
        stage: StagedContext,
    ): PreparedInvocation? {
        val adapterId = parsed.adapterId
        if (adapterId == null) {
            if (parsed.providerCommand.isEmpty()) {
                System.err.println("Provider command is required unless --dry-run is used.")
                return null
            }

            return PreparedInvocation(
                executable = parsed.providerCommand.first(),
                arguments = parsed.providerCommand.drop(1),
                workingDirectory = stage.root,
                stdinPayload = if (parsed.pipePromptToStdin && stage.promptFile != null) {
                    Files.readString(stage.promptFile)
                } else {
                    null
                },
                environment = emptyMap(),
            )
        }

        val adapter = adapters[adapterId]
        if (adapter == null) {
            System.err.println("Unknown adapter: $adapterId")
            System.err.println("Available adapters: ${adapters.keys.sorted().joinToString()}")
            return null
        }

        val executable = parsed.providerCommand.firstOrNull() ?: adapter.defaultExecutable
        val providerArguments = if (parsed.providerCommand.isEmpty()) emptyList() else parsed.providerCommand.drop(1)
        val prompt = stage.promptFile?.let { promptFile ->
            PromptPayload(
                content = Files.readString(promptFile),
                origin = PromptOrigin.FILE,
            )
        }

        return adapter.prepareInvocation(
            InvocationRequest(
                executable = executable,
                arguments = providerArguments,
                workingDirectory = stage.root,
                prompt = prompt,
                attachmentsDir = stage.attachmentsDir,
                attachments = stage.stagedAttachmentPaths.map(stage.root::resolve),
            ),
        )
    }

    private fun executeInvocation(
        invocation: PreparedInvocation,
        policyPath: Path,
        stage: StagedContext,
    ): Int {
        val processBuilder = ProcessBuilder(listOf(invocation.executable) + invocation.arguments)
            .directory(invocation.workingDirectory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)

        if (invocation.stdinPayload != null) {
            processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE)
        } else {
            processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT)
        }

        val environment = processBuilder.environment()
        environment["LLM_GUARD_POLICY"] = policyPath.toAbsolutePath().toString()
        environment["LLM_GUARD_STAGE_DIR"] = stage.root.toString()
        environment["LLM_GUARD_ATTACHMENTS_DIR"] = stage.attachmentsDir.toString()
        environment["LLM_GUARD_ATTACHMENT_COUNT"] = stage.attachmentCount.toString()
        stage.promptFile?.let { environment["LLM_GUARD_PROMPT_FILE"] = it.toString() }
        environment.putAll(invocation.environment)

        val process = processBuilder.start()
        if (invocation.stdinPayload != null) {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(invocation.stdinPayload)
            }
        }

        return process.waitFor()
    }

    private fun parseRunArguments(args: List<String>): RunCommandOptions? {
        var index = 0
        var policyPath: Path? = null
        var prompt: String? = null
        var promptFile: Path? = null
        val attachments = mutableListOf<Path>()
        var approve = false
        var dryRun = false
        var pipePromptToStdin = false
        var adapterId: String? = null

        while (index < args.size) {
            when (val arg = args[index]) {
                "--" -> {
                    return RunCommandOptions(
                        policyPath = policyPath,
                        prompt = prompt,
                        promptFile = promptFile,
                        attachments = attachments,
                        approve = approve,
                        dryRun = dryRun,
                        pipePromptToStdin = pipePromptToStdin,
                        adapterId = adapterId,
                        providerCommand = args.drop(index + 1),
                    )
                }
                "--adapter" -> {
                    adapterId = args.getOrNull(index + 1) ?: return missingValue(arg)
                    index += 2
                }
                "--policy" -> {
                    policyPath = args.getOrNull(index + 1)?.let(::Path) ?: return missingValue(arg)
                    index += 2
                }
                "--prompt" -> {
                    prompt = args.getOrNull(index + 1) ?: return missingValue(arg)
                    index += 2
                }
                "--prompt-file" -> {
                    promptFile = args.getOrNull(index + 1)?.let(::Path) ?: return missingValue(arg)
                    index += 2
                }
                "--attach" -> {
                    attachments.add(args.getOrNull(index + 1)?.let(::Path) ?: return missingValue(arg))
                    index += 2
                }
                "--approve" -> {
                    approve = true
                    index += 1
                }
                "--dry-run" -> {
                    dryRun = true
                    index += 1
                }
                "--pipe-prompt-to-stdin" -> {
                    pipePromptToStdin = true
                    index += 1
                }
                else -> {
                    System.err.println("Unknown run option: $arg")
                    printRunUsage()
                    return null
                }
            }
        }

        return RunCommandOptions(
            policyPath = policyPath,
            prompt = prompt,
            promptFile = promptFile,
            attachments = attachments,
            approve = approve,
            dryRun = dryRun,
            pipePromptToStdin = pipePromptToStdin,
            adapterId = adapterId,
            providerCommand = emptyList(),
        )
    }

    private fun missingValue(argument: String): RunCommandOptions? {
        System.err.println("Missing value for $argument")
        printRunUsage()
        return null
    }

    private fun printRunUsage() {
        println(
            """
            Run command:
              llm-guard run [options] -- <provider command>

            Options:
              --adapter <id>                  Provider adapter, for example gemini-cli
              --policy <path>                Policy file, defaults to llm-policy.yaml
              --prompt <text>                Inline prompt to sanitize
              --prompt-file <path>           Prompt file to sanitize
              --attach <path>                Attachment to sanitize, may be repeated
              --approve                      Continue when confirm rules match
              --dry-run                      Evaluate and stage without running provider
              --pipe-prompt-to-stdin         Send sanitized prompt to provider stdin
            """.trimIndent(),
        )
    }

    private fun printGuardSummary(result: GuardResult) {
        System.err.println("Guard summary:")
        result.prompt?.let { prompt ->
            System.err.println("  Prompt: ${if (prompt.wasRedacted) "redacted" else "unchanged"}")
        }

        if (result.attachments.isEmpty()) {
            System.err.println("  Attachments: none")
        } else {
            System.err.println("  Attachments:")
            result.attachments.forEach { attachment ->
                System.err.println("    - ${attachment.relativePath.invariantSeparatorsPathString}: ${attachment.disposition.label}")
            }
        }

        if (result.findings.isEmpty()) {
            System.err.println("  Findings: none")
        } else {
            System.err.println("  Findings:")
            result.findings.forEach { finding ->
                val source = when (finding.source) {
                    FindingSource.POLICY_RULE -> finding.ruleId ?: "policy"
                    FindingSource.SECRET_DETECTOR -> "secret-detector"
                }
                System.err.println("    - [${finding.action.name.lowercase()}] ${finding.target} via $source: ${finding.message}")
            }
        }
    }

    private fun stageSanitizedContext(result: GuardResult): StagedContext {
        val root = createTempDirectory("llm-guard-stage-")
        val attachmentsDir = root / "attachments"
        attachmentsDir.createDirectories()
        val promptFile = result.prompt?.let {
            val path = root / "prompt.txt"
            Files.writeString(path, it.content)
            path
        }

        var attachmentCount = 0
        val stagedAttachmentPaths = mutableListOf<Path>()
        result.attachments.forEach { attachment ->
            if (attachment.disposition == AttachmentDisposition.BLOCKED) {
                return@forEach
            }

            val destination = attachmentsDir.resolve(attachment.relativePath).normalize()
            destination.parent?.createDirectories()

            when (attachment.disposition) {
                AttachmentDisposition.COPY_ORIGINAL -> Files.copy(
                    attachment.originalPath,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                AttachmentDisposition.WRITE_SANITIZED_TEXT -> Files.writeString(destination, attachment.sanitizedText ?: "")
                AttachmentDisposition.BLOCKED -> Unit
            }
            attachmentCount += 1
            stagedAttachmentPaths.add(root.relativize(destination))
        }

        return StagedContext(
            root = root,
            promptFile = promptFile,
            attachmentsDir = attachmentsDir,
            attachmentCount = attachmentCount,
            stagedAttachmentPaths = stagedAttachmentPaths,
        )
    }
}

private val policyExample = """
version: 1
defaults:
  on_unmatched: allow
  redact_replacement: "<redacted>"
detectors:
  secrets:
    enabled: true
    action: redact
  kotlin_symbols:
    enabled: true
rules:
  - id: block-secret-files
    description: Block common Android and credential files.
    match:
      paths:
        - "**/local.properties"
        - "**/gradle.properties"
        - "**/*.jks"
        - "**/google-services.json"
    action:
      type: block
  - id: redact-sensitive-symbols
    description: Remove proprietary billing symbols before the prompt leaves the machine.
    match:
      kotlin:
        packages:
          - "com.company.billing.internal"
        classes:
          - "PaymentSignatureGenerator"
        functions:
          - "generateHmac"
        annotations:
          - "SensitiveForLLM"
    action:
      type: redact
      replacement: "<redacted:kotlin-symbol>"
""".trimIndent()

private data class RunCommandOptions(
    val policyPath: Path?,
    val prompt: String?,
    val promptFile: Path?,
    val attachments: List<Path>,
    val approve: Boolean,
    val dryRun: Boolean,
    val pipePromptToStdin: Boolean,
    val adapterId: String?,
    val providerCommand: List<String>,
)

private data class StagedContext(
    val root: Path,
    val promptFile: Path?,
    val attachmentsDir: Path,
    val attachmentCount: Int,
    val stagedAttachmentPaths: List<Path>,
)

private val AttachmentDisposition.label: String
    get() = when (this) {
        AttachmentDisposition.COPY_ORIGINAL -> "allowed"
        AttachmentDisposition.WRITE_SANITIZED_TEXT -> "redacted"
        AttachmentDisposition.BLOCKED -> "blocked"
    }
