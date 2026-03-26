package dev.alehkastsiukovich.llmguard.adapter

import java.nio.file.Path

interface InteractiveProviderAdapter {
    val id: String
    val defaultExecutable: String

    fun parseArguments(
        args: List<String>,
        currentWorkingDirectory: Path,
    ): ParsedInteractiveArguments?

    fun rewriteArguments(
        originalArguments: List<String>,
        parsedArguments: ParsedInteractiveArguments,
        sanitizedPrompt: String?,
        originalWorkingDirectory: Path,
        stagedWorkspace: StagedWorkspaceDescriptor,
    ): List<String>

    fun shouldSanitizeInteractiveInput(line: String): Boolean = line.isNotBlank()
}

enum class ProviderLaunchMode {
    INTERACTIVE_PROXY,
    DIRECT_PROCESS,
}

data class ParsedInteractiveArguments(
    val launchMode: ProviderLaunchMode,
    val prompt: InteractivePromptArgument?,
    val includeDirectories: List<Path> = emptyList(),
    val includeFiles: List<Path> = emptyList(),
    val requestedWorkingDirectory: Path? = null,
)

data class InteractivePromptArgument(
    val flag: String,
    val index: Int,
    val value: String,
)

data class StagedWorkspaceDescriptor(
    val root: Path,
    val projectRoot: Path,
    val workingDirectory: Path,
    val stagedExternalIncludes: Map<Path, Path>,
    val stagedExternalFiles: Map<Path, Path>,
)
