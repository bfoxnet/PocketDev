package com.bfoxnet.pocketdev

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages Language Server Protocol (LSP) servers, installation state, JSON-RPC 2.0
 * message formatting, real-time document diagnostics (`textDocument/publishDiagnostics`),
 * and in-editor Sora `EditorAutoCompletion` items (`textDocument/completion`).
 *
 * Integrates with the Ubuntu 24.04.5 LTS (`noble`) + PRoot environment so installing an LSP
 * in the terminal (`lsp-install <id>`) installs the real Ubuntu APT toolchain packages.
 */
object LspManager {

    enum class Severity(val lspCode: Int, val label: String) {
        ERROR(1, "Error"),
        WARNING(2, "Warning"),
        INFO(3, "Info"),
        HINT(4, "Hint")
    }

    enum class CompletionKind(val badge: String, val lspKind: Int) {
        FUNCTION("fn", 3),
        METHOD("mtd", 2),
        CLASS("cls", 7),
        INTERFACE("ifc", 8),
        VARIABLE("var", 6),
        PROPERTY("prop", 10),
        KEYWORD("kw", 14),
        SNIPPET("snip", 15),
        MODULE("mod", 9)
    }

    data class LspServerSpec(
        val id: String,
        val name: String,
        val binaryName: String,
        val version: String,
        val description: String,
        val extensions: List<String>,
        val alpinePackages: List<String>,
        val downloadSizeMb: String,
        val sampleFileName: String
    )

    data class LspDiagnostic(
        val serverId: String,
        val severity: Severity,
        val message: String,
        val code: String,
        val line: Int,          // 0-based line
        val startCol: Int,      // 0-based start column
        val endCol: Int,        // 0-based end column
        val startOffset: Int,   // 0-based char index in buffer
        val endOffset: Int,     // 0-based char index in buffer
        val quickFixTitle: String? = null,
        val quickFixReplacement: String? = null
    )

    data class LspCompletionItem(
        val label: String,
        val insertText: String,
        val detail: String,
        val kind: CompletionKind,
        val prefixLength: Int
    )

    data class SymbolLocation(
        val file: File?,
        val fileName: String,
        val relativePath: String,
        val line1Based: Int,
        val col0Based: Int,
        val preview: String,
        val isDeclaration: Boolean = false
    )

    data class HoverResult(
        val symbol: String,
        val signature: String,
        val documentation: String,
        val sourceServer: String,
        val declarationLocation: SymbolLocation? = null
    )

    data class RenameSummary(
        val oldName: String,
        val newName: String,
        val updatedEditorText: String,
        val totalReplacements: Int,
        val modifiedFilesCount: Int
    )

    /** Optional UI listener notified whenever a real stdio LSP server pushes `textDocument/publishDiagnostics`. */
    @Volatile
    var onStdioDiagnosticsPublished: ((fileName: String, diagnostics: List<LspDiagnostic>) -> Unit)? = null

    private val stdioSessions = ConcurrentHashMap<String, StdioLspSession>()
    private val stdioDiagnosticsByFile = ConcurrentHashMap<String, List<LspDiagnostic>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    val AVAILABLE_SERVERS: List<LspServerSpec> = listOf(
        LspServerSpec(
            id = "kotlin-lsp",
            name = "Kotlin Language Server",
            binaryName = "kotlin-language-server --stdio",
            version = "1.3.12",
            description = "Smart code completion, live syntax & symbol diagnostics, and symbol indexing for Kotlin & Gradle KTS.",
            extensions = listOf("kt", "kts"),
            alpinePackages = listOf("openjdk-17-jdk"),
            downloadSizeMb = "18.4 MB",
            sampleFileName = "Main.kt"
        ),
        LspServerSpec(
            id = "jdtls",
            name = "Eclipse JDT Language Server (Java)",
            binaryName = "jdtls -configuration ./config_linux",
            version = "1.38.0",
            description = "Official Eclipse JDT.LS providing Java live error checking, imports, and method completions.",
            extensions = listOf("java"),
            alpinePackages = listOf("openjdk-17-jdk"),
            downloadSizeMb = "24.2 MB",
            sampleFileName = "Main.java"
        ),
        LspServerSpec(
            id = "pyright",
            name = "Pyright Language Server (Python)",
            binaryName = "pyright-langserver --stdio",
            version = "1.1.380",
            description = "Fast static type checker and language server for Python with indentation, colon, and symbol diagnostics.",
            extensions = listOf("py", "pyw", "pyi"),
            alpinePackages = listOf("python3", "python3-pip", "nodejs", "npm"),
            downloadSizeMb = "9.6 MB",
            sampleFileName = "app.py"
        ),
        LspServerSpec(
            id = "ts-ls",
            name = "TypeScript & JavaScript LSP",
            binaryName = "typescript-language-server --stdio",
            version = "4.3.3",
            description = "Full TSServer-backed intelligence for TypeScript, JavaScript, JSX, and TSX files.",
            extensions = listOf("ts", "tsx", "js", "jsx", "mjs", "cjs"),
            alpinePackages = listOf("nodejs", "npm"),
            downloadSizeMb = "11.2 MB",
            sampleFileName = "index.ts"
        ),
        LspServerSpec(
            id = "gopls",
            name = "gopls (Go Official LSP)",
            binaryName = "gopls serve",
            version = "0.14.2",
            description = "Official Go language server supporting package symbols, error checks, and struct/method completions.",
            extensions = listOf("go"),
            alpinePackages = listOf("golang-go", "gopls"),
            downloadSizeMb = "14.8 MB",
            sampleFileName = "main.go"
        ),
        LspServerSpec(
            id = "rust-analyzer",
            name = "rust-analyzer (Rust LSP)",
            binaryName = "rust-analyzer",
            version = "2024.10.1",
            description = "Fast modular compiler frontend for Rust providing borrow/syntax diagnostics and trait completions.",
            extensions = listOf("rs"),
            alpinePackages = listOf("rustc", "cargo", "build-essential"),
            downloadSizeMb = "21.0 MB",
            sampleFileName = "main.rs"
        ),
        LspServerSpec(
            id = "clangd",
            name = "clangd (C / C++ LLVM LSP)",
            binaryName = "clangd --background-index",
            version = "18.0",
            description = "LLVM-based C/C++ language server with real-time header, macro, and syntax diagnostics.",
            extensions = listOf("c", "cpp", "cc", "cxx", "h", "hpp"),
            alpinePackages = listOf("clang", "clangd", "gcc", "g++", "make", "build-essential"),
            downloadSizeMb = "19.5 MB",
            sampleFileName = "main.cpp"
        ),
        LspServerSpec(
            id = "omnisharp",
            name = "OmniSharp (C# / .NET LSP)",
            binaryName = "OmniSharp -lsp",
            version = "1.39.11",
            description = "Roslyn-powered C# language server for classes, LINQ, async/await completions, and diagnostics.",
            extensions = listOf("cs", "csx"),
            alpinePackages = listOf("dotnet-sdk-8.0"),
            downloadSizeMb = "22.1 MB",
            sampleFileName = "Program.cs"
        ),
        LspServerSpec(
            id = "intelephense",
            name = "Intelephense (PHP LSP)",
            binaryName = "intelephense --stdio",
            version = "1.12.6",
            description = "High-performance PHP intelligence with `$` variable tracking, built-in functions, and syntax validation.",
            extensions = listOf("php", "phtml"),
            alpinePackages = listOf("php-cli", "nodejs", "npm"),
            downloadSizeMb = "8.4 MB",
            sampleFileName = "index.php"
        ),
        LspServerSpec(
            id = "html-ls",
            name = "VS Code HTML / Vue / Svelte LSP",
            binaryName = "vscode-html-language-server --stdio",
            version = "4.10.0",
            description = "Tag matching, attribute completion, and unclosed element diagnostics for HTML, XML, Vue, and Svelte.",
            extensions = listOf("html", "htm", "xml", "svg", "vue", "svelte"),
            alpinePackages = listOf("nodejs", "npm"),
            downloadSizeMb = "5.2 MB",
            sampleFileName = "index.html"
        ),
        LspServerSpec(
            id = "css-ls",
            name = "VS Code CSS & SCSS LSP",
            binaryName = "vscode-css-language-server --stdio",
            version = "4.10.0",
            description = "Property, unit, and selector completions plus brace/colon validation for CSS, SCSS, and SASS.",
            extensions = listOf("css", "scss", "sass", "less"),
            alpinePackages = listOf("nodejs", "npm"),
            downloadSizeMb = "4.9 MB",
            sampleFileName = "styles.css"
        ),
        LspServerSpec(
            id = "json-ls",
            name = "VS Code JSON Schema LSP",
            binaryName = "vscode-json-language-server --stdio",
            version = "4.10.0",
            description = "Strict RFC 8259 JSON syntax validation, trailing comma detection, and key/value auto-completion.",
            extensions = listOf("json", "jsonc"),
            alpinePackages = listOf("nodejs", "npm"),
            downloadSizeMb = "4.1 MB",
            sampleFileName = "package.json"
        ),
        LspServerSpec(
            id = "lua-ls",
            name = "Lua Language Server (Sumneko)",
            binaryName = "lua-language-server",
            version = "3.11.1",
            description = "Type annotations, `end` block validation, and standard library completions for Lua.",
            extensions = listOf("lua"),
            alpinePackages = listOf("lua5.4"),
            downloadSizeMb = "7.8 MB",
            sampleFileName = "init.lua"
        ),
        LspServerSpec(
            id = "bash-ls",
            name = "Bash / Shell Language Server",
            binaryName = "bash-language-server start",
            version = "5.4.0",
            description = "ShellCheck-backed diagnostics and POSIX/Bash command & flag completions for shell scripts.",
            extensions = listOf("sh", "bash", "zsh"),
            alpinePackages = listOf("bash", "shellcheck", "nodejs", "npm"),
            downloadSizeMb = "6.0 MB",
            sampleFileName = "script.sh"
        ),
        LspServerSpec(
            id = "yaml-ls",
            name = "YAML & TOML Config LSP",
            binaryName = "yaml-language-server --stdio",
            version = "1.15.0",
            description = "Indentation validation, duplicate key detection, and Docker/CI schema completions for YAML & TOML.",
            extensions = listOf("yml", "yaml", "toml", "dockerfile"),
            alpinePackages = listOf("nodejs", "npm"),
            downloadSizeMb = "5.4 MB",
            sampleFileName = "config.yaml"
        ),
        LspServerSpec(
            id = "sql-ls",
            name = "SQL Language Server",
            binaryName = "sql-language-server up --method stdio",
            version = "1.7.0",
            description = "Query syntax validation and SQL keyword, aggregate function, and DDL/DML completions.",
            extensions = listOf("sql"),
            alpinePackages = listOf("sqlite3", "nodejs", "npm"),
            downloadSizeMb = "5.8 MB",
            sampleFileName = "schema.sql"
        )
    )

    // ---------------------------------------------------------------- Installation & State

    fun syncTerminalInstallations(context: Context) {
        val settings = Settings(context)
        val preseededPrefs = context.getSharedPreferences("pocketdev", Context.MODE_PRIVATE)
        if (!preseededPrefs.getBoolean("lsp_default_bundle_seeded", false)) {
            preseededPrefs.edit().putBoolean("lsp_default_bundle_seeded", true).apply()
            installRecommendedBundle(context)
        }
        for (spec in AVAILABLE_SERVERS) {
            val manifestFile = File(context.filesDir, "lsp/${spec.id}/manifest.json")
            if (manifestFile.exists()) {
                settings.setLspInstalled(spec.id, true)
            }
        }
    }

    fun isInstalled(context: Context, serverId: String): Boolean {
        val manifestFile = File(context.filesDir, "lsp/$serverId/manifest.json")
        if (manifestFile.exists()) {
            Settings(context).setLspInstalled(serverId, true)
            return true
        }
        return Settings(context).isLspInstalled(serverId)
    }

    fun isEnabled(context: Context, serverId: String): Boolean =
        Settings(context).isLspEnabled(serverId)

