package com.bfoxnet.pocketdev

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Extensible IDE Plugins System for PocketDev (`PluginManager`).
 *
 * Includes 10 built-in productivity, code intelligence, formatting, and transformation plugins
 * plus support for user-authored custom plugins stored in `$filesDir/plugins/<id>.json`.
 */
object PluginManager {

    enum class PluginCategory(val label: String) {
        FORMATTING("Formatting"),
        PRODUCTIVITY("Editor Productivity"),
        INTELLIGENCE("Code Intelligence"),
        UI_TOOLS("UI & Color Tools"),
        DATA_TOOLS("Data & Text Tools"),
        GIT("Source Control"),
        PREVIEW("Live Preview"),
        CUSTOM("Custom Plugin")
    }

    data class IdePlugin(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val category: PluginCategory,
        val description: String,
        val actionLabel: String,
        val defaultEnabled: Boolean = true,
        val isCustom: Boolean = false,
        val customShellCommand: String? = null,
        val customInsertSnippet: String? = null
    )

    val BUILT_IN_PLUGINS: List<IdePlugin> = listOf(
        IdePlugin(
            id = "prettier-formatter",
            name = "Prettier Code Formatter",
            version = "3.3.3",
            author = "PocketDev Core",
            category = PluginCategory.FORMATTING,
            description = "Formats JS/TS, JSON, HTML, CSS, Markdown, Kotlin, Python, and more using your Prettier Settings.",
            actionLabel = "Format Active File",
            defaultEnabled = true
        ),
        IdePlugin(
            id = "auto-close-pairs",
            name = "Auto-Close Brackets & Quotes",
            version = "1.4.0",
            author = "PocketDev Core",
            category = PluginCategory.PRODUCTIVITY,
            description = "Automatically inserts matching closing ), }, ], \", ', and ` characters while typing in the code editor.",
            actionLabel = "Insert Pair Wrap",
            defaultEnabled = true
        ),
        IdePlugin(
            id = "todo-tree-scanner",
            name = "TODO / FIXME / NOTE Scanner",
            version = "2.1.0",
            author = "PocketDev Core",
            category = PluginCategory.INTELLIGENCE,
            description = "Scans the active buffer and project workspace for TODO, FIXME, BUG, HACK, and NOTE annotations with 1-tap jump.",
            actionLabel = "Scan TODOs",
            defaultEnabled = true
        ),
        IdePlugin(
            id = "color-swatch-inspector",
            name = "Inline Hex & RGB Color Palette",
            version = "1.8.0",
            author = "PocketDev Core",
            category = PluginCategory.UI_TOOLS,
            description = "Detects all #RRGGBB and #AARRGGBB color literals in the current file, previews swatches, and inserts material colors.",
            actionLabel = "Inspect Colors",
            defaultEnabled = true
        ),
        IdePlugin(
            id = "code-metrics-inspector",
            name = "Code Metrics & Complexity Analyzer",
            version = "1.5.2",
            author = "PocketDev Core",
            category = PluginCategory.INTELLIGENCE,
            description = "Computes lines of code (LOC), comments, blank lines, function/class declarations, cyclomatic complexity, and file size.",
            actionLabel = "Analyze Metrics",
            defaultEnabled = true
        ),
        IdePlugin(
            id = "smart-snippet-expander",
            name = "Boilerplate & Snippet Generator",
            version = "2.0.0",
            author = "PocketDev Core",
            category = PluginCategory.PRODUCTIVITY,
            description = "Insert ready-to-run starter templates, REST fetch patterns, coroutine builders, HTML5 scaffolds, and license headers.",
            actionLabel = "Insert Snippet",
            defaultEnabled = true
        ),
        IdePlugin(
            id = "json-data-swiss-knife",
            name = "JSON / Base64 / URL / Case Swiss-Knife",
            version = "1.9.0",
            author = "PocketDev Core",
            category = PluginCategory.DATA_TOOLS,
            description = "Minify or pretty-print JSON, sort JSON keys, Base64 encode/decode, URL encode/decode, or convert identifier casing.",
            actionLabel = "Transform Text",
            defaultEnabled = true
        ),
        IdePlugin(
            id = "git-blame-lens",
            name = "Git Line Blame & History Lens",
            version = "1.3.0",
            author = "PocketDev Core",
            category = PluginCategory.GIT,
            description = "Inspects the Git commit author, timestamp, commit hash, and recent history for the active line and file.",
            actionLabel = "Inspect Git Blame",
            defaultEnabled = true
        ),
        IdePlugin(
            id = "whitespace-hygiene",
            name = "Trailing Whitespace & EOF Cleaner",
            version = "1.2.1",
            author = "PocketDev Core",
            category = PluginCategory.FORMATTING,
            description = "Automatically strips trailing spaces from lines on save and enforces a clean POSIX newline at end of file.",
            actionLabel = "Clean Whitespace Now",
            defaultEnabled = true
        ),
        IdePlugin(
            id = "markdown-html-preview",
            name = "Markdown & HTML Live Preview",
            version = "1.6.0",
            author = "PocketDev Core",
            category = PluginCategory.PREVIEW,
            description = "Renders Markdown (.md) documents and HTML snippets into a styled live preview bottom sheet.",
            actionLabel = "Open Live Preview",
            defaultEnabled = true
        )
    )

