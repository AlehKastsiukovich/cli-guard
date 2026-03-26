package dev.alehkastsiukovich.llmguard.adapter.codex

import dev.alehkastsiukovich.llmguard.adapter.InteractivePromptArgument
import dev.alehkastsiukovich.llmguard.adapter.InteractiveProviderAdapter
import dev.alehkastsiukovich.llmguard.adapter.InvocationRequest
import dev.alehkastsiukovich.llmguard.adapter.ParsedInteractiveArguments
import dev.alehkastsiukovich.llmguard.adapter.PreparedInvocation
import dev.alehkastsiukovich.llmguard.adapter.PromptOrigin
import dev.alehkastsiukovich.llmguard.adapter.ProviderLaunchMode
import dev.alehkastsiukovich.llmguard.adapter.ProviderAdapter
import dev.alehkastsiukovich.llmguard.adapter.StagedWorkspaceDescriptor
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString

class CodexCliAdapter : ProviderAdapter, InteractiveProviderAdapter {
    override val id: String = "codex-cli"
    override val defaultExecutable: String = "codex"

    override fun prepareInvocation(request: InvocationRequest): PreparedInvocation {
        val promptPayload = requireNotNull(request.prompt) {
            "Codex CLI adapter requires a prompt. Use --prompt or --prompt-file."
        }
        val prompt = buildPrompt(
            basePrompt = promptPayload.content,
            attachmentPaths = request.attachments.map { attachment ->
                request.workingDirectory.relativize(attachment).invariantSeparatorsPathString
            },
        )

        val originalArguments = request.arguments
        val subcommand = originalArguments.firstOrNull()
            ?.takeIf { it in supportedHeadlessSubcommands }
            ?: "exec"
        val passthroughArguments = when {
            subcommand == "exec" && originalArguments.firstOrNull() == "exec" -> originalArguments.drop(1)
            subcommand == "review" && originalArguments.firstOrNull() == "review" -> originalArguments.drop(1)
            else -> originalArguments
        }

        return PreparedInvocation(
            executable = request.executable.ifBlank { defaultExecutable },
            arguments = listOf(subcommand) + passthroughArguments + listOf(promptArgument(promptPayload.origin, prompt)),
            workingDirectory = request.workingDirectory,
        )
    }

    override fun parseArguments(
        args: List<String>,
        currentWorkingDirectory: Path,
    ): ParsedInteractiveArguments? = when (args.firstOrNull()) {
        "exec" -> parseExecArguments(args, currentWorkingDirectory)
        "review" -> parseReviewArguments(args, currentWorkingDirectory)
        else -> parseInteractiveArguments(args, currentWorkingDirectory)
    }

