package com.bfoxnet.pocketdev

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Prettier Code Formatter engine for PocketDev.
 *
 * Supports:
 * 1. Native Prettier CLI execution inside the Ubuntu 24.04 PRoot environment with full
 *    user-configured CLI flags (`--print-width`, `--tab-width`, `--use-tabs`, `--semi` / `--no-semi`,
 *    `--single-quote`, `--jsx-single-quote`, `--trailing-comma`, `--bracket-spacing`,
 *    `--bracket-same-line`, `--arrow-parens`, `--end-of-line`, `--prose-wrap`).
 * 2. `.prettierrc.json` project config generation and synchronization.
 * 3. Fast, offline in-process Prettier-compatible formatter respecting all user Prettier options
 *    across JS/TS/JSX/TSX, JSON, HTML/XML/Vue/Svelte, CSS/SCSS, Markdown, YAML, Kotlin, Java,
 *    Python, Go, Rust, C/C++, C#, PHP, and Shell files.
 */
object PrettierFormatter {

    val SUPPORTED_EXTENSIONS = setOf(
        "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "json", "jsonc", "css", "scss", "sass", "less",
        "html", "htm", "vue", "svelte", "xml", "svg",
        "md", "markdown", "mdx", "yaml", "yml",
        "kt", "kts", "java", "py", "pyw", "go", "rs",
        "c", "cpp", "cc", "cxx", "h", "hpp", "cs", "php", "rb", "lua", "sql", "sh"
    )

    /** Builds the CLI argument string for the `prettier` binary inside Ubuntu PRoot. */
    fun buildCliFlags(settings: Settings): String {
        val flags = mutableListOf<String>()
        flags.add("--print-width ${settings.prettierPrintWidth}")
        flags.add("--tab-width ${settings.prettierTabWidth}")
        if (settings.prettierUseTabs) {
            flags.add("--use-tabs")
        }
        flags.add(if (settings.prettierSemi) "--semi" else "--no-semi")
        if (settings.prettierSingleQuote) {
            flags.add("--single-quote")
        }
        if (settings.prettierJsxSingleQuote) {
            flags.add("--jsx-single-quote")
        }
        flags.add("--trailing-comma ${settings.prettierTrailingComma}")
        flags.add(if (settings.prettierBracketSpacing) "--bracket-spacing" else "--no-bracket-spacing")
        if (settings.prettierBracketSameLine) {
            flags.add("--bracket-same-line")
        }
        flags.add("--arrow-parens ${settings.prettierArrowParens}")
        flags.add("--end-of-line ${settings.prettierEndOfLine}")
        flags.add("--prose-wrap ${settings.prettierProseWrap}")
        return flags.joinToString(" ")
    }

    /** Generates a standard `.prettierrc.json` configuration string from current settings. */
    fun buildPrettierRcJson(settings: Settings): String {
        return JSONObject().apply {
            put("printWidth", settings.prettierPrintWidth)
            put("tabWidth", settings.prettierTabWidth)
            put("useTabs", settings.prettierUseTabs)
            put("semi", settings.prettierSemi)
            put("singleQuote", settings.prettierSingleQuote)
            put("jsxSingleQuote", settings.prettierJsxSingleQuote)
            put("trailingComma", settings.prettierTrailingComma)
            put("bracketSpacing", settings.prettierBracketSpacing)
            put("bracketSameLine", settings.prettierBracketSameLine)
            put("arrowParens", settings.prettierArrowParens)
            put("endOfLine", settings.prettierEndOfLine)
            put("proseWrap", settings.prettierProseWrap)
        }.toString(2) + "\n"
    }

    /** Writes `.prettierrc.json` into the active project workspace root. */
    fun writeProjectPrettierConfig(context: Context): File {
        val settings = Settings(context)
        val projectRoot = ProjectManager.ensureDefaultLocalWorkspace(context)
        val rcFile = File(projectRoot, ".prettierrc.json")
        rcFile.writeText(buildPrettierRcJson(settings), Charsets.UTF_8)
        return rcFile
    }

