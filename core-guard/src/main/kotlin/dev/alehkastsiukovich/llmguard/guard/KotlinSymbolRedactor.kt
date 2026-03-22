package dev.alehkastsiukovich.llmguard.guard

import dev.alehkastsiukovich.llmguard.policy.KotlinSymbolCriteria
import kotlin.math.max

internal class KotlinSymbolRedactor {
    fun inspect(content: String, criteria: KotlinSymbolCriteria): KotlinInspection {
        val lines = content.toLineRanges()
        val packageName = packageRegex.find(content)?.groupValues?.get(1)
        val packageMatched = matchesPackage(packageName, criteria.packages)

        if (criteria.packages.isNotEmpty() && !packageMatched) {
            return KotlinInspection(false, emptyList())
        }

        if (criteria.classes.isEmpty() && criteria.functions.isEmpty() && criteria.annotations.isEmpty()) {
            return if (packageMatched) {
                KotlinInspection(
                    matched = true,
                    matches = listOf(
                        KotlinMatch(
                            description = "package ${packageName ?: "<unknown>"}",
                            range = 0 until content.length,
                        ),
                    ),
                )
            } else {
                KotlinInspection(false, emptyList())
            }
        }

        val classNames = criteria.classes.toSet()
        val functionNames = criteria.functions.toSet()
        val annotationNames = criteria.annotations.map(::shortName).toSet()
        val matches = mutableListOf<KotlinMatch>()

        val pendingAnnotations = mutableListOf<String>()
        var annotationStartLine: Int? = null

        lines.forEachIndexed { index, line ->
            val trimmed = line.text.trim()

            if (trimmed.isBlank()) {
                pendingAnnotations.clear()
                annotationStartLine = null
                return@forEachIndexed
            }

            val annotationMatch = annotationRegex.find(trimmed)
            if (annotationMatch != null) {
                pendingAnnotations += shortName(annotationMatch.groupValues[1])
                if (annotationStartLine == null) {
                    annotationStartLine = index
                }
                return@forEachIndexed
            }

            val classMatch = classRegex.find(trimmed)
            if (classMatch != null) {
                val name = classMatch.groupValues[2]
                val matchedAnnotations = pendingAnnotations.intersect(annotationNames)
                if (name in classNames || matchedAnnotations.isNotEmpty()) {
                    matches += KotlinMatch(
                        description = "class $name",
                        range = declarationRange(lines, annotationStartLine ?: index, index),
                    )
                }
                pendingAnnotations.clear()
                annotationStartLine = null
                return@forEachIndexed
            }

            val functionMatch = functionRegex.find(trimmed)
            if (functionMatch != null) {
                val name = functionMatch.groupValues[1]
                val matchedAnnotations = pendingAnnotations.intersect(annotationNames)
                if (name in functionNames || matchedAnnotations.isNotEmpty()) {
                    matches += KotlinMatch(
                        description = "fun $name",
                        range = declarationRange(lines, annotationStartLine ?: index, index),
                    )
                }
                pendingAnnotations.clear()
                annotationStartLine = null
                return@forEachIndexed
            }

            pendingAnnotations.clear()
            annotationStartLine = null
        }

        return KotlinInspection(matches.isNotEmpty(), matches.mergeOverlaps())
    }

    fun redact(content: String, matches: List<KotlinMatch>, replacement: String): String =
        replaceRanges(
            content = content,
            replacements = matches.map { match ->
                match.range to "$replacement // ${match.description}"
            },
        )

    private fun declarationRange(
        lines: List<LineRange>,
        startLine: Int,
        declarationLine: Int,
    ): IntRange {
        val declaration = lines[declarationLine]
        val openingBalance = declaration.text.count { it == '{' } - declaration.text.count { it == '}' }

        if (openingBalance > 0) {
            var balance = openingBalance
            var currentLine = declarationLine

            // Scan until the declaration's braces are balanced again.
            while (balance > 0 && currentLine < lines.lastIndex) {
                currentLine += 1
                balance += lines[currentLine].text.count { it == '{' }
                balance -= lines[currentLine].text.count { it == '}' }
            }
            return lines[startLine].start until lines[currentLine].endExclusive
        }

        val baseIndent = declaration.indent
        var endLine = declarationLine
        var currentLine = declarationLine + 1

        while (currentLine <= lines.lastIndex) {
            val candidate = lines[currentLine]
            val trimmed = candidate.text.trim()
            if (trimmed.isBlank()) {
                currentLine += 1
                continue
            }
            if (candidate.indent <= baseIndent && looksLikeDeclaration(trimmed)) {
                break
            }
            if (candidate.indent <= max(0, baseIndent - 1)) {
                break
            }
            endLine = currentLine
            currentLine += 1
        }

        return lines[startLine].start until lines[endLine].endExclusive
    }

    private fun List<KotlinMatch>.mergeOverlaps(): List<KotlinMatch> {
        if (isEmpty()) {
            return emptyList()
        }

        val sorted = sortedBy { it.range.first }
        val merged = mutableListOf<KotlinMatch>()
        var current = sorted.first()

        for (next in sorted.drop(1)) {
            if (next.range.first <= current.range.last + 1) {
                current = current.copy(
                    description = "${current.description}, ${next.description}",
                    range = current.range.first until max(current.range.last + 1, next.range.last + 1),
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun matchesPackage(packageName: String?, configuredPackages: List<String>): Boolean {
        if (configuredPackages.isEmpty()) {
            return true
        }
        if (packageName == null) {
            return false
        }

        return configuredPackages.any { configured ->
            packageName == configured || packageName.startsWith("$configured.")
        }
    }

    private fun looksLikeDeclaration(trimmed: String): Boolean =
        annotationRegex.containsMatchIn(trimmed) ||
            classRegex.containsMatchIn(trimmed) ||
            functionRegex.containsMatchIn(trimmed)

    private fun String.toLineRanges(): List<LineRange> {
        val rawLines = split('\n')
        val lines = mutableListOf<LineRange>()
        var offset = 0

        rawLines.forEachIndexed { index, line ->
            val hasNewLine = index < rawLines.lastIndex
            val endExclusive = offset + line.length + if (hasNewLine) 1 else 0
            lines += LineRange(
                text = line,
                start = offset,
                endExclusive = endExclusive,
                indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it },
            )
            offset = endExclusive
        }

        return lines
    }

    private fun shortName(name: String): String = name.substringAfterLast('.')

    private data class LineRange(
        val text: String,
        val start: Int,
        val endExclusive: Int,
        val indent: Int,
    )

    companion object {
        private val packageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", setOf(RegexOption.MULTILINE))
        private val annotationRegex = Regex("""^@([A-Za-z_][A-Za-z0-9_.]*)""")
        private val classRegex = Regex(
            """^(?:(?:public|private|internal|protected|data|sealed|enum|annotation|open|abstract|final|value|expect|actual|inner|companion)\s+)*(class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""",
        )
        private val functionRegex = Regex(
            """^(?:(?:public|private|internal|protected|suspend|inline|tailrec|operator|infix|override|open|abstract|final|actual|expect|external)\s+)*fun(?:\s*<[^>]+>)?\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
        )
    }
}

internal data class KotlinInspection(
    val matched: Boolean,
    val matches: List<KotlinMatch>,
)

internal data class KotlinMatch(
    val description: String,
    val range: IntRange,
)

