package dev.alehkastsiukovich.llmguard.adapter.gemini

import dev.alehkastsiukovich.llmguard.adapter.InteractivePromptArgument
import dev.alehkastsiukovich.llmguard.adapter.InteractiveProviderAdapter
import dev.alehkastsiukovich.llmguard.adapter.InvocationRequest
import dev.alehkastsiukovich.llmguard.adapter.ParsedInteractiveArguments
import dev.alehkastsiukovich.llmguard.adapter.PreparedInvocation
import dev.alehkastsiukovich.llmguard.adapter.ProviderAdapter
import dev.alehkastsiukovich.llmguard.adapter.StagedWorkspaceDescriptor
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString

class GeminiCliAdapter : ProviderAdapter, InteractiveProviderAdapter {
    override val id: String = "gemini-cli"
    override val defaultExecutable: String = "gemini"

    override fun prepareInvocation(request: InvocationRequest): PreparedInvocation {
        val promptPayload = requireNotNull(request.prompt) {
            "Gemini CLI adapter requires a prompt. Use --prompt or --prompt-file."
        }
        require(request.arguments.none { it == "-p" || it == "--prompt" }) {
            "Gemini CLI adapter manages -p internally. Remove prompt flags from provider arguments."
        }

        val prompt = buildPrompt(
            basePrompt = promptPayload.content,
            attachmentPaths = request.attachments.map { path ->
                request.workingDirectory.relativize(path).invariantSeparatorsPathString
            },
        )

        return PreparedInvocation(
            executable = request.executable.ifBlank { defaultExecutable },
            arguments = listOf("-p", prompt) + request.arguments,
            workingDirectory = request.workingDirectory,
        )
    }

    override fun parseArguments(
        args: List<String>,
        currentWorkingDirectory: Path,
    ): ParsedInteractiveArguments? {
        var index = 0
        var prompt: InteractivePromptArgument? = null
        val includeDirectories = mutableListOf<Path>()

        while (index < args.size) {
            when (val arg = args[index]) {
                "-p",
                "--prompt",
                -> {
                    val value = args.getOrNull(index + 1) ?: return missingGeminiValue(arg)
                    prompt = InteractivePromptArgument(flag = arg, index = index + 1, value = value)
                    index += 2
                }
                "--include-directories" -> {
                    val value = args.getOrNull(index + 1) ?: return missingGeminiValue(arg)
                    includeDirectories += parseIncludeDirectories(value, currentWorkingDirectory)
                    index += 2
                }
                else -> {
                    when {
                        arg.startsWith("--prompt=") -> {
                            prompt = InteractivePromptArgument(
                                flag = "--prompt",
                                index = index,
                                value = arg.substringAfter("="),
                            )
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

        return ParsedInteractiveArguments(
            prompt = prompt,
            includeDirectories = includeDirectories.distinct(),
        )
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

    override fun shouldSanitizeInteractiveInput(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            return false
        }

        return !trimmed.startsWith("/")
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

            Use only the sanitized files listed below if you need repository context:
            $fileList

            The current working directory already points to the staged sanitized workspace.
        """.trimIndent()
    }

    private fun rewriteIncludeDirectories(
        rawValue: String,
        originalWorkingDirectory: Path,
        stagedWorkspace: StagedWorkspaceDescriptor,
    ): String {
        val originalPaths = parseIncludeDirectories(rawValue, originalWorkingDirectory)
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

    private fun missingGeminiValue(argument: String): ParsedInteractiveArguments? {
        System.err.println("Missing value for Gemini argument $argument")
        return null
    }
}
