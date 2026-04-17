package com.github.projectstats

/**
 * Pure (IntelliJ-free) line-counting and cyclomatic-complexity heuristics.
 *
 * Extracted from [ProjectScanner] so the text-scanning logic can be unit-tested
 * without spinning up an IDE test fixture. ProjectScanner still owns the VFS walk,
 * module classification, and PSI-based complexity — this object just classifies
 * a file's textual content given its extension.
 */
object LineCounter {

    data class LineStats(
        val total: Int,
        val nonBlank: Int,
        val code: Int,
        val complexity: Int,
    )

    data class CommentStyle(
        val line: String? = null,
        val blockOpen: String? = null,
        val blockClose: String? = null,
    )

    val COMMENT_STYLES: Map<String, CommentStyle> = mapOf(
        // JVM / Kotlin / Groovy / Scala
        "java" to CommentStyle("//", "/*", "*/"),
        "kt" to CommentStyle("//", "/*", "*/"),
        "kts" to CommentStyle("//", "/*", "*/"),
        "groovy" to CommentStyle("//", "/*", "*/"),
        "gradle" to CommentStyle("//", "/*", "*/"),
        "scala" to CommentStyle("//", "/*", "*/"),
        "clj" to CommentStyle(";"),
        // C / C++ / Objective-C
        "c" to CommentStyle("//", "/*", "*/"),
        "h" to CommentStyle("//", "/*", "*/"),
        "cpp" to CommentStyle("//", "/*", "*/"),
        "cc" to CommentStyle("//", "/*", "*/"),
        "cxx" to CommentStyle("//", "/*", "*/"),
        "hpp" to CommentStyle("//", "/*", "*/"),
        "m" to CommentStyle("//", "/*", "*/"),
        // Web / scripting
        "js" to CommentStyle("//", "/*", "*/"),
        "jsx" to CommentStyle("//", "/*", "*/"),
        "ts" to CommentStyle("//", "/*", "*/"),
        "tsx" to CommentStyle("//", "/*", "*/"),
        "mjs" to CommentStyle("//", "/*", "*/"),
        "css" to CommentStyle(null, "/*", "*/"),
        "scss" to CommentStyle("//", "/*", "*/"),
        "less" to CommentStyle("//", "/*", "*/"),
        // Systems languages
        "go" to CommentStyle("//", "/*", "*/"),
        "rs" to CommentStyle("//", "/*", "*/"),
        "swift" to CommentStyle("//", "/*", "*/"),
        "cs" to CommentStyle("//", "/*", "*/"),
        "dart" to CommentStyle("//", "/*", "*/"),
        "php" to CommentStyle("//", "/*", "*/"),
        // Scripting / data
        "py" to CommentStyle("#"),
        "rb" to CommentStyle("#"),
        "sh" to CommentStyle("#"),
        "bash" to CommentStyle("#"),
        "zsh" to CommentStyle("#"),
        "fish" to CommentStyle("#"),
        "yaml" to CommentStyle("#"),
        "yml" to CommentStyle("#"),
        "toml" to CommentStyle("#"),
        "r" to CommentStyle("#"),
        "pl" to CommentStyle("#"),
        "pm" to CommentStyle("#"),
        "tf" to CommentStyle("#", "/*", "*/"),
        // SQL / functional
        "sql" to CommentStyle("--", "/*", "*/"),
        "hs" to CommentStyle("--", "{-", "-}"),
        "lua" to CommentStyle("--", "--[[", "]]"),
        // Markup
        "html" to CommentStyle(null, "<!--", "-->"),
        "htm" to CommentStyle(null, "<!--", "-->"),
        "xml" to CommentStyle(null, "<!--", "-->"),
        "xhtml" to CommentStyle(null, "<!--", "-->"),
        "svg" to CommentStyle(null, "<!--", "-->"),
    )

    val DECISION_KEYWORDS: Map<String, Array<String>> = mapOf(
        // JVM / Kotlin / Groovy / Scala
        "java" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "kt" to arrayOf("if", "for", "while", "do", "when", "catch"),
        "kts" to arrayOf("if", "for", "while", "do", "when", "catch"),
        "groovy" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "gradle" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "scala" to arrayOf("if", "for", "while", "do", "case", "catch", "match"),
        // C / C++ / Objective-C
        "c" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "h" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "cpp" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "cc" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "cxx" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "hpp" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "m" to arrayOf("if", "for", "while", "do", "case", "catch"),
        // Web / scripting
        "js" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "jsx" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "ts" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "tsx" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "mjs" to arrayOf("if", "for", "while", "do", "case", "catch"),
        // Systems
        "go" to arrayOf("if", "for", "switch", "case", "select"),
        "rs" to arrayOf("if", "for", "while", "loop", "match"),
        "swift" to arrayOf("if", "for", "while", "do", "case", "catch", "guard"),
        "cs" to arrayOf("if", "for", "foreach", "while", "do", "case", "catch"),
        "dart" to arrayOf("if", "for", "while", "do", "case", "catch"),
        "php" to arrayOf("if", "for", "foreach", "while", "do", "case", "catch"),
        // Scripting / data
        "py" to arrayOf("if", "elif", "for", "while", "except"),
        "rb" to arrayOf("if", "elsif", "unless", "for", "while", "rescue", "until"),
        "sh" to arrayOf("if", "elif", "for", "while", "case"),
        "bash" to arrayOf("if", "elif", "for", "while", "case"),
        "zsh" to arrayOf("if", "elif", "for", "while", "case"),
        // SQL / functional
        "sql" to arrayOf("case", "when", "if"),
        "lua" to arrayOf("if", "elseif", "for", "while", "repeat"),
        "r" to arrayOf("if", "for", "while"),
        "pl" to arrayOf("if", "elsif", "for", "foreach", "while", "unless", "until"),
        "pm" to arrayOf("if", "elsif", "for", "foreach", "while", "unless", "until"),
    )

