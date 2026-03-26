package dev.alehkastsiukovich.llmguard.adapter.codex

import dev.alehkastsiukovich.llmguard.adapter.InteractivePromptArgument
import dev.alehkastsiukovich.llmguard.adapter.InvocationRequest
import dev.alehkastsiukovich.llmguard.adapter.ParsedInteractiveArguments
import dev.alehkastsiukovich.llmguard.adapter.PromptOrigin
import dev.alehkastsiukovich.llmguard.adapter.PromptPayload
import dev.alehkastsiukovich.llmguard.adapter.ProviderLaunchMode
import dev.alehkastsiukovich.llmguard.adapter.StagedWorkspaceDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CodexCliAdapterTest {
    private val adapter = CodexCliAdapter()

    @Test
    fun `builds headless codex exec invocation with staged attachments`() {
        val invocation = adapter.prepareInvocation(
            InvocationRequest(
                executable = "codex",
                arguments = listOf("--json"),
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

        assertEquals(listOf("exec", "--json"), invocation.arguments.take(2))
        assertTrue(invocation.arguments.last().contains("Explain the billing implementation"))
        assertTrue(invocation.arguments.last().contains("attachments/Payment.kt"))
        assertTrue(invocation.arguments.last().contains("attachments/Fraud.kt"))
    }

    @Test
    fun `parses top level codex prompt and path arguments`() {
        val parsed = adapter.parseArguments(
            args = listOf(
                "--add-dir",
                "../shared",
                "-C",
                "app",
                "-i",
                "docs/diagram.png",
                "review this feature",
            ),
            currentWorkingDirectory = Path.of("/workspace/project"),
        )!!

        assertEquals(ProviderLaunchMode.INTERACTIVE_PROXY, parsed.launchMode)
        assertEquals("review this feature", parsed.prompt!!.value)
        assertEquals(listOf(Path.of("/workspace/shared")), parsed.includeDirectories)
        assertEquals(listOf(Path.of("/workspace/project/docs/diagram.png")), parsed.includeFiles)
        assertEquals(Path.of("/workspace/project/app"), parsed.requestedWorkingDirectory)
    }

    @Test
    fun `parses codex exec prompt as direct process`() {
        val parsed = adapter.parseArguments(
            args = listOf("exec", "--json", "review billing"),
            currentWorkingDirectory = Path.of("/workspace/project"),
        )!!

        assertEquals(ProviderLaunchMode.DIRECT_PROCESS, parsed.launchMode)
        assertEquals("review billing", parsed.prompt!!.value)
    }

    @Test
    fun `rewrites codex path arguments against staged workspace`() {
        val rewritten = adapter.rewriteArguments(
            originalArguments = listOf(
                "--add-dir",
                "/workspace/shared",
                "-C",
                "/workspace/project/app",
                "--image",
                "/tmp/screenshot.png",
                "unsafe",
            ),
            parsedArguments = ParsedInteractiveArguments(
                launchMode = ProviderLaunchMode.INTERACTIVE_PROXY,
                prompt = InteractivePromptArgument(flag = "positional", index = 6, value = "unsafe"),
                includeDirectories = listOf(Path.of("/workspace/shared")),
                includeFiles = listOf(Path.of("/tmp/screenshot.png")),
                requestedWorkingDirectory = Path.of("/workspace/project/app"),
            ),
            sanitizedPrompt = "safe",
            originalWorkingDirectory = Path.of("/workspace/project"),
            stagedWorkspace = StagedWorkspaceDescriptor(
                root = Path.of("/stage"),
                projectRoot = Path.of("/workspace/project"),
                workingDirectory = Path.of("/stage/app"),
                stagedExternalIncludes = mapOf(
                    Path.of("/workspace/shared") to Path.of("/stage/_external/1-shared"),
                ),
                stagedExternalFiles = mapOf(
                    Path.of("/tmp/screenshot.png") to Path.of("/stage/_external_files/1-screenshot.png"),
                ),
            ),
        )

        assertEquals("/stage/_external/1-shared", rewritten[1])
        assertEquals("/stage/app", rewritten[3])
        assertEquals("/stage/_external_files/1-screenshot.png", rewritten[5])
        assertEquals("safe", rewritten[6])
    }

    @Test
    fun `does not sanitize slash commands`() {
        assertFalse(adapter.shouldSanitizeInteractiveInput("/help"))
        assertTrue(adapter.shouldSanitizeInteractiveInput("implement feature x"))
    }
}