    private fun customPluginsDir(context: Context): File =
        File(context.filesDir, "plugins").apply { mkdirs() }

    fun loadCustomPlugins(context: Context): List<IdePlugin> {
        val dir = customPluginsDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val list = ArrayList<IdePlugin>()
        for (file in files.sortedBy { it.name }) {
            runCatching {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                list.add(
                    IdePlugin(
                        id = obj.getString("id"),
                        name = obj.optString("name", file.nameWithoutExtension),
                        version = obj.optString("version", "1.0.0"),
                        author = obj.optString("author", "User"),
                        category = PluginCategory.CUSTOM,
                        description = obj.optString("description", "Custom user plugin"),
                        actionLabel = obj.optString("actionLabel", "Run Plugin"),
                        defaultEnabled = true,
                        isCustom = true,
                        customShellCommand = obj.optString("shellCommand", "").takeIf { it.isNotBlank() },
                        customInsertSnippet = obj.optString("insertSnippet", "").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
        return list
    }

    fun allPlugins(context: Context): List<IdePlugin> =
        BUILT_IN_PLUGINS + loadCustomPlugins(context)

    fun enabledPluginsCount(context: Context): Int {
        val settings = Settings(context)
        return allPlugins(context).count { settings.isPluginEnabled(it.id, it.defaultEnabled) }
    }

    fun isEnabled(context: Context, pluginId: String): Boolean {
        val plugin = allPlugins(context).firstOrNull { it.id == pluginId }
        return Settings(context).isPluginEnabled(pluginId, plugin?.defaultEnabled ?: true)
    }

    fun setEnabled(context: Context, pluginId: String, enabled: Boolean) {
        Settings(context).setPluginEnabled(pluginId, enabled)
    }

    fun saveCustomPlugin(
        context: Context,
        name: String,
        description: String,
        snippetOrCommand: String,
        isShellCommand: Boolean
    ): IdePlugin {
        val cleanId = "custom-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "plugin" }
        val file = File(customPluginsDir(context), "$cleanId.json")
        val obj = JSONObject().apply {
            put("id", cleanId)
            put("name", name.trim())
            put("version", "1.0.0")
            put("author", "Local Developer")
            put("description", description.trim().ifEmpty { "Custom user plugin" })
            put("actionLabel", if (isShellCommand) "Run Command" else "Insert Template")
            if (isShellCommand) {
                put("shellCommand", snippetOrCommand)
            } else {
                put("insertSnippet", snippetOrCommand)
            }
        }
        file.writeText(obj.toString(2), Charsets.UTF_8)
        Settings(context).setPluginEnabled(cleanId, true)
        return loadCustomPlugins(context).first { it.id == cleanId }
    }

    fun deleteCustomPlugin(context: Context, pluginId: String): Boolean {
        val file = File(customPluginsDir(context), "$pluginId.json")
        return file.exists() && file.delete()
    }

    // ---------------------------------------------------------------- Editor Lifecycle Hooks

    /**
     * Pre-save hook invoked before writing a file to disk:
     * - Applies `whitespace-hygiene` if enabled.
     * - Applies `prettier-formatter` if `prettierFormatOnSave` and `prettier-formatter` are enabled.
     */
    fun applyPreSaveHooks(context: Context, fileName: String, content: String): String {
        val settings = Settings(context)
        var updated = content
        if (settings.isPluginEnabled("whitespace-hygiene", true)) {
            updated = cleanTrailingWhitespaceAndEof(updated)
        }
        if (settings.prettierFormatOnSave &&
            settings.prettierEnabled &&
            settings.isPluginEnabled("prettier-formatter", true)
        ) {
            updated = PrettierFormatter.formatInProcess(fileName, updated, settings)
        }
        return updated
    }

    fun cleanTrailingWhitespaceAndEof(content: String): String {
        if (content.isEmpty()) return content
        val eol = if (content.contains("\r\n")) "\r\n" else "\n"
        val cleaned = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .joinToString(eol) { it.trimEnd(' ', '\t') }
            .trimEnd()
        return if (cleaned.isEmpty()) "" else cleaned + eol
    }

    // ---------------------------------------------------------------- Interactive Plugin Execution

    fun executePluginAction(
        context: Context,
        plugin: IdePlugin,
        fileName: String,
        currentText: String,
        cursorLine0: Int,
        localFile: File?,
        onReplaceEditorText: (String) -> Unit,
        onInsertAtCursor: (String) -> Unit,
        onJumpToLine: (line1Based: Int, col0Based: Int) -> Unit
    ) {
        if (plugin.isCustom) {
            if (!plugin.customInsertSnippet.isNullOrBlank()) {
                onInsertAtCursor(plugin.customInsertSnippet)
                Toast.makeText(context, "Inserted snippet from ${plugin.name}", Toast.LENGTH_SHORT).show()
            } else if (!plugin.customShellCommand.isNullOrBlank()) {
                val dir = localFile?.parentFile ?: ProjectManager.ensureDefaultLocalWorkspace(context)
                context.startActivity(
                    android.content.Intent(context, TerminalActivity::class.java).apply {
                        putExtra(TerminalActivity.EXTRA_WORKING_DIR, dir.absolutePath)
                        putExtra(TerminalActivity.EXTRA_AUTORUN_CMD, plugin.customShellCommand)
                    }
                )
            }
            return
        }

        when (plugin.id) {
            "prettier-formatter" -> {
                val formatted = PrettierFormatter.formatInProcess(fileName, currentText, Settings(context))
                onReplaceEditorText(formatted)
                Toast.makeText(context, "Formatted with Prettier", Toast.LENGTH_SHORT).show()
            }
            "auto-close-pairs" -> {
                showAutoClosePairWrapMenu(context, onInsertAtCursor)
            }
            "todo-tree-scanner" -> {
                showTodoScannerDialog(context, fileName, currentText, onJumpToLine)
            }
            "color-swatch-inspector" -> {
                showColorSwatchInspectorDialog(context, fileName, currentText, onInsertAtCursor)
            }
            "code-metrics-inspector" -> {
                showCodeMetricsDialog(context, fileName, currentText)
            }
            "smart-snippet-expander" -> {
                showSnippetGeneratorMenu(context, fileName, onInsertAtCursor)
            }
            "json-data-swiss-knife" -> {
                showDataSwissKnifeMenu(context, fileName, currentText, onReplaceEditorText)
            }
            "git-blame-lens" -> {
                showGitBlameDialog(context, fileName, cursorLine0 + 1, localFile)
            }
            "whitespace-hygiene" -> {
                val cleaned = cleanTrailingWhitespaceAndEof(currentText)
                onReplaceEditorText(cleaned)
                Toast.makeText(context, "Cleaned trailing whitespace & EOF newline", Toast.LENGTH_SHORT).show()
            }
            "markdown-html-preview" -> {
                showMarkdownHtmlPreviewDialog(context, fileName, currentText)
            }
        }
    }

    /**
     * Opens the quick Plugins Launcher bottom sheet directly inside the editor.
     */
    fun showEditorPluginsBottomSheet(
        context: Context,
        fileName: String,
        currentText: String,
        cursorLine0: Int,
        localFile: File?,
        onReplaceEditorText: (String) -> Unit,
        onInsertAtCursor: (String) -> Unit,
        onJumpToLine: (line1Based: Int, col0Based: Int) -> Unit,
        onOpenPluginsSettings: () -> Unit
    ) {
        val settings = Settings(context)
        val enabledList = allPlugins(context).filter { settings.isPluginEnabled(it.id, it.defaultEnabled) }
        val options = ArrayList<ModernUiHelper.MenuOption>()

        for ((idx, plugin) in enabledList.withIndex()) {
            options.add(
                ModernUiHelper.MenuOption(
                    id = idx,
                    title = plugin.name,
                    subtitle = "${plugin.actionLabel} • ${plugin.category.label}",
                    iconRes = R.drawable.ic_code,
                    badgeText = "v${plugin.version}",
                    action = {
                        executePluginAction(
                            context = context,
                            plugin = plugin,
                            fileName = fileName,
                            currentText = currentText,
                            cursorLine0 = cursorLine0,
                            localFile = localFile,
                            onReplaceEditorText = onReplaceEditorText,
                            onInsertAtCursor = onInsertAtCursor,
                            onJumpToLine = onJumpToLine
                        )
                    }
                )
            )
        }

        options.add(
            ModernUiHelper.MenuOption(
                id = 999,
                title = "Manage Plugins & Create Custom Plugin",
                subtitle = "${enabledList.size} of ${allPlugins(context).size} plugins active",
                iconRes = R.drawable.ic_settings,
                badgeText = "MANAGE",
                action = onOpenPluginsSettings
            )
        )

        ModernUiHelper.showModernContextMenu(
            context = context,
            title = "Plugins",
            subtitle = "Active editor extensions • Tap to run on $fileName",
            headerIconRes = R.drawable.ic_code,
            options = options
        )
    }

    private fun showAutoClosePairWrapMenu(context: Context, onInsertAtCursor: (String) -> Unit) {
        val pairs = listOf(
            "Curly Braces { … }" to "{\n    \n}",
            "Parentheses ( … )" to "()",
            "Square Brackets [ … ]" to "[]",
            "Double Quotes \" … \"" to "\"\"",
            "Single Quotes ' … '" to "''",
            "Template Backticks ` … `" to "``",
            "Block Comment /* … */" to "/*  */"
        )
        ModernUiHelper.showModernContextMenu(
            context = context,
            title = "Auto-Close Pair Insert",
            subtitle = "Auto-close while typing is active • Tap to insert pair",
            headerIconRes = R.drawable.ic_code,
            options = pairs.mapIndexed { idx, (label, ins) ->
                ModernUiHelper.MenuOption(
                    id = idx,
                    title = label,
                    action = { onInsertAtCursor(ins) }
                )
            }
        )
    }

    private fun showTodoScannerDialog(
        context: Context,
        fileName: String,
        currentText: String,
        onJumpToLine: (Int, Int) -> Unit
    ) {
        val regex = Regex("\\b(TODO|FIXME|BUG|HACK|NOTE)\\b[:\\s]*(.*)", RegexOption.IGNORE_CASE)
        val lines = currentText.split('\n')
        val options = ArrayList<ModernUiHelper.MenuOption>()
        for (i in lines.indices) {
            val m = regex.find(lines[i]) ?: continue
            val tag = m.groupValues[1].uppercase()
            val detail = m.groupValues[2].trim().ifEmpty { lines[i].trim() }
            options.add(
                ModernUiHelper.MenuOption(
                    id = i,
                    title = "[$tag] $detail",
                    subtitle = "$fileName : Ln ${i + 1}",
                    iconRes = R.drawable.ic_diagnostics,
                    badgeText = "Ln ${i + 1}",
                    action = { onJumpToLine(i + 1, m.range.first) }
                )
            )
        }
        if (options.isEmpty()) {
            Toast.makeText(context, "No TODO / FIXME / NOTE tags found in $fileName", Toast.LENGTH_SHORT).show()
            return
        }
        ModernUiHelper.showModernContextMenu(
            context = context,
            title = "TODO & FIXME Scanner (${options.size})",
            subtitle = "Tap any item to jump to its line in $fileName",
            headerIconRes = R.drawable.ic_diagnostics,
            options = options
        )
    }

    private fun showColorSwatchInspectorDialog(
        context: Context,
        fileName: String,
        currentText: String,
        onInsertAtCursor: (String) -> Unit
    ) {
        val hexRegex = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})\\b")
        val foundHexes = hexRegex.findAll(currentText).map { it.value.uppercase() }.distinct().toList()
        val presets = listOf(
            "#B85648" to "PocketDev Primary Terracotta",
            "#D47264" to "PocketDev Warm Coral",
            "#61FFCA" to "Aura Mint",
            "#A277FF" to "Aura Purple",
            "#CBA6F7" to "Catppuccin Mauve",
            "#89B4FA" to "Catppuccin Blue",
            "#2F81F7" to "GitHub Accent Blue",
            "#FF5252" to "Material Error Red",
            "#66BB6A" to "Material Success Green"
        )

        val options = ArrayList<ModernUiHelper.MenuOption>()
        foundHexes.forEachIndexed { idx, hex ->
            options.add(
                ModernUiHelper.MenuOption(
                    id = idx,
                    title = "$hex (Detected in $fileName)",
                    subtitle = "Tap to copy or insert color literal",
                    iconRes = R.drawable.ic_edit,
                    badgeText = "IN FILE",
                    action = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cb?.setPrimaryClip(ClipData.newPlainText("Color", hex))
                        Toast.makeText(context, "Copied $hex", Toast.LENGTH_SHORT).show()
                    }
                )
            )
        }
        presets.forEachIndexed { idx, (hex, name) ->
            options.add(
                ModernUiHelper.MenuOption(
                    id = 100 + idx,
                    title = "$hex • $name",
                    subtitle = "Tap to insert hex color at cursor",
                    iconRes = R.drawable.ic_add,
                    badgeText = hex,
                    action = { onInsertAtCursor(hex) }
                )
            )
        }

        ModernUiHelper.showModernContextMenu(
            context = context,
            title = "Color Palette Inspector",
            subtitle = "${foundHexes.size} color(s) detected in $fileName",
            headerIconRes = R.drawable.ic_code,
            options = options
        )
    }

    private fun showCodeMetricsDialog(context: Context, fileName: String, currentText: String) {
        val lines = if (currentText.isEmpty()) emptyList() else currentText.split('\n')
        val totalLines = lines.size
        val blankLines = lines.count { it.isBlank() }
        val commentLines = lines.count {
            val t = it.trim()
            t.startsWith("//") || t.startsWith("#") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("--")
        }
        val codeLines = (totalLines - blankLines - commentLines).coerceAtLeast(0)
        val functions = Regex("\\b(fun|function|def|func|fn)\\b").findAll(currentText).count()
        val classes = Regex("\\b(class|interface|object|struct|enum|trait)\\b").findAll(currentText).count()
        val branches = Regex("\\b(if|else if|elif|when|switch|case|for|while|catch|&&|\\|\\|)\\b").findAll(currentText).count()
        val complexity = 1 + branches
        val bytes = currentText.toByteArray(Charsets.UTF_8).size

        val report = buildString {
            appendLine("File                : $fileName")
            appendLine("Total Lines         : $totalLines")
            appendLine("Lines of Code (LOC) : $codeLines")
            appendLine("Comment Lines       : $commentLines")
            appendLine("Blank Lines         : $blankLines")
            appendLine("Classes / Types     : $classes")
            appendLine("Functions / Methods : $functions")
            appendLine("Cyclomatic Score    : $complexity (${if (complexity < 15) "Low / Clean" else if (complexity < 35) "Moderate" else "High"})")
            append("Buffer Size         : $bytes bytes (${currentText.length} chars)")
        }

        ModernUiHelper.showModernConfirmDialog(
            context = context,
            title = "Code Metrics • $fileName",
            message = report,
            confirmText = "Copy Report",
            cancelText = "Close",
            iconRes = R.drawable.ic_info,
            isDestructive = false
        ) {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.setPrimaryClip(ClipData.newPlainText("Code Metrics", report))
            Toast.makeText(context, "Copied code metrics report", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSnippetGeneratorMenu(
        context: Context,
        fileName: String,
        onInsertAtCursor: (String) -> Unit
    ) {
        val snippets = listOf(
            Triple("Kotlin Coroutine Scope & Launch", "Main/IO coroutine template", "CoroutineScope(Dispatchers.IO).launch {\n    val result = runCatching {\n        // TODO: background work\n    }\n    withContext(Dispatchers.Main) {\n        result.onSuccess { }\n    }\n}\n"),
            Triple("TypeScript / JS Async Fetch", "REST JSON fetch with error handling", "async function fetchJson(url) {\n  const response = await fetch(url, {\n    headers: { \"Accept\": \"application/json\" },\n  });\n  if (!response.ok) {\n    throw new Error(`HTTP error: \${response.status}`);\n  }\n  return await response.json();\n}\n"),
            Triple("Python Main Entry Guard", "Standard CLI script scaffold", "def main() -> None:\n    print(\"PocketDev Python Ready\")\n\n\nif __name__ == \"__main__\":\n    main()\n"),
            Triple("HTML5 Responsive Starter", "Complete HTML5 boilerplate", "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\" />\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n  <title>PocketDev App</title>\n</head>\n<body>\n  <main id=\"app\"></main>\n</body>\n</html>\n"),
            Triple("MIT Open-Source License Header", "Standard copyright comment header", "/*\n * Copyright (c) 2026 Developer\n * Licensed under the MIT License.\n */\n")
        )
        ModernUiHelper.showModernContextMenu(
            context = context,
            title = "Smart Snippet Generator",
            subtitle = "Insert boilerplate into $fileName",
            headerIconRes = R.drawable.ic_code,
            options = snippets.mapIndexed { idx, (title, sub, code) ->
                ModernUiHelper.MenuOption(
                    id = idx,
                    title = title,
                    subtitle = sub,
                    iconRes = R.drawable.ic_add,
                    action = { onInsertAtCursor(code) }
                )
            }
        )
    }

    private fun showDataSwissKnifeMenu(
        context: Context,
        fileName: String,
        currentText: String,
        onReplaceEditorText: (String) -> Unit
    ) {
        val options = listOf(
            ModernUiHelper.MenuOption(
                id = 0,
                title = "Pretty-Print JSON (2 Spaces)",
                subtitle = "Format and validate JSON structure",
                iconRes = R.drawable.ic_format_align,
                action = {
                    runCatching {
                        val t = currentText.trim()
                        val out = if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
                        onReplaceEditorText(out + "\n")
                    }.onFailure {
                        Toast.makeText(context, "Invalid JSON: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            ),
            ModernUiHelper.MenuOption(
                id = 1,
                title = "Minify JSON (Single Line)",
                subtitle = "Remove all unnecessary whitespace from JSON",
                iconRes = R.drawable.ic_code,
                action = {
                    runCatching {
                        val t = currentText.trim()
                        val out = if (t.startsWith("[")) JSONArray(t).toString() else JSONObject(t).toString()
                        onReplaceEditorText(out)
                    }.onFailure {
                        Toast.makeText(context, "Invalid JSON: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            ),
            ModernUiHelper.MenuOption(
                id = 2,
                title = "Sort Lines Alphabetically (A → Z)",
                subtitle = "Sort all lines in current buffer",
                iconRes = R.drawable.ic_format_align,
                action = {
                    val sorted = currentText.split('\n').sorted().joinToString("\n")
                    onReplaceEditorText(sorted)
                }
            ),
            ModernUiHelper.MenuOption(
                id = 3,
                title = "Base64 Encode Buffer",
                subtitle = "Encode UTF-8 text to Base64",
                iconRes = R.drawable.ic_code,
                action = {
                    val encoded = Base64.encodeToString(currentText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    onReplaceEditorText(encoded)
                }
            ),
            ModernUiHelper.MenuOption(
                id = 4,
                title = "Base64 Decode Buffer",
                subtitle = "Decode Base64 string back to UTF-8 text",
                iconRes = R.drawable.ic_code,
                action = {
                    runCatching {
                        val decoded = String(Base64.decode(currentText.trim(), Base64.DEFAULT), Charsets.UTF_8)
                        onReplaceEditorText(decoded)
                    }.onFailure {
                        Toast.makeText(context, "Base64 decode failed", Toast.LENGTH_SHORT).show()
                    }
                }
            ),
            ModernUiHelper.MenuOption(
                id = 5,
                title = "URL Encode / Decode",
                subtitle = "Toggle percent-encoding on buffer",
                iconRes = R.drawable.ic_code,
                action = {
                    runCatching {
                        val out = if (currentText.contains("%")) {
                            URLDecoder.decode(currentText, "UTF-8")
                        } else {
                            URLEncoder.encode(currentText, "UTF-8")
                        }
                        onReplaceEditorText(out)
                    }
                }
            )
        )

        ModernUiHelper.showModernContextMenu(
            context = context,
            title = "JSON & Data Swiss-Knife",
            subtitle = "Transform content in $fileName",
            headerIconRes = R.drawable.ic_code,
            options = options
        )
    }

    private fun showGitBlameDialog(
        context: Context,
        fileName: String,
        line1Based: Int,
        localFile: File?
    ) {
        val projectDir = localFile?.parentFile ?: ProjectManager.ensureDefaultLocalWorkspace(context)
        Thread {
            val blameOut = if (localFile != null && GitHubManager.isGitRepo(projectDir)) {
                val res = GitHubManager.runGitCommand(
                    context,
                    projectDir,
                    "log -n 5 --pretty=format:'%h • %an (%ar): %s' -- ${localFile.name}"
                )
                res.stdout.ifBlank { "No committed history for ${localFile.name} yet." }
            } else {
                "File is not inside an initialized Git repository."
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ModernUiHelper.showModernConfirmDialog(
                    context = context,
                    title = "Git Blame & History • $fileName (Ln $line1Based)",
                    message = blameOut,
                    confirmText = "OK",
                    cancelText = "Close",
                    iconRes = R.drawable.ic_git,
                    isDestructive = false
                ) {}
            }
        }.start()
    }

    private fun showMarkdownHtmlPreviewDialog(
        context: Context,
        fileName: String,
        currentText: String
    ) {
        val palette = ModernUiHelper.resolvePalette(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
        }

        val header = MaterialTextView(context).apply {
            text = "Live Preview • $fileName"
            textSize = 15.5f
            typeface = ModernUiHelper.appFontBold(context)
            setTextColor(palette.fgColor)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(header)

        val contentBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(palette.surfaceBg)
                setStroke(dp(1), palette.borderColor)
            }
        }

        for (rawLine in currentText.split('\n').take(120)) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val tv = MaterialTextView(context).apply {
                when {
                    trimmed.startsWith("# ") -> {
                        text = trimmed.removePrefix("# ")
                        textSize = 17f
                        typeface = ModernUiHelper.appFontBold(context)
                        setTextColor(palette.accentColor)
                        setPadding(0, dp(6), 0, dp(4))
                    }
                    trimmed.startsWith("## ") -> {
                        text = trimmed.removePrefix("## ")
                        textSize = 15f
                        typeface = ModernUiHelper.appFontBold(context)
                        setTextColor(palette.fgColor)
                        setPadding(0, dp(5), 0, dp(3))
                    }
                    trimmed.startsWith("### ") -> {
                        text = trimmed.removePrefix("### ")
                        textSize = 13.5f
                        typeface = ModernUiHelper.appFontSemiBold(context)
                        setTextColor(palette.fgColor)
                        setPadding(0, dp(4), 0, dp(2))
                    }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        text = "• " + trimmed.substring(2)
                        textSize = 12.5f
                        typeface = ModernUiHelper.appFontRegular(context)
                        setTextColor(palette.fgColor)
                        setPadding(dp(8), dp(2), 0, dp(2))
                    }
                    else -> {
                        text = trimmed
                        textSize = 12.5f
                        typeface = ModernUiHelper.appFontRegular(context)
                        setTextColor(palette.subColor)
                        setPadding(0, dp(2), 0, dp(2))
                    }
                }
            }
            contentBox.addView(tv)
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            )
            addView(contentBox)
        }
        root.addView(scroll)
        ModernUiHelper.showModernCustomBottomSheet(context, root, palette)
    }

    /**
     * Populates a LinearLayout container with interactive Plugin cards (for Settings -> Plugins page).
     */
    fun renderPluginsSettingsList(
        context: Context,
        container: LinearLayout,
        onChanged: () -> Unit
    ) {
        container.removeAllViews()
        val settings = Settings(context)
        val palette = ModernUiHelper.resolvePalette(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val plugins = allPlugins(context)
        val switchThumbStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.accentColor, palette.subtleColor)
        )
        val switchTrackStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                ModernUiHelper.withAlpha(palette.accentColor, 0x48),
                ModernUiHelper.withAlpha(palette.subtleColor, 0x33)
            )
        )

        for (plugin in plugins) {
            val enabled = settings.isPluginEnabled(plugin.id, plugin.defaultEnabled)
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(palette.cardBg)
                    setStroke(
                        dp(1),
                        if (enabled) ModernUiHelper.withAlpha(palette.accentColor, 0x77) else palette.borderColor
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }
            ModernUiHelper.attachPressAnimation(card, 0.985f)

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = MaterialTextView(context).apply {
                text = plugin.name
                textSize = 14f
                typeface = ModernUiHelper.appFontBold(context)
                setTextColor(palette.fgColor)
            }
            val metaTv = MaterialTextView(context).apply {
                text = "${plugin.category.label} • v${plugin.version} • by ${plugin.author}"
                textSize = 11f
                typeface = ModernUiHelper.appFontMedium(context)
                setTextColor(palette.accentColor)
            }
            titleCol.addView(titleTv)
            titleCol.addView(metaTv)
            topRow.addView(titleCol)

            val toggle = MaterialSwitch(context).apply {
                isChecked = enabled
                thumbTintList = switchThumbStates
                trackTintList = switchTrackStates
                setOnCheckedChangeListener { _, isChecked ->
                    setEnabled(context, plugin.id, isChecked)
                    renderPluginsSettingsList(context, container, onChanged)
                    onChanged()
                }
            }
            topRow.addView(toggle)
            card.addView(topRow)

            val descTv = MaterialTextView(context).apply {
                text = plugin.description
                textSize = 12f
                typeface = ModernUiHelper.appFontRegular(context)
                setTextColor(palette.subtleColor)
                setPadding(0, dp(6), 0, dp(4))
            }
            card.addView(descTv)

            if (plugin.isCustom) {
                val delBtn = MaterialButton(
                    context,
                    null,
                    com.google.android.material.R.attr.borderlessButtonStyle
                ).apply {
                    text = "Remove Custom Plugin"
                    textSize = 11.5f
                    isAllCaps = false
                    typeface = ModernUiHelper.appFontSemiBold(context)
                    setTextColor(palette.destructiveColor)
                    setOnClickListener {
                        deleteCustomPlugin(context, plugin.id)
                        renderPluginsSettingsList(context, container, onChanged)
                        onChanged()
                    }
                }
                card.addView(delBtn)
            }

            container.addView(card)
        }
    }

    fun showCreateCustomPluginDialog(context: Context, onCreated: () -> Unit) {
        ModernUiHelper.showModernInputDialog(
            context = context,
            title = "Create Custom Plugin",
            subtitle = "Enter plugin name (e.g. 'React Component Template' or 'Cargo Check')",
            initialValue = "",
            hint = "My Custom Plugin",
            positiveText = "Next"
        ) { pluginName ->
            if (pluginName.isBlank()) return@showModernInputDialog
            ModernUiHelper.showModernInputDialog(
                context = context,
                title = "Plugin Snippet or Shell Action",
                subtitle = "Prefix with '$' to run in terminal, or enter code snippet to insert",
                initialValue = "// Generated by $pluginName\n",
                hint = "$ npm test  OR  code snippet",
                positiveText = "Create Plugin"
            ) { payload ->
                if (payload.isNotBlank()) {
                    val isCmd = payload.trimStart().startsWith("$")
                    val cleanPayload = if (isCmd) payload.trimStart().removePrefix("$").trim() else payload
                    saveCustomPlugin(
                        context = context,
                        name = pluginName,
                        description = if (isCmd) "Runs `$cleanPayload` in project terminal" else "Inserts custom template into active editor",
                        snippetOrCommand = cleanPayload,
                        isShellCommand = isCmd
                    )
                    onCreated()
                    Toast.makeText(context, "Created plugin '$pluginName'", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
