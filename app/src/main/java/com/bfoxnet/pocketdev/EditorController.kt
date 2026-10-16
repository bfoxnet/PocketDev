package com.bfoxnet.pocketdev

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.monarch.MonarchColorScheme
import io.github.rosemoe.sora.langs.monarch.MonarchLanguage
import io.github.rosemoe.sora.langs.monarch.registry.MonarchGrammarRegistry
import io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry as MonarchThemeRegistry
import io.github.rosemoe.sora.langs.monarch.registry.model.ThemeModel as MonarchThemeModel
import io.github.rosemoe.sora.langs.monarch.registry.model.ThemeSource as MonarchThemeSource
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns every sora-editor knob the app touches.
 *
 * Optimization & Error-Handling Architecture:
 * 1. **Per-Theme Lazy Loading**: Instead of reading, sanitizing, and parsing all
 *    22 theme JSON files twice on startup, only the single active theme is loaded
 *    on demand for the active engine (TextMate or Monarch) and cached in memory.
 * 2. **Per-File Lazy Language Loading**: Instead of loading all 35 TextMate grammars
 *    and all 25 Monarch grammars upfront on startup, each opened file resolves its
 *    target scope and loads ONLY that single language's grammar manifest (`textmate/lazy/<name>.json`
 *    or single Monarch definition) on first use.
 * 3. **In-Editor Sora Auto-Completion & LSP Diagnostics**: Publishes context-aware
 *    LSP completions, snippets, keywords, and buffer symbols directly into Sora Editor's
 *    native floating `EditorAutoCompletion` popup window (`requireAutoComplete`), and
 *    renders live error/warning squiggles via `DiagnosticsContainer`.
 */
class EditorController(private val context: Context, val editor: CodeEditor) {

    private val settings = Settings(context)

    /** Rolling on-device diagnostics log, shown by DiagActivity / shareable. */
    private val diagLog = ArrayDeque<String>()

    /** Tracks the currently active scope key so tab switches to the same language skip grammar recreation. */
    private var activeScopeKey: String = ""
    private var activeFileName: String = "untitled.kt"
    private var isCurrentLangMonarch: Boolean = false

    init {
        synchronized(diagLock) { instance = this }
    }