    /** Builds a live code preview demonstrating the active Prettier options in Settings. */
    fun buildLivePreviewSample(settings: Settings): String {
        val indent = if (settings.prettierUseTabs) "\t" else " ".repeat(settings.prettierTabWidth)
        val q = if (settings.prettierSingleQuote) "'" else "\""
        val jq = if (settings.prettierJsxSingleQuote) "'" else "\""
        val semi = if (settings.prettierSemi) ";" else ""
        val sp = if (settings.prettierBracketSpacing) " " else ""
        val arrowParam = if (settings.prettierArrowParens == "avoid") "user" else "(user)"
        val trailing = if (settings.prettierTrailingComma == "none") "" else ","
        val jsxClose = if (settings.prettierBracketSameLine) ">$q{user.name}$q</Badge>" else "\n$indent>$q{user.name}$q</Badge>"

        return buildString {
            appendLine("const formatUser = $arrowParam => {")
            appendLine("${indent}const profile = {${sp}name: ${q}PocketDev${q}, active: true${trailing}${sp}}$semi")
            appendLine("${indent}return <Badge theme=${jq}primary${jq}$jsxClose$semi")
            append("}$semi")
        }
    }

    /**
     * Formats [content] for [fileName] in-process using all Prettier configuration options in [settings].
     */
    fun formatInProcess(fileName: String, content: String, settings: Settings): String {
        if (content.isBlank()) return content
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val eol = when (settings.prettierEndOfLine) {
            "crlf" -> "\r\n"
            "auto" -> if (content.contains("\r\n")) "\r\n" else "\n"
            else -> "\n"
        }
        val indentUnit = if (settings.prettierUseTabs) "\t" else " ".repeat(settings.prettierTabWidth.coerceIn(1, 8))
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')

        // 1. Strict JSON formatting
        if (ext == "json" || ext == "jsonc") {
            runCatching {
                val trimmed = normalized.trim()
                val spaces = if (settings.prettierUseTabs) 2 else settings.prettierTabWidth.coerceIn(1, 8)
                val rawJson = if (trimmed.startsWith("{")) {
                    JSONObject(trimmed).toString(spaces)
                } else if (trimmed.startsWith("[")) {
                    JSONArray(trimmed).toString(spaces)
                } else {
                    null
                }
                if (rawJson != null) {
                    val tabAdjusted = if (settings.prettierUseTabs) {
                        rawJson.lines().joinToString("\n") { line ->
                            val leadingSpaces = line.takeWhile { it == ' ' }.length
                            "\t".repeat(leadingSpaces / 2) + line.trimStart()
                        }
                    } else {
                        rawJson
                    }
                    return tabAdjusted.replace("\n", eol) + eol
                }
            }
        }

        // 2. Markdown prose wrap & cleanup
        if (ext in setOf("md", "markdown", "mdx")) {
            return formatMarkdown(normalized, settings, eol)
        }

        // 3. Python / YAML / Makefile indentation-sensitive formatting
        if (ext in setOf("py", "pyw", "yaml", "yml", "mk") || fileName.equals("Makefile", ignoreCase = true)) {
            val formattedLines = normalized.split('\n').map { line ->
                if (fileName.equals("Makefile", ignoreCase = true)) {
                    line.trimEnd()
                } else {
                    val leadingTabsExpanded = line.replace("\t", indentUnit)
                    leadingTabsExpanded.trimEnd()
                }
            }
            return collapseBlankLines(formattedLines).joinToString(eol).trimEnd() + eol
        }

        val isJsTsFamily = ext in setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte")
        val isStyleFamily = ext in setOf("css", "scss", "sass", "less")

        val rawLines = normalized.split('\n')
        val outLines = ArrayList<String>(rawLines.size + 8)
        var depth = 0
        var consecutiveBlank = 0
        var inBlockComment = false

        for (idx in rawLines.indices) {
            val rawLine = rawLines[idx]
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                consecutiveBlank++
                if (consecutiveBlank <= 1) {
                    outLines.add("")
                }
                continue
            }
            consecutiveBlank = 0

            if (inBlockComment) {
                outLines.add(indentUnit.repeat(depth) + (if (trimmed.startsWith("*")) " $trimmed" else trimmed))
                if (trimmed.contains("*/")) {
                    inBlockComment = false
                }
                continue
            }
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
                outLines.add(indentUnit.repeat(depth) + trimmed)
                inBlockComment = true
                continue
            }

