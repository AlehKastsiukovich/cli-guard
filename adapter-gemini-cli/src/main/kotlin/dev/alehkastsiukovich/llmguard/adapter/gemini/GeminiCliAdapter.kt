package dev.alehkastsiukovich.llmguard.adapter.gemini

import dev.alehkastsiukovich.llmguard.adapter.InvocationRequest
import dev.alehkastsiukovich.llmguard.adapter.PreparedInvocation
import dev.alehkastsiukovich.llmguard.adapter.ProviderAdapter
import kotlin.io.path.invariantSeparatorsPathString

class GeminiCliAdapter : ProviderAdapter {
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
}
