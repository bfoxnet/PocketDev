package com.bfoxnet.pocketdev

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.roundToInt

/**
 * Compile/Run & Code-Formatting engine for PocketDev.
 */
object BuildRunManager {

    data class RunConfig(
        val languageKey: String,
        val displayName: String,
        val commandTemplate: String,
        val programArgs: String = "",
        val envVars: String = "",
        val runInTerminal: Boolean = false
    )

    @Volatile
    private var activeProcess: Process? = null

    fun isRunning(): Boolean = activeProcess?.isAlive == true

    fun stopCurrentBuild() {
        runCatching { activeProcess?.destroyForcibly() }
        activeProcess = null
    }

    fun sendStdinToCurrentBuild(inputLine: String): Boolean {
        val proc = activeProcess ?: return false
        if (!proc.isAlive) return false
        return try {
            val payload = (inputLine + "\n").toByteArray(Charsets.UTF_8)
            Thread {
                runCatching {
                    proc.outputStream.write(payload)
                    proc.outputStream.flush()
                }
            }.apply { isDaemon = true }.start()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun defaultRunConfigForFile(fileName: String): RunConfig {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "py", "pyw" -> RunConfig(
                languageKey = "python",
                displayName = "Python 3",
                commandTemplate = "python3 -u \$FILE \$ARGS"
            )
            "js", "mjs", "cjs" -> RunConfig(
                languageKey = "javascript",
                displayName = "Node.js",
                commandTemplate = "node \$FILE \$ARGS"
            )
            "ts", "tsx" -> RunConfig(
                languageKey = "typescript",
                displayName = "TypeScript",
                commandTemplate = "if command -v tsx >/dev/null 2>&1; then tsx \$FILE \$ARGS; elif command -v ts-node >/dev/null 2>&1; then ts-node \$FILE \$ARGS; else tsc \$FILE && node \$BASENAME.js \$ARGS; fi"
            )
            "go" -> RunConfig(
                languageKey = "go",
                displayName = "Go",
                commandTemplate = "if [ -f go.mod ]; then go run . \$ARGS; else go run \$FILE \$ARGS; fi"
            )
            "java" -> RunConfig(
                languageKey = "java",
                displayName = "Java",
                commandTemplate = "javac \$FILE && java \$BASENAME \$ARGS"
            )
            "kt", "kts" -> RunConfig(
                languageKey = "kotlin",
                displayName = "Kotlin",
                commandTemplate = "if command -v kotlinc >/dev/null 2>&1; then kotlinc \$FILE -include-runtime -d \$BASENAME.jar && java -jar \$BASENAME.jar \$ARGS; else echo 'Install kotlinc or run via Gradle'; fi"
            )
            "rs" -> RunConfig(
                languageKey = "rust",
                displayName = "Rust",
                commandTemplate = "if [ -f Cargo.toml ]; then cargo run -- \$ARGS; else rustc \$FILE -o /tmp/\$BASENAME && /tmp/\$BASENAME \$ARGS; fi"
            )
            "c" -> RunConfig(
                languageKey = "c",
                displayName = "C (Clang / GCC)",
                commandTemplate = "if command -v clang >/dev/null 2>&1; then clang -O2 -Wall \$FILE -o /tmp/\$BASENAME && /tmp/\$BASENAME \$ARGS; else gcc -O2 -Wall \$FILE -o /tmp/\$BASENAME && /tmp/\$BASENAME \$ARGS; fi"
            )
            "cpp", "cc", "cxx", "hpp" -> RunConfig(
                languageKey = "cpp",
                displayName = "C++ (Clang++ / G++)",
                commandTemplate = "if command -v clang++ >/dev/null 2>&1; then clang++ -std=c++20 -O2 -Wall \$FILE -o /tmp/\$BASENAME && /tmp/\$BASENAME \$ARGS; else g++ -std=c++20 -O2 -Wall \$FILE -o /tmp/\$BASENAME && /tmp/\$BASENAME \$ARGS; fi"
            )
            "sh", "bash", "zsh" -> RunConfig(
                languageKey = "shell",
                displayName = "Shell Script",
                commandTemplate = "sh \$FILE \$ARGS"
            )
            "php" -> RunConfig(
                languageKey = "php",
                displayName = "PHP",
                commandTemplate = "php \$FILE \$ARGS"
            )
            "rb" -> RunConfig(
                languageKey = "ruby",
                displayName = "Ruby",
                commandTemplate = "ruby \$FILE \$ARGS"
            )
            "lua" -> RunConfig(
                languageKey = "lua",
                displayName = "Lua",
                commandTemplate = "lua \$FILE \$ARGS"
            )
            else -> RunConfig(
                languageKey = "generic",
                displayName = "Custom Command",
                commandTemplate = "ls -la && echo 'Running '\"\${FILE}\""
            )
        }
    }

    fun loadRunConfig(context: Context, fileName: String): RunConfig {
        val def = defaultRunConfigForFile(fileName)
        val prefs = context.getSharedPreferences("pocketdev_run_configs", Context.MODE_PRIVATE)
        val key = def.languageKey
        val cmd = prefs.getString("${key}_cmd", null)?.takeIf { it.isNotBlank() } ?: def.commandTemplate
        val args = prefs.getString("${key}_args", "") ?: ""
        val env = prefs.getString("${key}_env", "") ?: ""
        val inTerm = prefs.getBoolean("${key}_term", false)
        return def.copy(
            commandTemplate = cmd,
            programArgs = args,
            envVars = env,
            runInTerminal = inTerm
        )
    }

    fun saveRunConfig(context: Context, config: RunConfig) {
        val prefs = context.getSharedPreferences("pocketdev_run_configs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("${config.languageKey}_cmd", config.commandTemplate.trim())
            .putString("${config.languageKey}_args", config.programArgs.trim())
            .putString("${config.languageKey}_env", config.envVars.trim())
            .putBoolean("${config.languageKey}_term", config.runInTerminal)
            .apply()
    }

    fun resetRunConfig(context: Context, fileName: String): RunConfig {
        val def = defaultRunConfigForFile(fileName)
        val prefs = context.getSharedPreferences("pocketdev_run_configs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("${def.languageKey}_cmd")
            .remove("${def.languageKey}_args")
            .remove("${def.languageKey}_env")
            .remove("${def.languageKey}_term")
            .apply()
        return def
    }

    private fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun buildExpandedShellCommand(context: Context, file: File, config: RunConfig): String {
        val workDir = file.parentFile ?: ProjectManager.ensureDefaultLocalWorkspace(context)
        val guestDir = UbuntuProotManager.hostDirToGuestPath(context, workDir)
        val quotedFile = shellSingleQuote(file.name)
        val baseName = file.nameWithoutExtension.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "app" }
        val envPrefix = config.envVars.trim().let { raw ->
            if (raw.isEmpty()) "" else "export $raw; "
        }
        val expanded = config.commandTemplate
            .replace("\$FILE", quotedFile)
            .replace("\$BASENAME", baseName)
            .replace("\$DIR", shellSingleQuote(guestDir))
            .replace("\$ARGS", config.programArgs.trim())
            .trim()
        return "${envPrefix}cd ${shellSingleQuote(guestDir)} 2>/dev/null || true; $expanded"
    }

    fun executeFileStreaming(
        context: Context,
        file: File,
        config: RunConfig = loadRunConfig(context, file.name),
        onOutputLine: (String) -> Unit,
        onFinished: (exitCode: Int, elapsedMs: Long) -> Unit
    ) {
        stopCurrentBuild()
        val startMs = System.currentTimeMillis()
        val workDir = file.parentFile ?: ProjectManager.ensureDefaultLocalWorkspace(context)
        val fullCmd = buildExpandedShellCommand(context, file, config)

        DiagLogger.log("Build", "Executing (${config.displayName}): $fullCmd", context)

        Thread {
            var exitCode = -1
            try {
                onOutputLine("▶ [${config.displayName}] ${file.name}")
                onOutputLine("\$ $fullCmd")
                onOutputLine("────────────────────────────────────────────────")

                val pb = if (UbuntuProotManager.isUbuntuInstalled(context)) {
                    val mode = UbuntuProotManager.preferredLaunchMode
                        ?: UbuntuProotManager.availableLaunchModes(context).firstOrNull()
                        ?: UbuntuProotManager.ProotLaunchMode.DIRECT
                    val cmdList = UbuntuProotManager.buildProotCommandListForMode(
                        context = context,
                        hostWorkingDir = workDir,
                        guestScript = fullCmd,
                        mode = mode,
                        fastNoRc = true
                    )
                    ProcessBuilder(cmdList).apply {
                        directory(workDir)
                        redirectErrorStream(true)
                        UbuntuProotManager.applyHostProotEnv(context, environment())
                    }
                } else {
                    onOutputLine("ℹ Linux environment not initialized — running with device shell.")
                    ProcessBuilder("/system/bin/sh", "-c", "cd ${shellSingleQuote(workDir.absolutePath)} && ${config.commandTemplate.replace("\$FILE", shellSingleQuote(file.name)).replace("\$BASENAME", file.nameWithoutExtension).replace("\$ARGS", config.programArgs)}")
                        .directory(workDir)
                        .redirectErrorStream(true)
                }

                val proc = pb.start()
                activeProcess = proc

                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        onOutputLine(line)
                        if (line.contains("error:", ignoreCase = true) || line.contains("Exception", ignoreCase = true)) {
                            DiagLogger.log("Build", line, context)
                        }
                        line = reader.readLine()
                    }
                }
                exitCode = proc.waitFor()
            } catch (e: Exception) {
                val msg = "Execution error: ${e.message}"
                onOutputLine("✗ $msg")
                DiagLogger.log("Build", msg, context, e)
            } finally {
                activeProcess = null
                val elapsed = System.currentTimeMillis() - startMs
                onFinished(exitCode, elapsed)
            }
        }.apply {
            name = "PocketDev-BuildRunner"
            isDaemon = true
        }.start()
    }

