package dev.alehkastsiukovich.llmguard.cli

import com.pty4j.PtyProcessBuilder
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
        val environment = HashMap(System.getenv()).apply {
            putAll(config.environment)
            putIfAbsent("TERM", "xterm-256color")
        }

        val process = PtyProcessBuilder()
            .setCommand((listOf(config.executable) + config.arguments).toTypedArray())
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

        Runtime.getRuntime().addShutdownHook(
            Thread {
                process.destroy()
            },
        )

        val exitCode = process.waitFor()
        outputThread.join(250)
        inputThread.join(250)
        return exitCode
    }
}
