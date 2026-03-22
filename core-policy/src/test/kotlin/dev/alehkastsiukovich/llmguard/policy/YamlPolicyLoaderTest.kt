package dev.alehkastsiukovich.llmguard.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class YamlPolicyLoaderTest {
    private val loader = YamlPolicyLoader()

    @Test
    fun `loads valid policy`() {
        val file = Files.createTempFile("llm-guard-policy", ".yaml")
        Files.writeString(
            file,
            """
            version: 1
            rules:
              - id: block-secret-files
                match:
                  paths:
                    - "**/*.jks"
                action:
                  type: block
            """.trimIndent(),
        )

        val policy = loader.load(file)

        assertEquals(1, policy.version)
        assertEquals(1, policy.rules.size)
        assertEquals("block-secret-files", policy.rules.single().id)
    }

    @Test
    fun `rejects duplicate ids`() {
        val file = Files.createTempFile("llm-guard-policy", ".yaml")
        Files.writeString(
            file,
            """
            version: 1
            rules:
              - id: duplicate
                match:
                  paths:
                    - "**/*.jks"
                action:
                  type: block
              - id: duplicate
                match:
                  paths:
                    - "**/*.pem"
                action:
                  type: block
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            loader.load(file)
        }
    }
}