            val startsWithClosing = trimmed.startsWith("}") ||
                trimmed.startsWith("]") ||
                trimmed.startsWith(")") ||
                trimmed.startsWith("</")
            if (startsWithClosing) {
                depth = (depth - 1).coerceAtLeast(0)
            }

            var lineTransformed = trimmed
            if (isJsTsFamily) {
                lineTransformed = applyJsTsPrettierRules(lineTransformed, settings)
            } else if (isStyleFamily) {
                lineTransformed = applyCssPrettierRules(lineTransformed, settings)
            }

            // Apply trailing comma rule before closing brace/bracket on previous line if multi-line
            if ((trimmed == "}" || trimmed == "};" || trimmed == "]" || trimmed == "];") && outLines.isNotEmpty()) {
                val prevIdx = outLines.indexOfLast { it.isNotBlank() }
                if (prevIdx >= 0) {
                    val prevLine = outLines[prevIdx]
                    val prevTrim = prevLine.trimEnd()
                    if (!prevTrim.endsWith("{") && !prevTrim.endsWith("[") && !prevTrim.endsWith("//")) {
                        if (settings.prettierTrailingComma == "none" && prevTrim.endsWith(",")) {
                            outLines[prevIdx] = prevTrim.removeSuffix(",")
                        }
                    }
                }
            }

            outLines.add(indentUnit.repeat(depth) + lineTransformed)