    fun showRunConfigDialog(
        context: Context,
        fileName: String,
        onSaveAndRun: (RunConfig) -> Unit
    ) {
        val current = loadRunConfig(context, fileName)
        val palette = ModernUiHelper.resolvePalette(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(14))
        }

        val titleView = TextView(context).apply {
            text = "Run Configuration • ${current.displayName}"
            textSize = 16f
            typeface = ModernUiHelper.appFontBold(context)
            setTextColor(palette.fgColor)
        }
        root.addView(titleView)

        val subView = TextView(context).apply {
            text = "Variables: \$FILE, \$BASENAME, \$DIR, \$ARGS"
            textSize = 11.5f
            typeface = ModernUiHelper.appFontRegular(context)
            setTextColor(palette.subColor)
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(subView)

        fun styledField(label: String, initialValue: String, hintText: String): EditText {
            val lbl = TextView(context).apply {
                text = label
                textSize = 11.5f
                typeface = ModernUiHelper.appFontBold(context)
                setTextColor(palette.accentColor)
                setPadding(0, dp(6), 0, dp(4))
            }
            root.addView(lbl)
            val input = EditText(context).apply {
                setText(initialValue)
                hint = hintText
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(palette.fgColor)
                setHintTextColor(palette.subColor)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(palette.inputBgColor)
                    setStroke(dp(1), palette.strokeColor)
                }
            }
            root.addView(input)
            return input
        }