    override fun rewriteArguments(
        originalArguments: List<String>,
        parsedArguments: ParsedInteractiveArguments,
        sanitizedPrompt: String?,
        originalWorkingDirectory: Path,
        stagedWorkspace: StagedWorkspaceDescriptor,
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
                arg == "-C" || arg == "--cd" || arg == "--add-dir" || arg == "-i" || arg == "--image" -> {
                    val rawValue = rewritten.getOrNull(index + 1) ?: break
                    rewritten[index + 1] = rewritePathValue(
                        rawValue = rawValue,
                        originalWorkingDirectory = originalWorkingDirectory,
                        stagedWorkspace = stagedWorkspace,
                    )
                    index += 2
                }
                arg.startsWith("--cd=") -> {
                    rewritten[index] = "--cd=" + rewritePathValue(
                        rawValue = arg.substringAfter("="),
                        originalWorkingDirectory = originalWorkingDirectory,
                        stagedWorkspace = stagedWorkspace,
                    )
                    index += 1
                }
                arg.startsWith("--add-dir=") -> {
                    rewritten[index] = "--add-dir=" + rewritePathValue(
                        rawValue = arg.substringAfter("="),
                        originalWorkingDirectory = originalWorkingDirectory,
                        stagedWorkspace = stagedWorkspace,
                    )
                    index += 1
                }
                arg.startsWith("--image=") -> {
                    rewritten[index] = "--image=" + rewritePathValue(
                        rawValue = arg.substringAfter("="),
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

    override fun shouldSanitizeInteractiveInput(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            return false
        }

        return !trimmed.startsWith("/")
    }

    private fun parseInteractiveArguments(
        args: List<String>,
        currentWorkingDirectory: Path,
    ): ParsedInteractiveArguments? = parseCodexArguments(
        args = args,
        currentWorkingDirectory = currentWorkingDirectory,
        launchMode = ProviderLaunchMode.INTERACTIVE_PROXY,
        positionalPromptMode = PositionalPromptMode.TOP_LEVEL,
    )

    private fun parseExecArguments(
        args: List<String>,
        currentWorkingDirectory: Path,
    ): ParsedInteractiveArguments? = parseCodexArguments(
        args = args,
        currentWorkingDirectory = currentWorkingDirectory,
        launchMode = ProviderLaunchMode.DIRECT_PROCESS,
        positionalPromptMode = PositionalPromptMode.EXEC,
    )

    private fun parseReviewArguments(
        args: List<String>,
        currentWorkingDirectory: Path,
    ): ParsedInteractiveArguments? = parseCodexArguments(
        args = args,
        currentWorkingDirectory = currentWorkingDirectory,
        launchMode = ProviderLaunchMode.DIRECT_PROCESS,
        positionalPromptMode = PositionalPromptMode.REVIEW,
    )

    private fun parseCodexArguments(
        args: List<String>,
        currentWorkingDirectory: Path,
        launchMode: ProviderLaunchMode,
        positionalPromptMode: PositionalPromptMode,
    ): ParsedInteractiveArguments? {
        var index = if (positionalPromptMode == PositionalPromptMode.TOP_LEVEL) 0 else 1
        var prompt: InteractivePromptArgument? = null
        var requestedWorkingDirectory: Path? = null
        val includeDirectories = mutableListOf<Path>()
        val includeFiles = mutableListOf<Path>()

        while (index < args.size) {
            when (val arg = args[index]) {
                "-C",
                "--cd",
                -> {
                    val value = args.getOrNull(index + 1) ?: return missingValue(arg)
                    requestedWorkingDirectory = resolvePath(value, currentWorkingDirectory)
                    index += 2
                }
                "--add-dir" -> {
                    val value = args.getOrNull(index + 1) ?: return missingValue(arg)
                    includeDirectories.add(resolvePath(value, currentWorkingDirectory))
                    index += 2
                }
                "-i",
                "--image",
                -> {
                    val value = args.getOrNull(index + 1) ?: return missingValue(arg)
                    includeFiles.add(resolvePath(value, currentWorkingDirectory))
                    index += 2
                }
                else -> {
                    when {
                        arg.startsWith("--cd=") -> {
                            requestedWorkingDirectory = resolvePath(arg.substringAfter("="), currentWorkingDirectory)
                            index += 1
                        }
                        arg.startsWith("--add-dir=") -> {
                            includeDirectories.add(resolvePath(arg.substringAfter("="), currentWorkingDirectory))
                            index += 1
                        }
                        arg.startsWith("--image=") -> {
                            includeFiles.add(resolvePath(arg.substringAfter("="), currentWorkingDirectory))
                            index += 1
                        }
                        arg.startsWith("-") -> index += 1
                        positionalPromptMode.acceptsPositionalPrompt(arg) && prompt == null -> {
                            prompt = InteractivePromptArgument(
                                flag = "positional",
                                index = index,
                                value = arg,
                            )
                            index += 1
                        }
                        else -> index += 1
                    }
                }
            }
        }

        return ParsedInteractiveArguments(
            launchMode = launchMode,
            prompt = prompt,
            includeDirectories = includeDirectories.distinct(),
            includeFiles = includeFiles.distinct(),
            requestedWorkingDirectory = requestedWorkingDirectory,
        )
    }

    private fun rewritePathValue(
        rawValue: String,
        originalWorkingDirectory: Path,
        stagedWorkspace: StagedWorkspaceDescriptor,
    ): String {
        val originalPath = resolvePath(rawValue, originalWorkingDirectory)
        return mapToStagedPath(originalPath, stagedWorkspace).toString()
    }

    private fun mapToStagedPath(
        originalPath: Path,
        stagedWorkspace: StagedWorkspaceDescriptor,
    ): Path {
        if (originalPath.startsWith(stagedWorkspace.projectRoot)) {
            return stagedWorkspace.root.resolve(stagedWorkspace.projectRoot.relativize(originalPath))
        }

        stagedWorkspace.stagedExternalIncludes.entries.firstOrNull { (externalRoot, _) ->
            originalPath.startsWith(externalRoot)
        }?.let { (externalRoot, stagedRoot) ->
            return stagedRoot.resolve(externalRoot.relativize(originalPath))
        }

        stagedWorkspace.stagedExternalFiles[originalPath]?.let { return it }

        return stagedWorkspace.root.resolve("_unmapped").resolve(
            originalPath.fileName?.toString() ?: "external-path",
        )
    }

    private fun buildPrompt(
        basePrompt: String,
        attachmentPaths: List<String>,
    ): String {
        if (attachmentPaths.isEmpty()) {
            return basePrompt
        }

        val fileList = attachmentPaths.joinToString(separator = "\n") { path -> "- $path" }
        return """
            $basePrompt

            Use only the sanitized files listed below if you need additional context:
            $fileList

            The current working directory already points to the staged sanitized workspace.
        """.trimIndent()
    }

    private fun promptArgument(
        origin: PromptOrigin,
        prompt: String,
    ): String = when (origin) {
        PromptOrigin.STDIN -> "-"
        PromptOrigin.INLINE,
        PromptOrigin.FILE,
        -> prompt
    }

    private fun missingValue(
        argument: String,
    ): ParsedInteractiveArguments? {
        System.err.println("Missing value for Codex argument $argument")
        return null
    }

    private fun resolvePath(
        rawPath: String,
        currentWorkingDirectory: Path,
    ): Path {
        val path = Path(rawPath)
        return if (path.isAbsolute) path.normalize() else currentWorkingDirectory.resolve(path).normalize()
    }

    private enum class PositionalPromptMode {
        TOP_LEVEL,
        EXEC,
        REVIEW,
        ;

        fun acceptsPositionalPrompt(value: String): Boolean = when (this) {
            TOP_LEVEL,
            REVIEW,
            -> value != "-"
            EXEC -> value != "-" && value !in execNestedCommands
        }
    }

    private companion object {
        private val supportedHeadlessSubcommands = setOf(
            "exec",
            "review",
        )

        private val execNestedCommands = setOf(
            "help",
            "resume",
            "review",
        )
    }
}
