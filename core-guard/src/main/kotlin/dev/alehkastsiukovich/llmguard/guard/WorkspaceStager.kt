package dev.alehkastsiukovich.llmguard.guard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.alehkastsiukovich.llmguard.policy.LlmGuardPolicy
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo

class WorkspaceStager(
    private val guardEngine: GuardEngine = GuardEngine(),
    private val preferPassThroughLinks: Boolean = true,
) {
    private val manifestMapper = jacksonObjectMapper()

    fun stage(
        policy: LlmGuardPolicy,
        projectRoot: Path,
        currentWorkingDirectory: Path,
        externalIncludeDirectories: List<Path> = emptyList(),
        externalIncludeFiles: List<Path> = emptyList(),
    ): StagedWorkspace {
        val normalizedProjectRoot = projectRoot.toAbsolutePath().normalize()
        val normalizedCwd = currentWorkingDirectory.toAbsolutePath().normalize()
        val root = prepareStableWorkspaceRoot(normalizedProjectRoot)
        val cacheManifest = loadCacheManifest(normalizedProjectRoot, policy)
        val cacheContext = StageCacheContext(
            policyFingerprint = policyFingerprint(policy),
            previousEntriesBySourcePath = cacheManifest.entries.associateBy { it.sourcePath },
        )
        val findings = mutableListOf<GuardFinding>()
        var mirroredFiles = 0
        var redactedFiles = 0
        var blockedFiles = 0
        var passThroughFiles = 0

        val projectMirror = mirrorDirectory(
            policy = policy,
            evaluationRoot = normalizedProjectRoot,
            sourceDirectory = normalizedProjectRoot,
            destinationDirectory = root,
            findings = findings,
            cacheContext = cacheContext,
        )
        mirroredFiles += projectMirror.mirroredFiles
        redactedFiles += projectMirror.redactedFiles
        blockedFiles += projectMirror.blockedFiles
        passThroughFiles += projectMirror.passThroughFiles

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
                cacheContext = cacheContext,
            )
            mirroredFiles += externalMirror.mirroredFiles
            redactedFiles += externalMirror.redactedFiles
            blockedFiles += externalMirror.blockedFiles
            passThroughFiles += externalMirror.passThroughFiles
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
                    cacheContext = cacheContext,
                )
                mirroredFiles += fileMirror.mirroredFiles
                redactedFiles += fileMirror.redactedFiles
                blockedFiles += fileMirror.blockedFiles
                passThroughFiles += fileMirror.passThroughFiles
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
        writeCacheManifest(
            projectRoot = normalizedProjectRoot,
            manifest = WorkspaceStageCacheManifest(
                version = cacheManifestVersion,
                policyFingerprint = cacheContext.policyFingerprint,
                entries = cacheContext.recordedEntries.values.sortedBy { it.sourcePath },
            ),
        )

        return StagedWorkspace(
            root = root,
            projectRoot = normalizedProjectRoot,
            workingDirectory = stagedWorkingDirectory,
            stagedExternalIncludes = stagedExternalIncludes,
            stagedExternalFiles = stagedExternalFiles,
            mirroredFiles = mirroredFiles,
            redactedFiles = redactedFiles,
            blockedFiles = blockedFiles,
            passThroughFiles = passThroughFiles,
            cacheHits = cacheContext.cacheHits,
            findings = findings,
        )
    }

    private fun mirrorDirectory(
        policy: LlmGuardPolicy,
        evaluationRoot: Path,
        sourceDirectory: Path,
        destinationDirectory: Path,
        findings: MutableList<GuardFinding>,
        cacheContext: StageCacheContext,
    ): MirrorStats {
        val directoryPlan = scanDirectory(
            policy = policy,
            evaluationRoot = evaluationRoot,
            directory = sourceDirectory,
            findings = findings,
            cacheContext = cacheContext,
        )
        return materializeDirectoryPlan(
            plan = directoryPlan,
            destinationDirectory = destinationDirectory,
            allowDirectoryPassThrough = true,
        )
    }

    private fun mirrorFile(
        policy: LlmGuardPolicy,
        sourceFile: Path,
        destinationFile: Path,
        findings: MutableList<GuardFinding>,
        cacheContext: StageCacheContext,
    ): MirrorStats {
        require(Files.readAttributes(sourceFile, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile) {
            "External include is not a regular file: $sourceFile"
        }
        val plannedFile = evaluateFile(
            policy = policy,
            evaluationRoot = sourceFile.parent ?: sourceFile.toAbsolutePath().parent,
            file = sourceFile,
            findings = findings,
            cacheContext = cacheContext,
        )

        return when (plannedFile.disposition) {
            AttachmentDisposition.BLOCKED -> MirrorStats(
                mirroredFiles = 0,
                redactedFiles = 0,
                blockedFiles = 1,
                passThroughFiles = 0,
            )
            AttachmentDisposition.COPY_ORIGINAL -> {
                val materialization = materializeAllowedFile(
                    sourceFile = sourceFile,
                    destinationFile = destinationFile,
                )
                MirrorStats(
                    mirroredFiles = 1,
                    redactedFiles = 0,
                    blockedFiles = 0,
                    passThroughFiles = if (materialization == MaterializationMode.PASS_THROUGH_LINK) 1 else 0,
                )
            }
            AttachmentDisposition.WRITE_SANITIZED_TEXT -> {
                destinationFile.parent?.createDirectories()
                Files.writeString(destinationFile, plannedFile.sanitizedText.orEmpty())
                MirrorStats(
                    mirroredFiles = 1,
                    redactedFiles = 1,
                    blockedFiles = 0,
                    passThroughFiles = 0,
                )
            }
        }
    }

    private fun materializeAllowedFile(
        sourceFile: Path,
        destinationFile: Path,
    ): MaterializationMode {
        destinationFile.parent?.createDirectories()
        Files.deleteIfExists(destinationFile)

        if (preferPassThroughLinks) {
            runCatching {
                Files.createSymbolicLink(destinationFile, sourceFile)
            }.onSuccess {
                return MaterializationMode.PASS_THROUGH_LINK
            }
        }

        Files.copy(
            sourceFile,
            destinationFile,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        return MaterializationMode.COPIED_FILE
    }

    private fun scanDirectory(
        policy: LlmGuardPolicy,
        evaluationRoot: Path,
        directory: Path,
        findings: MutableList<GuardFinding>,
        cacheContext: StageCacheContext,
    ): PlannedDirectory {
        val childDirectories = mutableListOf<PlannedDirectory>()
        val childFiles = mutableListOf<PlannedFile>()

        Files.newDirectoryStream(directory).use { children ->
            children.sortedBy { it.fileName?.toString().orEmpty() }.forEach { child ->
                val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                when {
                    attributes.isDirectory -> {
                        if (!shouldSkipDirectory(child)) {
                            childDirectories += scanDirectory(
                                policy = policy,
                                evaluationRoot = evaluationRoot,
                                directory = child,
                                findings = findings,
                                cacheContext = cacheContext,
                            )
                        }
                    }
                    attributes.isRegularFile -> {
                        childFiles += evaluateFile(
                            policy = policy,
                            evaluationRoot = evaluationRoot,
                            file = child,
                            findings = findings,
                            cacheContext = cacheContext,
                        )
                    }
                }
            }
        }

        val mirroredFiles = childDirectories.sumOf { it.stats.mirroredFiles } + childFiles.count { it.isMirrored }
        val redactedFiles = childDirectories.sumOf { it.stats.redactedFiles } + childFiles.count { it.isRedacted }
        val blockedFiles = childDirectories.sumOf { it.stats.blockedFiles } + childFiles.count { it.isBlocked }
        val allChildrenPassThroughCandidates = childFiles.all { it.disposition == AttachmentDisposition.COPY_ORIGINAL } &&
            childDirectories.all { it.isFullyPassThroughCandidate }
        val isFullyPassThroughCandidate = mirroredFiles > 0 && redactedFiles == 0 && blockedFiles == 0 && allChildrenPassThroughCandidates

        return PlannedDirectory(
            sourceDirectory = directory,
            childDirectories = childDirectories,
            childFiles = childFiles,
            isFullyPassThroughCandidate = isFullyPassThroughCandidate,
            stats = MirrorStats(
                mirroredFiles = mirroredFiles,
                redactedFiles = redactedFiles,
                blockedFiles = blockedFiles,
                passThroughFiles = 0,
            ),
        )
    }

    private fun evaluateFile(
        policy: LlmGuardPolicy,
        evaluationRoot: Path,
        file: Path,
        findings: MutableList<GuardFinding>,
        cacheContext: StageCacheContext,
    ): PlannedFile {
        val normalizedSourcePath = file.toAbsolutePath().normalize()
        val sourcePath = normalizedSourcePath.toString()
        val sourceFingerprint = SourceFingerprint.from(normalizedSourcePath)
        cacheContext.previousEntriesBySourcePath[sourcePath]
            ?.takeIf { cacheEntry ->
                cacheEntry.policyFingerprint == cacheContext.policyFingerprint &&
                    cacheEntry.sourceFingerprint == sourceFingerprint
            }
            ?.let { cacheEntry ->
                findings += cacheEntry.findings
                cacheContext.recordedEntries[sourcePath] = cacheEntry
                cacheContext.cacheHits += 1
                return PlannedFile(
                    sourceFile = normalizedSourcePath,
                    disposition = cacheEntry.disposition,
                    sanitizedText = cacheEntry.sanitizedText,
                    cacheHit = true,
                )
            }

        val evaluation = guardEngine.evaluate(
            policy = policy,
            request = GuardRequest(
                projectRoot = evaluationRoot,
                attachments = listOf(normalizedSourcePath),
            ),
        )
        val attachment = evaluation.attachments.single()
        findings += evaluation.findings
        val plannedFile = PlannedFile(
            sourceFile = normalizedSourcePath,
            disposition = attachment.disposition,
            sanitizedText = attachment.sanitizedText,
            cacheHit = false,
        )
        cacheContext.recordedEntries[sourcePath] = CachedFileEvaluation(
            sourcePath = sourcePath,
            policyFingerprint = cacheContext.policyFingerprint,
            sourceFingerprint = sourceFingerprint,
            disposition = plannedFile.disposition,
            sanitizedText = plannedFile.sanitizedText,
            findings = evaluation.findings,
        )
        return plannedFile
    }

    private fun materializeDirectoryPlan(
        plan: PlannedDirectory,
        destinationDirectory: Path,
        allowDirectoryPassThrough: Boolean,
    ): MirrorStats {
        if (allowDirectoryPassThrough &&
            preferPassThroughLinks &&
            plan.isFullyPassThroughCandidate &&
            tryCreatePassThroughDirectoryLink(plan.sourceDirectory, destinationDirectory)
        ) {
            return plan.stats.copy(passThroughFiles = plan.stats.mirroredFiles)
        }

        destinationDirectory.createDirectories()

        var mirroredFiles = 0
        var redactedFiles = 0
        var blockedFiles = 0
        var passThroughFiles = 0

        plan.childDirectories.forEach { childDirectory ->
            val childStats = materializeDirectoryPlan(
                plan = childDirectory,
                destinationDirectory = destinationDirectory.resolve(childDirectory.sourceDirectory.fileName),
                allowDirectoryPassThrough = true,
            )
            mirroredFiles += childStats.mirroredFiles
            redactedFiles += childStats.redactedFiles
            blockedFiles += childStats.blockedFiles
            passThroughFiles += childStats.passThroughFiles
        }

        plan.childFiles.forEach { file ->
            when (file.disposition) {
                AttachmentDisposition.BLOCKED -> blockedFiles += 1
                AttachmentDisposition.COPY_ORIGINAL -> {
                    mirroredFiles += 1
                    val destinationFile = destinationDirectory.resolve(file.sourceFile.fileName)
                    val materialization = materializeAllowedFile(
                        sourceFile = file.sourceFile,
                        destinationFile = destinationFile,
                    )
                    if (materialization == MaterializationMode.PASS_THROUGH_LINK) {
                        passThroughFiles += 1
                    }
                }
                AttachmentDisposition.WRITE_SANITIZED_TEXT -> {
                    mirroredFiles += 1
                    redactedFiles += 1
                    val destinationFile = destinationDirectory.resolve(file.sourceFile.fileName)
                    destinationFile.parent?.createDirectories()
                    Files.writeString(destinationFile, file.sanitizedText.orEmpty())
                }
            }
        }

        return MirrorStats(
            mirroredFiles = mirroredFiles,
            redactedFiles = redactedFiles,
            blockedFiles = blockedFiles,
            passThroughFiles = passThroughFiles,
        )
    }

    private fun tryCreatePassThroughDirectoryLink(
        sourceDirectory: Path,
        destinationDirectory: Path,
    ): Boolean {
        destinationDirectory.parent?.createDirectories()
        Files.deleteIfExists(destinationDirectory)
        return runCatching {
            Files.createSymbolicLink(destinationDirectory, sourceDirectory)
        }.isSuccess
    }

    private fun prepareStableWorkspaceRoot(projectRoot: Path): Path {
        val baseDirectory = Path.of(System.getProperty("java.io.tmpdir"), stableWorkspaceDirectoryName)
        baseDirectory.createDirectories()

        val root = baseDirectory.resolve(stableWorkspaceName(projectRoot))
        clearDirectory(root)
        root.createDirectories()
        return root
    }

    private fun loadCacheManifest(
        projectRoot: Path,
        policy: LlmGuardPolicy,
    ): WorkspaceStageCacheManifest {
        val cacheFile = cacheManifestPath(projectRoot)
        if (!cacheFile.exists()) {
            return WorkspaceStageCacheManifest(
                version = cacheManifestVersion,
                policyFingerprint = policyFingerprint(policy),
            )
        }

        return runCatching {
            manifestMapper.readValue(cacheFile.toFile(), WorkspaceStageCacheManifest::class.java)
        }.getOrNull()
            ?.takeIf { it.version == cacheManifestVersion && it.policyFingerprint == policyFingerprint(policy) }
            ?: WorkspaceStageCacheManifest(
                version = cacheManifestVersion,
                policyFingerprint = policyFingerprint(policy),
            )
    }

    private fun writeCacheManifest(
        projectRoot: Path,
        manifest: WorkspaceStageCacheManifest,
    ) {
        val cacheFile = cacheManifestPath(projectRoot)
        cacheFile.parent?.createDirectories()
        manifestMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), manifest)
    }

    private fun cacheManifestPath(projectRoot: Path): Path =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            stableWorkspaceDirectoryName,
            "${stableWorkspaceName(projectRoot)}.manifest.json",
        )

    private fun policyFingerprint(policy: LlmGuardPolicy): String = sha256(
        manifestMapper.writeValueAsBytes(policy),
    )

    private fun clearDirectory(path: Path) {
        if (!path.exists()) {
            return
        }

        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: java.io.IOException?,
                ): FileVisitResult {
                    if (dir != path) {
                        Files.deleteIfExists(dir)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun stableWorkspaceName(projectRoot: Path): String {
        val projectName = sanitizeDirectoryName(projectRoot)
        val digest = sha256(projectRoot.toString().toByteArray(Charsets.UTF_8)).take(12)
        return "$projectName-$digest"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

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
        val passThroughFiles: Int,
    )

    private data class PlannedDirectory(
        val sourceDirectory: Path,
        val childDirectories: List<PlannedDirectory>,
        val childFiles: List<PlannedFile>,
        val isFullyPassThroughCandidate: Boolean,
        val stats: MirrorStats,
    )

    private data class PlannedFile(
        val sourceFile: Path,
        val disposition: AttachmentDisposition,
        val sanitizedText: String?,
        val cacheHit: Boolean,
    ) {
        val isMirrored: Boolean
            get() = disposition != AttachmentDisposition.BLOCKED

        val isRedacted: Boolean
            get() = disposition == AttachmentDisposition.WRITE_SANITIZED_TEXT

        val isBlocked: Boolean
            get() = disposition == AttachmentDisposition.BLOCKED
    }

    private data class StageCacheContext(
        val policyFingerprint: String,
        val previousEntriesBySourcePath: Map<String, CachedFileEvaluation>,
        val recordedEntries: LinkedHashMap<String, CachedFileEvaluation> = linkedMapOf(),
        var cacheHits: Int = 0,
    )

    private data class WorkspaceStageCacheManifest(
        val version: Int = cacheManifestVersion,
        val policyFingerprint: String = "",
        val entries: List<CachedFileEvaluation> = emptyList(),
    )

    private data class CachedFileEvaluation(
        val sourcePath: String,
        val policyFingerprint: String,
        val sourceFingerprint: SourceFingerprint,
        val disposition: AttachmentDisposition,
        val sanitizedText: String? = null,
        val findings: List<GuardFinding> = emptyList(),
    )

    private data class SourceFingerprint(
        val size: Long,
        val lastModifiedMillis: Long,
    ) {
        companion object {
            fun from(path: Path): SourceFingerprint {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                return SourceFingerprint(
                    size = attributes.size(),
                    lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
                )
            }
        }
    }

    private enum class MaterializationMode {
        PASS_THROUGH_LINK,
        COPIED_FILE,
    }

    private companion object {
        private const val stableWorkspaceDirectoryName = "llm-guard-workspaces"
        private const val cacheManifestVersion = 1

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
    val passThroughFiles: Int,
    val cacheHits: Int,
    val findings: List<GuardFinding>,
)
