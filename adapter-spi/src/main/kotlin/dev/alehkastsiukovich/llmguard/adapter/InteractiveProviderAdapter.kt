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

data class ParsedInteractiveArguments(
    val prompt: InteractivePromptArgument?,
    val includeDirectories: List<Path>,
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
)
