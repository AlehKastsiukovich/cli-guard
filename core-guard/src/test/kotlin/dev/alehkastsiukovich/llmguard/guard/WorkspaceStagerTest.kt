package dev.alehkastsiukovich.llmguard.guard

import dev.alehkastsiukovich.llmguard.policy.KotlinSymbolCriteria
import dev.alehkastsiukovich.llmguard.policy.LlmGuardPolicy
import dev.alehkastsiukovich.llmguard.policy.MatchCriteria
import dev.alehkastsiukovich.llmguard.policy.PolicyRule
import dev.alehkastsiukovich.llmguard.policy.RuleAction
import dev.alehkastsiukovich.llmguard.policy.RuleActionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists

class WorkspaceStagerTest {
    @Test
    fun `fully safe subtree is materialized as live pass through directory when supported`() {
        val projectRoot = Files.createTempDirectory("llm-guard-stage-root")
        val sourceFile = projectRoot.resolve("src/Foo.kt")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, "fun keepMe(): String = \"before\"")

        val workspace = WorkspaceStager().stage(
            policy = LlmGuardPolicy(version = 1),
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )

        val stagedDirectory = workspace.root.resolve("src")
        val stagedFile = workspace.root.resolve("src/Foo.kt")
        assumeTrue(Files.isSymbolicLink(stagedDirectory), "Symbolic links are not supported in this environment")

        Files.writeString(sourceFile, "fun keepMe(): String = \"after\"")