    /** logcat + Crashlytics breadcrumb + non-fatal so issues surface remotely. */
    private fun report(tag: String, message: String, e: Throwable? = null) {
        val line = "[$tag] $message" + (e?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        if (e != null) {
            Log.e("PocketDevHL", line, e)
        } else {
            Log.i("PocketDevHL", line)
        }
        synchronized(diagLog) {
            diagLog.addLast(line)
            while (diagLog.size > DIAG_LOG_LIMIT) diagLog.removeFirst()
        }
        DiagLogger.log("Editor-$tag", message, context, e)
        try {
            val cr = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            cr.log("HL $line")
            if (e != null) cr.recordException(e)
        } catch (ignored: Throwable) {
            // Crashlytics not initialized (unit tests) — logcat is enough
        }
    }

    fun setup() {
        registerFileProviders(context.applicationContext)
        with(editor) {
            typefaceText = MONOSPACE
            setLineSpacing(2f, 1.1f)
            props.deleteEmptyLineFast = false
            props.overScrollEnabled = true
        }
        enableSoraAutoCompletionWindow(editor)
        removeGutterDivider(editor)
        report("setup", "start (lazy mode); theme=${settings.editorTheme} langs=${editor.javaClass.simpleName}")
        applyEditorTheme(settings.editorTheme)
        applyFontSettings()
        editor.setWordwrap(settings.wordWrap)
        report("setup", "done; language=${editor.editorLanguage?.javaClass?.simpleName}")
    }

    private fun enableSoraAutoCompletionWindow(target: CodeEditor) {
        runCatching {
            val autoCompCls = Class.forName("io.github.rosemoe.sora.widget.component.EditorAutoCompletion")
            val getComp = target.javaClass.getMethod("getComponent", Class::class.java)
            val comp = getComp.invoke(target, autoCompCls)
            comp?.javaClass?.getMethod("setEnabled", Boolean::class.javaPrimitiveType)?.invoke(comp, true)
        }
    }

    private fun removeGutterDivider(target: CodeEditor) {
        runCatching {
            target.javaClass.getMethod("setDividerWidth", Float::class.javaPrimitiveType)
                .invoke(target, 0f)
        }
        runCatching {
            target.javaClass.getMethod("setBlockLineEnabled", Boolean::class.javaPrimitiveType)
                .invoke(target, settings.codeFoldingEnabled)
        }
    }

    fun applyEditorTheme(name: String) {
        val theme = themeById(name)
        settings.editorTheme = theme.id
        removeGutterDivider(editor)
        val scheme: EditorColorScheme = try {
            val isMonarch = isCurrentLangMonarch || editor.editorLanguage is MonarchLanguage
            val tmOk = ensureTextMateThemeLoaded(context.applicationContext, theme.id)
            val moOk = if (isMonarch) {
                ensureMonarchThemeLoaded(context.applicationContext, theme.id)
            } else {
                true
            }
            report("applyTheme", "name=${theme.id} tmSet=$tmOk moSet=$moOk langIsMonarch=$isMonarch")
            createConfiguredScheme(theme.id, isMonarch, context.applicationContext)
        } catch (e: Exception) {
            report("applyTheme", "scheme create failed -> SchemeDarcula fallback", e)
            fallbackScheme(theme.id)
        }
        editor.colorScheme = scheme
        editor.setBackgroundColor(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
        editor.invalidate()
    }

    private fun fallbackScheme(name: String): EditorColorScheme {
        val s = SchemeDarcula()
        applyThemeUiColors(s, themeById(name))
        return s
    }

    fun currentSurfaceColor(): Int =
        runCatching { editor.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND) }
            .getOrDefault(Color.parseColor(themeById(settings.editorTheme).ui.bg))

    fun currentChromeColor(): Int =
        Color.parseColor(themeById(settings.editorTheme).ui.chromeBg)

    fun currentSymbolBarColor(): Int =
        Color.parseColor(themeById(settings.editorTheme).ui.symbolBg)

    fun currentForegroundColor(): Int =
        Color.parseColor(themeById(settings.editorTheme).ui.fg)

    fun currentSubtleColor(): Int =
        Color.parseColor(themeById(settings.editorTheme).ui.lineNumActive)

    fun isCurrentThemeDark(): Boolean =
        themeById(settings.editorTheme).dark

    /**
     * Assigns a language for [fileName] on demand and wraps it with in-editor Sora
     * `EditorAutoCompletion` support so completions appear directly inside the editor popup.
     */
    fun setLanguageFor(fileName: String, forceReload: Boolean = false) {
        activeFileName = fileName
        val tmSpec = resolveTextMateSpec(fileName)
        val moScope = resolveMonarchScope(fileName)
        val targetScopeKey = tmSpec?.let { "tm:${it.first}" }
            ?: moScope?.let { "mo:$it" }
            ?: "plain"

        if (!forceReload && targetScopeKey == activeScopeKey && editor.editorLanguage != null) {
            applyEditorTheme(settings.editorTheme)
            return
        }

        val baseLanguage = languageFor(fileName)
        isCurrentLangMonarch = baseLanguage is MonarchLanguage
        val wrappedLanguage = wrapLanguageWithSoraCompletion(baseLanguage)
        activeScopeKey = targetScopeKey
        report("setLang", "$fileName -> ${baseLanguage.javaClass.simpleName} ($targetScopeKey)")
        editor.setEditorLanguage(wrappedLanguage)
        enableSoraAutoCompletionWindow(editor)
        applyEditorTheme(settings.editorTheme)
    }

    private fun languageFor(fileName: String): Language {
        val appCtx = context.applicationContext
        registerFileProviders(appCtx)

        val tmSpec = resolveTextMateSpec(fileName)
        if (tmSpec != null) {
            val (tmScope, lazyName) = tmSpec
            try {
                ensureTextMateThemeLoaded(appCtx, settings.editorTheme)
                ensureTextMateScopeLoaded(tmScope, lazyName)
                return TextMateLanguage.create(tmScope, true).also { tmLang ->
                    runCatching {
                        val kw = LspManager.keywordsForFile(fileName)
                        if (kw.isNotEmpty()) {
                            tmLang.setCompleterKeywords(kw)
                        }
                    }
                    report("langCreate", "TextMate $tmScope ok (lazy=$lazyName)")
                }
            } catch (e: Exception) {
                report("langCreate", "TextMate $tmScope FAILED, trying Monarch fallback", e)
            }
        }

        val monarchScope = resolveMonarchScope(fileName)
        if (monarchScope != null) {
            return try {
                ensureMonarchThemeLoaded(appCtx, settings.editorTheme)
                ensureMonarchScopeLoaded(monarchScope)
                MonarchLanguage.create(monarchScope, true).also {
                    report("langCreate", "Monarch $monarchScope ok")
                }
            } catch (e: Exception) {
                report("langCreate", "Monarch $monarchScope FAILED", e)
                EmptyLanguage()
            }
        }

        return EmptyLanguage().also {
            report("langCreate", "no scope for $fileName -> EmptyLanguage")
        }
    }

    /**
     * Wraps any [Language] (`TextMateLanguage`, `MonarchLanguage`, or `EmptyLanguage`) with
     * a dynamic proxy that intercepts `requireAutoComplete` and feeds LSP & language
     * completion items directly into Sora Editor's native floating completion window.
     */
    private fun wrapLanguageWithSoraCompletion(base: Language): Language {
        return runCatching {
            Proxy.newProxyInstance(
                Language::class.java.classLoader,
                arrayOf(Language::class.java)
            ) { _, method, args ->
                val actualArgs = args ?: emptyArray()
                val result = method.invoke(base, *actualArgs)
                if (method.name == "requireAutoComplete" && actualArgs.size >= 3) {
                    publishSoraEditorCompletions(
                        contentRef = actualArgs[0],
                        charPosition = actualArgs[1],
                        publisher = actualArgs[2]
                    )
                }
                result
            } as Language
        }.getOrDefault(base)
    }

    private fun publishSoraEditorCompletions(
        contentRef: Any?,
        charPosition: Any?,
        publisher: Any?
    ) {
        if (charPosition == null || publisher == null) return
        runCatching {
            val line = runCatching {
                charPosition.javaClass.getField("line").getInt(charPosition)
            }.getOrElse {
                charPosition.javaClass.getMethod("getLine").invoke(charPosition) as Int
            }
            val column = runCatching {
                charPosition.javaClass.getField("column").getInt(charPosition)
            }.getOrElse {
                charPosition.javaClass.getMethod("getColumn").invoke(charPosition) as Int
            }

            val fullText = runCatching { editor.text.toString() }
                .getOrElse { contentRef?.toString() ?: "" }

            val items = LspManager.computeEditorCompletions(
                context = context,
                fileName = activeFileName,
                text = fullText,
                cursorLine = line,
                cursorCol = column
            )
            if (items.isEmpty()) return

            val itemCls = Class.forName("io.github.rosemoe.sora.lang.completion.CompletionItem")
            val simpleCls = Class.forName("io.github.rosemoe.sora.lang.completion.SimpleCompletionItem")
            val ctor = simpleCls.getConstructor(
                CharSequence::class.java,
                CharSequence::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val addItemMethod = publisher.javaClass.getMethod("addItem", itemCls)

            val kindCls = runCatching {
                Class.forName("io.github.rosemoe.sora.lang.completion.CompletionItemKind")
            }.getOrNull()
            val kindMethod = if (kindCls != null) {
                runCatching { itemCls.getMethod("kind", kindCls) }.getOrNull()
            } else {
                null
            }

            for (lspItem in items) {
                val soraItem = ctor.newInstance(
                    lspItem.label,
                    "${lspItem.kind.badge} • ${lspItem.detail}",
                    lspItem.prefixLength,
                    lspItem.insertText
                )
                if (kindCls != null && kindMethod != null) {
                    runCatching {
                        val enumName = when (lspItem.kind) {
                            LspManager.CompletionKind.FUNCTION -> "Function"
                            LspManager.CompletionKind.METHOD -> "Method"
                            LspManager.CompletionKind.CLASS -> "Class"
                            LspManager.CompletionKind.INTERFACE -> "Interface"
                            LspManager.CompletionKind.VARIABLE -> "Variable"
                            LspManager.CompletionKind.PROPERTY -> "Property"
                            LspManager.CompletionKind.KEYWORD -> "Keyword"
                            LspManager.CompletionKind.SNIPPET -> "Snippet"
                            LspManager.CompletionKind.MODULE -> "Module"
                        }
                        val kindConst = java.lang.Enum.valueOf(
                            kindCls.asSubclass(Enum::class.java),
                            enumName
                        )
                        kindMethod.invoke(soraItem, kindConst)
                    }
                }
                addItemMethod.invoke(publisher, soraItem)
            }
        }.onFailure { e ->
            report("soraComplete", "publishSoraEditorCompletions error", e)
        }
    }

    /**
     * Pushes live Language Server Protocol [diagnostics] into [CodeEditor]'s
     * `DiagnosticsContainer` so red error squiggles, yellow warning squiggles,
     * and info squiggles render directly underneath the offending code ranges.
     */
    fun applyLspDiagnosticsToEditor(diagnostics: List<LspManager.LspDiagnostic>) {
        runCatching {
            val containerCls = Class.forName("io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer")
            val regionCls = Class.forName("io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion")
            val container = containerCls.getDeclaredConstructor().newInstance()
            val addMethod = containerCls.getMethod("addDiagnostic", regionCls)

            val ctor3 = runCatching {
                regionCls.getConstructor(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Short::class.javaPrimitiveType
                )
            }.getOrNull()
            val ctor4 = runCatching {
                regionCls.getConstructor(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Short::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType
                )
            }.getOrNull()

            val textLen = editor.text.length
            diagnostics.forEachIndexed { idx, d ->
                val start = d.startOffset.coerceIn(0, textLen)
                val end = d.endOffset.coerceIn((start + 1).coerceAtMost(textLen), textLen)
                if (end > start) {
                    val soraSeverity: Short = when (d.severity) {
                        LspManager.Severity.ERROR -> 3.toShort()
                        LspManager.Severity.WARNING -> 2.toShort()
                        else -> 1.toShort()
                    }
                    val region = when {
                        ctor3 != null -> ctor3.newInstance(start, end, soraSeverity)
                        ctor4 != null -> ctor4.newInstance(start, end, soraSeverity, idx.toLong())
                        else -> null
                    }
                    if (region != null) {
                        addMethod.invoke(container, region)
                    }
                }
            }

            val setDiagMethod = editor.javaClass.getMethod("setDiagnostics", containerCls)
            setDiagMethod.invoke(editor, container)
            editor.invalidate()
        }.onFailure { e ->
            report("lspDiag", "applyLspDiagnosticsToEditor reflection fallback", e)
        }
    }

    /** Clears all diagnostic squiggles from the editor when no LSP is active. */
    fun clearLspDiagnostics() {
        runCatching {
            val containerCls = Class.forName("io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer")
            val setDiagMethod = editor.javaClass.getMethod("setDiagnostics", containerCls)
            setDiagMethod.invoke(editor, null)
            editor.invalidate()
        }
    }

    /** Applies an LSP quick-fix replacement on a specific diagnostic range. */
    fun applyLspQuickFix(diagnostic: LspManager.LspDiagnostic) {
        val replacement = diagnostic.quickFixReplacement ?: return
        runCatching {
            val text = editor.text
            val line = diagnostic.line.coerceIn(0, (text.lineCount - 1).coerceAtLeast(0))
            val lineLen = text.getLineString(line).length
            text.beginBatchEdit()
            try {
                if (diagnostic.code == "reportGeneralTypeIssues" ||
                    diagnostic.code == "yaml-no-tabs" ||
                    diagnostic.code == "syntax-unclosed-string"
                ) {
                    text.replace(line, 0, line, lineLen, replacement)
                } else {
                    val sc = diagnostic.startCol.coerceIn(0, lineLen)
                    val ec = diagnostic.endCol.coerceIn(sc, lineLen)
                    text.replace(line, sc, line, ec, replacement)
                }
            } finally {
                text.endBatchEdit()
            }
        }.onFailure { report("lspQuickFix", "applyLspQuickFix failed", it) }
    }

    fun applyFontSettings() {
        val font = Settings.fontById(settings.fontFamily)
        val typeface = runCatching { ResourcesCompat.getFont(context, font.resId) }.getOrNull()
            ?: Typeface.MONOSPACE
        editor.typefaceText = typeface
        editor.typefaceLineNumber = typeface
        editor.setTextSize(settings.fontSizeSp.toFloat())
        applyFontLigatures(settings.fontLigatures)
    }

    fun applyFontLigatures(enabled: Boolean) {
        settings.fontLigatures = enabled
        val featureSettings = if (enabled) "\"liga\" 1, \"calt\" 1" else "\"liga\" 0, \"calt\" 0"
        runCatching {
            for (methodName in listOf("getTextPaint", "getPaint", "getLineNumberPaint")) {
                runCatching {
                    val m = editor.javaClass.getMethod(methodName)
                    (m.invoke(editor) as? Paint)?.fontFeatureSettings = featureSettings
                }
            }
            applyPaintFeaturesReflectively(editor, featureSettings)
            val rendererField = runCatching {
                editor.javaClass.getDeclaredField("renderer").apply { isAccessible = true }.get(editor)
            }.getOrNull()
            if (rendererField != null) {
                applyPaintFeaturesReflectively(rendererField, featureSettings)
            }
        }
        editor.invalidate()
    }

    private fun applyPaintFeaturesReflectively(owner: Any, featureSettings: String) {
        var cls: Class<*>? = owner.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (Paint::class.java.isAssignableFrom(field.type)) {
                    runCatching {
                        field.isAccessible = true
                        (field.get(owner) as? Paint)?.fontFeatureSettings = featureSettings
                    }
                }
            }
            cls = cls.superclass
        }
    }

