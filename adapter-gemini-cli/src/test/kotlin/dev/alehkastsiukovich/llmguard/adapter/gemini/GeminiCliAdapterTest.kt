package dev.alehkastsiukovich.llmguard.adapter.gemini

import dev.alehkastsiukovich.llmguard.adapter.ParsedInteractiveArguments
import dev.alehkastsiukovich.llmguard.adapter.InvocationRequest
import dev.alehkastsiukovich.llmguard.adapter.InteractivePromptArgument
import dev.alehkastsiukovich.llmguard.adapter.PromptOrigin
import dev.alehkastsiukovich.llmguard.adapter.PromptPayload
import dev.alehkastsiukovich.llmguard.adapter.StagedWorkspaceDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GeminiCliAdapterTest {
    private val adapter = GeminiCliAdapter()

    @Test
    fun `builds headless gemini invocation with staged attachments`() {
        val invocation = adapter.prepareInvocation(
            InvocationRequest(
                executable = "gemini",
                arguments = listOf("--output-format", "json"),
                workingDirectory = Path.of("/tmp/llm-guard-stage"),
                prompt = PromptPayload(
                    content = "Explain the billing implementation",
                    origin = PromptOrigin.INLINE,
                ),
                attachments = listOf(
                    Path.of("/tmp/llm-guard-stage/attachments/Payment.kt"),
                    Path.of("/tmp/llm-guard-stage/attachments/Fraud.kt"),
                ),
            ),
        )

        assertEquals("gemini", invocation.executable)
        assertEquals("--output-format", invocation.arguments[2])
        assertTrue(invocation.arguments[1].contains("Explain the billing implementation"))
        assertTrue(invocation.arguments[1].contains("attachments/Payment.kt"))
        assertTrue(invocation.arguments[1].contains("attachments/Fraud.kt"))
    }

    @Test
    fun `parses prompt and include directories for interactive proxy`() {
        val parsed = adapter.parseArguments(
            args = listOf("-p", "review this", "--include-directories", "../shared,docs"),
            currentWorkingDirectory = Path.of("/workspace/project"),
        )

        assertEquals("review this", parsed!!.prompt!!.value)
        assertEquals(
            listOf(
                Path.of("/workspace/shared"),
                Path.of("/workspace/project/docs"),
            ),
            parsed.includeDirectories,
        )
    }

    @Test
    fun `rewrites prompt and include directories against staged workspace`() {
        val stageRoot = Path.of("/stage")
        val rewritten = adapter.rewriteArguments(
            originalArguments = listOf("-p", "unsafe", "--include-directories", "/workspace/shared"),
            parsedArguments = ParsedInteractiveArguments(
                prompt = InteractivePromptArgument(flag = "-p", index = 1, value = "unsafe"),
                includeDirectories = listOf(Path.of("/workspace/shared")),
            ),
            sanitizedPrompt = "safe",
            originalWorkingDirectory = Path.of("/workspace/project"),
            stagedWorkspace = StagedWorkspaceDescriptor(
                root = stageRoot,
                projectRoot = Path.of("/workspace/project"),
                workingDirectory = Path.of("/stage/app"),
                stagedExternalIncludes = mapOf(Path.of("/workspace/shared") to Path.of("/stage/_external/1-shared")),
            ),
        )

        assertEquals("safe", rewritten[1])
        assertEquals("../_external/1-shared", rewritten[3])
    }

    @Test
    fun `does not sanitize slash commands`() {
        assertFalse(adapter.shouldSanitizeInteractiveInput("/help"))
        assertTrue(adapter.shouldSanitizeInteractiveInput("implement feature x"))
    }
}
