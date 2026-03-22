package dev.alehkastsiukovich.llmguard.guard

internal class SecretDetector {
    fun find(content: String): List<SecretMatch> = patterns.flatMap { pattern ->
        pattern.regex.findAll(content).map { match ->
            SecretMatch(pattern.id, match.range.first until match.range.last + 1)
        }
    }

    private data class SecretPattern(
        val id: String,
        val regex: Regex,
    )

    companion object {
        private val patterns = listOf(
            SecretPattern(
                id = "generic-assignment",
                regex = Regex("""(?im)\b(api[_-]?key|token|secret|password)\b\s*[:=]\s*["'][^"'\n]{6,}["']"""),
            ),
            SecretPattern(
                id = "google-api-key",
                regex = Regex("""AIza[0-9A-Za-z\-_]{35}"""),
            ),
            SecretPattern(
                id = "github-token",
                regex = Regex("""gh[pousr]_[0-9A-Za-z]{36,255}"""),
            ),
            SecretPattern(
                id = "oauth-token",
                regex = Regex("""ya29\.[0-9A-Za-z\-_]+"""),
            ),
            SecretPattern(
                id = "pem-private-key",
                regex = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]+?-----END [A-Z ]*PRIVATE KEY-----"""),
            ),
        )
    }
}

internal data class SecretMatch(
    val detectorId: String,
    val range: IntRange,
)

