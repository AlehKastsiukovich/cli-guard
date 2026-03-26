package dev.alehkastsiukovich.llmguard.guard

import dev.alehkastsiukovich.llmguard.policy.LlmGuardPolicy
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo

class WorkspaceStager(
    private val guardEngine: GuardEngine = GuardEngine(),
) {
    fun stage(
        policy: LlmGuardPolicy,
        projectRoot: Path,
        currentWorkingDirectory: Path,
        externalIncludeDirectories: List<Path> = emptyList(),
        externalIncludeFiles: List<Path> = emptyList(),
    ): StagedWorkspace {
        val normalizedProjectRoot = projectRoot.toAbsolutePath().normalize()
        val normalizedCwd = currentWorkingDirectory.toAbsolutePath().normalize()
        val root = Files.createTempDirectory("llm-guard-workspace-")
        val findings = mutableListOf<GuardFinding>()
        var mirroredFiles = 0
        var redactedFiles = 0
        var blockedFiles = 0

        val projectMirror = mirrorDirectory(
            policy = policy,
            evaluationRoot = normalizedProjectRoot,
            sourceDirectory = normalizedProjectRoot,
            destinationDirectory = root,
            findings = findings,
        )
        mirroredFiles += projectMirror.mirroredFiles
        redactedFiles += projectMirror.redactedFiles
        blockedFiles += projectMirror.blockedFiles

        val stagedExternalIncludes = linkedMapOf<Path, Path>()
        val normalizedExternalDirectories = externalIncludeDirectories
            .map { it.toAbsolutePath().normalize() }
            .distinct()
        normalizedExternalDirectories.forEachIndexed { index, normalizedExternal ->
            val externalDestination = root / "_external" / "${index + 1}-${sanitizeDirectoryName(normalizedExternal)}"
            val externalMirror = mirrorDirectory(
                policy = policy,
                evaluationRoot = normalizedExternal,
                sourceDirectory = normalizedExternal,
                destinationDirectory = externalDestination,
                findings = findings,
            )
            mirroredFiles += externalMirror.mirroredFiles
            redactedFiles += externalMirror.redactedFiles
            blockedFiles += externalMirror.blockedFiles
            stagedExternalIncludes[normalizedExternal] = externalDestination
        }

        val stagedExternalFiles = linkedMapOf<Path, Path>()
        externalIncludeFiles
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .filterNot { externalFile ->
                externalFile.startsWith(normalizedProjectRoot) || normalizedExternalDirectories.any(externalFile::startsWith)
            }
            .forEachIndexed { index, normalizedExternalFile ->
                val stagedExternalFile = root / "_external_files" / "${index + 1}-${sanitizeFileName(normalizedExternalFile)}"
                val fileMirror = mirrorFile(
                    policy = policy,
                    sourceFile = normalizedExternalFile,
                    destinationFile = stagedExternalFile,
                    findings = findings,
                )
                mirroredFiles += fileMirror.mirroredFiles
                redactedFiles += fileMirror.redactedFiles
                blockedFiles += fileMirror.blockedFiles
                if (fileMirror.mirroredFiles > 0) {
                    stagedExternalFiles[normalizedExternalFile] = stagedExternalFile
                }
            }

        val stagedWorkingDirectory = if (normalizedCwd.startsWith(normalizedProjectRoot)) {
            root.resolve(normalizedCwd.relativeTo(normalizedProjectRoot))
        } else {
            root
        }
        stagedWorkingDirectory.createDirectories()

        return StagedWorkspace(
            root = root,
            projectRoot = normalizedProjectRoot,
            workingDirectory = stagedWorkingDirectory,
            stagedExternalIncludes = stagedExternalIncludes,
            stagedExternalFiles = stagedExternalFiles,
            mirroredFiles = mirroredFiles,
            redactedFiles = redactedFiles,
            blockedFiles = blockedFiles,
            findings = findings,
        )
    }

    private fun mirrorDirectory(
        policy: LlmGuardPolicy,
        evaluationRoot: Path,
        sourceDirectory: Path,
        destinationDirectory: Path,
        findings: MutableList<GuardFinding>,
    ): MirrorStats {
        destinationDirectory.createDirectories()
        var mirroredFiles = 0
        var redactedFiles = 0
        var blockedFiles = 0

        Files.walkFileTree(
            sourceDirectory,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != sourceDirectory && shouldSkipDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }

                    val targetDirectory = destinationDirectory.resolve(sourceDirectory.relativize(dir))
                    targetDirectory.createDirectories()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!attrs.isRegularFile) {
                        return FileVisitResult.CONTINUE
                    }

                    val evaluation = guardEngine.evaluate(
                        policy = policy,
                        request = GuardRequest(
                            projectRoot = evaluationRoot,
                            attachments = listOf(file),
                        ),
                    )
                    val attachment = evaluation.attachments.single()
                    findings += evaluation.findings

                    when (attachment.disposition) {
                        AttachmentDisposition.BLOCKED -> blockedFiles += 1
                        AttachmentDisposition.COPY_ORIGINAL -> {
                            mirroredFiles += 1
                            Files.copy(
                                file,
                                destinationDirectory.resolve(attachment.relativePath),
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES,
                            )
                        }
                        AttachmentDisposition.WRITE_SANITIZED_TEXT -> {
                            mirroredFiles += 1
                            redactedFiles += 1
                            val destination = destinationDirectory.resolve(attachment.relativePath)
                            destination.parent?.createDirectories()
                            Files.writeString(destination, attachment.sanitizedText ?: "")
                        }
                    }

                    return FileVisitResult.CONTINUE
                }
            },
        )

        return MirrorStats(
            mirroredFiles = mirroredFiles,
            redactedFiles = redactedFiles,
            blockedFiles = blockedFiles,
        )
    }

    private fun mirrorFile(
        policy: LlmGuardPolicy,
        sourceFile: Path,
        destinationFile: Path,
        findings: MutableList<GuardFinding>,
    ): MirrorStats {
        require(Files.isRegularFile(sourceFile)) { "External include is not a regular file: $sourceFile" }

        val evaluation = guardEngine.evaluate(
            policy = policy,
            request = GuardRequest(
                projectRoot = sourceFile.parent ?: sourceFile.toAbsolutePath().parent,
                attachments = listOf(sourceFile),
            ),
        )
        val attachment = evaluation.attachments.single()
        findings += evaluation.findings

        return when (attachment.disposition) {
            AttachmentDisposition.BLOCKED -> MirrorStats(
                mirroredFiles = 0,
                redactedFiles = 0,
                blockedFiles = 1,
            )
            AttachmentDisposition.COPY_ORIGINAL -> {
                destinationFile.parent?.createDirectories()
                Files.copy(
                    sourceFile,
                    destinationFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
                MirrorStats(
                    mirroredFiles = 1,
                    redactedFiles = 0,
                    blockedFiles = 0,
                )
            }
            AttachmentDisposition.WRITE_SANITIZED_TEXT -> {
                destinationFile.parent?.createDirectories()
                Files.writeString(destinationFile, attachment.sanitizedText ?: "")
                MirrorStats(
                    mirroredFiles = 1,
                    redactedFiles = 1,
                    blockedFiles = 0,
                )
            }
        }
    }

    private fun sanitizeDirectoryName(path: Path): String {
        val name = path.fileName?.toString().orEmpty().ifBlank { "include" }
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun sanitizeFileName(path: Path): String {
        val name = path.fileName?.toString().orEmpty().ifBlank { "file" }
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun shouldSkipDirectory(path: Path): Boolean =
        path.isDirectory() && path.name in skippedDirectoryNames

    private data class MirrorStats(
        val mirroredFiles: Int,
        val redactedFiles: Int,
        val blockedFiles: Int,
    )

    private companion object {
        private val skippedDirectoryNames = setOf(
            ".git",
            ".gradle",
            ".idea",
            "build",
            "out",
            "node_modules",
        )
    }
}

data class StagedWorkspace(
    val root: Path,
    val projectRoot: Path,
    val workingDirectory: Path,
    val stagedExternalIncludes: Map<Path, Path>,
    val stagedExternalFiles: Map<Path, Path>,
    val mirroredFiles: Int,
    val redactedFiles: Int,
    val blockedFiles: Int,
    val findings: List<GuardFinding>,
)
