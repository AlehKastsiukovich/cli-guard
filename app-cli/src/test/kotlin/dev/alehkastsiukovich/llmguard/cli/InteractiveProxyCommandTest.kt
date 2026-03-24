package dev.alehkastsiukovich.llmguard.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class InteractiveProxyCommandTest {
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
    fun `parses generic proxy arguments`() {
        val parsed = parseInteractiveProxyArguments(
            listOf(
                "--guard-dry-run",
                "--guard-policy",
                "llm-policy.yaml",
                "--guard-real-gemini",
                "/usr/local/bin/gemini",
                "-p",
                "review this",
            ),
        )

        assertEquals(true, parsed!!.guardDryRun)
        assertEquals(Path.of("llm-policy.yaml"), parsed.guardPolicyPath)
        assertEquals("/usr/local/bin/gemini", parsed.guardRealExecutable)
        assertEquals(listOf("-p", "review this"), parsed.providerArguments)
    }
}
