package dev.alehkastsiukovich.llmguard.adapter.gemini

import dev.alehkastsiukovich.llmguard.adapter.InvocationRequest
import dev.alehkastsiukovich.llmguard.adapter.PromptOrigin
import dev.alehkastsiukovich.llmguard.adapter.PromptPayload
import org.junit.jupiter.api.Assertions.assertEquals
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
}