        assertEquals("fun keepMe(): String = \"after\"", Files.readString(stagedFile))
        assertEquals(1, workspace.passThroughFiles)
    }

    @Test
    fun `redacted files stay materialized as sanitized copies`() {
        val projectRoot = Files.createTempDirectory("llm-guard-stage-root")
        val sourceFile = projectRoot.resolve("src/Payment.kt")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            package com.company.billing.internal

            class PaymentSignatureGenerator {
                fun generateHmac(input: String): String = input.reversed()
            }
            """.trimIndent(),
        )

        val workspace = WorkspaceStager().stage(
            policy = LlmGuardPolicy(
                version = 1,
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
                        action = RuleAction(
                            type = RuleActionType.REDACT,
                            replacement = "<redacted:kotlin>",
                        ),
                    ),
                ),
            ),
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )

        val stagedFile = workspace.root.resolve("src/Payment.kt")
        assertFalse(Files.isSymbolicLink(stagedFile))
        assertTrue(Files.readString(stagedFile).contains("<redacted:kotlin>"))
        assertEquals(0, workspace.passThroughFiles)
        assertEquals(1, workspace.redactedFiles)
    }

    @Test
    fun `mixed subtree keeps safe files live while materializing only redacted files`() {
        val projectRoot = Files.createTempDirectory("llm-guard-stage-root")
        val safeFile = projectRoot.resolve("src/Safe.kt")
        val redactedFile = projectRoot.resolve("src/Payment.kt")
        Files.createDirectories(safeFile.parent)
        Files.writeString(safeFile, "fun keepMe(): String = \"before\"")
        Files.writeString(
            redactedFile,
            """
            package com.company.billing.internal

            class PaymentSignatureGenerator {
                fun generateHmac(input: String): String = input.reversed()
            }
            """.trimIndent(),
        )

        val workspace = WorkspaceStager().stage(
            policy = LlmGuardPolicy(
                version = 1,
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
                        action = RuleAction(
                            type = RuleActionType.REDACT,
                            replacement = "<redacted:kotlin>",
                        ),
                    ),
                ),
            ),
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )

        val stagedDirectory = workspace.root.resolve("src")
        val stagedSafeFile = stagedDirectory.resolve("Safe.kt")
        val stagedRedactedFile = stagedDirectory.resolve("Payment.kt")

        assertFalse(Files.isSymbolicLink(stagedDirectory))
        assumeTrue(Files.isSymbolicLink(stagedSafeFile), "Symbolic links are not supported in this environment")
        assertFalse(Files.isSymbolicLink(stagedRedactedFile))

        Files.writeString(safeFile, "fun keepMe(): String = \"after\"")

        assertEquals("fun keepMe(): String = \"after\"", Files.readString(stagedSafeFile))
        assertTrue(Files.readString(stagedRedactedFile).contains("<redacted:kotlin>"))
        assertEquals(1, workspace.passThroughFiles)
        assertEquals(1, workspace.redactedFiles)
    }

    @Test
    fun `symlinked directories are not traversed into the staged workspace`() {
        val projectRoot = Files.createTempDirectory("llm-guard-stage-root")
        val externalDirectory = Files.createTempDirectory("llm-guard-stage-external")
        Files.writeString(externalDirectory.resolve("Secret.kt"), "fun leaked(): String = \"secret\"")

        val linkedDirectory = projectRoot.resolve("linked-external")
        runCatching {
            Files.createSymbolicLink(linkedDirectory, externalDirectory)
        }.onFailure {
            assumeTrue(false, "Symbolic links are not supported in this environment")
        }

        val workspace = WorkspaceStager().stage(
            policy = LlmGuardPolicy(version = 1),
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )

        assertFalse(workspace.root.resolve("linked-external").exists())
        assertEquals(0, workspace.mirroredFiles)
    }

    @Test
    fun `unchanged files reuse cached guard decisions on subsequent staging`() {
        val projectRoot = Files.createTempDirectory("llm-guard-stage-root")
        val sourceFile = projectRoot.resolve("src/Payment.kt")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            package com.company.billing.internal

            class PaymentSignatureGenerator {
                fun generateHmac(input: String): String = input.reversed()
            }
            """.trimIndent(),
        )

        val policy = LlmGuardPolicy(
            version = 1,
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
                    action = RuleAction(
                        type = RuleActionType.REDACT,
                        replacement = "<redacted:kotlin>",
                    ),
                ),
            ),
        )

        val firstStage = WorkspaceStager().stage(
            policy = policy,
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )
        val originalTimestamp = Files.getLastModifiedTime(sourceFile)

        val secondStage = WorkspaceStager().stage(
            policy = policy,
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )

        assertEquals(0, firstStage.cacheHits)
        assertEquals(1, secondStage.cacheHits)
        assertEquals(originalTimestamp, Files.getLastModifiedTime(sourceFile))
        assertTrue(Files.readString(secondStage.root.resolve("src/Payment.kt")).contains("<redacted:kotlin>"))
    }

    @Test
    fun `changing a file invalidates the cached guard decision`() {
        val projectRoot = Files.createTempDirectory("llm-guard-stage-root")
        val sourceFile = projectRoot.resolve("src/Payment.kt")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            package com.company.billing.internal

            class PaymentSignatureGenerator {
                fun generateHmac(input: String): String = input.reversed()
            }
            """.trimIndent(),
        )

        val policy = LlmGuardPolicy(
            version = 1,
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
                    action = RuleAction(
                        type = RuleActionType.REDACT,
                        replacement = "<redacted:kotlin>",
                    ),
                ),
            ),
        )

        WorkspaceStager().stage(
            policy = policy,
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )

        Files.writeString(
            sourceFile,
            """
            package com.company.billing.internal

            class PaymentSignatureGenerator {
                fun generateHmac(input: String): String = input.uppercase()
            }
            """.trimIndent(),
        )
        Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(System.currentTimeMillis() + 1_000))

        val secondStage = WorkspaceStager().stage(
            policy = policy,
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )

        assertEquals(0, secondStage.cacheHits)
        assertTrue(Files.readString(secondStage.root.resolve("src/Payment.kt")).contains("<redacted:kotlin>"))
    }

    @Test
    fun `live sync replaces pass through subtree when a safe file becomes redacted`() {
        val projectRoot = Files.createTempDirectory("llm-guard-stage-root")
        val sourceFile = projectRoot.resolve("src/Payment.kt")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            package com.company.billing.internal

            class PaymentDetails {
                fun keep(input: String): String = input
            }
            """.trimIndent(),
        )
        val policy = kotlinRedactionPolicy()

        val workspaceStager = WorkspaceStager()
        val workspace = workspaceStager.stage(
            policy = policy,
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )

        val stagedDirectory = workspace.root.resolve("src")
        assumeTrue(Files.isSymbolicLink(stagedDirectory), "Symbolic links are not supported in this environment")

        Files.writeString(
            sourceFile,
            """
            package com.company.billing.internal

            class PaymentSignatureGenerator {
                fun generateHmac(input: String): String = input.reversed()
            }
            """.trimIndent(),
        )
        Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(System.currentTimeMillis() + 1_000))

        val syncResult = workspaceStager.synchronizeSourceRoot(
            policy = policy,
            cacheProjectRoot = projectRoot,
            evaluationRoot = projectRoot,
            sourceRoot = projectRoot,
            stagedRoot = workspace.root,
            changedPath = sourceFile,
        )

        assertEquals(WorkspaceSyncAction.UPDATED, syncResult.action)
        assertFalse(Files.isSymbolicLink(stagedDirectory))
        assertTrue(Files.readString(workspace.root.resolve("src/Payment.kt")).contains("<redacted:kotlin>"))
        assertEquals(1, syncResult.redactedFiles)
    }

    @Test
    fun `live sync restores pass through subtree when a redacted file becomes safe`() {
        val projectRoot = Files.createTempDirectory("llm-guard-stage-root")
        val sourceFile = projectRoot.resolve("src/Payment.kt")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            package com.company.billing.internal

            class PaymentSignatureGenerator {
                fun generateHmac(input: String): String = input.reversed()
            }
            """.trimIndent(),
        )
        val policy = kotlinRedactionPolicy()

        val workspaceStager = WorkspaceStager()
        val workspace = workspaceStager.stage(
            policy = policy,
            projectRoot = projectRoot,
            currentWorkingDirectory = projectRoot,
        )

        val stagedDirectory = workspace.root.resolve("src")
        assertFalse(Files.isSymbolicLink(stagedDirectory))

        Files.writeString(
            sourceFile,
            """
            package com.company.billing.internal

            class PaymentDetails {
                fun keep(input: String): String = input
            }
            """.trimIndent(),
        )
        Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(System.currentTimeMillis() + 1_000))

        val syncResult = workspaceStager.synchronizeSourceRoot(
            policy = policy,
            cacheProjectRoot = projectRoot,
            evaluationRoot = projectRoot,
            sourceRoot = projectRoot,
            stagedRoot = workspace.root,
            changedPath = sourceFile,
        )

        assumeTrue(Files.isSymbolicLink(stagedDirectory), "Symbolic links are not supported in this environment")
        assertEquals(WorkspaceSyncAction.UPDATED, syncResult.action)
        assertEquals(1, syncResult.passThroughFiles)
        assertEquals(
            """
            package com.company.billing.internal

            class PaymentDetails {
                fun keep(input: String): String = input
            }
            """.trimIndent(),
            Files.readString(workspace.root.resolve("src/Payment.kt")),
        )
    }

    private fun kotlinRedactionPolicy(): LlmGuardPolicy = LlmGuardPolicy(
        version = 1,
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
                action = RuleAction(
                    type = RuleActionType.REDACT,
                    replacement = "<redacted:kotlin>",
                ),
            ),
        ),
    )
}