        val cmdInput = styledField(
            label = "Command",
            initialValue = current.commandTemplate,
            hintText = defaultRunConfigForFile(fileName).commandTemplate
        )
        val argsInput = styledField(
            label = "Arguments (\$ARGS)",
            initialValue = current.programArgs,
            hintText = "--port 8080 input.txt"
        )
        val envInput = styledField(
            label = "Environment Variables",
            initialValue = current.envVars,
            hintText = "RUST_BACKTRACE=1 PYTHONUNBUFFERED=1"
        )

        val terminalCheck = CheckBox(context).apply {
            text = "Run in interactive terminal"
            textSize = 12.5f
            typeface = ModernUiHelper.appFontMedium(context)
            setTextColor(palette.fgColor)
            buttonTintList = ColorStateList.valueOf(palette.accentColor)
            isChecked = current.runInTerminal
            setPadding(dp(4), dp(10), 0, dp(8))
        }
        root.addView(terminalCheck)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val scroll = ScrollView(context).apply { addView(root) }
        val sheetDialog = ModernUiHelper.showModernCustomBottomSheet(context, scroll, palette)

        val resetBtn = TextView(context).apply {
            text = "Reset"
            textSize = 12.5f
            typeface = ModernUiHelper.appFontMedium(context)
            setTextColor(palette.subColor)
            setPadding(dp(10), dp(8), dp(12), dp(8))
            setOnClickListener {
                val def = resetRunConfig(context, fileName)
                cmdInput.setText(def.commandTemplate)
                argsInput.setText(def.programArgs)
                envInput.setText(def.envVars)
                terminalCheck.isChecked = def.runInTerminal
                Toast.makeText(context, "Reset to default", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(resetBtn)

        val saveOnlyBtn = TextView(context).apply {
            text = "Save"
            textSize = 13f
            typeface = ModernUiHelper.appFontBold(context)
            setTextColor(palette.fgColor)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                val updated = current.copy(
                    commandTemplate = cmdInput.text?.toString()?.trim().orEmpty().ifEmpty { current.commandTemplate },
                    programArgs = argsInput.text?.toString()?.trim().orEmpty(),
                    envVars = envInput.text?.toString()?.trim().orEmpty(),
                    runInTerminal = terminalCheck.isChecked
                )
                saveRunConfig(context, updated)
                sheetDialog.dismiss()
                Toast.makeText(context, "Saved configuration", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(saveOnlyBtn)

        val runBtn = TextView(context).apply {
            text = "Save & Run"
            textSize = 13f
            typeface = ModernUiHelper.appFontBold(context)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener {
                val updated = current.copy(
                    commandTemplate = cmdInput.text?.toString()?.trim().orEmpty().ifEmpty { current.commandTemplate },
                    programArgs = argsInput.text?.toString()?.trim().orEmpty(),
                    envVars = envInput.text?.toString()?.trim().orEmpty(),
                    runInTerminal = terminalCheck.isChecked
                )
                saveRunConfig(context, updated)
                sheetDialog.dismiss()
                onSaveAndRun(updated)
            }
        }
        btnRow.addView(runBtn)
        root.addView(btnRow)
    }

    fun formatDocumentAsync(
        context: Context,
        fileName: String,
        content: String,
        localFile: File?,
        onFormatted: (formattedText: String, formatterLabel: String) -> Unit
    ) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val settings = Settings(context)
            var formatted: String? = null
            var label = if (settings.prettierEnabled) "Prettier" else "Built-in Formatter"
            val prettierFlags = PrettierFormatter.buildCliFlags(settings)

            if (UbuntuProotManager.isUbuntuInstalled(context) && localFile != null && localFile.exists()) {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val quoted = shellSingleQuote(localFile.name)
                val formatterCmd = when (ext) {
                    "c", "cpp", "cc", "cxx", "h", "hpp", "java", "cs", "proto" ->
                        "command -v clang-format >/dev/null 2>&1 && clang-format $quoted"
                    "go" ->
                        "command -v gofmt >/dev/null 2>&1 && gofmt $quoted"
                    "js", "jsx", "ts", "tsx", "mjs", "cjs", "json", "jsonc", "css", "scss", "sass", "less", "html", "htm", "vue", "svelte", "md", "markdown", "yaml", "yml" ->
                        "command -v prettier >/dev/null 2>&1 && prettier $prettierFlags --stdin-filepath $quoted < $quoted"
                    "py", "pyw" ->
                        "if command -v black >/dev/null 2>&1; then black -q - < $quoted; elif command -v autopep8 >/dev/null 2>&1; then autopep8 - < $quoted; else exit 1; fi"
                    "rs" ->
                        "command -v rustfmt >/dev/null 2>&1 && rustfmt --emit stdout < $quoted"
                    "sh", "bash", "zsh" ->
                        "command -v shfmt >/dev/null 2>&1 && shfmt -i ${settings.prettierTabWidth} < $quoted"
                    else -> null
                }

                if (formatterCmd != null) {
                    runCatching {
                        val workDir = localFile.parentFile ?: ProjectManager.ensureDefaultLocalWorkspace(context)
                        val guestDir = UbuntuProotManager.hostDirToGuestPath(context, workDir)
                        val mode = UbuntuProotManager.preferredLaunchMode
                            ?: UbuntuProotManager.availableLaunchModes(context).firstOrNull()
                            ?: UbuntuProotManager.ProotLaunchMode.DIRECT
                        val cmdList = UbuntuProotManager.buildProotCommandListForMode(
                            context = context,
                            hostWorkingDir = workDir,
                            guestScript = "cd ${shellSingleQuote(guestDir)} && $formatterCmd",
                            mode = mode,
                            fastNoRc = true
                        )
                        val pb = ProcessBuilder(cmdList)
                            .directory(workDir)
                            .redirectErrorStream(false)
                        UbuntuProotManager.applyHostProotEnv(context, pb.environment())
                        val proc = pb.start()
                        val stdout = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                        val code = proc.waitFor()
                        if (code == 0 && stdout.isNotBlank()) {
                            formatted = stdout
                            label = when (ext) {
                                "go" -> "gofmt"
                                "c", "cpp", "cc", "cxx", "h", "hpp", "java" -> "clang-format"
                                "py", "pyw" -> "black"
                                "rs" -> "rustfmt"
                                "sh", "bash", "zsh" -> "shfmt"
                                else -> "prettier"
                            }
                        }
                    }
                }
            }

            val finalFormatted = formatted ?: if (settings.prettierEnabled) {
                PrettierFormatter.formatInProcess(fileName, content, settings)
            } else {
                formatStructureBuiltIn(fileName, content, settings.prettierTabWidth)
            }
            mainHandler.post {
                onFormatted(finalFormatted, label)
            }
        }.apply { isDaemon = true }.start()
    }

    fun formatStructureBuiltIn(fileName: String, content: String, tabSize: Int = 4): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "json") {
            runCatching {
                val trimmed = content.trim()
                if (trimmed.startsWith("{")) {
                    return org.json.JSONObject(trimmed).toString(2) + "\n"
                } else if (trimmed.startsWith("[")) {
                    return org.json.JSONArray(trimmed).toString(2) + "\n"
                }
            }
        }
        val indentUnit = " ".repeat(if (ext in setOf("xml", "html", "htm", "json", "yaml", "yml", "js", "ts", "jsx", "tsx", "css")) 2 else tabSize)
        val rawLines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        if (ext in setOf("py", "pyw", "yaml", "yml", "mk") || fileName.equals("Makefile", ignoreCase = true)) {
            return rawLines.joinToString("\n") { line ->
                if (fileName.equals("Makefile", ignoreCase = true)) {
                    line.trimEnd()
                } else {
                    line.replace("\t", indentUnit).trimEnd()
                }
            }.trimEnd() + "\n"
        }

        val out = StringBuilder(content.length + 64)
        var depth = 0
        var consecutiveBlank = 0

        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                consecutiveBlank++
                if (consecutiveBlank <= 1) {
                    out.append('\n')
                }
                continue
            }
            consecutiveBlank = 0

            val startsWithClosing = trimmed.startsWith("}") ||
                trimmed.startsWith("]") ||
                trimmed.startsWith(")") ||
                trimmed.startsWith("</")
            if (startsWithClosing) {
                depth = (depth - 1).coerceAtLeast(0)
            }

            out.append(indentUnit.repeat(depth)).append(trimmed).append('\n')

            var netDelta = 0
            var inStr: Char? = null
            var i = 0
            while (i < trimmed.length) {
                val c = trimmed[i]
                if (c == '/' && i + 1 < trimmed.length && trimmed[i + 1] == '/' && inStr == null) {
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
                        '"', '\'' -> inStr = c
                        '{', '[' -> netDelta++
                        '}', ']' -> netDelta--
                    }
                }
                i++
            }
            if (startsWithClosing) {
                netDelta += 1
            }
            depth = (depth + netDelta).coerceAtLeast(0)
        }

        return out.toString().trimEnd() + "\n"
    }
}
