package dev.alehkastsiukovich.llmguard.cli

import com.pty4j.PtyProcessBuilder
import java.util.Locale
import java.lang.ProcessBuilder
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.concurrent.thread

internal interface InteractiveSessionRunner {
    fun run(
        config: InteractiveSessionConfig,
        inputTransformer: (String) -> String?,
    ): Int
}

internal data class InteractiveSessionConfig(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: java.nio.file.Path,
    val environment: Map<String, String>,
)

internal class PtySessionRunner : InteractiveSessionRunner {
    override fun run(
        config: InteractiveSessionConfig,
        inputTransformer: (String) -> String?,
    ): Int {
        if (isWindows()) {
            return runWithoutPty(config)
        }

        val environment = HashMap(System.getenv()).apply {
            putAll(config.environment)
            putIfAbsent("TERM", "xterm-256color")
        }

        val process = PtyProcessBuilder()
            .setCommand(resolveCommand(config).toTypedArray())
            .setDirectory(config.workingDirectory.toString())
            .setEnvironment(environment)
            .start()

        val outputThread = thread(
            isDaemon = true,
            name = "llm-guard-pty-output",
        ) {
            process.inputStream.copyTo(System.out)
            System.out.flush()
        }

        val inputThread = thread(
            isDaemon = true,
            name = "llm-guard-pty-input",
        ) {
            val writer = process.outputStream.bufferedWriter()
            val reader = System.`in`.bufferedReader()

            while (true) {
                val line = reader.readLine() ?: break
                val forwarded = inputTransformer(line) ?: continue
                writer.write(forwarded)
                writer.newLine()
                writer.flush()
            }

            writer.close()
        }

        val shutdownHook = Thread { process.destroy() }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        val exitCode = process.waitFor()
        outputThread.join(250)
        inputThread.join(250)
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // JVM is already shutting down.
        }
        return exitCode
    }

    private fun runWithoutPty(config: InteractiveSessionConfig): Int {
        val processBuilder = ProcessBuilder(resolveCommand(config))
            .directory(config.workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)

        processBuilder.environment().putAll(config.environment)
        val process = processBuilder.start()
        return process.waitFor()
    }

    private fun resolveCommand(config: InteractiveSessionConfig): List<String> {
        val executablePath = Path(config.executable)
        if (!isWindows()) {
            return listOf(config.executable) + config.arguments
        }

        return when (executablePath.extension.lowercase(Locale.ROOT)) {
            "ps1" -> listOf(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                executablePath.toString(),
            ) + config.arguments
            "cmd",
            "bat",
            -> listOf(
                "cmd.exe",
                "/c",
                executablePath.toString(),
            ) + config.arguments
            else -> listOf(config.executable) + config.arguments
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
