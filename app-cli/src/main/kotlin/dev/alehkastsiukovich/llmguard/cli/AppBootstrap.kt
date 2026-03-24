package dev.alehkastsiukovich.llmguard.cli

import dev.alehkastsiukovich.llmguard.adapter.ProviderAdapter
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
        val adapters: Map<String, ProviderAdapter> = mapOf(geminiAdapter.id to geminiAdapter)

        return CliApplication(
            policyLoader = policyLoader,
            guardEngine = guardEngine,
            adapters = adapters,
            geminiWrapperCommand = InteractiveProxyCommand(
                providerDisplayName = "gemini",
                adapter = geminiAdapter,
                realExecutableEnvVar = "LLM_GUARD_REAL_GEMINI",
                policyLoader = policyLoader,
                guardEngine = guardEngine,
                workspaceStager = workspaceStager,
                sessionRunner = PtySessionRunner(),
                environment = environment,
            ),
        )
    }
}
