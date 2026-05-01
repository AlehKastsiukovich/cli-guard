package dev.alehkastsiukovich.llmguard.guard

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.alehkastsiukovich.llmguard.policy.DetectorConfig
import dev.alehkastsiukovich.llmguard.policy.DetectorToggle
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class PrivacyFilterTextSanitizerBackend(
    private val environment: Map<String, String>,
) : TextSanitizerBackend {
    override val id: String = "privacy-filter"

    override fun config(detectors: DetectorConfig): DetectorToggle = detectors.privacyFilter

    override fun sanitize(request: TextSanitizerRequest): TextSanitizerResult? {
        val executable = resolveExecutable(
            explicit = environment["LLM_GUARD_PRIVACY_FILTER_BIN"],
            fallback = "opf",
            pathValue = environment["PATH"],
            pathExtValue = environment["PATHEXT"],
        ) ?: return null

        val output = executeCommand(
            command = listOf(executable),
            input = request.content,
        ) ?: return null

        val parsed = runCatching {
            objectMapper.readValue(output.stdout.trim(), PrivacyFilterOutput::class.java)
        }.getOrNull() ?: return null

        val matchedValues = parsed.detectedSpans.mapNotNull { span ->
            request.content.safeSubstring(span.start, span.end)
        }
        val hasSignal = parsed.redactedText != null || matchedValues.isNotEmpty()
        if (!hasSignal) {
            return null
        }

        return TextSanitizerResult(
            redactedText = parsed.redactedText,
            matchedValues = matchedValues,
            summary = parsed.summary ?: parsed.warning ?: "Detected sensitive text via privacy-filter",
        )
    }
}

internal class GitleaksTextSanitizerBackend(
    private val environment: Map<String, String>,
) : TextSanitizerBackend {
    override val id: String = "gitleaks"

    override fun config(detectors: DetectorConfig): DetectorToggle = detectors.gitleaks

    override fun sanitize(request: TextSanitizerRequest): TextSanitizerResult? {
        val executable = resolveExecutable(
            explicit = environment["LLM_GUARD_GITLEAKS_BIN"],
            fallback = "gitleaks",
            pathValue = environment["PATH"],
            pathExtValue = environment["PATHEXT"],
        ) ?: return null

        val output = executeCommand(
            command = listOf(
                executable,
                "stdin",
                "--report-format",
                "json",
                "--redact",
                "--no-banner",
                "--exit-code",
                "0",
            ),
            input = request.content,
        ) ?: return null

        val stdout = output.stdout.trim()
        if (stdout.isBlank() || stdout == "[]") {
            return null
        }

        val findings = runCatching {
            objectMapper.readValue(stdout, object : TypeReference<List<GitleaksFinding>>() {})
        }.getOrNull().orEmpty()
        if (findings.isEmpty()) {
            return null
        }

        val matchedValues = findings.mapNotNull { it.secret }
            .filter { it.isNotBlank() && it != "REDACTED" }

        return TextSanitizerResult(
            matchedValues = matchedValues,
            summary = "Detected ${findings.size} secret-like value(s) via gitleaks",
        )
    }
}

private data class CommandExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PrivacyFilterOutput(
    @JsonProperty("redacted_text")
    val redactedText: String? = null,
    val summary: String? = null,
    val warning: String? = null,
    @JsonProperty("detected_spans")
    val detectedSpans: List<PrivacyFilterSpan> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PrivacyFilterSpan(
    val start: Int = -1,
    val end: Int = -1,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GitleaksFinding(
    @JsonProperty("Secret")
    val secret: String? = null,
)

private fun resolveExecutable(
    explicit: String?,
    fallback: String,
    pathValue: String?,
    pathExtValue: String?,
): String? = explicit
    ?.takeIf { it.isNotBlank() }
    ?.takeIf(::isExecutablePath)
    ?: pathValue
        ?.split(File.pathSeparator)
        ?.asSequence()
        ?.flatMap { directory ->
            candidateExecutableNames(fallback, pathExtValue).asSequence()
                .map { candidate -> Path.of(directory).resolve(candidate) }
        }
        ?.firstOrNull(::isExecutablePath)
        ?.toString()

private fun executeCommand(
    command: List<String>,
    input: String,
): CommandExecutionResult? = runCatching {
    val process = ProcessBuilder(command)
        .redirectErrorStream(false)
        .start()

    process.outputStream.bufferedWriter().use { writer ->
        writer.write(input)
    }

    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        null
    } else {
        CommandExecutionResult(
            stdout = stdout,
            stderr = stderr,
            exitCode = process.exitValue(),
        )
    }
}.getOrNull()?.takeIf { it.exitCode == 0 || it.stdout.isNotBlank() }

private fun String.safeSubstring(
    start: Int,
    end: Int,
): String? {
    if (start < 0 || end <= start || end > length) {
        return null
    }
    return substring(start, end)
}

private val objectMapper = jacksonObjectMapper()

private fun candidateExecutableNames(
    fallback: String,
    pathExtValue: String?,
): List<String> {
    if (fallback.contains('.')) {
        return listOf(fallback)
    }

    val extensions = pathExtValue
        ?.split(File.pathSeparatorChar, ';')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        .orEmpty()
    if (extensions.isEmpty()) {
        return listOf(fallback)
    }

    return buildList {
        add(fallback)
        extensions.forEach { extension ->
            add(fallback + extension.lowercase())
            add(fallback + extension.uppercase())
        }
    }.distinct()
}

private fun isExecutablePath(path: String): Boolean = isExecutablePath(Path.of(path))

private fun isExecutablePath(path: Path): Boolean =
    Files.exists(path) && !Files.isDirectory(path) && Files.isExecutable(path)