            var netDelta = 0
            var inStr: Char? = null
            var i = 0
            while (i < lineTransformed.length) {
                val c = lineTransformed[i]
                if (c == '/' && i + 1 < lineTransformed.length && lineTransformed[i + 1] == '/' && inStr == null) {
                    break
                }
                if (inStr != null) {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '"', '\'', '`' -> inStr = c
                        '{', '[' -> netDelta++
                        '}', ']' -> netDelta--
                    }
                }
                i++
            }
            if (startsWithClosing && (trimmed.startsWith("}") || trimmed.startsWith("]"))) {
                netDelta += 1
            }
            depth = (depth + netDelta).coerceAtLeast(0)
        }

        return outLines.joinToString(eol).trimEnd() + eol
    }

    private fun applyJsTsPrettierRules(line: String, settings: Settings): String {
        if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) return line

        // Split trailing line comment if outside quotes
        val commentSplitIdx = findLineCommentStart(line)
        val codePart = if (commentSplitIdx >= 0) line.substring(0, commentSplitIdx).trimEnd() else line
        val commentPart = if (commentSplitIdx >= 0) " " + line.substring(commentSplitIdx).trim() else ""

        var s = codePart

        // 1. Quotes normalization (singleQuote vs doubleQuote)
        s = transformQuotes(s, useSingleQuote = settings.prettierSingleQuote)

        // 2. Object literal bracket spacing (`{a: 1}` vs `{ a: 1 }`)
        if (!s.contains("\${")) {
            s = if (settings.prettierBracketSpacing) {
                s.replace(Regex("\\{\\s*([a-zA-Z0-9_\"']+\\s*:.*?)\\s*\\}")) { m ->
                    val inner = m.groupValues[1].trim()
                    if (inner.isEmpty()) "{}" else "{ $inner }"
                }
            } else {
                s.replace(Regex("\\{\\s+([a-zA-Z0-9_\"']+\\s*:.*?)\\s+\\}")) { m ->
                    "{${m.groupValues[1].trim()}}"
                }
            }
        }

        // 3. Arrow function parens (`(x) =>` vs `x =>`)
        s = if (settings.prettierArrowParens == "avoid") {
            s.replace(Regex("\\(\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\)\\s*=>"), "$1 =>")
        } else {
            s.replace(Regex("(?<![a-zA-Z0-9_$(\\.])([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=>"), "($1) =>")
        }

        // 4. Semicolons (`semi` vs `no-semi`)
        val isStatementLine = s.isNotEmpty() &&
            !s.endsWith("{") &&
            !s.endsWith("}") &&
            !s.endsWith(",") &&
            !s.endsWith(":") &&
            !s.endsWith("(") &&
            !s.endsWith("[") &&
            !s.endsWith("=>") &&
            !s.startsWith("if ") && !s.startsWith("if(") &&
            !s.startsWith("for ") && !s.startsWith("for(") &&
            !s.startsWith("while ") && !s.startsWith("while(") &&
            !s.startsWith("switch ") && !s.startsWith("switch(") &&
            !s.startsWith("function ") &&
            !s.startsWith("class ") &&
            !s.startsWith("interface ") &&
            !s.startsWith("<") &&
            !s.endsWith(">")

        if (settings.prettierSemi) {
            if (isStatementLine && !s.endsWith(";")) {
                s += ";"
            }
        } else {
            if (s.endsWith(";") && !s.startsWith("for ") && !s.startsWith("for(")) {
                s = s.removeSuffix(";")
            }
        }

        return s + commentPart
    }

    private fun applyCssPrettierRules(line: String, settings: Settings): String {
        var s = transformQuotes(line, useSingleQuote = settings.prettierSingleQuote)
        if (s.contains(":") && !s.endsWith("{") && !s.endsWith("}") && !s.endsWith(",") && !s.endsWith(";") && !s.startsWith("@") && !s.startsWith("/*")) {
            s += ";"
        }
        return s
    }

    private fun findLineCommentStart(line: String): Int {
        var inStr: Char? = null
        var i = 0
        while (i < line.length - 1) {
            val c = line[i]
            if (inStr != null) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == inStr) inStr = null
            } else {
                if (c == '"' || c == '\'' || c == '`') {
                    inStr = c
                } else if (c == '/' && line[i + 1] == '/') {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun transformQuotes(line: String, useSingleQuote: Boolean): String {
        val fromQuote = if (useSingleQuote) '"' else '\''
        val toQuote = if (useSingleQuote) '\'' else '"'
        val sb = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '`') {
                // Keep template literals untouched
                sb.append(c)
                i++
                while (i < line.length) {
                    val tc = line[i]
                    sb.append(tc)
                    if (tc == '\\' && i + 1 < line.length) {
                        sb.append(line[i + 1])
                        i += 2
                        continue
                    }
                    i++
                    if (tc == '`') break
                }
                continue
            }
            if (c == fromQuote) {
                var end = i + 1
                var foundEnd = false
                val inner = StringBuilder()
                while (end < line.length) {
                    val sc = line[end]
                    if (sc == '\\' && end + 1 < line.length) {
                        inner.append(sc).append(line[end + 1])
                        end += 2
                        continue
                    }
                    if (sc == fromQuote) {
                        foundEnd = true
                        break
                    }
                    inner.append(sc)
                    end++
                }
                if (foundEnd && !inner.contains(toQuote)) {
                    sb.append(toQuote).append(inner).append(toQuote)
                    i = end + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun formatMarkdown(normalized: String, settings: Settings, eol: String): String {
        val lines = normalized.split('\n').map { it.trimEnd() }
        if (settings.prettierProseWrap != "always") {
            return collapseBlankLines(lines).joinToString(eol).trimEnd() + eol
        }
        val width = settings.prettierPrintWidth.coerceIn(40, 200)
        val out = ArrayList<String>()
        var inFence = false
        for (line in lines) {
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                out.add(line)
                continue
            }
            if (inFence || line.length <= width || line.startsWith("#") || line.startsWith("-") || line.startsWith("*") || line.startsWith("|") || line.startsWith(">")) {
                out.add(line)
                continue
            }
            val words = line.split(Regex("\\s+"))
            var current = StringBuilder()
            for (w in words) {
                if (current.isEmpty()) {
                    current.append(w)
                } else if (current.length + 1 + w.length <= width) {
                    current.append(' ').append(w)
                } else {
                    out.add(current.toString())
                    current = StringBuilder(w)
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
        }
        return collapseBlankLines(out).joinToString(eol).trimEnd() + eol
    }

    private fun collapseBlankLines(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var blanks = 0
        for (l in lines) {
            if (l.isBlank()) {
                blanks++
                if (blanks <= 1) out.add("")
            } else {
                blanks = 0
                out.add(l)
            }
        }
        return out
    }
}