    /**
     * Count total, non-blank, code, and approximate cyclomatic complexity for [text].
     *
     * [extension] is lower-case, no leading dot (e.g. "kt"). Unknown extensions yield
     * total/non-blank counts only (code == non-blank, complexity == 0).
     */
    fun count(text: String, extension: String): LineStats {
        val ext = extension.lowercase()
        val style = COMMENT_STYLES[ext]
        val keywords = DECISION_KEYWORDS[ext]
        var total = 0
        var nonBlank = 0
        var codeL = 0
        var complexity = 0
        var inBlock = false
        val codeBuffer = if (keywords != null) StringBuilder(128) else null
        var lineStart = 0
        var i = 0
        while (i <= text.length) {
            if (i == text.length || text[i] == '\n') {
                total++
                val lineEnd = if (i > lineStart && text[i - 1] == '\r') i - 1 else i
                var hasContent = false
                for (j in lineStart until lineEnd) {
                    val c = text[j]
                    if (c != ' ' && c != '\t') {
                        hasContent = true; break
                    }
                }
                if (hasContent) {
                    nonBlank++
                    if (style == null) {
                        codeL++
                        if (keywords != null) {
                            complexity += countKeywordsInRange(text, lineStart, lineEnd, keywords)
                        }
                    } else {
                        codeBuffer?.setLength(0)
                        val (hasCode, newInBlock) = classifyLine(
                            text, lineStart, lineEnd, inBlock, style, codeBuffer
                        )
                        inBlock = newInBlock
                        if (hasCode) {
                            codeL++
                            if (codeBuffer != null && keywords != null) {
                                complexity += countKeywordsInBuffer(codeBuffer, keywords)
                            }
                        }
                    }
                }
                lineStart = i + 1
            }
            i++
        }
        if (text.isNotEmpty() && text.last() == '\n') total = (total - 1).coerceAtLeast(0)
        return LineStats(total, nonBlank, codeL, complexity)
    }

    /**
     * Scans one line of [text] from [lineStart] to [lineEnd] (exclusive) respecting comment syntax.
     * Returns (hasCode, inBlockAfterLine). If [codeBuffer] is non-null, code characters are
     * appended for keyword counting.
     */
    internal fun classifyLine(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        inBlockIn: Boolean,
        style: CommentStyle,
        codeBuffer: StringBuilder? = null,
    ): Pair<Boolean, Boolean> {
        var hasCode = false
        var inBlock = inBlockIn
        var j = lineStart
        while (j < lineEnd) {
            if (inBlock) {
                val bc = style.blockClose
                if (bc != null && text.startsWith(bc, j)) {
                    inBlock = false
                    j += bc.length
                } else {
                    j++
                }
            } else {
                val bo = style.blockOpen
                val lc = style.line
                when {
                    bo != null && text.startsWith(bo, j) -> {
                        inBlock = true; j += bo.length
                    }

                    lc != null && text.startsWith(lc, j) -> break // rest of line is comment
                    else -> {
                        val c = text[j]
                        if (c != ' ' && c != '\t') hasCode = true
                        codeBuffer?.append(c)
                        j++
                    }
                }
            }
        }
        return Pair(hasCode, inBlock)
    }

    /**
     * Count occurrences of [keywords] in [text] between [start] and [end] (exclusive),
     * respecting word boundaries.
     */
    internal fun countKeywordsInRange(text: String, start: Int, end: Int, keywords: Array<String>): Int {
        var count = 0
        for (kw in keywords) {
            var pos = start
            while (pos < end) {
                val idx = text.indexOf(kw, pos)
                if (idx == -1 || idx >= end) break
                val before = if (idx > 0) text[idx - 1] else ' '
                val after = if (idx + kw.length < text.length) text[idx + kw.length] else ' '
                if (!before.isLetterOrDigit() && before != '_' &&
                    !after.isLetterOrDigit() && after != '_'
                ) count++
                pos = idx + 1
            }
        }
        return count
    }

    internal fun countKeywordsInBuffer(buf: StringBuilder, keywords: Array<String>): Int {
        val s = buf.toString()
        return countKeywordsInRange(s, 0, s.length, keywords)
    }
}