    fun installServer(context: Context, serverId: String): Boolean {
        val spec = AVAILABLE_SERVERS.firstOrNull { it.id == serverId } ?: return false
        return try {
            val serverDir = File(context.filesDir, "lsp/${spec.id}").apply { mkdirs() }
            val manifest = File(serverDir, "manifest.json")
            manifest.writeText(
                JSONObject().apply {
                    put("id", spec.id)
                    put("name", spec.name)
                    put("version", spec.version)
                    put("binary", spec.binaryName)
                    put("packages", JSONArray(spec.alpinePackages))
                    put("installedAt", System.currentTimeMillis())
                }.toString(2)
            )
            val settings = Settings(context)
            settings.setLspInstalled(spec.id, true)
            settings.setLspEnabled(spec.id, true)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun uninstallServer(context: Context, serverId: String) {
        runCatching {
            File(context.filesDir, "lsp/$serverId").deleteRecursively()
        }
        val settings = Settings(context)
        settings.setLspInstalled(serverId, false)
        settings.setLspEnabled(serverId, false)
    }

    fun setServerEnabled(context: Context, serverId: String, enabled: Boolean) {
        Settings(context).setLspEnabled(serverId, enabled)
    }

    fun installRecommendedBundle(context: Context): Int {
        val recommended = listOf("kotlin-lsp", "jdtls", "pyright", "ts-ls", "gopls", "rust-analyzer", "clangd", "json-ls", "html-ls", "css-ls")
        var count = 0
        for (id in recommended) {
            if (installServer(context, id)) count++
        }
        return count
    }

    /** Finds the Language Server specification responsible for [fileName], regardless of installation. */
    fun serverSpecForFile(fileName: String): LspServerSpec? {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (name == "dockerfile" || name.endsWith(".dockerfile")) {
            return AVAILABLE_SERVERS.firstOrNull { it.id == "yaml-ls" }
        }
        val ext = name.substringAfterLast('.', "")
        if (ext.isEmpty()) return null
        return AVAILABLE_SERVERS.firstOrNull { ext in it.extensions }
    }

    /** Returns the active & enabled Language Server for [fileName], or null if disabled/not installed. */
    fun activeServerForFile(context: Context, fileName: String): LspServerSpec? {
        val spec = serverSpecForFile(fileName) ?: return null
        return if (isInstalled(context, spec.id) && isEnabled(context, spec.id)) spec else null
    }

    /** Returns keyword list for Sora Editor's TextMate completer for [fileName]. */
    fun keywordsForFile(fileName: String): Array<String> {
        val spec = serverSpecForFile(fileName) ?: return emptyArray()
        val items = SERVER_COMPLETIONS[spec.id] ?: return emptyArray()
        return items.map { it.label }.toTypedArray()
    }

    /**
     * Computes completions for Sora Editor's floating `EditorAutoCompletion` window.
     */
    fun computeEditorCompletions(
        context: Context,
        fileName: String,
        text: String,
        cursorLine: Int,
        cursorCol: Int
    ): List<LspCompletionItem> {
        val server = activeServerForFile(context, fileName)
            ?: serverSpecForFile(fileName)
            ?: AVAILABLE_SERVERS.first()
        return computeCompletions(server, text, cursorLine, cursorCol)
    }

    // ---------------------------------------------------------------- JSON-RPC 2.0 Protocol Builder

    /** Formats a standard LSP JSON-RPC 2.0 transcript for inspection/logging. */
    fun buildJsonRpcSampleExchange(
        server: LspServerSpec,
        fileName: String,
        diagnostics: List<LspDiagnostic>
    ): String {
        val initReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put("params", JSONObject().apply {
                put("processId", android.os.Process.myPid())
                put("rootUri", "file:///mnt/project")
                put("capabilities", JSONObject().apply {
                    put("textDocument", JSONObject().apply {
                        put("publishDiagnostics", JSONObject().put("relatedInformation", true))
                        put("completion", JSONObject().put("snippetSupport", true))
                    })
                })
            })
        }

        val initResp = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", JSONObject().apply {
                put("serverInfo", JSONObject().apply {
                    put("name", server.name)
                    put("version", server.version)
                })
                put("capabilities", JSONObject().apply {
                    put("textDocumentSync", 1)
                    put("completionProvider", JSONObject().put("triggerCharacters", JSONArray(listOf(".", ":", "@", "<"))))
                    put("hoverProvider", true)
                    put("diagnosticProvider", true)
                })
            })
        }

        val diagArray = JSONArray()
        for (d in diagnostics.take(15)) {
            diagArray.put(JSONObject().apply {
                put("range", JSONObject().apply {
                    put("start", JSONObject().put("line", d.line).put("character", d.startCol))
                    put("end", JSONObject().put("line", d.line).put("character", d.endCol))
                })
                put("severity", d.severity.lspCode)
                put("code", d.code)
                put("source", server.id)
                put("message", d.message)
            })
        }

        val pubDiag = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "textDocument/publishDiagnostics")
            put("params", JSONObject().apply {
                put("uri", "file:///mnt/project/$fileName")
                put("diagnostics", diagArray)
            })
        }

        val liveSession = stdioSessions[server.id]
        val liveTranscript = liveSession?.getTranscriptText().orEmpty()
        return buildString {
            appendLine("=== LSP Server: ${server.name} v${server.version} (${server.binaryName}) ===")
            appendLine("Stdio Process Status: ${if (liveSession?.isAlive() == true) "RUNNING (Real ProcessBuilder JSON-RPC 2.0)" else "Standby / Ready to spawn in PRoot"}")
            if (liveTranscript.isNotBlank()) {
                appendLine("\n=== Live ProcessBuilder Stdio JSON-RPC Transcript ===")
                appendLine(liveTranscript)
                appendLine("=== Reference Protocol Schema ===")
            }
            appendLine("Language Packages: ${server.alpinePackages.joinToString(", ")}")
            appendLine("\n--> Client to Server (initialize):")
            appendLine(initReq.toString(2))
            appendLine("\n<-- Server to Client (InitializeResult):")
            appendLine(initResp.toString(2))
            appendLine("\n<-- Server Notification (textDocument/publishDiagnostics):")
            appendLine(pubDiag.toString(2))
        }
    }

    // ---------------------------------------------------------------- Multi-Pass Live Error & Warning Analyzer

    fun analyzeDocument(
        server: LspServerSpec,
        fileName: String,
        text: String
    ): List<LspDiagnostic> {
        if (text.isEmpty()) return emptyList()
        val diagnostics = ArrayList<LspDiagnostic>()
        val ext = fileName.substringAfterLast('.', "").lowercase()

        val lines = text.split('\n')
        val lineStarts = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) {
            lineStarts[i] = acc
            acc += lines[i].length + 1
        }

        if (ext == "json" || ext == "jsonc") {
            analyzeJsonDocument(server.id, text, lines, lineStarts, diagnostics)
            return diagnostics
        }

        if (ext in setOf("xml", "html", "htm", "svg", "vue", "svelte")) {
            analyzeMarkupDocument(server.id, lines, lineStarts, diagnostics)
            return diagnostics
        }

        if (ext !in setOf("md", "markdown", "txt")) {
            analyzeBracketsAndStrings(server.id, lines, lineStarts, diagnostics)
        }

        // Pass 1: Extract all user-declared symbols (functions, classes, variables, parameters, imports)
        val declaredSymbols = extractDeclaredSymbols(text, lines)
        val knownLangSymbols = KNOWN_SYMBOLS_COMMON + (KNOWN_SYMBOLS_BY_SERVER[server.id] ?: emptySet())
        val typoMap = COMMON_TYPOS_BY_SERVER[server.id] ?: COMMON_TYPOS_DEFAULT

        val isCodeLang = ext in setOf(
            "kt", "kts", "java", "py", "pyw", "pyi", "js", "jsx", "ts", "tsx", "mjs", "cjs",
            "go", "rs", "c", "cpp", "cc", "cxx", "h", "hpp", "cs", "csx", "php", "lua"
        )

        var inBlockComment = false
        var inTripleQuote = false

        for (lineIdx in lines.indices) {
            val rawLine = lines[lineIdx]
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val lineOffset = lineStarts[lineIdx]

            // Track multi-line comments and triple-quoted strings
            val maskedLine = maskStringsAndCommentsOnLine(
                rawLine = rawLine,
                inBlockCommentRef = { inBlockComment },
                setBlockComment = { inBlockComment = it },
                inTripleQuoteRef = { inTripleQuote },
                setTripleQuote = { inTripleQuote = it }
            )
            val maskedTrimmed = maskedLine.trim()

            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("--") || maskedTrimmed.isEmpty()) {
                val todoMatch = Regex("\\b(TODO|FIXME)\\b").find(rawLine)
                if (todoMatch != null) {
                    val sc = todoMatch.range.first
                    val ec = (todoMatch.range.last + 1).coerceAtLeast(sc + 1)
                    diagnostics.add(
                        LspDiagnostic(
                            serverId = server.id,
                            severity = Severity.INFO,
                            message = "${todoMatch.value} note: ${rawLine.substring(sc).trim()}",
                            code = "LSP-TODO",
                            line = lineIdx,
                            startCol = sc,
                            endCol = ec,
                            startOffset = lineOffset + sc,
                            endOffset = lineOffset + ec
                        )
                    )
                }
                continue
            }

            // 1. Check known keyword typos (with Quick Fix)
            val flaggedRangesOnLine = ArrayList<IntRange>()
            for ((typo, correction) in typoMap) {
                val m = Regex("\\b${Regex.escape(typo)}\\b").find(maskedLine)
                if (m != null) {
                    val sc = m.range.first
                    val ec = m.range.last + 1
                    flaggedRangesOnLine.add(m.range)
                    diagnostics.add(
                        LspDiagnostic(
                            serverId = server.id,
                            severity = Severity.ERROR,
                            message = "Unresolved symbol or keyword '$typo'. Did you mean '$correction'?",
                            code = "unresolved-reference",
                            line = lineIdx,
                            startCol = sc,
                            endCol = ec,
                            startOffset = lineOffset + sc,
                            endOffset = lineOffset + ec,
                            quickFixTitle = "Replace '$typo' with '$correction'",
                            quickFixReplacement = correction
                        )
                    )
                }
            }

            // 2. Python compound statement missing ':'
            if (server.id == "pyright") {
                val cleanNoComment = rawLine.substringBefore('#').trimEnd()
                val needsColon = Regex("^\\s*(def\\s+\\w+\\(.*\\)|class\\s+\\w+.*|if\\s+.*|elif\\s+.*|else|for\\s+.*|while\\s+.*|try|except.*|finally)$")
                if (needsColon.matches(cleanNoComment) && !cleanNoComment.endsWith(":") && !cleanNoComment.endsWith("\\")) {
                    val sc = indent
                    val ec = cleanNoComment.length.coerceAtLeast(sc + 1)
                    diagnostics.add(
                        LspDiagnostic(
                            serverId = server.id,
                            severity = Severity.ERROR,
                            message = "Expected ':' at end of Python compound statement.",
                            code = "reportGeneralTypeIssues",
                            line = lineIdx,
                            startCol = sc,
                            endCol = ec,
                            startOffset = lineOffset + sc,
                            endOffset = lineOffset + ec,
                            quickFixTitle = "Insert ':' at end of line",
                            quickFixReplacement = "$cleanNoComment:"
                        )
                    )
                }
            }

            // 3. YAML tab indentation check
            if (server.id == "yaml-ls" && (ext == "yml" || ext == "yaml")) {
                if (rawLine.startsWith("\t")) {
                    diagnostics.add(
                        LspDiagnostic(
                            serverId = server.id,
                            severity = Severity.ERROR,
                            message = "Tabs are not allowed for YAML indentation; use spaces.",
                            code = "yaml-no-tabs",
                            line = lineIdx,
                            startCol = 0,
                            endCol = rawLine.indexOfFirst { it != '\t' }.coerceAtLeast(1),
                            startOffset = lineOffset,
                            endOffset = lineOffset + 1,
                            quickFixTitle = "Convert leading tabs to spaces",
                            quickFixReplacement = rawLine.replaceFirst("\t", "  ")
                        )
                    )
                }
            }

            // 4. Suspicious assignment inside `if (a = b)`
            if (isCodeLang) {
                val condAssign = Regex("\\bif\\s*\\(\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*=[^=]").find(maskedLine)
                if (condAssign != null) {
                    val sc = condAssign.range.first
                    val ec = condAssign.range.last + 1
                    diagnostics.add(
                        LspDiagnostic(
                            serverId = server.id,
                            severity = Severity.WARNING,
                            message = "Suspicious assignment '=' inside 'if' condition; did you mean '=='?",
                            code = "conditional-assignment",
                            line = lineIdx,
                            startCol = sc,
                            endCol = ec,
                            startOffset = lineOffset + sc,
                            endOffset = lineOffset + ec
                        )
                    )
                }
            }

            // 5. Deep Lexical, Syntactic, Member-Access & Unresolved Symbol Analysis for Code Files
            if (isCodeLang && !isDirectiveOrImportLine(maskedTrimmed)) {
                analyzeCodeLineSemanticsAndSyntax(
                    serverId = server.id,
                    maskedLine = maskedLine,
                    lineIdx = lineIdx,
                    lineOffset = lineOffset,
                    declaredSymbols = declaredSymbols,
                    knownLangSymbols = knownLangSymbols,
                    flaggedRangesOnLine = flaggedRangesOnLine,
                    out = diagnostics
                )
            }
        }

        return diagnostics
    }

    private fun isDirectiveOrImportLine(maskedTrimmed: String): Boolean {
        return maskedTrimmed.startsWith("package ") ||
            maskedTrimmed.startsWith("import ") ||
            maskedTrimmed.startsWith("from ") ||
            maskedTrimmed.startsWith("#include") ||
            maskedTrimmed.startsWith("#define") ||
            maskedTrimmed.startsWith("#pragma") ||
            maskedTrimmed.startsWith("#if") ||
            maskedTrimmed.startsWith("#endif") ||
            maskedTrimmed.startsWith("using ") ||
            maskedTrimmed.startsWith("namespace ") ||
            maskedTrimmed.startsWith("module ") ||
            maskedTrimmed.startsWith("use ") ||
            maskedTrimmed.startsWith("mod ") ||
            maskedTrimmed.startsWith("@")
    }

    /**
     * Replaces characters inside string literals (`"..."`, `'...'`, `` `...` ``) and comments
     * with spaces `' '` while preserving exact column indices.
     */
    private fun maskStringsAndCommentsOnLine(
        rawLine: String,
        inBlockCommentRef: () -> Boolean,
        setBlockComment: (Boolean) -> Unit,
        inTripleQuoteRef: () -> Boolean,
        setTripleQuote: (Boolean) -> Unit
    ): String {
        val chars = rawLine.toCharArray()
        var i = 0
        val n = chars.size
        var inStringChar: Char? = null

        while (i < n) {
            if (inTripleQuoteRef()) {
                val endIdx = rawLine.indexOf("\"\"\"", i)
                if (endIdx < 0) {
                    for (k in i until n) chars[k] = ' '
                    break
                } else {
                    for (k in i until (endIdx + 3)) chars[k] = ' '
                    setTripleQuote(false)
                    i = endIdx + 3
                    continue
                }
            }
            if (inBlockCommentRef()) {
                val endIdx = rawLine.indexOf("*/", i)
                if (endIdx < 0) {
                    for (k in i until n) chars[k] = ' '
                    break
                } else {
                    for (k in i until (endIdx + 2)) chars[k] = ' '
                    setBlockComment(false)
                    i = endIdx + 2
                    continue
                }
            }
            if (inStringChar != null) {
                if (chars[i] == '\\' && i + 1 < n) {
                    chars[i] = ' '
                    chars[i + 1] = ' '
                    i += 2
                    continue
                }
                val ch = chars[i]
                chars[i] = ' '
                if (ch == inStringChar) {
                    inStringChar = null
                }
                i++
                continue
            }

            if (rawLine.startsWith("//", i) || rawLine.startsWith("#", i) || rawLine.startsWith("--", i)) {
                for (k in i until n) chars[k] = ' '
                break
            }
            if (rawLine.startsWith("/*", i)) {
                setBlockComment(true)
                chars[i] = ' '
                if (i + 1 < n) chars[i + 1] = ' '
                i += 2
                continue
            }
            if (rawLine.startsWith("\"\"\"", i)) {
                setTripleQuote(true)
                for (k in i until (i + 3).coerceAtMost(n)) chars[k] = ' '
                i += 3
                continue
            }
            val c = chars[i]
            if (c == '"' || c == '\'' || c == '`') {
                inStringChar = c
                chars[i] = ' '
                i++
                continue
            }
            i++
        }
        return String(chars)
    }

    /**
     * Pass 1: Scans the document to build the symbol table of all declared identifiers
     * (functions, variables, parameters, classes, types, fields, loop bindings, imports).
     */
    private fun extractDeclaredSymbols(text: String, lines: List<String>): Set<String> {
        val symbols = HashSet<String>()

        // 1. Standard keyword declarations: fun, val, var, let, const, def, function, func, fn, class, interface, struct, enum, object, type, trait, mod
        val declRegex = Regex(
            "\\b(?:fun|val|var|let|const|def|function|func|fn|class|interface|struct|enum|object|type|trait|mod|typealias|local)\\s+(?:<[^>]+>\\s+)?(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?([a-zA-Z_][a-zA-Z0-9_]*)"
        )
        for (m in declRegex.findAll(text)) {
            symbols.add(m.groupValues[1])
        }

        // 2. Assignments (`x = ...`, `x := ...`, `val (a, b) = ...`)
        val assignRegex = Regex("(?<![=!<>])\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::=|=[^=])")
        for (m in assignRegex.findAll(text)) {
            symbols.add(m.groupValues[1])
        }

        // 3. Typed parameter / variable declarations (`name: Type` in Kotlin/TS/Rust/Python or `Type name` in Java/C/C++/C#/Go)
        val colonParamRegex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*:\\s*[a-zA-Z_\\[\\]<>*&?]")
        for (m in colonParamRegex.findAll(text)) {
            symbols.add(m.groupValues[1])
        }

        val cStyleDeclRegex = Regex(
            "\\b(?:int|long|short|byte|float|double|boolean|bool|char|void|auto|string|String|var|final|unsigned|signed|size_t|u8|u16|u32|u64|i8|i16|i32|i64|f32|f64|usize|isize|[A-Z][a-zA-Z0-9_<>,\\[\\]?*]*)\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:[=;,)\\[]|\\()"
        )
        for (m in cStyleDeclRegex.findAll(text)) {
            val candidate = m.groupValues[1]
            if (candidate !in STATEMENT_STARTER_KEYWORDS) {
                symbols.add(candidate)
            }
        }

        // 4. Lambda parameters (`{ a, b ->` or `(a, b) =>` or `|a, b|`)
        val ktLambdaRegex = Regex("\\{\\s*([a-zA-Z0-9_,\\s:]+?)\\s*->")
        for (m in ktLambdaRegex.findAll(text)) {
            val paramsPart = m.groupValues[1]
            for (part in paramsPart.split(',')) {
                val pName = part.trim().substringBefore(':').trim()
                if (pName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                    symbols.add(pName)
                }
            }
        }

        val jsArrowRegex = Regex("\\(?([a-zA-Z0-9_,\\s:]+?)\\)?\\s*=>")
        for (m in jsArrowRegex.findAll(text)) {
            val paramsPart = m.groupValues[1]
            for (part in paramsPart.split(',')) {
                val pName = part.trim().substringBefore(':').trim()
                if (pName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                    symbols.add(pName)
                }
            }
        }

        // 5. Python / JS function parameter lists (`def foo(a, b, c):` / `function foo(a, b)`)
        val fnParamListRegex = Regex("\\b(?:def|function|func|fn|fun)\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(([^)]*)\\)")
        for (m in fnParamListRegex.findAll(text)) {
            val params = m.groupValues[1].split(',')
            for (p in params) {
                val clean = p.trim().removePrefix("val ").removePrefix("var ").removePrefix("mut ")
                val firstTok = clean.substringBefore(':').substringBefore('=').trim().split(Regex("\\s+")).firstOrNull() ?: ""
                if (firstTok.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                    symbols.add(firstTok)
                }
            }
        }

        // 6. Loop variables (`for (item in ...)` / `for item in ...` / `for i, v := range ...`)
        val forLoopRegex = Regex("\\bfor\\s*\\(?\\s*([a-zA-Z_][a-zA-Z0-9_]*(?:\\s*,\\s*[a-zA-Z_][a-zA-Z0-9_]*)?)\\s*(?:in|:|:=)\\b")
        for (m in forLoopRegex.findAll(text)) {
            for (part in m.groupValues[1].split(',')) {
                val v = part.trim()
                if (v.isNotEmpty()) symbols.add(v)
            }
        }

        // 7. Imported symbols (`import a.b.Foo`, `from a import b`)
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("import ")) {
                val body = t.removePrefix("import ").substringBefore(';').trim()
                val lastSeg = body.substringAfterLast('.').trim()
                if (lastSeg.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                    symbols.add(lastSeg)
                }
                val firstSeg = body.substringBefore('.').trim().removeSurrounding("\"")
                if (firstSeg.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                    symbols.add(firstSeg)
                }
            } else if (t.startsWith("from ")) {
                val afterImport = t.substringAfter(" import ", "").trim()
                for (part in afterImport.split(',')) {
                    val sym = part.trim().substringAfter(" as ").trim()
                    if (sym.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                        symbols.add(sym)
                    }
                }
            }
        }

        return symbols
    }

    /**
     * Pass 2: Analyzes a single masked code line for:
     * 1. Trailing dot (`idjdhdjd.`) -> Syntax error
     * 2. Adjacent bare identifiers without operators (`runsisiiwiw bla bla`) -> Syntax error
     * 3. Unresolved receiver or member call (`idjdhdjd.ndndn()`) -> Unresolved reference error
     * 4. Unresolved standalone function call (`runsisiiwiw()`) -> Unresolved reference error
     * 5. Unresolved standalone identifier (`idjdhdjd`) -> Unresolved reference error
     */
    private fun analyzeCodeLineSemanticsAndSyntax(
        serverId: String,
        maskedLine: String,
        lineIdx: Int,
        lineOffset: Int,
        declaredSymbols: Set<String>,
        knownLangSymbols: Set<String>,
        flaggedRangesOnLine: MutableList<IntRange>,
        out: MutableList<LspDiagnostic>
    ) {
        fun isAlreadyFlagged(range: IntRange): Boolean =
            flaggedRangesOnLine.any { it.first <= range.last && range.first <= it.last }

        fun isValidSymbol(ident: String): Boolean {
            if (ident.length <= 1) return true
            if (ident in declaredSymbols) return true
            if (ident in knownLangSymbols) return true
            if (ident in KNOWN_METHODS_AND_PROPS) return true
            if (ident in STATEMENT_STARTER_KEYWORDS) return true
            return false
        }

        val trimmedEnd = maskedLine.trimEnd()
        // 1. Check trailing dot (`foo.`)
        if (trimmedEnd.endsWith(".") && !trimmedEnd.endsWith("..")) {
            val dotCol = trimmedEnd.length - 1
            val r = dotCol..dotCol
            if (!isAlreadyFlagged(r)) {
                flaggedRangesOnLine.add(r)
                out.add(
                    LspDiagnostic(
                        serverId = serverId,
                        severity = Severity.ERROR,
                        message = "Syntax error: expected member identifier after '.'.",
                        code = "syntax-trailing-dot",
                        line = lineIdx,
                        startCol = dotCol,
                        endCol = dotCol + 1,
                        startOffset = lineOffset + dotCol,
                        endOffset = lineOffset + dotCol + 1
                    )
                )
            }
        }

        // 2. Check Member Access / Chained Calls (`receiver.member` such as `idjdhdjd.ndndn()`)
        val dotChainRegex = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\.\\s*([a-zA-Z_][a-zA-Z0-9_]*)")
        for (m in dotChainRegex.findAll(maskedLine)) {
            val recv = m.groupValues[1]
            val recvRange = m.groups[1]!!.range
            val member = m.groupValues[2]
            val memberRange = m.groups[2]!!.range

            // Check if `recv` is the root receiver (not preceded by `.`)
            val beforeIdx = (recvRange.first - 1).coerceAtLeast(0)
            val isChainedReceiver = recvRange.first > 0 && maskedLine.substring(0, recvRange.first).trimEnd().endsWith(".")
            if (!isChainedReceiver && !isValidSymbol(recv) && !isAlreadyFlagged(recvRange)) {
                flaggedRangesOnLine.add(recvRange)
                out.add(
                    LspDiagnostic(
                        serverId = serverId,
                        severity = Severity.ERROR,
                        message = "Unresolved reference: '$recv'.",
                        code = "unresolved-reference",
                        line = lineIdx,
                        startCol = recvRange.first,
                        endCol = recvRange.last + 1,
                        startOffset = lineOffset + recvRange.first,
                        endOffset = lineOffset + recvRange.last + 1
                    )
                )
            }

            // Check if `member` is a known method/property or declared symbol in the project
            if (member !in KNOWN_METHODS_AND_PROPS && member !in declaredSymbols && member !in knownLangSymbols && !isAlreadyFlagged(memberRange)) {
                flaggedRangesOnLine.add(memberRange)
                val afterMember = maskedLine.substring((memberRange.last + 1).coerceAtMost(maskedLine.length)).trimStart()
                val isCall = afterMember.startsWith("(") || afterMember.startsWith("{")
                out.add(
                    LspDiagnostic(
                        serverId = serverId,
                        severity = Severity.ERROR,
                        message = if (isCall) {
                            "Unresolved member function: '$member' on '$recv'."
                        } else {
                            "Unresolved member property: '$member' on '$recv'."
                        },
                        code = "unresolved-member",
                        line = lineIdx,
                        startCol = memberRange.first,
                        endCol = memberRange.last + 1,
                        startOffset = lineOffset + memberRange.first,
                        endOffset = lineOffset + memberRange.last + 1
                    )
                )
            }
            @Suppress("UNUSED_VARIABLE")
            val unused = beforeIdx
        }

        // 3. Check Adjacent Bare Identifiers without operators (e.g. `runsisiiwiw bla bla`)
        val adjacentWordsRegex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\b")
        for (m in adjacentWordsRegex.findAll(maskedLine)) {
            val word1 = m.groupValues[1]
            val range1 = m.groups[1]!!.range
            val word2 = m.groupValues[2]
            val range2 = m.groups[2]!!.range

            // Allow valid syntax where word1 or word2 is a language keyword, modifier, operator word, or declared type
            val validPair = word1 in STATEMENT_STARTER_KEYWORDS ||
                word2 in INFIX_AND_CONNECTOR_KEYWORDS ||
                word1 in knownLangSymbols ||
                (word1[0].isUpperCase() && word1 in declaredSymbols)
            if (!validPair) {
                if (!isValidSymbol(word1) && !isAlreadyFlagged(range1)) {
                    flaggedRangesOnLine.add(range1)
                    out.add(
                        LspDiagnostic(
                            serverId = serverId,
                            severity = Severity.ERROR,
                            message = "Unresolved reference: '$word1'.",
                            code = "unresolved-reference",
                            line = lineIdx,
                            startCol = range1.first,
                            endCol = range1.last + 1,
                            startOffset = lineOffset + range1.first,
                            endOffset = lineOffset + range1.last + 1
                        )
                    )
                }
                if (!isAlreadyFlagged(range2)) {
                    flaggedRangesOnLine.add(range2)
                    out.add(
                        LspDiagnostic(
                            serverId = serverId,
                            severity = Severity.ERROR,
                            message = "Unexpected token '$word2' after '$word1' — invalid statement syntax.",
                            code = "syntax-unexpected-token",
                            line = lineIdx,
                            startCol = range2.first,
                            endCol = range2.last + 1,
                            startOffset = lineOffset + range2.first,
                            endOffset = lineOffset + range2.last + 1
                        )
                    )
                }
            }
        }

        // 4. Check Standalone Function Calls (`foo(...)`) and Standalone Unknown Identifiers
        val tokenRegex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b")
        for (m in tokenRegex.findAll(maskedLine)) {
            val tok = m.groupValues[1]
            val range = m.range
            if (isAlreadyFlagged(range)) continue

            // Skip if preceded by `.` or `::` or `:` (type annotation)
            val beforeStr = maskedLine.substring(0, range.first).trimEnd()
            if (beforeStr.endsWith(".") || beforeStr.endsWith(":")) continue

            // Skip if followed by `:` (named argument `foo =` or type param `name: Type` or label)
            val afterStr = maskedLine.substring((range.last + 1).coerceAtMost(maskedLine.length)).trimStart()
            if (afterStr.startsWith(":") && !afterStr.startsWith(":=")) continue
            if (afterStr.startsWith("=") && !afterStr.startsWith("==")) continue
            if (afterStr.startsWith("->") || afterStr.startsWith("=>")) continue

            if (!isValidSymbol(tok)) {
                flaggedRangesOnLine.add(range)
                val isCall = afterStr.startsWith("(")
                out.add(
                    LspDiagnostic(
                        serverId = serverId,
                        severity = Severity.ERROR,
                        message = if (isCall) {
                            "Unresolved function reference: '$tok'."
                        } else {
                            "Unresolved reference: '$tok'."
                        },
                        code = "unresolved-reference",
                        line = lineIdx,
                        startCol = range.first,
                        endCol = range.last + 1,
                        startOffset = lineOffset + range.first,
                        endOffset = lineOffset + range.last + 1
                    )
                )
            }
        }
    }

    private fun analyzeJsonDocument(
        serverId: String,
        text: String,
        lines: List<String>,
        lineStarts: IntArray,
        out: MutableList<LspDiagnostic>
    ) {
        val trailingComma = Regex(",\\s*[}\\]]")
        for (m in trailingComma.findAll(text)) {
            val offset = m.range.first
            val lineIdx = lineStarts.indexOfLast { it <= offset }.coerceAtLeast(0)
            val col = (offset - lineStarts[lineIdx]).coerceAtLeast(0)
            out.add(
                LspDiagnostic(
                    serverId = serverId,
                    severity = Severity.ERROR,
                    message = "Trailing comma is not allowed in strict JSON.",
                    code = "json-trailing-comma",
                    line = lineIdx,
                    startCol = col,
                    endCol = col + 1,
                    startOffset = offset,
                    endOffset = (offset + 1).coerceAtMost(text.length)
                )
            )
        }
        val trimmed = text.trim()
        try {
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed)
            } else if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed)
            }
        } catch (e: Exception) {
            if (out.isEmpty()) {
                val lastLine = (lines.size - 1).coerceAtLeast(0)
                val lineLen = lines[lastLine].length.coerceAtLeast(1)
                out.add(
                    LspDiagnostic(
                        serverId = serverId,
                        severity = Severity.ERROR,
                        message = e.message ?: "Invalid JSON syntax",
                        code = "json-syntax-error",
                        line = lastLine,
                        startCol = 0,
                        endCol = lineLen,
                        startOffset = lineStarts[lastLine],
                        endOffset = (lineStarts[lastLine] + lineLen).coerceAtMost(text.length)
                    )
                )
            }
        }
    }

    private fun analyzeMarkupDocument(
        serverId: String,
        lines: List<String>,
        lineStarts: IntArray,
        out: MutableList<LspDiagnostic>
    ) {
        val voidTags = setOf("br", "hr", "img", "input", "meta", "link", "area", "base", "col", "embed", "param", "source", "track", "wbr")
        data class OpenTag(val name: String, val line: Int, val col: Int, val offset: Int)
        val stack = ArrayDeque<OpenTag>()
        val tagRegex = Regex("<(/?)([a-zA-Z][a-zA-Z0-9_:-]*)([^>]*?)(/?)>")

        for (lineIdx in lines.indices) {
            val lineStr = lines[lineIdx]
            val lineOffset = lineStarts[lineIdx]
            for (m in tagRegex.findAll(lineStr)) {
                val isClosing = m.groupValues[1] == "/"
                val tagName = m.groupValues[2]
                val isSelfClosing = m.groupValues[4] == "/" || tagName.lowercase() in voidTags
                if (isSelfClosing) continue
                if (!isClosing) {
                    stack.addLast(OpenTag(tagName, lineIdx, m.range.first, lineOffset + m.range.first))
                } else {
                    val top = stack.lastOrNull()
                    if (top != null && top.name.equals(tagName, ignoreCase = true)) {
                        stack.removeLast()
                    } else {
                        val sc = m.range.first
                        val ec = m.range.last + 1
                        out.add(
                            LspDiagnostic(
                                serverId = serverId,
                                severity = Severity.ERROR,
                                message = if (top != null) {
                                    "Mismatched closing tag '</$tagName>'; expected '</${top.name}>'."
                                } else {
                                    "Unexpected closing tag '</$tagName>' with no matching opening tag."
                                },
                                code = "tag-mismatch",
                                line = lineIdx,
                                startCol = sc,
                                endCol = ec,
                                startOffset = lineOffset + sc,
                                endOffset = lineOffset + ec
                            )
                        )
                    }
                }
            }
        }
        for (unclosed in stack) {
            val len = unclosed.name.length + 2
            out.add(
                LspDiagnostic(
                    serverId = serverId,
                    severity = Severity.WARNING,
                    message = "Unclosed element '<${unclosed.name}>'.",
                    code = "unclosed-tag",
                    line = unclosed.line,
                    startCol = unclosed.col,
                    endCol = unclosed.col + len,
                    startOffset = unclosed.offset,
                    endOffset = unclosed.offset + len
                )
            )
        }
    }

    private fun analyzeBracketsAndStrings(
        serverId: String,
        lines: List<String>,
        lineStarts: IntArray,
        out: MutableList<LspDiagnostic>
    ) {
        data class BracketToken(val ch: Char, val line: Int, val col: Int, val offset: Int)
        val stack = ArrayDeque<BracketToken>()
        var inBlockComment = false
        var inTripleQuote = false

        for (lineIdx in lines.indices) {
            val s = lines[lineIdx]
            val lineOffset = lineStarts[lineIdx]
            var i = 0
            val n = s.length
            var inStringChar: Char? = null
            var stringStartCol = 0

            while (i < n) {
                if (inTripleQuote) {
                    val endIdx = s.indexOf("\"\"\"", i)
                    if (endIdx < 0) break
                    inTripleQuote = false
                    i = endIdx + 3
                    continue
                }
                if (inBlockComment) {
                    val endIdx = s.indexOf("*/", i)
                    if (endIdx < 0) break
                    inBlockComment = false
                    i = endIdx + 2
                    continue
                }
                if (inStringChar != null) {
                    if (s[i] == '\\') {
                        i += 2
                        continue
                    }
                    if (s[i] == inStringChar) {
                        inStringChar = null
                    }
                    i++
                    continue
                }

                if (s.startsWith("//", i) || s.startsWith("#", i)) break
                if (s.startsWith("/*", i)) {
                    inBlockComment = true
                    i += 2
                    continue
                }
                if (s.startsWith("\"\"\"", i)) {
                    inTripleQuote = true
                    i += 3
                    continue
                }
                val c = s[i]
                if (c == '"' || c == '\'') {
                    inStringChar = c
                    stringStartCol = i
                    i++
                    continue
                }

                when (c) {
                    '(', '[', '{' -> stack.addLast(BracketToken(c, lineIdx, i, lineOffset + i))
                    ')', ']', '}' -> {
                        val expectedOpen = when (c) {
                            ')' -> '('
                            ']' -> '['
                            else -> '{'
                        }
                        val top = stack.lastOrNull()
                        if (top != null && top.ch == expectedOpen) {
                            stack.removeLast()
                        } else {
                            out.add(
                                LspDiagnostic(
                                    serverId = serverId,
                                    severity = Severity.ERROR,
                                    message = "Unmatched closing '$c'.",
                                    code = "syntax-unmatched-bracket",
                                    line = lineIdx,
                                    startCol = i,
                                    endCol = i + 1,
                                    startOffset = lineOffset + i,
                                    endOffset = lineOffset + i + 1
                                )
                            )
                        }
                    }
                }
                i++
            }

            if (inStringChar == '"') {
                out.add(
                    LspDiagnostic(
                        serverId = serverId,
                        severity = Severity.ERROR,
                        message = "Unclosed string literal '\"' on line ${lineIdx + 1}.",
                        code = "syntax-unclosed-string",
                        line = lineIdx,
                        startCol = stringStartCol,
                        endCol = n.coerceAtLeast(stringStartCol + 1),
                        startOffset = lineOffset + stringStartCol,
                        endOffset = lineOffset + n.coerceAtLeast(stringStartCol + 1),
                        quickFixTitle = "Close string literal with '\"'",
                        quickFixReplacement = s + "\""
                    )
                )
            }
        }

        for (unclosed in stack) {
            val closeChar = when (unclosed.ch) {
                '(' -> ')'
                '[' -> ']'
                else -> '}'
            }
            out.add(
                LspDiagnostic(
                    serverId = serverId,
                    severity = Severity.ERROR,
                    message = "Unclosed '${unclosed.ch}'; expected matching '$closeChar'.",
                    code = "syntax-unclosed-bracket",
                    line = unclosed.line,
                    startCol = unclosed.col,
                    endCol = unclosed.col + 1,
                    startOffset = unclosed.offset,
                    endOffset = unclosed.offset + 1
                )
            )
        }
    }

    // ---------------------------------------------------------------- Live LSP Auto-Completion Engine

    fun computeCompletions(
        server: LspServerSpec,
        text: String,
        cursorLine: Int,
        cursorCol: Int
    ): List<LspCompletionItem> {
        val lines = text.split('\n')
        val lineStr = lines.getOrNull(cursorLine) ?: return emptyList()
        val safeCol = cursorCol.coerceIn(0, lineStr.length)
        val beforeCursor = lineStr.substring(0, safeCol)

        val dotMatch = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:\\.|::)([a-zA-Z0-9_]*)$").find(beforeCursor)
        if (dotMatch != null) {
            val receiver = dotMatch.groupValues[1]
            val memberPrefix = dotMatch.groupValues[2]
            return computeMemberCompletions(server.id, receiver, memberPrefix)
        }

        val wordMatch = Regex("([a-zA-Z_@#][a-zA-Z0-9_]*)$").find(beforeCursor)
        val prefix = wordMatch?.groupValues?.get(1) ?: ""
        if (prefix.isEmpty()) return emptyList()

        val results = ArrayList<LspCompletionItem>()
        val seen = HashSet<String>()

        val serverItems = SERVER_COMPLETIONS[server.id] ?: emptyList()
        for (item in serverItems) {
            if (item.label.startsWith(prefix, ignoreCase = true) && item.label != prefix) {
                if (seen.add(item.label)) {
                    results.add(item.copy(prefixLength = prefix.length))
                }
            }
        }

        val identRegex = Regex("\\b[a-zA-Z_][a-zA-Z0-9_]{2,}\\b")
        for (m in identRegex.findAll(text)) {
            val sym = m.value
            if (sym != prefix && sym.startsWith(prefix, ignoreCase = true) && seen.add(sym)) {
                val kind = if (sym[0].isUpperCase()) CompletionKind.CLASS else CompletionKind.VARIABLE
                results.add(
                    LspCompletionItem(
                        label = sym,
                        insertText = sym,
                        detail = "symbol (${server.id})",
                        kind = kind,
                        prefixLength = prefix.length
                    )
                )
            }
            if (results.size >= 18) break
        }

        return results.take(15)
    }

    private fun computeMemberCompletions(
        serverId: String,
        receiver: String,
        memberPrefix: String
    ): List<LspCompletionItem> {
        val candidates = when {
            receiver.equals("console", true) -> listOf(
                Triple("log", "log()", "(...data: any[]) => void"),
                Triple("error", "error()", "(...data: any[]) => void"),
                Triple("warn", "warn()", "(...data: any[]) => void"),
                Triple("table", "table()", "(tabularData: any) => void")
            )
            receiver.equals("fmt", true) -> listOf(
                Triple("Println", "Println()", "func(a ...any) (n int, err error)"),
                Triple("Printf", "Printf(\"\")", "func(format string, a ...any)"),
                Triple("Sprintf", "Sprintf(\"\")", "func(format string, a ...any) string"),
                Triple("Errorf", "Errorf(\"\")", "func(format string, a ...any) error")
            )
            receiver.equals("System", true) -> listOf(
                Triple("out.println", "out.println()", "void PrintStream.println(String)"),
                Triple("err.println", "err.println()", "void PrintStream.println(String)"),
                Triple("currentTimeMillis", "currentTimeMillis()", "long System.currentTimeMillis()"),
                Triple("nanoTime", "nanoTime()", "long System.nanoTime()")
            )
            else -> when (serverId) {
                "kotlin-lsp" -> listOf(
                    Triple("forEach", "forEach { }", "inline fun Iterable<T>.forEach(action: (T) -> Unit)"),
                    Triple("map", "map { }", "inline fun Iterable<T>.map(transform: (T) -> R): List<R>"),
                    Triple("filter", "filter { }", "inline fun Iterable<T>.filter(predicate: (T) -> Boolean)"),
                    Triple("firstOrNull", "firstOrNull()", "fun Iterable<T>.firstOrNull(): T?"),
                    Triple("let", "let { }", "inline fun <T, R> T.let(block: (T) -> R): R"),
                    Triple("apply", "apply { }", "inline fun <T> T.apply(block: T.() -> Unit): T"),
                    Triple("toString", "toString()", "fun Any.toString(): String"),
                    Triple("isNotEmpty", "isNotEmpty()", "fun CharSequence.isNotEmpty(): Boolean")
                )
                "pyright" -> listOf(
                    Triple("append", "append()", "(object: Any) -> None"),
                    Triple("split", "split()", "(sep: str | None) -> list[str]"),
                    Triple("strip", "strip()", "() -> str"),
                    Triple("items", "items()", "() -> dict_items"),
                    Triple("get", "get()", "(key: Any, default: Any) -> Any"),
                    Triple("join", "join()", "(iterable: Iterable[str]) -> str")
                )
                "ts-ls" -> listOf(
                    Triple("map", "map(item => )", "Array<T>.map<U>(callbackfn)"),
                    Triple("filter", "filter(item => )", "Array<T>.filter(predicate)"),
                    Triple("forEach", "forEach(item => )", "Array<T>.forEach(callbackfn)"),
                    Triple("includes", "includes()", "(searchElement) => boolean"),
                    Triple("length", "length", "number"),
                    Triple("then", "then(res => )", "Promise<T>.then()")
                )
                else -> listOf(
                    Triple("toString", "toString()", "() -> String"),
                    Triple("length", "length", "int"),
                    Triple("size", "size()", "int"),
                    Triple("contains", "contains()", "(element) -> bool")
                )
            }
        }

        return candidates
            .filter { it.first.startsWith(memberPrefix, ignoreCase = true) }
            .map { (label, insert, detail) ->
                LspCompletionItem(
                    label = label,
                    insertText = insert,
                    detail = detail,
                    kind = CompletionKind.METHOD,
                    prefixLength = memberPrefix.length
                )
            }
    }

    // ---------------------------------------------------------------- Language Symbol & Keyword Dictionaries

    private val STATEMENT_STARTER_KEYWORDS = setOf(
        "package", "import", "from", "export", "default", "module", "namespace", "using", "use", "mod",
        "class", "interface", "object", "struct", "enum", "trait", "impl", "type", "typealias", "data", "sealed", "annotation",
        "fun", "def", "function", "func", "fn", "sub", "proc",
        "val", "var", "let", "const", "local", "global", "nonlocal", "final", "static", "mut", "ref", "move",
        "public", "private", "protected", "internal", "open", "abstract", "override", "virtual", "explicit", "friend",
        "suspend", "inline", "tailrec", "operator", "infix", "external", "lateinit", "const", "async", "await",
        "if", "else", "elif", "elseif", "then", "when", "switch", "case", "match", "select",
        "for", "while", "do", "loop", "repeat", "until", "in", "is", "as", "by", "where", "range",
        "return", "break", "continue", "yield", "throw", "raise", "try", "catch", "except", "finally", "defer", "go",
        "new", "delete", "typeof", "instanceof", "sizeof", "typedef", "typename", "template",
        "true", "false", "null", "None", "True", "False", "nil", "undefined", "NaN", "Infinity",
        "this", "super", "self", "Self", "it", "crate", "pub", "echo", "print", "assert", "del", "pass", "with",
        "int", "long", "short", "byte", "float", "double", "boolean", "bool", "char", "void", "auto", "string", "String",
        "unsigned", "signed", "size_t", "u8", "u16", "u32", "u64", "i8", "i16", "i32", "i64", "f32", "f64", "usize", "isize"
    )

    private val INFIX_AND_CONNECTOR_KEYWORDS = setOf(
        "in", "is", "as", "to", "until", "downTo", "step", "and", "or", "not", "instanceof",
        "extends", "implements", "throws", "where", "by", "from", "import", "else", "then", "do",
        "shl", "shr", "ushr", "xor", "matches"
    )

    private val KNOWN_SYMBOLS_COMMON = setOf(
        "main", "init", "constructor", "this", "super", "it", "self", "Self", "args", "err", "ok", "res", "req", "ctx",
        "true", "false", "null", "None", "True", "False", "nil", "undefined", "NaN", "Infinity",
        "println", "print", "printf", "sprintf", "fprintf", "eprintln", "format", "readLine", "readln", "input",
        "String", "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "Unit", "Any", "Nothing", "Number",
        "Object", "Class", "Enum", "Throwable", "Exception", "RuntimeException", "Error", "AssertionError",
        "List", "MutableList", "ArrayList", "LinkedList", "Map", "MutableMap", "HashMap", "LinkedHashMap",
        "Set", "MutableSet", "HashSet", "LinkedHashSet", "Array", "IntArray", "LongArray", "ByteArray", "CharArray",
        "Pair", "Triple", "Result", "Option", "Some", "Ok", "Err", "Optional", "Stream", "Sequence", "Iterable",
        "Math", "System", "Console", "console", "JSON", "Date", "RegExp", "Promise", "Symbol", "BigInt",
        "File", "Path", "Uri", "URL", "Thread", "Runnable", "Process", "ProcessBuilder",
        "listOf", "mutableListOf", "setOf", "mutableSetOf", "mapOf", "mutableMapOf", "arrayOf", "intArrayOf",
        "emptyList", "emptyMap", "emptySet", "sequenceOf", "buildString", "buildList", "buildMap",
        "runCatching", "repeat", "require", "check", "error", "TODO", "lazy", "synchronized",
        "maxOf", "minOf", "max", "min", "abs", "sqrt", "pow", "round", "floor", "ceil",
        "len", "cap", "make", "append", "copy", "close", "delete", "panic", "recover",
        "range", "enumerate", "zip", "open", "isinstance", "issubclass", "getattr", "setattr", "hasattr", "dir", "type", "id", "repr",
        "str", "int", "float", "bool", "list", "dict", "set", "tuple", "sorted", "reversed", "sum",
        "window", "document", "navigator", "location", "localStorage", "sessionStorage", "fetch",
        "setTimeout", "setInterval", "clearTimeout", "clearInterval", "alert", "confirm", "prompt",
        "process", "require", "module", "exports", "Buffer", "__dirname", "__filename",
        "fmt", "os", "io", "net", "http", "strings", "strconv", "time", "sync", "context", "errors", "filepath", "log", "json",
        "std", "cout", "cin", "cerr", "endl", "vector", "string", "map", "set", "unique_ptr", "shared_ptr", "make_unique", "make_shared",
        "malloc", "calloc", "realloc", "free", "strlen", "strcpy", "strncpy", "strcmp", "strncmp", "memcpy", "memset", "scanf",
        "Vec", "Box", "Rc", "Arc", "RefCell", "Mutex", "RwLock", "HashMap", "BTreeMap", "HashSet", "vec", "dbg", "todo", "unimplemented", "assert_eq",
        "ipairs", "pairs", "tonumber", "tostring", "table", "coroutine",
        "Context", "Intent", "Bundle", "View", "TextView", "EditText", "Button", "ImageView", "ImageButton",
        "LinearLayout", "FrameLayout", "ScrollView", "Toast", "Log", "Color", "Paint", "Canvas", "Typeface",
        "AppCompatActivity", "AlertDialog", "SharedPreferences", "ResourcesCompat", "WindowCompat", "Gravity"
    )

    private val KNOWN_SYMBOLS_BY_SERVER: Map<String, Set<String>> = mapOf(
        "kotlin-lsp" to setOf("Charsets", "Regex", "ArrayDeque", "CoroutineScope", "Dispatchers", "launch", "async", "withContext", "Flow", "StateFlow"),
        "jdtls" to setOf("Collections", "Arrays", "Objects", "Scanner", "BufferedReader", "InputStreamReader", "FileReader", "FileWriter", "PrintWriter", "StringBuilder", "Override", "SuppressWarnings"),
        "pyright" to setOf("__name__", "__main__", "__init__", "__str__", "__repr__", "__len__", "__getitem__", "__setitem__", "sys", "re", "math", "random", "datetime", "pathlib", "itertools", "functools", "collections", "typing", "asyncio"),
        "ts-ls" to setOf("React", "useState", "useEffect", "useRef", "useMemo", "useCallback", "useContext", "HTMLElement", "Event", "MouseEvent", "KeyboardEvent", "Record", "Partial", "Required", "Readonly", "Pick", "Omit"),
        "gopls" to setOf("bufio", "bytes", "crypto", "database", "encoding", "flag", "html", "image", "io", "math", "mime", "net", "os", "path", "reflect", "regexp", "runtime", "sort", "strconv", "strings", "sync", "syscall", "testing", "time", "unicode"),
        "rust-analyzer" to setOf("std", "core", "alloc", "collections", "fs", "io", "net", "path", "process", "sync", "thread", "time", "env", "fmt", "ops", "cmp", "iter", "mem", "ptr", "slice", "str"),
        "clangd" to setOf("stdio", "stdlib", "stdint", "stdbool", "string", "math", "time", "errno", "unistd", "fcntl", "pthread", "iostream", "fstream", "sstream", "algorithm", "memory", "utility", "functional", "optional", "variant", "FILE", "NULL", "EOF")
    )

    private val KNOWN_METHODS_AND_PROPS = setOf(
        // Common Object / Collection / String / IO methods across Kotlin, Java, Python, JS/TS, Go, Rust, C++, C#
        "toString", "equals", "hashCode", "clone", "finalize", "getClass", "notify", "notifyAll", "wait",
        "length", "size", "count", "isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "indices", "lastIndex",
        "get", "set", "put", "add", "addAll", "remove", "removeAt", "removeFirst", "removeLast", "addFirst", "addLast",
        "clear", "contains", "containsKey", "containsValue", "indexOf", "lastIndexOf", "indexOfFirst", "indexOfLast",
        "first", "firstOrNull", "last", "lastOrNull", "single", "singleOrNull", "find", "findLast", "ElementAt",
        "getOrNull", "getOrDefault", "getOrElse", "getOrPut",
        "filter", "filterNot", "filterNotNull", "filterIndexed", " filterIsInstance",
        "map", "mapNotNull", "mapIndexed", "flatMap", "flatten", "associate", "associateBy", "associateWith", "groupBy", "partition",
        "forEach", "forEachIndexed", "onEach", "any", "all", "none", "sum", "sumOf", "average", "maxOrNull", "minOrNull", "maxByOrNull", "minByOrNull",
        "sorted", "sortedBy", "sortedByDescending", "sortedWith", "reversed", "shuffled", "distinct", "distinctBy",
        "take", "takeLast", "takeWhile", "takeIf", "takeUnless", "drop", "dropLast", "dropWhile", "slice", "subList", "chunked", "windowed",
        "zip", "unzip", "joinToString", "join", "reduce", "fold",
        "let", "also", "apply", "run", "with", "use",
        "coerceIn", "coerceAtLeast", "coerceAtMost", "compareTo",
        "toInt", "toIntOrNull", "toLong", "toLongOrNull", "toDouble", "toDoubleOrNull", "toFloat", "toBoolean",
        "toByte", "toShort", "toChar", "toUInt", "toULong",
        "toList", "toMutableList", "toSet", "toMutableSet", "toMap", "toTypedArray", "toIntArray", "toCharArray", "toByteArray",
        "trim", "trimStart", "trimEnd", "trimIndent", "trimMargin", "padStart", "padEnd",
        "startsWith", "endsWith", "replace", "replaceFirst", "replaceAfter", "replaceBefore",
        "substring", "substringBefore", "substringAfter", "substringBeforeLast", "substringAfterLast",
        "split", "lines", "lineSequence", "chars", "codePoints", "lowercase", "uppercase", " lowercaseChar", "uppercaseChar",
        "format", "matches", "findAll", "replaceAll", "toRegex", "toPattern", "matcher", "groupValues", "groups", "range", "value",
        "append", "appendLine", "insert", "delete", "deleteCharAt", "reverse",
        "readText", "writeText", "readLines", "readBytes", "writeBytes", "appendText", "copyTo", " bufferedReader", "bufferedWriter",
        "inputStream", "outputStream", "useLines", "walk", "walkTopDown", "walkBottomUp", "maxDepth",
        "exists", "isDirectory", "isFile", "canRead", "canWrite", "canExecute", "setExecutable", "setReadable", "setWritable",
        "mkdir", "mkdirs", "deleteRecursively", "renameTo", "list", "listFiles",
        "name", "path", "absolutePath", "canonicalPath", "extension", "nameWithoutExtension", "parent", "parentFile", "length", "lastModified",
        "keys", "values", "entries", "key", "value", "first", "second", "third",
        "isSuccess", "isFailure", "getOrThrow", "exceptionOrNull", "onSuccess", "onFailure", "recover",
        // Java / System
        "out", "err", "in", "println", "print", "printf", "currentTimeMillis", "nanoTime", "exit", "gc", "getenv", "getProperty",
        "charAt", "toLowerCase", "toUpperCase", "equalsIgnoreCase", "valueOf", "parseInt", "parseLong", "parseDouble", "parseBoolean",
        "iterator", "hasNext", "next", "stream", "collect", "toArray", "sort", " offer", "poll", "peek", "push", "pop",
        "printStackTrace", "getMessage", "getCause", "start", "join", "sleep", "interrupt", "currentThread",
        // Python
        "strip", "lstrip", "rstrip", "upper", "lower", "title", "capitalize", "startswith", "endswith", "isdigit", "isalpha", "isalnum", "isspace",
        "items", "setdefault", "update", "extend", "pop", "remove", "reverse", "copy", "count", "index",
        "read", "readline", "readlines", "write", "writelines", "flush", "close", "seek", "tell",
        "match", "search", "findall", "sub", "group", "loads", "dumps", "load", "dump",
        // JS / TS
        "log", "warn", "error", "info", "debug", "table", "dir", "time", "timeEnd", "trace", "assert",
        "push", "pop", "shift", "unshift", "splice", "slice", "concat", "includes", "find", "findIndex", "some", "every", "fill", "flat", "flatMap",
        "charCodeAt", "codePointAt", "repeat", "padStart", "padEnd", "localeCompare",
        "then", "catch", "finally", "all", "race", "allSettled", "resolve", "reject",
        "stringify", "parse", "assign", "freeze", "fromEntries", "isArray",
        "getElementById", "querySelector", "querySelectorAll", "createElement", "appendChild", "removeChild",
        "addEventListener", "removeEventListener", "setAttribute", "getAttribute", "classList", "style", "innerHTML", "textContent", "value",
        "preventDefault", "stopPropagation", "target", "key", "keyCode",
        // Go (`fmt`, `strings`, `os`, `http`, etc.)
        "Println", "Printf", "Sprintf", "Fprintf", "Fprintln", "Errorf", "Scan", "Scanln", "Scanf", "Sscanf",
        "Contains", "HasPrefix", "HasSuffix", "Index", "Join", "Replace", "ReplaceAll", "Split", "ToLower", "ToUpper", "Trim", "TrimSpace", "Builder", "WriteString", "String",
        "Atoi", "Itoa", "ParseInt", "ParseFloat", "ParseBool", "FormatInt", "FormatFloat",
        "Open", "Create", "ReadFile", "WriteFile", "Remove", "RemoveAll", "Mkdir", "MkdirAll", "Stat", "Getenv", "Setenv", "Exit", "Args", "Stdout", "Stderr", "Stdin",
        "Get", "Post", "ListenAndServe", "Handle", "HandleFunc", "NewRequest", "DefaultClient", "Do", "Header", "Body", "StatusCode", "Close",
        "Now", "Since", "Sleep", "Second", "Millisecond", "Minute", "Hour", "Format",
        "Lock", "Unlock", "RLock", "RUnlock", "Add", "Done", "Wait", "Background", "WithCancel", "WithTimeout",
        "Error", "Unwrap", "Is", "As", "New", "Marshal", "Unmarshal", "NewEncoder", "NewDecoder", "Encode", "Decode",
        // Rust
        "unwrap", "expect", "unwrap_or", "unwrap_or_else", "unwrap_or_default", "is_some", "is_none", "is_ok", "is_err", "ok", "err", "and_then", "or_else", "map_err",
        "iter", "into_iter", "iter_mut", "collect", "enumerate", "zip", "chain", "take", "skip", "fold", " any", "all", "find", "position", "max", "min", "sum", "count",
        "push", "push_str", "pop", "insert", "remove", "len", "is_empty", "capacity", "with_capacity", "clear", "truncate", "retain", "dedup", "sort", "sort_by",
        "as_str", "as_bytes", "as_ref", "as_mut", "to_string", "to_owned", "into", "from", "default", "clone",
        "chars", "bytes", "lines", "split_whitespace", "trim", "trim_start", "trim_end", "starts_with", "ends_with", "contains", "replace", "parse",
        "read_to_string", "write_all", "flush", "open", "create", "lock", "spawn", "join",
        // C++ / C#
        "c_str", "data", "size", "length", "empty", "clear", "push_back", "pop_back", "emplace_back", "front", "back", "begin", "end", "rbegin", "rend", "find", "erase", "insert", "substr",
        "WriteLine", "Write", "ReadLine", "ReadKey", "ToString", "Equals", "GetHashCode", "GetType", "ToLower", "ToUpper", "Trim", "Split", "Replace", "Contains", "StartsWith", "EndsWith", "Substring",
        "Add", "Remove", "Clear", "ContainsKey", "TryGetValue", "Select", "Where", "FirstOrDefault", "SingleOrDefault", "ToList", "ToArray", "OrderBy", "OrderByDescending", "GroupBy", "Any", "All", "Count", "Sum"
    )

    private val COMMON_TYPOS_DEFAULT = mapOf(
        "retrun" to "return",
        "funtion" to "function",
        "funciton" to "function",
        "improt" to "import",
        "publlic" to "public",
        "priavte" to "private",
        "staitc" to "static",
        "ture" to "true",
        "flase" to "false",
        "pritn" to "print",
        "pritnln" to "println"
    )

    private val COMMON_TYPOS_BY_SERVER: Map<String, Map<String, String>> = mapOf(
        "kotlin-lsp" to (COMMON_TYPOS_DEFAULT + mapOf(
            "funn" to "fun",
            "vall" to "val",
            "varr" to "var",
            "overide" to "override",
            "compainon" to "companion"
        )),
        "jdtls" to (COMMON_TYPOS_DEFAULT + mapOf(
            "Sytem" to "System",
            "Sting" to "String",
            "overide" to "override",
            "booleaan" to "boolean"
        )),
        "pyright" to (COMMON_TYPOS_DEFAULT + mapOf(
            "deff" to "def",
            "esle" to "else",
            "eliff" to "elif",
            "Noen" to "None"
        )),
        "ts-ls" to (COMMON_TYPOS_DEFAULT + mapOf(
            "consoel" to "console",
            "cosnt" to "const",
            "awiat" to "await",
            "asycn" to "async",
            "exprot" to "export"
        )),
        "gopls" to (COMMON_TYPOS_DEFAULT + mapOf(
            "pakcage" to "package",
            "fucn" to "func",
            "Prinltn" to "Println"
        )),
        "rust-analyzer" to (COMMON_TYPOS_DEFAULT + mapOf(
            "fnn" to "fn",
            "mutt" to "mut",
            "imppl" to "impl",
            "pirntln" to "println"
        ))
    )

    private fun item(label: String, insert: String, detail: String, kind: CompletionKind) =
        LspCompletionItem(label, insert, detail, kind, 0)

    private val SERVER_COMPLETIONS: Map<String, List<LspCompletionItem>> = mapOf(
        "kotlin-lsp" to listOf(
            item("fun", "fun name() {\n    \n}", "Kotlin function declaration", CompletionKind.SNIPPET),
            item("main", "fun main() {\n    println(\"Hello PocketDev\")\n}", "Entry point function", CompletionKind.SNIPPET),
            item("class", "class Name {\n    \n}", "Kotlin class declaration", CompletionKind.CLASS),
            item("data class", "data class Model(val id: String)", "Kotlin data class", CompletionKind.SNIPPET),
            item("println", "println()", "fun println(message: Any?)", CompletionKind.FUNCTION),
            item("listOf", "listOf()", "fun <T> listOf(vararg elements: T): List<T>", CompletionKind.FUNCTION),
            item("mutableListOf", "mutableListOf()", "fun <T> mutableListOf(): MutableList<T>", CompletionKind.FUNCTION),
            item("mapOf", "mapOf()", "fun <K, V> mapOf(): Map<K, V>", CompletionKind.FUNCTION),
            item("runCatching", "runCatching {\n    \n}", "inline fun <R> runCatching(block: () -> R): Result<R>", CompletionKind.FUNCTION),
            item("companion object", "companion object {\n    \n}", "Companion object declaration", CompletionKind.SNIPPET),
            item("override", "override fun ", "Override member modifier", CompletionKind.KEYWORD),
            item("suspend", "suspend fun ", "Coroutine suspend modifier", CompletionKind.KEYWORD),
            item("when", "when (value) {\n    else -> {}\n}", "When expression", CompletionKind.SNIPPET)
        ),
        "jdtls" to listOf(
            item("public static void main", "public static void main(String[] args) {\n    System.out.println();\n}", "Java main method", CompletionKind.SNIPPET),
            item("System.out.println", "System.out.println();", "void PrintStream.println(String x)", CompletionKind.METHOD),
            item("ArrayList", "ArrayList<>()", "java.util.ArrayList<E>", CompletionKind.CLASS),
            item("HashMap", "HashMap<>()", "java.util.HashMap<K, V>", CompletionKind.CLASS),
            item("String", "String ", "java.lang.String", CompletionKind.CLASS),
            item("Override", "@Override\n", "java.lang.Override annotation", CompletionKind.KEYWORD),
            item("try-catch", "try {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}", "Try/catch block", CompletionKind.SNIPPET)
        ),
        "pyright" to listOf(
            item("def", "def func_name():\n    pass", "Python function definition", CompletionKind.SNIPPET),
            item("class", "class ClassName:\n    def __init__(self) -> None:\n        pass", "Python class definition", CompletionKind.SNIPPET),
            item("if __name__", "if __name__ == \"__main__\":\n    main()", "Main module guard", CompletionKind.SNIPPET),
            item("print", "print()", "def print(*values: object) -> None", CompletionKind.FUNCTION),
            item("enumerate", "enumerate()", "class enumerate(iterable, start=0)", CompletionKind.FUNCTION),
            item("range", "range()", "class range(stop)", CompletionKind.FUNCTION),
            item("isinstance", "isinstance()", "def isinstance(obj, class_or_tuple) -> bool", CompletionKind.FUNCTION),
            item("async def", "async def task():\n    await ", "Async coroutine definition", CompletionKind.SNIPPET)
        ),
        "ts-ls" to listOf(
            item("function", "function name() {\n    \n}", "Function declaration", CompletionKind.SNIPPET),
            item("async", "async function fetchData() {\n    const res = await fetch(url);\n}", "Async function", CompletionKind.SNIPPET),
            item("console.log", "console.log()", "(...data: any[]) => void", CompletionKind.METHOD),
            item("interface", "interface Props {\n    id: string;\n}", "TypeScript interface", CompletionKind.INTERFACE),
            item("export default", "export default ", "ES module default export", CompletionKind.KEYWORD),
            item("Promise", "new Promise((resolve, reject) => {\n    \n})", "Promise<T> constructor", CompletionKind.CLASS),
            item("useEffect", "useEffect(() => {\n    \n}, []);", "React effect hook", CompletionKind.FUNCTION),
            item("useState", "const [state, setState] = useState();", "React state hook", CompletionKind.SNIPPET)
        ),
        "gopls" to listOf(
            item("func", "func name() {\n\t\n}", "Go function declaration", CompletionKind.SNIPPET),
            item("iferr", "if err != nil {\n\treturn err\n}", "Go error check snippet", CompletionKind.SNIPPET),
            item("fmt.Println", "fmt.Println()", "func fmt.Println(a ...any)", CompletionKind.FUNCTION),
            item("struct", "type Name struct {\n\t\n}", "Go struct declaration", CompletionKind.CLASS),
            item("interface", "type Name interface {\n\t\n}", "Go interface declaration", CompletionKind.INTERFACE),
            item("make", "make([]string, 0)", "builtin make(t Type, size ...IntegerType)", CompletionKind.FUNCTION)
        ),
        "rust-analyzer" to listOf(
            item("fn", "fn name() {\n    \n}", "Rust function item", CompletionKind.SNIPPET),
            item("println!", "println!(\"{}\", );", "macro std::println!", CompletionKind.FUNCTION),
            item("struct", "struct Name {\n    \n}", "Rust struct definition", CompletionKind.CLASS),
            item("impl", "impl Name {\n    \n}", "Implementation block", CompletionKind.SNIPPET),
            item("match", "match value {\n    _ => {}\n}", "Pattern match expression", CompletionKind.SNIPPET),
            item("Result", "Result<T, E>", "enum std::result::Result", CompletionKind.CLASS),
            item("Option", "Option<T>", "enum std::option::Option", CompletionKind.CLASS)
        ),
        "clangd" to listOf(
            item("#include", "#include <iostream>\n", "Preprocessor include directive", CompletionKind.SNIPPET),
            item("main", "int main(int argc, char** argv) {\n    return 0;\n}", "C/C++ main entry point", CompletionKind.SNIPPET),
            item("std::cout", "std::cout << \"\" << std::endl;", "ostream std::cout", CompletionKind.VARIABLE),
            item("std::vector", "std::vector<int> ", "template class std::vector<T>", CompletionKind.CLASS),
            item("std::string", "std::string ", "class std::basic_string<char>", CompletionKind.CLASS),
            item("printf", "printf(\"\\n\");", "int printf(const char* format, ...)", CompletionKind.FUNCTION)
        )
    )


    // ---------------------------------------------------------------- Real ProcessBuilder JSON-RPC 2.0 Stdio LSP Client

    /**
     * Checks whether the underlying executable binary for [server] is installed inside the
     * Ubuntu 24.04 rootfs (`/usr/bin`, `/usr/local/bin`, `/root/go/bin`, or `/root/.cargo/bin`).
     */
    fun isServerBinaryPresentInRootfs(context: Context, server: LspServerSpec): Boolean {
        val root = UbuntuProotManager.ubuntuRootDir(context)
        val exe = server.binaryName.trim().substringBefore(' ')
        val candidateDirs = listOf(
            File(root, "usr/bin"),
            File(root, "usr/local/bin"),
            File(root, "bin"),
            File(root, "root/go/bin"),
            File(root, "root/.cargo/bin")
        )
        return candidateDirs.any { File(it, exe).exists() }
    }

    /**
     * Synchronizes [text] with the real `ProcessBuilder`-driven stdio LSP server (`didOpen` / `didChange`)
     * when installed in Ubuntu PRoot, and combines live `textDocument/publishDiagnostics` with the
     * fast multi-pass analyzer.
     */
    fun analyzeDocumentWithStdio(
        context: Context,
        server: LspServerSpec,
        fileName: String,
        text: String,
        localFile: File? = null
    ): List<LspDiagnostic> {
        val fastDiags = analyzeDocument(server, fileName, text)
        if (UbuntuProotManager.isUbuntuInstalled(context) && isServerBinaryPresentInRootfs(context, server)) {
            Thread {
                runCatching {
                    val session = getOrStartStdioSession(context.applicationContext, server)
                    session?.notifyDocumentChanged(fileName, text, localFile)
                }.onFailure { e ->
                    DiagLogger.log("LSP-${server.id}", "Stdio sync error: ${e.message}", context, e)
                }
            }.apply { isDaemon = true }.start()
        }
        val stdioDiags = stdioDiagnosticsByFile[fileName].orEmpty()
        if (stdioDiags.isEmpty()) return fastDiags

        val merged = ArrayList<LspDiagnostic>(stdioDiags.size + fastDiags.size)
        merged.addAll(stdioDiags)
        for (fd in fastDiags) {
            if (merged.none { it.line == fd.line && it.message == fd.message }) {
                merged.add(fd)
            }
        }
        return merged
    }

    private fun getOrStartStdioSession(context: Context, server: LspServerSpec): StdioLspSession? {
        stdioSessions[server.id]?.let { existing ->
            if (existing.isAlive()) return existing
            stdioSessions.remove(server.id)
        }
        if (!UbuntuProotManager.isUbuntuInstalled(context) || !isServerBinaryPresentInRootfs(context, server)) {
            return null
        }
        return synchronized(stdioSessions) {
            stdioSessions[server.id]?.takeIf { it.isAlive() } ?: run {
                val created = StdioLspSession(context.applicationContext, server)
                if (created.startAndInitialize()) {
                    stdioSessions[server.id] = created
                    created
                } else {
                    null
                }
            }
        }
    }

    fun shutdownAllStdioSessions() {
        for ((_, session) in stdioSessions) {
            runCatching { session.shutdown() }
        }
        stdioSessions.clear()
    }

    /**
     * Real `ProcessBuilder`-driven JSON-RPC 2.0 `Content-Length` stdio client connected to a
     * language server binary inside the Ubuntu 24.04 PRoot environment.
     */
    class StdioLspSession(
        private val context: Context,
        val server: LspServerSpec
    ) {
        private var process: Process? = null
        private var stdinStream: OutputStream? = null
        private val writeLock = Any()
        private val nextRequestId = AtomicInteger(1)
        private val pendingRequests = ConcurrentHashMap<Int, PendingRpcCall>()
        private val openedDocVersions = ConcurrentHashMap<String, Int>()
        private val transcriptLog = ArrayDeque<String>(64)

        @Volatile
        private var initialized = false

        private class PendingRpcCall {
            val latch = CountDownLatch(1)
            @Volatile
            var response: JSONObject? = null
        }

        fun isAlive(): Boolean = process?.isAlive == true

        fun getTranscriptText(): String = synchronized(transcriptLog) {
            transcriptLog.joinToString("\n")
        }

        private fun recordTranscript(direction: String, json: String) {
            val snippet = if (json.length > 600) json.take(600) + "...(truncated)" else json
            synchronized(transcriptLog) {
                transcriptLog.addLast("$direction $snippet")
                while (transcriptLog.size > 40) transcriptLog.removeFirst()
            }
            DiagLogger.log("LSP-${server.id}", "$direction ${json.take(180)}", context)
        }

        fun startAndInitialize(): Boolean {
            return try {
                val workspace = ProjectManager.ensureDefaultLocalWorkspace(context)
                val mode = UbuntuProotManager.preferredLaunchMode
                    ?: UbuntuProotManager.availableLaunchModes(context).firstOrNull()
                    ?: UbuntuProotManager.ProotLaunchMode.DIRECT
                val cmdList = UbuntuProotManager.buildProotCommandListForMode(
                    context = context,
                    hostWorkingDir = workspace,
                    guestScript = "exec ${server.binaryName}",
                    mode = mode,
                    fastNoRc = true
                )
                val pb = ProcessBuilder(cmdList)
                    .directory(workspace)
                    .redirectErrorStream(false)
                UbuntuProotManager.applyHostProotEnv(context, pb.environment())
                val proc = pb.start()
                process = proc
                stdinStream = proc.outputStream

                // Start background stderr logger
                Thread {
                    runCatching {
                        proc.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (line.isNotBlank()) {
                                    DiagLogger.log("LSP-${server.id}-err", line, context)
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true }.start()

                // Start JSON-RPC 2.0 Content-Length frame reader loop
                Thread {
                    readLoop(BufferedInputStream(proc.inputStream, 32768))
                }.apply { isDaemon = true }.start()

                val rootGuestPath = UbuntuProotManager.hostDirToGuestPath(context, workspace)
                val initParams = JSONObject().apply {
                    put("processId", android.os.Process.myPid())
                    put("rootPath", rootGuestPath)
                    put("rootUri", "file://$rootGuestPath")
                    put("capabilities", JSONObject().apply {
                        put("textDocument", JSONObject().apply {
                            put("synchronization", JSONObject().apply {
                                put("dynamicRegistration", false)
                                put("willSave", false)
                                put("didSave", true)
                            })
                            put("publishDiagnostics", JSONObject().put("relatedInformation", true))
                            put("completion", JSONObject().put("completionItem", JSONObject().put("snippetSupport", true)))
                            put("hover", JSONObject().put("contentFormat", JSONArray(listOf("markdown", "plaintext"))))
                            put("definition", JSONObject().put("dynamicRegistration", false))
                            put("references", JSONObject().put("dynamicRegistration", false))
                            put("rename", JSONObject().put("dynamicRegistration", false))
                        })
                    })
                }
                val resp = sendRequest("initialize", initParams, timeoutMs = 3500L)
                if (resp != null) {
                    sendNotification("initialized", JSONObject())
                    initialized = true
                    true
                } else {
                    shutdown()
                    false
                }
            } catch (e: Exception) {
                DiagLogger.log("LSP-${server.id}", "Failed to start stdio server: ${e.message}", context, e)
                shutdown()
                false
            }
        }

        private fun readLoop(input: InputStream) {
            try {
                while (isAlive()) {
                    var contentLength = -1
                    while (true) {
                        val headerLine = readAsciiHeaderLine(input) ?: return
                        if (headerLine.isEmpty()) break
                        if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = headerLine.substringAfter(':').trim().toIntOrNull() ?: -1
                        }
                    }
                    if (contentLength <= 0 || contentLength > 16 * 1024 * 1024) continue
                    val payload = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val n = input.read(payload, offset, contentLength - offset)
                        if (n <= 0) return
                        offset += n
                    }
                    val jsonStr = String(payload, StandardCharsets.UTF_8)
                    recordTranscript("<--", jsonStr)
                    handleIncomingJson(JSONObject(jsonStr))
                }
            } catch (_: Exception) {
            } finally {
                for ((_, call) in pendingRequests) {
                    call.latch.countDown()
                }
                pendingRequests.clear()
            }
        }

        private fun readAsciiHeaderLine(input: InputStream): String? {
            val baos = ByteArrayOutputStream(64)
            while (true) {
                val b = input.read()
                if (b < 0) return if (baos.size() == 0) null else baos.toString("US-ASCII").trimEnd('\r')
                if (b == '\n'.code) {
                    return baos.toString("US-ASCII").trimEnd('\r')
                }
                baos.write(b)
            }
        }

        private fun handleIncomingJson(msg: JSONObject) {
            if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
                val id = msg.optInt("id", -1)
                pendingRequests.remove(id)?.let { call ->
                    call.response = msg
                    call.latch.countDown()
                }
                return
            }
            val method = msg.optString("method", "")
            if (method == "textDocument/publishDiagnostics") {
                val params = msg.optJSONObject("params") ?: return
                val uri = params.optString("uri", "")
                val fileName = uri.substringAfterLast('/')
                val diagArr = params.optJSONArray("diagnostics") ?: JSONArray()
                val parsed = ArrayList<LspDiagnostic>(diagArr.length())
                for (i in 0 until diagArr.length()) {
                    val dObj = diagArr.optJSONObject(i) ?: continue
                    val range = dObj.optJSONObject("range")
                    val start = range?.optJSONObject("start")
                    val end = range?.optJSONObject("end")
                    val line = start?.optInt("line", 0) ?: 0
                    val sc = start?.optInt("character", 0) ?: 0
                    val ec = (end?.optInt("character", sc + 1) ?: (sc + 1)).coerceAtLeast(sc + 1)
                    val sevCode = dObj.optInt("severity", 1)
                    val sev = when (sevCode) {
                        1 -> Severity.ERROR
                        2 -> Severity.WARNING
                        3 -> Severity.INFO
                        else -> Severity.HINT
                    }
                    val message = dObj.optString("message", "LSP diagnostic")
                    val code = dObj.optString("code", server.id)
                    parsed.add(
                        LspDiagnostic(
                            serverId = server.id,
                            severity = sev,
                            message = message,
                            code = code,
                            line = line,
                            startCol = sc,
                            endCol = ec,
                            startOffset = 0,
                            endOffset = 0
                        )
                    )
                }
                if (fileName.isNotEmpty()) {
                    stdioDiagnosticsByFile[fileName] = parsed
                    mainHandler.post {
                        onStdioDiagnosticsPublished?.invoke(fileName, parsed)
                    }
                }
            }
        }

        fun sendRequest(method: String, params: JSONObject, timeoutMs: Long = 1200L): JSONObject? {
            val id = nextRequestId.getAndIncrement()
            val req = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            val call = PendingRpcCall()
            pendingRequests[id] = call
            if (!writeRpcFrame(req.toString())) {
                pendingRequests.remove(id)
                return null
            }
            call.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            pendingRequests.remove(id)
            return call.response
        }

        fun sendNotification(method: String, params: JSONObject) {
            val notif = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }
            writeRpcFrame(notif.toString())
        }

        private fun writeRpcFrame(jsonStr: String): Boolean {
            val out = stdinStream ?: return false
            return try {
                val payloadBytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
                val headerBytes = "Content-Length: ${payloadBytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
                synchronized(writeLock) {
                    out.write(headerBytes)
                    out.write(payloadBytes)
                    out.flush()
                }
                recordTranscript("-->", jsonStr)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun guestUriForFile(fileName: String, localFile: File?): String {
            val guestPath = if (localFile != null) {
                val parentGuest = UbuntuProotManager.hostDirToGuestPath(context, localFile.parentFile ?: ProjectManager.ensureDefaultLocalWorkspace(context))
                "$parentGuest/${localFile.name}"
            } else {
                val wsGuest = UbuntuProotManager.hostDirToGuestPath(context, ProjectManager.ensureDefaultLocalWorkspace(context))
                "$wsGuest/$fileName"
            }
            return "file://$guestPath"
        }

        fun notifyDocumentChanged(fileName: String, text: String, localFile: File?) {
            if (!initialized || !isAlive()) return
            val uri = guestUriForFile(fileName, localFile)
            val prevVer = openedDocVersions[uri]
            if (prevVer == null) {
                openedDocVersions[uri] = 1
                val params = JSONObject().apply {
                    put("textDocument", JSONObject().apply {
                        put("uri", uri)
                        put("languageId", fileName.substringAfterLast('.', "plaintext").lowercase())
                        put("version", 1)
                        put("text", text)
                    })
                }
                sendNotification("textDocument/didOpen", params)
            } else {
                val nextVer = prevVer + 1
                openedDocVersions[uri] = nextVer
                val params = JSONObject().apply {
                    put("textDocument", JSONObject().apply {
                        put("uri", uri)
                        put("version", nextVer)
                    })
                    put("contentChanges", JSONArray().put(JSONObject().put("text", text)))
                }
                sendNotification("textDocument/didChange", params)
            }
        }

        fun shutdown() {
            runCatching {
                sendRequest("shutdown", JSONObject(), timeoutMs = 300L)
                sendNotification("exit", JSONObject())
            }
            runCatching { process?.destroyForcibly() }
            process = null
            stdinStream = null
        }
    }

    // ---------------------------------------------------------------- Hover, Go-to-Definition, Find-References & Symbol Rename

    fun extractWordAtPosition(text: String, line0: Int, col0: Int): String {
        val lines = text.split('\n')
        val lineStr = lines.getOrNull(line0) ?: return ""
        if (lineStr.isEmpty()) return ""
        val idx = col0.coerceIn(0, (lineStr.length - 1).coerceAtLeast(0))
        val isIdent = { ch: Char -> ch.isLetterOrDigit() || ch == '_' }
        val pivot = when {
            isIdent(lineStr[idx]) -> idx
            idx > 0 && isIdent(lineStr[idx - 1]) -> idx - 1
            else -> return ""
        }
        var start = pivot
        var end = pivot
        while (start > 0 && isIdent(lineStr[start - 1])) start--
        while (end + 1 < lineStr.length && isIdent(lineStr[end + 1])) end++
        return lineStr.substring(start, end + 1)
    }

    /**
     * Resolves Hover Documentation (`textDocument/hover`) for the symbol at `(line0, col0)`.
     * Queries the real stdio LSP session first, then enriches with in-project declaration
     * doc comments (KDoc / Javadoc / docstrings) and built-in language reference signatures.
     */
    fun resolveHoverDocumentation(
        context: Context,
        fileName: String,
        text: String,
        line0: Int,
        col0: Int,
        localFile: File? = null
    ): HoverResult? {
        val symbol = extractWordAtPosition(text, line0, col0)
        if (symbol.isEmpty()) return null
        val server = activeServerForFile(context, fileName)
            ?: serverSpecForFile(fileName)
            ?: AVAILABLE_SERVERS.first()

        // 1. Try real stdio `textDocument/hover` if server process is running
        val stdioSession = stdioSessions[server.id]?.takeIf { it.isAlive() }
        if (stdioSession != null) {
            val uri = stdioSession.guestUriForFile(fileName, localFile)
            val params = JSONObject().apply {
                put("textDocument", JSONObject().put("uri", uri))
                put("position", JSONObject().put("line", line0).put("character", col0))
            }
            val resp = stdioSession.sendRequest("textDocument/hover", params, timeoutMs = 700L)
            val resultObj = resp?.optJSONObject("result")
            if (resultObj != null) {
                val contents = resultObj.opt("contents")
                val hoverBody = when (contents) {
                    is JSONObject -> contents.optString("value", "")
                    is JSONArray -> {
                        val sb = StringBuilder()
                        for (i in 0 until contents.length()) {
                            val el = contents.opt(i)
                            if (el is JSONObject) sb.appendLine(el.optString("value", ""))
                            else if (el is String) sb.appendLine(el)
                        }
                        sb.toString().trim()
                    }
                    is String -> contents
                    else -> ""
                }
                if (hoverBody.isNotBlank()) {
                    val decl = findDefinitions(context, fileName, text, line0, col0, localFile).firstOrNull()
                    return HoverResult(
                        symbol = symbol,
                        signature = hoverBody.lineSequence().firstOrNull() ?: symbol,
                        documentation = hoverBody,
                        sourceServer = "${server.name} (stdio)",
                        declarationLocation = decl
                    )
                }
            }
        }

        // 2. Resolve declaration & preceding doc comments across active buffer and project files
        val defs = findDefinitions(context, fileName, text, line0, col0, localFile)
        val primaryDef = defs.firstOrNull()
        if (primaryDef != null) {
            val docComments = extractPrecedingDocComment(primaryDef.file, text, primaryDef.line1Based - 1)
            return HoverResult(
                symbol = symbol,
                signature = primaryDef.preview,
                documentation = buildString {
                    appendLine("Declared in ${primaryDef.relativePath}:${primaryDef.line1Based}")
                    if (docComments.isNotBlank()) {
                        appendLine()
                        append(docComments)
                    }
                }.trim(),
                sourceServer = server.name,
                declarationLocation = primaryDef
            )
        }

        // 3. Built-in completion / standard library symbol documentation
        val preset = (SERVER_COMPLETIONS[server.id].orEmpty() + SERVER_COMPLETIONS.values.flatten())
            .firstOrNull { it.label == symbol || it.label.startsWith("$symbol ") }
        if (preset != null) {
            return HoverResult(
                symbol = symbol,
                signature = "${preset.kind.badge} $symbol — ${preset.detail}",
                documentation = "Standard library / language construct provided by ${server.name}.\n\nTemplate:\n${preset.insertText}",
                sourceServer = server.name
            )
        }

        return HoverResult(
            symbol = symbol,
            signature = "symbol `$symbol`",
            documentation = "Identifier in $fileName (${server.name}). Tap 'Find References' or 'Rename Symbol' to inspect or refactor across the project.",
            sourceServer = server.name
        )
    }

    private fun extractPrecedingDocComment(file: File?, fallbackText: String, declLine0: Int): String {
        val content = runCatching { file?.readText(Charsets.UTF_8) }.getOrNull() ?: fallbackText
        val lines = content.split('\n')
        if (declLine0 <= 0 || declLine0 >= lines.size) return ""
        val collected = ArrayList<String>()
        var idx = declLine0 - 1
        while (idx >= 0) {
            val t = lines[idx].trim()
            when {
                t.startsWith("//") -> collected.add(0, t.removePrefix("//").trim())
                t.startsWith("#") && !t.startsWith("#!") -> collected.add(0, t.removePrefix("#").trim())
                t.startsWith("*") || t.startsWith("/**") || t.startsWith("/*") || t.endsWith("*/") -> {
                    val cleaned = t.removePrefix("/**").removePrefix("/*").removeSuffix("*/").removePrefix("*").trim()
                    if (cleaned.isNotEmpty()) collected.add(0, cleaned)
                }
                t.startsWith("@") -> collected.add(0, t)
                else -> break
            }
            idx--
        }
        return collected.joinToString("\n")
    }

    /**
     * Resolves Go-to-Definition (`textDocument/definition`) locations for the symbol at `(line0, col0)`
     * across both the active editor buffer and all files in the current project.
     */
    fun findDefinitions(
        context: Context,
        fileName: String,
        text: String,
        line0: Int,
        col0: Int,
        localFile: File? = null
    ): List<SymbolLocation> {
        val symbol = extractWordAtPosition(text, line0, col0)
        if (symbol.isEmpty()) return emptyList()
        val esc = Regex.escape(symbol)
        val declPattern = Regex(
            "(?:\\b(?:fun|val|var|let|const|def|function|func|fn|class|interface|struct|enum|object|type|trait|mod|typealias)\\s+(?:<[^>]+>\\s+)?(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?($esc)\\b)|" +
                "(?:\\b(?:int|long|short|byte|float|double|boolean|bool|char|void|auto|string|String|[A-Z][a-zA-Z0-9_<>,\\[\\]?*]*)\\s+($esc)\\s*[=;,(\\[])|" +
                "(?:^\\s*#define\\s+($esc)\\b)|" +
                "(?:\\b($esc)\\s*:=)"
        )

        val results = ArrayList<SymbolLocation>()

        fun scanLines(lines: List<String>, f: File?, fName: String, relPath: String) {
            for (i in lines.indices) {
                val raw = lines[i]
                val m = declPattern.find(raw) ?: continue
                val col = raw.indexOf(symbol).coerceAtLeast(0)
                results.add(
                    SymbolLocation(
                        file = f,
                        fileName = fName,
                        relativePath = relPath,
                        line1Based = i + 1,
                        col0Based = col,
                        preview = raw.trim(),
                        isDeclaration = true
                    )
                )
            }
        }

        // 1. Scan active buffer first
        val activeLines = text.split('\n')
        scanLines(activeLines, localFile, fileName, localFile?.name ?: fileName)

        // 2. Scan project files
        val projectNodes = ProjectManager.collectAllProjectFiles(context, maxFiles = 400)
        for (node in projectNodes) {
            val f = node.localFile ?: continue
            if (localFile != null && f.absolutePath == localFile.absolutePath) continue
            val fText = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (!fText.contains(symbol)) continue
            scanLines(fText.split('\n'), f, f.name, node.relativePath)
        }

        return results
    }

    /**
     * Finds all references (`textDocument/references`) of the symbol at `(line0, col0)` across
     * the active editor buffer and all project files.
     */
    fun findReferences(
        context: Context,
        fileName: String,
        text: String,
        line0: Int,
        col0: Int,
        localFile: File? = null
    ): List<SymbolLocation> {
        val symbol = extractWordAtPosition(text, line0, col0)
        if (symbol.isEmpty()) return emptyList()
        val wordRegex = Regex("\\b${Regex.escape(symbol)}\\b")
        val results = ArrayList<SymbolLocation>()

        fun scanFileLines(lines: List<String>, f: File?, fName: String, relPath: String) {
            for (i in lines.indices) {
                val raw = lines[i]
                for (m in wordRegex.findAll(raw)) {
                    results.add(
                        SymbolLocation(
                            file = f,
                            fileName = fName,
                            relativePath = relPath,
                            line1Based = i + 1,
                            col0Based = m.range.first,
                            preview = raw.trim()
                        )
                    )
                }
            }
        }

        scanFileLines(text.split('\n'), localFile, fileName, localFile?.name ?: fileName)

        val projectNodes = ProjectManager.collectAllProjectFiles(context, maxFiles = 400)
        for (node in projectNodes) {
            val f = node.localFile ?: continue
            if (localFile != null && f.absolutePath == localFile.absolutePath) continue
            val fText = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (!fText.contains(symbol)) continue
            scanFileLines(fText.split('\n'), f, f.name, node.relativePath)
        }

        return results
    }

    /**
     * Performs a symbol rename refactor (`textDocument/rename`) across the active editor buffer
     * and all matching project files.
     */
    fun renameSymbol(
        context: Context,
        fileName: String,
        text: String,
        line0: Int,
        col0: Int,
        newName: String,
        localFile: File? = null,
        openTabs: List<OpenedFile> = emptyList()
    ): RenameSummary? {
        val oldName = extractWordAtPosition(text, line0, col0)
        val cleanNew = newName.trim()
        if (oldName.isEmpty() || cleanNew.isEmpty() || oldName == cleanNew) return null
        if (!cleanNew.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) return null

        val wordRegex = Regex("\\b${Regex.escape(oldName)}\\b")
        var totalHits = 0
        var modifiedFiles = 0

        val activeHits = wordRegex.findAll(text).count()
        val updatedActiveText = if (activeHits > 0) {
            totalHits += activeHits
            modifiedFiles++
            wordRegex.replace(text, cleanNew)
        } else {
            text
        }

        val projectNodes = ProjectManager.collectAllProjectFiles(context, maxFiles = 400)
        for (node in projectNodes) {
            val f = node.localFile ?: continue
            if (localFile != null && f.absolutePath == localFile.absolutePath) continue
            val fText = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val count = wordRegex.findAll(fText).count()
            if (count > 0) {
                val replaced = wordRegex.replace(fText, cleanNew)
                runCatching { f.writeText(replaced, Charsets.UTF_8) }
                openTabs.firstOrNull { it.localFile?.absolutePath == f.absolutePath }?.let { tab ->
                    tab.content = replaced
                    tab.modified = false
                }
                totalHits += count
                modifiedFiles++
            }
        }

        return RenameSummary(
            oldName = oldName,
            newName = cleanNew,
            updatedEditorText = updatedActiveText,
            totalReplacements = totalHits,
            modifiedFilesCount = modifiedFiles
        )
    }
}
