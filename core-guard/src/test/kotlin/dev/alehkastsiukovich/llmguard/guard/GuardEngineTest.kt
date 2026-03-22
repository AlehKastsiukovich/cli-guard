package dev.alehkastsiukovich.llmguard.guard

import dev.alehkastsiukovich.llmguard.policy.LlmGuardPolicy
import dev.alehkastsiukovich.llmguard.policy.MatchCriteria
import dev.alehkastsiukovich.llmguard.policy.PolicyDefaults
import dev.alehkastsiukovich.llmguard.policy.PolicyRule
import dev.alehkastsiukovich.llmguard.policy.RuleAction
import dev.alehkastsiukovich.llmguard.policy.RuleActionType
import dev.alehkastsiukovich.llmguard.policy.KotlinSymbolCriteria
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class GuardEngineTest {
    private val engine = GuardEngine()

    @Test
    fun `blocks attachment matched by path rule`() {
        val root = Files.createTempDirectory("llm-guard-test")
        val file = root.resolve("secrets/release.jks")
        Files.createDirectories(file.parent)
        Files.writeString(file, "binary-ish")

        val result = engine.evaluate(
            policy = LlmGuardPolicy(
                version = 1,
                rules = listOf(
                    PolicyRule(
                        id = "block-jks",
                        match = MatchCriteria(paths = listOf("**/*.jks")),
                        action = RuleAction(RuleActionType.BLOCK),
                    ),
                ),
            ),
            request = GuardRequest(
                projectRoot = root,
                attachments = listOf(file),
            ),
        )

        assertTrue(result.isBlocked)
        assertEquals(AttachmentDisposition.BLOCKED, result.attachments.single().disposition)
    }

    @Test
    fun `redacts exact kotlin class and function`() {
        val root = Files.createTempDirectory("llm-guard-test")
        val file = root.resolve("src/Payment.kt")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """
            package com.company.billing.internal

            class PaymentSignatureGenerator {
                fun generateHmac(input: String): String {
                    return input.reversed()
                }
            }

            fun keepMe(): String = "ok"
            """.trimIndent(),
        )

        val result = engine.evaluate(
            policy = LlmGuardPolicy(
                version = 1,
                defaults = PolicyDefaults(redactReplacement = "<redacted>"),
                rules = listOf(
                    PolicyRule(
                        id = "redact-symbols",
                        match = MatchCriteria(
                            kotlin = KotlinSymbolCriteria(
                                packages = listOf("com.company.billing.internal"),
                                classes = listOf("PaymentSignatureGenerator"),
                                functions = listOf("generateHmac"),
                            ),
                        ),
                        action = RuleAction(RuleActionType.REDACT, "<redacted:kotlin>"),
                    ),
                ),
            ),
            request = GuardRequest(
                projectRoot = root,
                attachments = listOf(file),
            ),
        )

        val attachment = result.attachments.single()
        assertEquals(AttachmentDisposition.WRITE_SANITIZED_TEXT, attachment.disposition)
        assertTrue(attachment.sanitizedText!!.contains("<redacted:kotlin>"))
        assertTrue(attachment.sanitizedText.contains("keepMe"))
        assertFalse(result.isBlocked)
    }

    @Test
    fun `redacts secrets in prompt`() {
        val result = engine.evaluate(
            policy = LlmGuardPolicy(version = 1),
            request = GuardRequest(
                projectRoot = Files.createTempDirectory("llm-guard-test"),
                prompt = GuardPrompt("""token = "1234567890abcdef""""),
            ),
        )

        assertTrue(result.prompt!!.wasRedacted)
        assertEquals("<redacted>", result.prompt.content.trim())
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `maps external attachment under safe relative path`() {
        val root = Files.createTempDirectory("llm-guard-root")
        val externalRoot = Files.createTempDirectory("llm-guard-external")
        val file = externalRoot.resolve("Payment.kt")
        Files.writeString(file, """fun keepMe(): String = "ok"""")

        val result = engine.evaluate(
            policy = LlmGuardPolicy(version = 1),
            request = GuardRequest(
                projectRoot = root,
                attachments = listOf(file),
            ),
        )

        assertEquals("_external/Payment.kt", result.attachments.single().relativePath.toString())
    }
}
