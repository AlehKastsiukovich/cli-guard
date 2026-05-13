package dev.alehkastsiukovich.llmguard.cli

import dev.alehkastsiukovich.llmguard.guard.StagedWorkspace
import dev.alehkastsiukovich.llmguard.guard.WorkspaceStager
import dev.alehkastsiukovich.llmguard.guard.WorkspaceSyncAction
import dev.alehkastsiukovich.llmguard.policy.LlmGuardPolicy
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.thread
import kotlin.io.path.exists

internal class WorkspaceLiveSyncSession(
    private val providerDisplayName: String,
    private val policy: LlmGuardPolicy,
    private val workspace: StagedWorkspace,
    private val workspaceStager: WorkspaceStager,
) : AutoCloseable {
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val watchRegistrations = linkedMapOf<WatchKey, DirectoryRegistration>()
    @Volatile
    private var running = false
    private var watchThread: Thread? = null

    fun start() {
        if (running) {
            return
        }

        registerRecursive(
            rootPath = workspace.projectRoot,
            registration = DirectoryRegistration.SourceRoot(
                cacheProjectRoot = workspace.projectRoot,
                evaluationRoot = workspace.projectRoot,
                sourceRoot = workspace.projectRoot,
                stagedRoot = workspace.root,
            ),
        )

        workspace.stagedExternalIncludes.forEach { (sourceRoot, stagedRoot) ->
            registerRecursive(
                rootPath = sourceRoot,
                registration = DirectoryRegistration.SourceRoot(
                    cacheProjectRoot = workspace.projectRoot,
                    evaluationRoot = sourceRoot,
                    sourceRoot = sourceRoot,
                    stagedRoot = stagedRoot,
                ),
            )
        }

        workspace.stagedExternalFiles.forEach { (sourceFile, stagedFile) ->
            sourceFile.parent?.let { parent ->
                registerDirectory(
                    directory = parent,
                    registration = DirectoryRegistration.ExternalFile(
                        cacheProjectRoot = workspace.projectRoot,
                        evaluationRoot = sourceFile.parent ?: sourceFile,
                        sourceFile = sourceFile,
                        stagedFile = stagedFile,
                        directory = parent,
                    ),
                )
            }
        }

        running = true
        watchThread = thread(
            start = true,
            isDaemon = true,
            name = "llm-guard-workspace-live-sync",
        ) {
            runLoop()
        }
        System.err.println("[llm-guard] live workspace sync active for $providerDisplayName.")
    }

    override fun close() {
        running = false
        runCatching { watchService.close() }
        watchThread?.join(500)
    }

    private fun runLoop() {
        while (running) {
            val key = try {
                watchService.take()
            } catch (_: ClosedWatchServiceException) {
                return
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }

            val registration = synchronized(watchRegistrations) { watchRegistrations[key] }
            if (registration == null) {
                key.reset()
                continue
            }

            val changedPaths = linkedSetOf<Path>()
            key.pollEvents().forEach { event ->
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    return@forEach
                }
                val relativePath = event.context() as? Path ?: return@forEach
                val changedPath = registration.directory.resolve(relativePath).toAbsolutePath().normalize()
                changedPaths.add(changedPath)

                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerCreatedPath(registration, changedPath)
                }
            }

            changedPaths.forEach { changedPath ->
                handleChangedPath(registration, changedPath)
            }

            if (!key.reset()) {
                synchronized(watchRegistrations) {
                    watchRegistrations.remove(key)
                }
            }
        }
    }

    private fun handleChangedPath(
        registration: DirectoryRegistration,
        changedPath: Path,
    ) {
        when (registration) {
            is DirectoryRegistration.SourceRoot -> {
                val result = workspaceStager.synchronizeSourceRoot(
                    policy = policy,
                    cacheProjectRoot = registration.cacheProjectRoot,
                    evaluationRoot = registration.evaluationRoot,
                    sourceRoot = registration.sourceRoot,
                    stagedRoot = registration.stagedRoot,
                    changedPath = changedPath,
                )
                printSyncResult(result)
            }
            is DirectoryRegistration.ExternalFile -> {
                if (changedPath == registration.sourceFile) {
                    val result = workspaceStager.synchronizeExternalFile(
                        policy = policy,
                        cacheProjectRoot = registration.cacheProjectRoot,
                        evaluationRoot = registration.evaluationRoot,
                        sourceFile = registration.sourceFile,
                        stagedFile = registration.stagedFile,
                    )
                    printSyncResult(result)
                }
            }
        }
    }

    private fun registerCreatedPath(
        registration: DirectoryRegistration,
        changedPath: Path,
    ) {
        val attributes = runCatching {
            Files.readAttributes(changedPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return
        if (!attributes.isDirectory || Files.isSymbolicLink(changedPath) || shouldSkipDirectory(changedPath)) {
            return
        }
        registerRecursive(changedPath, registration)
    }

    private fun registerRecursive(
        rootPath: Path,
        registration: DirectoryRegistration,
    ) {
        if (!rootPath.existsNoFollow() || Files.isSymbolicLink(rootPath) || shouldSkipDirectory(rootPath)) {
            return
        }

        registerDirectory(rootPath, registration)
        Files.newDirectoryStream(rootPath).use { children ->
            children.forEach { child ->
                val attributes = runCatching {
                    Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                }.getOrNull() ?: return@forEach
                if (attributes.isDirectory && !Files.isSymbolicLink(child) && !shouldSkipDirectory(child)) {
                    registerRecursive(child, registration)
                }
            }
        }
    }

    private fun registerDirectory(
        directory: Path,
        registration: DirectoryRegistration,
    ) {
        val key = directory.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
        synchronized(watchRegistrations) {
            watchRegistrations[key] = registration.withDirectory(directory)
        }
    }

    private fun printSyncResult(result: dev.alehkastsiukovich.llmguard.guard.WorkspaceSyncResult) {
        when (result.action) {
            WorkspaceSyncAction.IGNORED -> Unit
            WorkspaceSyncAction.REMOVED -> {
                System.err.println("[llm-guard] live sync removed ${result.targetSourcePath.fileName}.")
            }
            WorkspaceSyncAction.UPDATED -> {
                val status = when {
                    result.blockedFiles > 0 -> "blocked"
                    result.redactedFiles > 0 -> "redacted"
                    else -> "pass-through"
                }
                System.err.println(
                    "[llm-guard] live sync updated ${result.targetSourcePath.fileName}: " +
                        "$status, cacheHits=${result.cacheHits}.",
                )
            }
        }
    }

    private fun Path.existsNoFollow(): Boolean = exists(LinkOption.NOFOLLOW_LINKS)

    private fun shouldSkipDirectory(path: Path): Boolean = path.fileName?.toString() in skippedDirectoryNames

    private sealed class DirectoryRegistration(
        open val directory: Path,
    ) {
        abstract fun withDirectory(directory: Path): DirectoryRegistration

        data class SourceRoot(
            val cacheProjectRoot: Path,
            val evaluationRoot: Path,
            val sourceRoot: Path,
            val stagedRoot: Path,
            override val directory: Path = sourceRoot,
        ) : DirectoryRegistration(directory) {
            override fun withDirectory(directory: Path): DirectoryRegistration = copy(directory = directory)
        }

        data class ExternalFile(
            val cacheProjectRoot: Path,
            val evaluationRoot: Path,
            val sourceFile: Path,
            val stagedFile: Path,
            override val directory: Path,
        ) : DirectoryRegistration(directory) {
            override fun withDirectory(directory: Path): DirectoryRegistration = copy(directory = directory)
        }
    }

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