    fun applyWordWrap(enabled: Boolean) {
        settings.wordWrap = enabled
        editor.setWordwrap(enabled)
    }

    // ----------------------------------------------------------------- Quick Editor Actions

    fun moveLineUp() {
        runCatching {
            val text = editor.text
            val cursor = editor.cursor
            val line = cursor.leftLine
            if (line <= 0) return
            val col = cursor.leftColumn
            val currentLineText = text.getLineString(line)
            val prevLineText = text.getLineString(line - 1)
            text.beginBatchEdit()
            try {
                text.replace(line - 1, 0, line, currentLineText.length, "$currentLineText\n$prevLineText")
                editor.setSelection(line - 1, col.coerceAtMost(currentLineText.length))
            } finally {
                text.endBatchEdit()
            }
        }.onFailure { report("quickAction", "moveLineUp failed", it) }
    }

    fun moveLineDown() {
        runCatching {
            val text = editor.text
            val cursor = editor.cursor
            val line = cursor.leftLine
            if (line >= text.lineCount - 1) return
            val col = cursor.leftColumn
            val currentLineText = text.getLineString(line)
            val nextLineText = text.getLineString(line + 1)
            text.beginBatchEdit()
            try {
                text.replace(line, 0, line + 1, nextLineText.length, "$nextLineText\n$currentLineText")
                editor.setSelection(line + 1, col.coerceAtMost(currentLineText.length))
            } finally {
                text.endBatchEdit()
            }
        }.onFailure { report("quickAction", "moveLineDown failed", it) }
    }

    fun copyLineUp() {
        runCatching {
            val text = editor.text
            val cursor = editor.cursor
            val line = cursor.leftLine
            val col = cursor.leftColumn
            val currentLineText = text.getLineString(line)
            text.insert(line, 0, "$currentLineText\n")
            editor.setSelection(line, col.coerceAtMost(currentLineText.length))
        }.onFailure { report("quickAction", "copyLineUp failed", it) }
    }

    fun copyLineDown() {
        runCatching {
            val text = editor.text
            val cursor = editor.cursor
            val line = cursor.leftLine
            val col = cursor.leftColumn
            val currentLineText = text.getLineString(line)
            text.insert(line, currentLineText.length, "\n$currentLineText")
            editor.setSelection(line + 1, col.coerceAtMost(currentLineText.length))
        }.onFailure { report("quickAction", "copyLineDown failed", it) }
    }

    fun indentLineOrSelection() {
        runCatching {
            val text = editor.text
            val cursor = editor.cursor
            val startLine = cursor.leftLine
            val endLine = cursor.rightLine
            val col = cursor.leftColumn
            text.beginBatchEdit()
            try {
                for (l in startLine..endLine) {
                    text.insert(l, 0, "    ")
                }
                if (startLine == endLine) {
                    editor.setSelection(startLine, col + 4)
                }
            } finally {
                text.endBatchEdit()
            }
        }.onFailure { report("quickAction", "indent failed", it) }
    }

    fun outdentLineOrSelection() {
        runCatching {
            val text = editor.text
            val cursor = editor.cursor
            val startLine = cursor.leftLine
            val endLine = cursor.rightLine
            val col = cursor.leftColumn
            var firstLineRemoved = 0
            text.beginBatchEdit()
            try {
                for (l in startLine..endLine) {
                    val lineStr = text.getLineString(l)
                    val removeCount = when {
                        lineStr.startsWith("\t") -> 1
                        lineStr.startsWith("    ") -> 4
                        lineStr.startsWith("   ") -> 3
                        lineStr.startsWith("  ") -> 2
                        lineStr.startsWith(" ") -> 1
                        else -> 0
                    }
                    if (removeCount > 0) {
                        text.delete(l, 0, l, removeCount)
                        if (l == startLine) firstLineRemoved = removeCount
                    }
                }
                if (startLine == endLine && firstLineRemoved > 0) {
                    editor.setSelection(startLine, (col - firstLineRemoved).coerceAtLeast(0))
                }
            } finally {
                text.endBatchEdit()
            }
        }.onFailure { report("quickAction", "outdent failed", it) }
    }

    fun deleteCurrentLine() {
        runCatching {
            val text = editor.text
            val cursor = editor.cursor
            val line = cursor.leftLine
            val lineLen = text.getLineString(line).length
            text.beginBatchEdit()
            try {
                if (text.lineCount == 1) {
                    text.delete(0, 0, 0, lineLen)
                } else if (line < text.lineCount - 1) {
                    text.delete(line, 0, line + 1, 0)
                } else {
                    val prevLen = text.getLineString(line - 1).length
                    text.delete(line - 1, prevLen, line, lineLen)
                }
            } finally {
                text.endBatchEdit()
            }
        }.onFailure { report("quickAction", "deleteLine failed", it) }
    }

    fun toggleLineComment(fileName: String) {
        runCatching {
            val prefix = commentPrefixFor(fileName)
            val text = editor.text
            val cursor = editor.cursor
            val startLine = cursor.leftLine
            val endLine = cursor.rightLine
            text.beginBatchEdit()
            try {
                for (l in startLine..endLine) {
                    val lineStr = text.getLineString(l)
                    val trimmed = lineStr.trimStart()
                    val indentLen = lineStr.length - trimmed.length
                    if (trimmed.startsWith("$prefix ")) {
                        text.delete(l, indentLen, l, indentLen + prefix.length + 1)
                    } else if (trimmed.startsWith(prefix)) {
                        text.delete(l, indentLen, l, indentLen + prefix.length)
                    } else {
                        text.insert(l, indentLen, "$prefix ")
                    }
                }
            } finally {
                text.endBatchEdit()
            }
        }.onFailure { report("quickAction", "toggleComment failed", it) }
    }

