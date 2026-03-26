package dev.alehkastsiukovich.llmguard.cli

import dev.alehkastsiukovich.llmguard.adapter.ProviderAdapter
import dev.alehkastsiukovich.llmguard.adapter.codex.CodexCliAdapter
import dev.alehkastsiukovich.llmguard.adapter.gemini.GeminiCliAdapter
import dev.alehkastsiukovich.llmguard.guard.GuardEngine
import dev.alehkastsiukovich.llmguard.guard.WorkspaceStager
import dev.alehkastsiukovich.llmguard.policy.YamlPolicyLoader

internal object AppBootstrap {
    fun create(environment: Map<String, String> = System.getenv()): CliApplication {
        val policyLoader = YamlPolicyLoader()
        val guardEngine = GuardEngine()
        val workspaceStager = WorkspaceStager(guardEngine)
        val geminiAdapter = GeminiCliAdapter()
        val codexAdapter = CodexCliAdapter()
        val adapters: Map<String, ProviderAdapter> = listOf<ProviderAdapter>(
            geminiAdapter,
            codexAdapter,
        ).associateBy { it.id }
        val interactiveCommands = mapOf(
            "gemini" to InteractiveProxyCommand(
                providerDisplayName = "gemini",
                adapter = geminiAdapter,
                realExecutableEnvVar = "LLM_GUARD_REAL_GEMINI",
                realExecutableFlags = setOf(
                    "--guard-real-executable",
                    "--guard-real-gemini",
                ),
                policyLoader = policyLoader,
                guardEngine = guardEngine,
                workspaceStager = workspaceStager,
                sessionRunner = PtySessionRunner(),
                environment = environment,
            ),
            "codex" to InteractiveProxyCommand(
                providerDisplayName = "codex",
                adapter = codexAdapter,
                realExecutableEnvVar = "LLM_GUARD_REAL_CODEX",
                realExecutableFlags = setOf(
                    "--guard-real-executable",
                    "--guard-real-codex",
                ),
                policyLoader = policyLoader,
                guardEngine = guardEngine,
                workspaceStager = workspaceStager,
                sessionRunner = PtySessionRunner(),
                environment = environment,
            ),
        )

        return CliApplication(
            policyLoader = policyLoader,
            guardEngine = guardEngine,
            adapters = adapters,
            interactiveCommands = interactiveCommands,
        )
    }
}
