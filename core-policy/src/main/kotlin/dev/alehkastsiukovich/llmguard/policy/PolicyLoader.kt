package dev.alehkastsiukovich.llmguard.policy

import java.nio.file.Path

interface PolicyLoader {
    fun load(path: Path): LlmGuardPolicy
}

