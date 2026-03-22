package dev.alehkastsiukovich.llmguard.adapter

import java.nio.file.Path

interface ProviderAdapter {
    val id: String
    val defaultExecutable: String

    fun prepareInvocation(request: InvocationRequest): PreparedInvocation
}

data class InvocationRequest(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: Path,
    val prompt: PromptPayload? = null,
    val attachmentsDir: Path? = null,
    val attachments: List<Path> = emptyList(),
)

data class PromptPayload(
    val content: String,
    val origin: PromptOrigin,
)

enum class PromptOrigin {
    INLINE,
    FILE,
    STDIN,
}

data class PreparedInvocation(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: Path,
    val stdinPayload: String? = null,
    val environment: Map<String, String> = emptyMap(),
)