    private fun commentPrefixFor(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".py") || lower.endsWith(".sh") || lower.endsWith(".bash") ||
                lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".toml") ||
                lower.endsWith(".ini") || lower.endsWith(".properties") || lower.endsWith(".rb") ||
                lower.endsWith(".pl") || lower.endsWith(".r") || lower.endsWith("dockerfile") -> "#"
            lower.endsWith(".sql") || lower.endsWith(".lua") || lower.endsWith(".hs") -> "--"
            lower.endsWith(".bat") || lower.endsWith(".cmd") -> "REM"
            lower.endsWith(".erl") || lower.endsWith(".m") || lower.endsWith(".tex") -> "%"
            else -> "//"
        }
    }


    // ---------------------------------------------------------------- Word-Match Highlighting, Code Folding & Sticky-Scroll

    data class StickyHeaderInfo(
        val line1Based: Int,
        val signature: String
    )

    data class FoldRange(
        val startLine0: Int,
        val endLine0: Int,
        val headerLine: String
    )

    private var isAutoWordHighlightActive: Boolean = false
    private var lastAutoHighlightedWord: String = ""
    private val foldedBlocksById = ConcurrentHashMap<String, String>()
    private var foldIdCounter: Int = 1

    /**
     * Highlights all occurrences of the identifier word currently under the cursor when
     * [findBarVisible] is false and `settings.wordMatchHighlightEnabled` is enabled.
     */
    fun updateWordMatchHighlight(findBarVisible: Boolean) {
        if (findBarVisible || !settings.wordMatchHighlightEnabled) {
            clearAutoWordHighlight()
            return
        }
        runCatching {
            val cursor = editor.cursor
            if (cursor.leftLine != cursor.rightLine) {
                clearAutoWordHighlight()
                return
            }
            val line0 = cursor.leftLine
            val col0 = cursor.leftColumn
            val lineStr = editor.text.getLineString(line0)
            if (lineStr.isEmpty()) {
                clearAutoWordHighlight()
                return
            }
            val isIdent = { ch: Char -> ch.isLetterOrDigit() || ch == '_' }
            val idx = col0.coerceIn(0, (lineStr.length - 1).coerceAtLeast(0))
            val pivot = when {
                isIdent(lineStr[idx]) -> idx
                idx > 0 && isIdent(lineStr[idx - 1]) -> idx - 1
                else -> -1
            }
            if (pivot < 0) {
                clearAutoWordHighlight()
                return
            }
            var s = pivot
            var e = pivot
            while (s > 0 && isIdent(lineStr[s - 1])) s--
            while (e + 1 < lineStr.length && isIdent(lineStr[e + 1])) e++
            val word = lineStr.substring(s, e + 1)
            if (word.length < 2 || word[0].isDigit()) {
                clearAutoWordHighlight()
                return
            }
            if (word == lastAutoHighlightedWord && isAutoWordHighlightActive) {
                return
            }
            val fullText = editor.text.toString()
            val regex = Regex("\\b${Regex.escape(word)}\\b")
            if (regex.findAll(fullText).take(2).count() < 2) {
                clearAutoWordHighlight()
                return
            }
            lastAutoHighlightedWord = word
            isAutoWordHighlightActive = true
            editor.searcher.search("\\b${Regex.escape(word)}\\b", EditorSearcher.SearchOptions(true, true))
        }
    }

    fun clearAutoWordHighlight() {
        if (isAutoWordHighlightActive) {
            isAutoWordHighlightActive = false
            lastAutoHighlightedWord = ""
            runCatching { editor.searcher.stopSearch() }
        }
    }

    /**
     * Resolves the enclosing top-level and nested block header for [firstVisibleLine0]
     * (like VS Code's Sticky Scroll headers).
     */
    fun resolveStickyHeaderForLine(firstVisibleLine0: Int): StickyHeaderInfo? {
        if (!settings.stickyScrollEnabled || firstVisibleLine0 <= 0) return null
        return runCatching {
            val textObj = editor.text
            val totalLines = textObj.lineCount
            if (firstVisibleLine0 >= totalLines) return null

            val ranges = detectFoldableRanges(textObj.toString())
            val enclosing = ranges.filter { r ->
                r.startLine0 < firstVisibleLine0 && r.endLine0 > firstVisibleLine0
            }.sortedBy { it.startLine0 }

            if (enclosing.isEmpty()) return null
            val targetLine1 = enclosing.last().startLine0 + 1
            val breadcrumb = enclosing.takeLast(2).joinToString("  ›  ") { r ->
                r.headerLine.trim().removeSuffix("{").trim()
            }
            StickyHeaderInfo(line1Based = targetLine1, signature = breadcrumb)
        }.getOrNull()
    }

    /**
     * Detects foldable multi-line `{ ... }` blocks and Python/YAML indentation blocks.
     */
    fun detectFoldableRanges(fullText: String): List<FoldRange> {
        val lines = fullText.split('\n')
        val ranges = ArrayList<FoldRange>()
        val braceStack = ArrayDeque<Int>()
        val blockStartRegex = Regex("^\\s*(?:(?:public|private|protected|internal|open|abstract|override|final|static|suspend|inline|data|sealed|async|export|default)\\s+)*(?:class|interface|object|struct|enum|trait|impl|fun|def|function|func|fn|if|else|when|switch|for|while|try|catch)\\b.*")

        for (i in lines.indices) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("#")) continue
            for (ch in trimmed) {
                if (ch == '{') {
                    braceStack.addLast(i)
                } else if (ch == '}' && braceStack.isNotEmpty()) {
                    val startLine = braceStack.removeLast()
                    if (i - startLine >= 2) {
                        val hdr = lines[startLine].trim()
                        if (hdr.isNotEmpty()) {
                            ranges.add(FoldRange(startLine, i, hdr))
                        }
                    }
                }
            }
        }

        // Also support Python/YAML colon-indented blocks if no braces were matched
        if (ranges.isEmpty()) {
            for (i in lines.indices) {
                val raw = lines[i]
                val trimmed = raw.trimEnd()
                if (trimmed.endsWith(":") && blockStartRegex.matches(trimmed)) {
                    val baseIndent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    var endLine = i
                    for (j in (i + 1) until lines.size) {
                        val nextRaw = lines[j]
                        if (nextRaw.isBlank()) continue
                        val nextIndent = nextRaw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        if (nextIndent > baseIndent) {
                            endLine = j
                        } else {
                            break
                        }
                    }
                    if (endLine - i >= 2) {
                        ranges.add(FoldRange(i, endLine, trimmed))
                    }
                }
            }
        }

        return ranges.sortedBy { it.startLine0 }
    }

    /**
     * Toggles code folding for the block enclosing the current cursor line (or unfolds if the
     * cursor line already contains a folded block marker).
     */
    fun toggleFoldBlockAtCursor(): String {
        val textObj = editor.text
        val curLine = editor.cursor.leftLine
        val curLineStr = textObj.getLineString(curLine)

        // Check if current line has a folded marker `/* ⋯ folded #F1 (12 lines) ⋯ */`
        val markerRegex = Regex("/\\* ⋯ folded #(F\\d+) \\(\\d+ lines\\) ⋯ \\*/")
        val match = markerRegex.find(curLineStr)
        if (match != null) {
            val foldId = match.groupValues[1]
            val originalBlock = foldedBlocksById.remove(foldId)
            if (originalBlock != null) {
                textObj.beginBatchEdit()
                try {
                    textObj.replace(curLine, 0, curLine, curLineStr.length, originalBlock)
                } finally {
                    textObj.endBatchEdit()
                }
                return "Unfolded block #$foldId"
            }
        }

        val fullText = textObj.toString()
        val ranges = detectFoldableRanges(fullText)
        val target = ranges
            .filter { curLine in it.startLine0..it.endLine0 }
            .minByOrNull { it.endLine0 - it.startLine0 }
            ?: return "No foldable code block at Ln ${curLine + 1}"

        val lines = fullText.split('\n')
        val originalSlice = lines.subList(target.startLine0, target.endLine0 + 1).joinToString("\n")
        val foldId = "F${foldIdCounter++}"
        foldedBlocksById[foldId] = originalSlice

        val firstLine = lines[target.startLine0]
        val lineCount = target.endLine0 - target.startLine0
        val foldedReplacement = "$firstLine /* ⋯ folded #$foldId ($lineCount lines) ⋯ */ }"
        val endCol = lines[target.endLine0].length

        textObj.beginBatchEdit()
        try {
            textObj.replace(target.startLine0, 0, target.endLine0, endCol, foldedReplacement)
            editor.setSelection(target.startLine0, firstLine.length.coerceAtLeast(0))
        } finally {
            textObj.endBatchEdit()
        }
        return "Folded $lineCount lines (#$foldId)"
    }

    /** Unfolds all currently folded blocks in the editor buffer (e.g. before saving or running). */
    fun unfoldAllBlocks(): Int {
        if (foldedBlocksById.isEmpty()) return 0
        var current = editor.text.toString()
        var count = 0
        val markerRegex = Regex("^.*(/\\* ⋯ folded #(F\\d+) \\(\\d+ lines\\) ⋯ \\*/.*)$", RegexOption.MULTILINE)
        var changed = true
        while (changed) {
            changed = false
            val m = markerRegex.find(current)
            if (m != null) {
                val id = m.groupValues[2]
                val orig = foldedBlocksById.remove(id)
                if (orig != null) {
                    current = current.replaceRange(m.range, orig)
                    count++
                    changed = true
                }
            }
        }
        if (count > 0) {
            editor.setText(current)
        }
        return count
    }

    companion object {
        private val MONOSPACE = Typeface.MONOSPACE
        private const val DIAG_LOG_LIMIT = 400

        @Volatile
        private var instance: EditorController? = null

        @Volatile
        private var providersRegistered: Boolean = false

        private val diagLock = Any()

        private val sanitizedThemeCache = ConcurrentHashMap<String, String>()
        private val loadedTmThemes = ConcurrentHashMap.newKeySet<String>()
        private val loadedMoThemes = ConcurrentHashMap.newKeySet<String>()
        private val loadedTmScopes = ConcurrentHashMap.newKeySet<String>()
        private val loadedMoScopes = ConcurrentHashMap.newKeySet<String>()

        @JvmStatic
        fun recentDiagnostics(): List<String> = synchronized(diagLock) {
            instance?.let { ArrayList(it.diagLog) } ?: emptyList()
        }

        fun registerFileProviders(appContext: Context) {
            if (providersRegistered) return
            synchronized(diagLock) {
                if (providersRegistered) return
                FileProviderRegistry.getInstance().addFileProvider(
                    AssetsFileResolver(appContext.assets)
                )
                io.github.rosemoe.sora.langs.monarch.registry.FileProviderRegistry.addProvider(
                    io.github.rosemoe.sora.langs.monarch.registry.provider.AssetsFileResolver(
                        appContext.assets
                    )
                )
                providersRegistered = true
            }
        }

        private fun getSanitizedThemeJson(appContext: Context, theme: Theme): String {
            return sanitizedThemeCache.getOrPut(theme.id) {
                val raw = appContext.assets.open(theme.assetPath).bufferedReader().use { it.readText() }
                sanitizeColors(raw, theme.ui.bg)
            }
        }

        fun ensureTextMateThemeLoaded(appContext: Context, themeId: String): Boolean {
            registerFileProviders(appContext)
            val theme = themeById(themeId)
            val tmRegistry = ThemeRegistry.getInstance()
            if (loadedTmThemes.add(theme.id)) {
                try {
                    val sanitized = getSanitizedThemeJson(appContext, theme)
                    tmRegistry.loadTheme(
                        ThemeModel(
                            IThemeSource.fromInputStream(
                                sanitized.byteInputStream(), theme.assetPath, null
                            ),
                            theme.id
                        ).apply { setDark(theme.dark) },
                        false
                    )
                } catch (e: Exception) {
                    loadedTmThemes.remove(theme.id)
                    throw e
                }
            }
            return tmRegistry.setTheme(theme.id)
        }

        fun ensureMonarchThemeLoaded(appContext: Context, themeId: String): Boolean {
            registerFileProviders(appContext)
            val theme = themeById(themeId)
            if (loadedMoThemes.add(theme.id)) {
                try {
                    val sanitized = getSanitizedThemeJson(appContext, theme)
                    MonarchThemeRegistry.loadTheme(
                        MonarchThemeModel(
                            MonarchThemeSource(theme.assetPath, theme.id, sanitized)
                        ),
                        false
                    )
                } catch (e: Exception) {
                    loadedMoThemes.remove(theme.id)
                    throw e
                }
            }
            return MonarchThemeRegistry.setTheme(theme.id)
        }

        fun ensureTextMateScopeLoaded(scopeName: String, lazyName: String) {
            val gr = GrammarRegistry.getInstance()
            if (loadedTmScopes.contains(scopeName) && gr.findGrammar(scopeName) != null) {
                return
            }
            try {
                gr.loadGrammars("textmate/lazy/$lazyName.json")
                loadedTmScopes.add(scopeName)
            } catch (lazyErr: Exception) {
                gr.loadGrammars("textmate/languages.json")
                loadedTmScopes.add(scopeName)
            }
        }

        fun ensureMonarchScopeLoaded(scopeName: String) {
            if (loadedMoScopes.contains(scopeName)) return
            val entry = MONARCH_DEFS.firstOrNull { it.second.second == scopeName } ?: return
            val (id, def) = entry
            val (lang, scope, config) = def
            sanitizeMonarchLanguage(lang)
            val dsl = io.github.rosemoe.sora.langs.monarch.registry.dsl.monarchLanguages {
                language(id) {
                    monarchLanguage = lang
                    this.scopeName = scope
                    if (config != null) languageConfiguration = config
                }
            }
            MonarchGrammarRegistry.INSTANCE.loadGrammars(dsl)
            loadedMoScopes.add(scopeName)
        }

        fun resolveTextMateSpec(fileName: String): Pair<String, String>? {
            val lower = fileName.lowercase()
            val name = lower.substringAfterLast('/').substringAfterLast('\\')
            return when {
                name.endsWith(".kt") || name.endsWith(".kts") -> "source.kotlin" to "kotlin"
                name.endsWith(".java") -> "source.java" to "java"
                name.endsWith(".py") || name.endsWith(".pyw") -> "source.python" to "python"
                name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs") ||
                    name.endsWith(".jsx") -> "source.js" to "javascript"
                name.endsWith(".ts") || name.endsWith(".tsx") -> "source.ts" to "typescript"
                name.endsWith(".go") -> "source.go" to "go"
                name.endsWith(".rs") -> "source.rust" to "rust"
                name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".h") ||
                    name.endsWith(".hpp") || name.endsWith(".c") || name.endsWith(".cxx") -> "source.cpp" to "cpp"
                name.endsWith(".cs") -> "source.cs" to "csharp"
                name.endsWith(".css") -> "source.css" to "css"
                name.endsWith(".scss") || name.endsWith(".sass") -> "source.scss" to "scss"
                name.endsWith(".php") -> "source.php" to "php"
                name.endsWith(".rb") -> "source.ruby" to "ruby"
                name.endsWith(".sh") || name.endsWith(".bash") || name.endsWith(".zsh") ||
                    name == "gradlew" -> "source.shell" to "shell"
                name.endsWith(".yml") || name.endsWith(".yaml") -> "source.yaml" to "yaml"
                name.endsWith(".sql") -> "source.sql" to "sql"
                name.endsWith(".dart") -> "source.dart" to "dart"
                name.endsWith(".swift") -> "source.swift" to "swift"
                name.endsWith(".lua") -> "source.lua" to "lua"
                name.endsWith(".scala") || name.endsWith(".sc") -> "source.scala" to "scala"
                name.endsWith(".xml") || name.endsWith(".svg") -> "text.xml" to "xml"
                name.endsWith(".html") || name.endsWith(".htm") -> "text.html.basic" to "html"
                name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".mdx") -> "text.html.markdown" to "markdown"
                name.endsWith(".json") || name.endsWith(".jsonc") -> "source.json" to "json"
                name.endsWith(".ini") || name.endsWith(".cfg") || name.endsWith(".conf") ||
                    name.endsWith(".properties") || name.endsWith(".pro") -> "source.ini" to "ini"
                name.endsWith(".toml") -> "source.toml" to "toml"
                name == "dockerfile" || name.endsWith(".dockerfile") -> "source.dockerfile" to "dockerfile"
                name.endsWith(".gradle") || name.endsWith(".groovy") || name.endsWith(".jenkinsfile") -> "source.groovy" to "groovy"
                name.endsWith(".vue") -> "source.vue" to "vue"
                name.endsWith(".svelte") -> "source.svelte" to "svelte"
                name.endsWith(".hs") || name.endsWith(".lhs") -> "source.haskell" to "haskell"
                name.endsWith(".smali") -> "source.smali" to "smali"
                name.endsWith(".erl") || name.endsWith(".hrl") -> "source.erlang" to "erlang"
                name.endsWith(".m") || name.endsWith(".mm") -> "source.matlab" to "matlab"
                else -> null
            }
        }

        fun resolveMonarchScope(fileName: String): String? {
            val lower = fileName.lowercase()
            val name = lower.substringAfterLast('/').substringAfterLast('\\')
            return when {
                name.endsWith(".css") -> "source.css"
                name.endsWith(".scss") || name.endsWith(".sass") -> "source.scss"
                name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs") ||
                    name.endsWith(".jsx") -> "source.js"
                name.endsWith(".ts") || name.endsWith(".tsx") -> "source.ts"
                name.endsWith(".php") -> "source.php"
                name.endsWith(".go") -> "source.go"
                name.endsWith(".rs") -> "source.rust"
                name.endsWith(".rb") -> "source.ruby"
                name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".h") ||
                    name.endsWith(".hpp") || name.endsWith(".c") || name.endsWith(".cxx") -> "source.cpp"
                name.endsWith(".cs") -> "source.cs"
                name.endsWith(".sh") || name.endsWith(".bash") || name.endsWith(".zsh") -> "source.shell"
                name.endsWith(".yml") || name.endsWith(".yaml") -> "source.yaml"
                name.endsWith(".sql") -> "source.sql"
                name.endsWith(".dart") -> "source.dart"
                name.endsWith(".swift") -> "source.swift"
                name.endsWith(".lua") -> "source.lua"
                name.endsWith(".scala") || name.endsWith(".sc") -> "source.scala"
                name.endsWith(".pl") || name.endsWith(".pm") -> "source.perl"
                name.endsWith(".clj") || name.endsWith(".cljs") || name.endsWith(".cljc") ||
                    name.endsWith(".edn") -> "source.clojure"
                name.endsWith(".ps1") || name.endsWith(".psm1") -> "source.powershell"
                name.endsWith(".bat") || name.endsWith(".cmd") -> "source.bat"
                name.endsWith(".gql") || name.endsWith(".graphql") -> "source.graphql"
                name.endsWith(".fs") || name.endsWith(".fsx") || name.endsWith(".fsi") -> "source.fsharp"
                name.endsWith(".r") -> "source.r"
                else -> null
            }
        }

        data class ThemeUiPalette(
            val bg: String,
            val fg: String,
            val lineHighlight: String,
            val selection: String,
            val cursor: String,
            val lineNum: String,
            val lineNumActive: String,
            val indentGuide: String,
            val indentGuideActive: String,
            val whitespace: String,
            val widgetBg: String,
            val chromeBg: String,
            val symbolBg: String,
            val bracketMatch: String,
            val findMatch: String
        )

        data class Theme(
            val id: String,
            val label: String,
            val assetPath: String,
            val dark: Boolean,
            val ui: ThemeUiPalette
        )

        val AVAILABLE_THEMES = listOf(
            Theme(
                "one_dark_pro", "One Dark Pro", "textmate/one_dark_pro.json", dark = true,
                ThemeUiPalette("#282c34", "#abb2bf", "#2c313c", "#3e4451", "#528bff", "#495162", "#abb2bf", "#3b4048", "#626772", "#3b4048", "#21252b", "#21252b", "#1d1f23", "#515a6b", "#525243")
            ),
            Theme(
                "dracula", "Dracula Official", "textmate/dracula.json", dark = true,
                ThemeUiPalette("#282a36", "#f8f8f2", "#343746", "#44475a", "#f8f8f2", "#6272a4", "#f8f8f2", "#3b3e4f", "#6272a4", "#44475a", "#21222c", "#21222c", "#191a21", "#6272a4", "#5b514c")
            ),
            Theme(
                "darcula", "Darcula", "textmate/darcula.json", dark = true,
                ThemeUiPalette("#2b2b2b", "#a9b7c6", "#323232", "#214283", "#bbbbbb", "#606366", "#a4a3a3", "#373737", "#505050", "#404040", "#3c3f41", "#3c3f41", "#313335", "#57f6c0", "#32593d")
            ),
            Theme(
                "monokai", "Monokai", "textmate/monokai.json", dark = true,
                ThemeUiPalette("#272822", "#f8f8f2", "#3e3d32", "#49483e", "#f8f8f0", "#90908a", "#c2c2bf", "#464741", "#767771", "#464741", "#1e1f1c", "#1e1f1c", "#1e1f1c", "#767771", "#575b61")
            ),
            Theme(
                "night_owl", "Night Owl", "textmate/night_owl.json", dark = true,
                ThemeUiPalette("#011627", "#d6deeb", "#010e1a", "#1d3b53", "#80a4c2", "#4b6479", "#c5e4fd", "#1f3347", "#7e97ac", "#2e4052", "#021320", "#01111d", "#021320", "#5f7e97", "#2b4c5e")
            ),
            Theme(
                "tokyo_night", "Tokyo Night", "textmate/tokyo_night.json", dark = true,
                ThemeUiPalette("#1a1b26", "#a9b1d6", "#1e202e", "#283457", "#c0caf5", "#3b4261", "#737aa2", "#232433", "#3b4261", "#363b54", "#16161e", "#16161e", "#13141c", "#42465d", "#3d59a1")
            ),
            Theme(
                "catppuccin_mocha", "Catppuccin Mocha", "textmate/catppuccin_mocha.json", dark = true,
                ThemeUiPalette("#1e1e2e", "#cdd6f4", "#2a2b3c", "#45475a", "#f5e0dc", "#7f849c", "#cba6f7", "#313244", "#585b70", "#45475a", "#181825", "#181825", "#11111b", "#585b70", "#4d3847")
            ),
            Theme(
                "material_theme", "Material Theme", "textmate/material_theme.json", dark = true,
                ThemeUiPalette("#212121", "#eeffff", "#1a1a1a", "#404040", "#ffcc00", "#424242", "#848484", "#303030", "#424242", "#424242", "#1a1a1a", "#1a1a1a", "#151515", "#ffcc00", "#3b3522")
            ),
            Theme(
                "ayu_dark", "Ayu Dark", "textmate/ayu_dark.json", dark = true,
                ThemeUiPalette("#0b0e14", "#bfbdb6", "#131721", "#1b2a42", "#e6b450", "#475266", "#707a8c", "#1c212b", "#384152", "#262d38", "#0f131a", "#0f131a", "#0d1017", "#475266", "#3a3225")
            ),
            Theme(
                "nord", "Nord", "textmate/nord.json", dark = true,
                ThemeUiPalette("#2e3440", "#d8dee9", "#3b4252", "#434c5e", "#d8dee9", "#4c566a", "#d8dee9", "#3d4555", "#4c566a", "#434c5e", "#242933", "#242933", "#242933", "#88c0d0", "#42505c")
            ),
            Theme(
                "gruvbox_dark", "Gruvbox Dark", "textmate/gruvbox_dark.json", dark = true,
                ThemeUiPalette("#282828", "#ebdbb2", "#32302f", "#504945", "#ebdbb2", "#665c54", "#fabd2f", "#3c3836", "#665c54", "#504945", "#1d2021", "#1d2021", "#1d2021", "#a89984", "#5a492a")
            ),
            Theme(
                "solarized_dark", "Solarized Dark", "textmate/solarized_dark.json", dark = true,
                ThemeUiPalette("#002b36", "#839496", "#073642", "#134b5c", "#d30102", "#586e75", "#93a1a1", "#073642", "#586e75", "#073642", "#00212b", "#00212b", "#001f27", "#586e75", "#274642")
            ),
            Theme(
                "synthwave_84", "SynthWave '84", "textmate/synthwave_84.json", dark = true,
                ThemeUiPalette("#262335", "#ffffff", "#312945", "#463465", "#f97e72", "#746f8c", "#ffffff", "#38334c", "#70427b", "#444251", "#1f1b2c", "#1f1b2c", "#191524", "#495495", "#5e4332")
            ),
            Theme(
                "cobalt2", "Cobalt2", "textmate/cobalt2.json", dark = true,
                ThemeUiPalette("#193549", "#ffffff", "#1f4662", "#0050a4", "#ffc600", "#708a9e", "#ffc600", "#29465b", "#ffc600", "#29465b", "#15232d", "#15232d", "#122738", "#ffc600", "#59542b")
            ),
            Theme(
                "andromeda", "Andromeda", "textmate/andromeda.json", dark = true,
                ThemeUiPalette("#23262e", "#d5ced9", "#2e323d", "#3d4352", "#ffffff", "#746f77", "#d5ced9", "#333844", "#585c66", "#333844", "#20232a", "#1e2027", "#1b1d23", "#746f77", "#59482c")
            ),
            Theme(
                "panda", "Panda", "textmate/panda.json", dark = true,
                ThemeUiPalette("#292a2b", "#e6e6e6", "#31353a", "#45474c", "#ff4b82", "#676b79", "#e6e6e6", "#373b41", "#676b79", "#3e4250", "#222324", "#222324", "#1d1e1f", "#19f9d8", "#5e4c6b")
            ),
            Theme(
                "shades_of_purple", "Shades of Purple", "textmate/shades_of_purple.json", dark = true,
                ThemeUiPalette("#2d2b55", "#ffffff", "#1f1f41", "#573c99", "#fad000", "#a599e9", "#fad000", "#3b386b", "#6c64a6", "#4d4980", "#222244", "#1e1e3f", "#191935", "#fad000", "#5c3f8c")
            ),
            Theme(
                "horizon", "Horizon", "textmate/horizon.json", dark = true,
                ThemeUiPalette("#1c1e26", "#d5d8da", "#252833", "#383c4e", "#e95378", "#565969", "#9da0a8", "#2e303e", "#4b4e63", "#2e303e", "#232530", "#16161c", "#16161c", "#6c6f93", "#44475d")
            ),
            Theme(
                "rose_pine", "Rosé Pine", "textmate/rose_pine.json", dark = true,
                ThemeUiPalette("#191724", "#e0def4", "#221f30", "#332f47", "#e0def4", "#6e6a86", "#e0def4", "#26233a", "#6e6a86", "#524f67", "#1f1d2e", "#1f1d2e", "#26233a", "#908caa", "#463938")
            ),
            Theme(
                "vitesse_dark", "Vitesse Dark", "textmate/vitesse_dark.json", dark = true,
                ThemeUiPalette("#121212", "#dbd7ca", "#181818", "#2b2b2b", "#dbd7ca", "#54524d", "#bfbaaa", "#242424", "#3d3d3d", "#2e2e2e", "#181818", "#181818", "#1e1e1e", "#4d9375", "#3b3324")
            ),
            Theme(
                "abyss", "Abyss", "textmate/abyss.json", dark = true,
                ThemeUiPalette("#000c18", "#6688cc", "#082050", "#770811", "#ddbb88", "#406385", "#80a2c2", "#002952", "#204972", "#002040", "#061940", "#061940", "#051336", "#596f99", "#2b3b66")
            ),
            Theme(
                "quietlight", "Quiet Light", "textmate/quietlight.json", dark = false,
                ThemeUiPalette("#f5f5f5", "#333333", "#e4f6d4", "#c9d0d9", "#54494b", "#6d705b", "#9769dc", "#d8d8d8", "#9d9d9d", "#aaaaaa", "#ededf5", "#ededf5", "#e4e4ee", "#705697", "#edc9d8")
            ),
        )

        val MONARCH_DEFS = listOf(
            "css" to Triple(io.github.dingyi222666.monarch.languages.CssLanguage, "source.css", "textmate/css/language-configuration.json" as String?),
            "scss" to Triple(io.github.dingyi222666.monarch.languages.ScssLanguage, "source.scss", "textmate/scss/language-configuration.json"),
            "javascript" to Triple(io.github.dingyi222666.monarch.languages.JavascriptLanguage, "source.js", "textmate/javascript/language-configuration.json"),
            "typescript" to Triple(io.github.dingyi222666.monarch.languages.TypescriptLanguage, "source.ts", "textmate/typescript/language-configuration.json"),
            "php" to Triple(io.github.dingyi222666.monarch.languages.PhpLanguage, "source.php", "textmate/php/language-configuration.json"),
            "go" to Triple(io.github.dingyi222666.monarch.languages.GoLanguage, "source.go", "textmate/go/language-configuration.json"),
            "rust" to Triple(io.github.dingyi222666.monarch.languages.RustLanguage, "source.rust", "textmate/rust/language-configuration.json"),
            "python" to Triple(io.github.dingyi222666.monarch.languages.PythonLanguage, "source.python", "textmate/python/language-configuration.json"),
            "ruby" to Triple(io.github.dingyi222666.monarch.languages.RubyLanguage, "source.ruby", "textmate/ruby/language-configuration.json"),
            "cpp" to Triple(io.github.dingyi222666.monarch.languages.CppLanguage, "source.cpp", "textmate/cpp/language-configuration.json"),
            "csharp" to Triple(io.github.dingyi222666.monarch.languages.CsharpLanguage, "source.cs", "textmate/csharp/language-configuration.json"),
            "shell" to Triple(io.github.dingyi222666.monarch.languages.ShellLanguage, "source.shell", "textmate/shell/language-configuration.json"),
            "yaml" to Triple(io.github.dingyi222666.monarch.languages.YamlLanguage, "source.yaml", "textmate/yaml/language-configuration.json"),
            "sql" to Triple(io.github.dingyi222666.monarch.languages.SqlLanguage, "source.sql", "textmate/sql/language-configuration.json"),
            "dart" to Triple(io.github.dingyi222666.monarch.languages.DartLanguage, "source.dart", "textmate/dart/language-configuration.json"),
            "swift" to Triple(io.github.dingyi222666.monarch.languages.SwiftLanguage, "source.swift", "textmate/swift/language-configuration.json"),
            "lua" to Triple(io.github.dingyi222666.monarch.languages.LuaLanguage, "source.lua", "textmate/lua/language-configuration.json"),
            "scala" to Triple(io.github.dingyi222666.monarch.languages.ScalaLanguage, "source.scala", "textmate/scala/language-configuration.json"),
            "perl" to Triple(io.github.dingyi222666.monarch.languages.PerlLanguage, "source.perl", null),
            "clojure" to Triple(io.github.dingyi222666.monarch.languages.ClojureLanguage, "source.clojure", null),
            "powershell" to Triple(io.github.dingyi222666.monarch.languages.PowershellLanguage, "source.powershell", null),
            "bat" to Triple(io.github.dingyi222666.monarch.languages.BatLanguage, "source.bat", null),
            "graphql" to Triple(io.github.dingyi222666.monarch.languages.GraphqlLanguage, "source.graphql", null),
            "fsharp" to Triple(io.github.dingyi222666.monarch.languages.FsharpLanguage, "source.fsharp", null),
            "r" to Triple(io.github.dingyi222666.monarch.languages.RLanguage, "source.r", null),
        )

        fun themeById(id: String): Theme = AVAILABLE_THEMES.firstOrNull { it.id == id } ?: AVAILABLE_THEMES.first()

        fun createConfiguredScheme(
            themeId: String,
            isMonarch: Boolean,
            appContext: Context? = null
        ): EditorColorScheme {
            val theme = themeById(themeId)
            if (appContext != null) {
                if (isMonarch) {
                    ensureMonarchThemeLoaded(appContext, theme.id)
                } else {
                    ensureTextMateThemeLoaded(appContext, theme.id)
                }
            } else {
                ThemeRegistry.getInstance().setTheme(theme.id)
                MonarchThemeRegistry.setTheme(theme.id)
            }

            val s: EditorColorScheme = if (isMonarch) {
                val curMo = MonarchThemeRegistry.currentTheme
                val moScheme = MonarchColorScheme.create(curMo)
                if (curMo != null) {
                    runCatching {
                        moScheme.javaClass.getMethod("setTheme", MonarchThemeModel::class.java)
                            .invoke(moScheme, curMo)
                    }
                    runCatching { moScheme.applyDefault() }
                }
                moScheme
            } else {
                val tmScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                ThemeRegistry.getInstance().currentThemeModel?.let { tmScheme.setTheme(it) }
                tmScheme
            }
            applyThemeUiColors(s, theme)
            return s
        }

        fun applyThemeUiColors(s: EditorColorScheme, theme: Theme) {
            val ui = theme.ui
            val bg = Color.parseColor(ui.bg)
            val fg = Color.parseColor(ui.fg)
            val lineHighlight = Color.parseColor(ui.lineHighlight)
            val selection = Color.parseColor(ui.selection)
            val cursor = Color.parseColor(ui.cursor)
            val lineNum = Color.parseColor(ui.lineNum)
            val lineNumActive = Color.parseColor(ui.lineNumActive)
            val whitespace = Color.parseColor(ui.whitespace)
            val widgetBg = Color.parseColor(ui.widgetBg)
            val bracketMatch = Color.parseColor(ui.bracketMatch)
            val findMatch = Color.parseColor(ui.findMatch)

            s.setColor(EditorColorScheme.WHOLE_BACKGROUND, bg)
            s.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, bg)
            s.setColor(EditorColorScheme.TEXT_NORMAL, fg)
            s.setColor(EditorColorScheme.LINE_NUMBER, lineNum)
            s.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, lineNumActive)
            s.setColor(EditorColorScheme.LINE_NUMBER_PANEL, widgetBg)
            s.setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, fg)
            s.setColor(EditorColorScheme.CURRENT_LINE, lineHighlight)
            s.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, selection)
            s.setColor(EditorColorScheme.SELECTION_INSERT, cursor)
            s.setColor(EditorColorScheme.SELECTION_HANDLE, cursor)
            s.setColor(EditorColorScheme.BLOCK_LINE, Color.TRANSPARENT)
            s.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, Color.TRANSPARENT)
            s.setColor(EditorColorScheme.SIDE_BLOCK_LINE, Color.TRANSPARENT)
            s.setColor(EditorColorScheme.LINE_DIVIDER, Color.TRANSPARENT)
            s.setColor(EditorColorScheme.NON_PRINTABLE_CHAR, whitespace)
            s.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, findMatch)
            s.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, bracketMatch)
            s.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, lineHighlight)
            s.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, Color.TRANSPARENT)
            s.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, widgetBg)
            s.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, fg)
            s.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, lineNumActive)
            s.setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, selection)
            s.setColor(EditorColorScheme.PROBLEM_ERROR, Color.parseColor("#FF5252"))
            s.setColor(EditorColorScheme.PROBLEM_WARNING, Color.parseColor("#FFB300"))
            s.setColor(EditorColorScheme.PROBLEM_TYPO, Color.parseColor("#4FC3F7"))
        }

        fun sanitizeMonarchLanguage(root: Any?) {
            if (root == null) return
            val visited = IdentityHashMap<Any, Boolean>()
            walkAndFixMonarch(root, null, visited)
        }

        @Suppress("UNCHECKED_CAST")
        private fun walkAndFixMonarch(obj: Any?, fieldName: String?, visited: IdentityHashMap<Any, Boolean>) {
            if (obj == null) return
            if (obj is String || obj is Number || obj is Boolean || obj is Enum<*>) return
            if (visited.put(obj, true) != null) return

            val clsName = obj.javaClass.name
            if (clsName.startsWith("java.lang.") || clsName.contains("Bracket")) return

            if (obj is MutableList<*>) {
                val list = obj as MutableList<Any?>
                for (i in list.indices) {
                    val item = list[i]
                    if (item is String) {
                        val fixed = fixMonarchRegexString(item, fieldName == "regex")
                        if (fixed != item) {
                            runCatching { list[i] = fixed }
                        }
                    } else {
                        walkAndFixMonarch(item, fieldName, visited)
                    }
                }
                return
            }

            if (obj is MutableMap<*, *>) {
                val map = obj as MutableMap<Any?, Any?>
                val entries = map.entries.toList()
                for (entry in entries) {
                    val v = entry.value
                    if (v is String) {
                        val fixed = fixMonarchRegexString(v, false)
                        if (fixed != v) {
                            runCatching { map[entry.key] = fixed }
                        }
                    } else {
                        walkAndFixMonarch(v, fieldName, visited)
                    }
                }
                return
            }

            if (obj is Array<*>) {
                val arr = obj as Array<Any?>
                for (i in arr.indices) {
                    val item = arr[i]
                    if (item is String && arr.isArrayOf<String>()) {
                        val fixed = fixMonarchRegexString(item, fieldName == "regex")
                        if (fixed != item) {
                            runCatching { arr[i] = fixed }
                        }
                    } else {
                        walkAndFixMonarch(item, fieldName, visited)
                    }
                }
                return
            }

            var c: Class<*>? = obj.javaClass
            while (c != null && c != Any::class.java && !c.name.startsWith("java.")) {
                for (field in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    val fname = field.name
                    if (fname == "brackets" || fname == "open" || fname == "close" || fname == "token") continue
                    try {
                        field.isAccessible = true
                        val value = field.get(obj) ?: continue
                        if (value is String) {
                            val isRegexField = fname.contains("regex", ignoreCase = true) ||
                                fname.contains("pattern", ignoreCase = true) ||
                                fname == "escape" || fname == "ws"
                            val fixed = fixMonarchRegexString(value, isRegexField)
                            if (fixed != value) {
                                field.set(obj, fixed)
                            }
                        } else if (value is List<*>) {
                            val newList = ArrayList<Any?>(value.size)
                            var changed = false
                            for (item in value) {
                                if (item is String) {
                                    val fixed = fixMonarchRegexString(item, fname.contains("regex", ignoreCase = true))
                                    if (fixed != item) changed = true
                                    newList.add(fixed)
                                } else {
                                    walkAndFixMonarch(item, fname, visited)
                                    newList.add(item)
                                }
                            }
                            if (changed) {
                                runCatching { field.set(obj, newList) }
                            }
                        } else {
                            walkAndFixMonarch(value, fname, visited)
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                c = c.superclass
            }
        }

        private fun fixMonarchRegexString(s: String, isRegexField: Boolean): String {
            var out = s
            if (out.contains("[^[0-9a-fA-F]")) {
                out = out.replace("[^[0-9a-fA-F]", "[^0-9a-fA-F]")
            }
            if (out.contains("[\u0009\u000a\u000d\u000c]")) {
                out = out.replace("[\u0009\u000a\u000d\u000c]", "\\s")
            }
            if (out == "}" && isRegexField) {
                return "\\}"
            }
            if (out.contains("(?:})")) {
                out = out.replace("(?:})", "(?:\\})")
            }
            if (isRegexField && out.contains("}")) {
                out = escapeStandaloneClosingBraces(out)
            }
            return out
        }

        private fun escapeStandaloneClosingBraces(s: String): String {
            val sb = StringBuilder(s.length + 4)
            var inCharClass = false
            var i = 0
            val n = s.length
            while (i < n) {
                val c = s[i]
                if (c == '\\' && i + 1 < n) {
                    sb.append(c).append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == '[') {
                    inCharClass = true
                    sb.append(c)
                    i++
                    continue
                }
                if (c == ']' && inCharClass) {
                    inCharClass = false
                    sb.append(c)
                    i++
                    continue
                }
                if (c == '}' && !inCharClass) {
                    val openIdx = sb.lastIndexOf("{")
                    var isQuantifier = false
                    if (openIdx >= 0 && (openIdx == 0 || sb[openIdx - 1] != '\\')) {
                        val inside = sb.substring(openIdx + 1)
                        if (inside.isNotEmpty() && inside.all { it.isDigit() || it == ',' }) {
                            isQuantifier = true
                        }
                    }
                    if (!isQuantifier) {
                        sb.append("\\}")
                        i++
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        fun sanitizeColors(text: String, bgHex: String = "#1e1e2e"): String {
            val sb = StringBuilder(text.length)
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (c == '#' && i > 0 && text[i - 1] == '"' && i + 1 < n) {
                    var j = i + 1
                    while (j < n && (text[j] in '0'..'9' || text[j] in 'a'..'f' || text[j] in 'A'..'F')) j++
                    val hex = text.substring(i + 1, j)
                    if (j < n && text[j] == '"') {
                        when (hex.length) {
                            3, 4, 8 -> {
                                sb.append(normalizeHexToRgb6(hex, bgHex))
                                i = j
                                continue
                            }
                        }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun normalizeHexToRgb6(hex: String, bgHex: String): String {
            if (hex.length == 3) {
                return "#${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}".lowercase()
            }
            val cleanBg = bgHex.removePrefix("#").padEnd(6, '0')
            val br = cleanBg.substring(0, 2).toIntOrNull(16) ?: 30
            val bg = cleanBg.substring(2, 4).toIntOrNull(16) ?: 30
            val bb = cleanBg.substring(4, 6).toIntOrNull(16) ?: 46

            val r: Int
            val g: Int
            val b: Int
            val a: Int
            if (hex.length == 4) {
                r = "${hex[0]}${hex[0]}".toInt(16)
                g = "${hex[1]}${hex[1]}".toInt(16)
                b = "${hex[2]}${hex[2]}".toInt(16)
                a = "${hex[3]}${hex[3]}".toInt(16)
            } else if (hex.length == 8) {
                r = hex.substring(0, 2).toInt(16)
                g = hex.substring(2, 4).toInt(16)
                b = hex.substring(4, 6).toInt(16)
                a = hex.substring(6, 8).toInt(16)
            } else {
                return "#$hex"
            }
            val nr = (r * a + br * (255 - a) + 127) / 255
            val ng = (g * a + bg * (255 - a) + 127) / 255
            val nb = (b * a + bb * (255 - a) + 127) / 255
            return String.format("#%02x%02x%02x", nr.coerceIn(0, 255), ng.coerceIn(0, 255), nb.coerceIn(0, 255))
        }
    }
}
