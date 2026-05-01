package dev.alehkastsiukovich.llmguard.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CommandTextSanitizersTest {
    @Test
    fun `privacy filter backend reads redacted json output`() {
        val script = createExecutableScript(
            """
            #!/bin/sh
            cat >/dev/null
            printf '%s' '{"redacted_text":"Hello <redacted>","summary":"Detected PII","detected_spans":[{"start":6,"end":10}]}'
            """.trimIndent(),
        )

        val backend = PrivacyFilterTextSanitizerBackend(
            environment = mapOf("LLM_GUARD_PRIVACY_FILTER_BIN" to script.toString()),
        )

        val result = backend.sanitize(
            TextSanitizerRequest(
                target = "prompt:inline",
                content = "Hello John",
            ),
        )

        assertNotNull(result)
        assertEquals("Hello <redacted>", result!!.redactedText)
        assertEquals(listOf("John"), result.matchedValues)
        assertEquals("Detected PII", result.summary)
    }

    @Test
    fun `gitleaks backend reads matched secret values`() {
        val script = createExecutableScript(
            """
            #!/bin/sh
            cat >/dev/null
            printf '%s' '[{"Secret":"1234567890abcdef"}]'
            """.trimIndent(),
        )

        val backend = GitleaksTextSanitizerBackend(
            environment = mapOf("LLM_GUARD_GITLEAKS_BIN" to script.toString()),
        )

        val result = backend.sanitize(
            TextSanitizerRequest(
                target = "prompt:inline",
                content = "token = \"1234567890abcdef\"",
            ),
        )

        assertNotNull(result)
        assertEquals(listOf("1234567890abcdef"), result!!.matchedValues)
        assertEquals("Detected 1 secret-like value(s) via gitleaks", result.summary)
    }

    private fun createExecutableScript(content: String) = Files.createTempFile("llm-guard-test", ".sh").also { path ->
        Files.writeString(path, content + "\n")
        path.toFile().setExecutable(true)
    }
}
