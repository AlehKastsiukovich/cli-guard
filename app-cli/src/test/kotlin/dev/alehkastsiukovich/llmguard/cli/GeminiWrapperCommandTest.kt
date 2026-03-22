package dev.alehkastsiukovich.llmguard.cli

import dev.alehkastsiukovich.llmguard.guard.StagedWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GeminiWrapperCommandTest {
    @Test
    fun `finds policy by walking up parent directories`() {
        val root = Files.createTempDirectory("llm-guard-policy-root")
        val nested = root.resolve("app/src/main")
        Files.createDirectories(nested)
        val policy = root.resolve("llm-policy.yaml")
        Files.writeString(policy, "version: 1\nrules: []\n")

        val discovered = findPolicyPath(
            explicitPath = null,
            startDirectory = nested,
            environment = emptyMap(),
        )

        assertEquals(policy, discovered)
    }

    @Test
    fun `parses gemini prompt and include directories`() {
        val cwd = Path.of("/workspace/project")
        val parsed = parseGeminiCliArguments(
            args = listOf("-p", "review this", "--include-directories", "../shared,docs"),
            currentWorkingDirectory = cwd,
        )

        assertNotNull(parsed)
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
    fun `rewrites prompt and external include directories against staged workspace`() {
        val stageRoot = Files.createTempDirectory("llm-guard-stage")
        val workingDirectory = stageRoot.resolve("app")
        Files.createDirectories(workingDirectory)
        val externalOriginal = Path.of("/workspace/shared")
        val externalMirror = stageRoot.resolve("_external/1-shared")
        Files.createDirectories(externalMirror)

        val rewritten = rewriteGeminiArguments(
            originalArguments = listOf("-p", "unsafe", "--include-directories", "/workspace/shared"),
            parsedArguments = ParsedGeminiCliArguments(
                prompt = PromptArgument(flag = "-p", index = 1, value = "unsafe"),
                includeDirectories = listOf(externalOriginal),
            ),
            sanitizedPrompt = "safe",
            originalWorkingDirectory = workingDirectory,
            stagedWorkspace = StagedWorkspace(
                root = stageRoot,
                projectRoot = Path.of("/workspace/project"),
                workingDirectory = workingDirectory,
                stagedExternalIncludes = mapOf(externalOriginal to externalMirror),
                mirroredFiles = 0,
                redactedFiles = 0,
                blockedFiles = 0,
                findings = emptyList(),
            ),
        )

        assertEquals("safe", rewritten[1])
        assertEquals("../_external/1-shared", rewritten[3])
    }
}
