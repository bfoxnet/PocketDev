package com.bfoxnet.pocketdev

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * System & Grammar Diagnostics screen (`DiagActivity`).
 */
class DiagActivity : AppCompatActivity() {

    private lateinit var output: MaterialTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = Settings(this)
        val theme = EditorController.themeById(settings.editorTheme)
        val chrome = Color.parseColor(theme.ui.chromeBg)
        val surface = Color.parseColor(theme.ui.bg)
        val fg = Color.parseColor(theme.ui.fg)
        val subtle = Color.parseColor(theme.ui.lineNumActive)
        val accent = ModernUiHelper.PRIMARY_COLOR
        val density = resources.displayMetrics.density

        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.menu_diag)
            subtitle = "System logs, runtime status, and syntax engine health"
            setTitleTextAppearance(this@DiagActivity, R.style.PocketDev_ToolbarTitle)
            setSubtitleTextAppearance(this@DiagActivity, R.style.PocketDev_ToolbarSubtitle)
            minimumHeight = (46 * density).toInt()
            setBackgroundColor(chrome)
            setTitleTextColor(fg)
            setSubtitleTextColor(subtle)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationIcon?.setTint(fg)
            setNavigationOnClickListener { finish() }
        }

        val appBar = AppBarLayout(this).apply {
            setBackgroundColor(chrome)
            elevation = 0f
            addView(
                toolbar,
                AppBarLayout.LayoutParams(
                    AppBarLayout.LayoutParams.MATCH_PARENT,
                    AppBarLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(surface)
        }
        output = MaterialTextView(this).apply {
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt()
            )
            setTextColor(fg)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        scroll.addView(output)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val m = (12 * density).toInt()
            setPadding(m, (6 * density).toInt(), m, (8 * density).toInt())
        }

        val clearBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Clear Logs"
            isAllCaps = false
            typeface = ModernUiHelper.appFontSemiBold(this@DiagActivity)
            setTextColor(fg)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener {
                DiagLogger.clear(this@DiagActivity)
                Toast.makeText(this@DiagActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
                refreshDiagnosticsReport()
            }
        }

        val share = MaterialButton(this).apply {
            text = "Share Report"
            isAllCaps = false
            typeface = ModernUiHelper.appFontBold(this@DiagActivity)
            backgroundTintList = ColorStateList.valueOf(accent)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        buttonRow.addView(clearBtn)
        buttonRow.addView(share)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(chrome)
            addView(
                appBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(buttonRow)
        }
        setContentView(root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window?.let { win ->
            win.statusBarColor = chrome
            win.navigationBarColor = chrome
            val controller = WindowCompat.getInsetsController(win, root)
            controller.isAppearanceLightStatusBars = !theme.dark
            controller.isAppearanceLightNavigationBars = !theme.dark
        }

        share.setOnClickListener {
            val text = output.text.toString()
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "PocketDev Diagnostics Report")
            }
            startActivity(android.content.Intent.createChooser(send, "Share Report"))
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }

        refreshDiagnosticsReport()
    }

    private fun refreshDiagnosticsReport() {
        val log = StringBuilder("PocketDev System Diagnostics\n============================\n")
        Thread {
            val result = runDiag(log)
            runOnUiThread {
                output.text = result.toString()
                uploadToCrashlytics(result.toString())
            }
        }.start()
    }

    private fun line(sb: StringBuilder, s: String) {
        sb.appendLine(s)
        android.util.Log.i("PocketDevDiag", s)
    }

    private fun hexColor(c: Int): String = String.format("#%08x", c)

    private fun runDiag(sb: StringBuilder): StringBuilder {
        val s = sb
        EditorController.registerFileProviders(applicationContext)
        val settings = Settings(this)
        val activeThemeId = settings.editorTheme
        val activeTheme = EditorController.themeById(activeThemeId)
        line(s, "device: android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL} (${UbuntuProotManager.detectHostAbi()})")
        line(s, "saved theme: $activeThemeId (expected bg=${activeTheme.ui.bg} fg=${activeTheme.ui.fg})")
        line(s, "default shell: ${settings.defaultShell} (zshTheme=${settings.zshTheme})")

        line(s, "\n== 0. Runtime Diagnostics Stream ==")
        val prootDiag = UbuntuProotManager.lastVerifyDiagnostics
        if (prootDiag.isNotBlank()) {
            line(s, "[DIAG] PRoot Last Verify: $prootDiag")
        }
        line(
            s,
            "[STATE] UbuntuExtracted=${UbuntuProotManager.isUbuntuRootfsExtracted(this)} " +
                "PRootUsable=${UbuntuProotManager.isProotUsable(this)} " +
                "ZshInstalled=${UbuntuProotManager.isZshBinaryInstalled(this)} " +
                "LaunchMode=${UbuntuProotManager.preferredLaunchMode ?: "auto"}"
        )

        val diagEntries = DiagLogger.getRecentEntries(this, limit = 80)
        if (diagEntries.isEmpty()) {
            line(s, "(No errors or warnings recorded in current session)")
        } else {
            diagEntries.forEach { entry ->
                line(s, "DIAG $entry")
            }
        }

        EditorController.recentDiagnostics().takeLast(30).forEach {
            line(s, "HIST $it")
        }

        line(s, "\n== 1. assets")
        for (theme in EditorController.AVAILABLE_THEMES) {
            try {
                val raw = assets.open(theme.assetPath).bufferedReader().readText()
                val san = EditorController.sanitizeColors(raw, theme.ui.bg)
                val changed = san != raw
                line(s, "OK  ${theme.assetPath} ${raw.length}B sanitized=$changed")
            } catch (e: Throwable) {
                line(s, "FAIL ${theme.assetPath}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        line(s, "\n== 2. TextMate registries")
        try {
            val tmSetOk = EditorController.ensureTextMateThemeLoaded(applicationContext, activeThemeId)
            val tm = io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance()
            val cur = try {
                "current=" + tm.currentThemeModel?.name
            } catch (e: Throwable) {
                "current=? ${e.message}"
            }
            line(s, "tmTheme setTheme($activeThemeId) -> $tmSetOk $cur")
        } catch (e: Throwable) {
            line(s, "FAIL ThemeRegistry access: $e")
        }

        try {
            val gr = io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry.getInstance()
            gr.loadGrammars("textmate/languages.json")
            line(s, "OK  tm loadGrammars")
            val scopes = listOf(
                "source.java", "source.kotlin", "source.python", "source.js",
                "source.ts", "source.go", "source.cpp", "source.rust",
                "source.cs", "source.css", "source.scss", "source.php",
                "source.ruby", "source.shell", "source.yaml", "source.sql",
                "source.dart", "source.swift", "source.lua", "source.scala",
                "text.xml", "text.html.basic", "text.html.markdown", "source.json",
                "source.ini", "source.dockerfile", "source.groovy", "source.toml",
                "source.vue", "text.html.vue-html", "source.svelte", "source.haskell",
                "source.smali", "source.erlang", "source.matlab"
            )
            for (scope in scopes) {
                val g = try { gr.findGrammar(scope) } catch (e: Throwable) { null }
                line(s, (if (g == null) "MISS " else "OK   ") + scope)
            }
        } catch (e: Throwable) {
            line(s, "FAIL tm loadGrammars: $e")
        }

        line(s, "\n== 3. TextMateLanguage")
        for (scope in listOf("source.java", "source.go", "source.js", "source.json")) {
            try {
                val lang = io.github.rosemoe.sora.langs.textmate.TextMateLanguage.create(scope, true)
                val am = lang.analyzeManager
                line(s, "OK  create $scope analyzer=${am.javaClass.simpleName}")
            } catch (e: Throwable) {
                line(s, "FAIL create $scope: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        line(s, "\n== 4. Monarch")
        try {
            val set = EditorController.ensureMonarchThemeLoaded(applicationContext, activeThemeId)
            val moThemeReg = io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry
            line(s, "mo setTheme $activeThemeId -> $set current=${moThemeReg.currentTheme?.name}")

            var moLoaded = 0
            for ((_, def) in EditorController.MONARCH_DEFS) {
                val (_, scope, _) = def
                try {
                    EditorController.ensureMonarchScopeLoaded(scope)
                    moLoaded++
                } catch (e: Throwable) {
                    line(s, "FAIL moGrammar ($scope): ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            line(s, "OK  mo loadGrammars count=$moLoaded/${EditorController.MONARCH_DEFS.size}")
            for (scope in listOf("source.js", "source.go", "source.css", "source.cs", "source.scala")) {
                try {
                    val lang = io.github.rosemoe.sora.langs.monarch.MonarchLanguage.create(scope, true)
                    line(s, "OK  MonarchLanguage $scope analyzer=${lang.analyzeManager.javaClass.simpleName}")
                } catch (e: Throwable) {
                    line(s, "FAIL MonarchLanguage $scope: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            line(s, "FAIL monarch: ${e.javaClass.simpleName}: ${e.message}")
        }

        line(s, "\n== 5. schemes (theme=$activeThemeId)")
        try {
            val scheme = EditorController.createConfiguredScheme(activeThemeId, isMonarch = false, applicationContext)
            val bg = hexColor(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
            val fgColor = hexColor(scheme.getColor(EditorColorScheme.TEXT_NORMAL))
            val div = hexColor(scheme.getColor(EditorColorScheme.LINE_DIVIDER))
            val blk = hexColor(scheme.getColor(EditorColorScheme.BLOCK_LINE))
            var nonzero = 0
            var sample = ""
            for (id in 256..320) {
                val c = scheme.getColor(id)
                if (c != 0) {
                    nonzero++
                    if (sample.isEmpty()) sample = "id=$id color=${hexColor(c)}"
                }
            }
            line(s, "OK  TextMateColorScheme; bg=$bg fg=$fgColor divider=$div blockLine=$blk; token colors: $nonzero/65 $sample")
        } catch (e: Throwable) {
            line(s, "FAIL TextMateColorScheme: ${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            val scheme = EditorController.createConfiguredScheme(activeThemeId, isMonarch = true, applicationContext)
            val bg = hexColor(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
            val fgColor = hexColor(scheme.getColor(EditorColorScheme.TEXT_NORMAL))
            val div = hexColor(scheme.getColor(EditorColorScheme.LINE_DIVIDER))
            val blk = hexColor(scheme.getColor(EditorColorScheme.BLOCK_LINE))
            var nonzero = 0
            var sample = ""
            for (id in 256..320) {
                val c = scheme.getColor(id)
                if (c != 0) {
                    nonzero++
                    if (sample.isEmpty()) sample = "id=$id color=${hexColor(c)}"
                }
            }
            line(s, "OK  MonarchColorScheme; bg=$bg fg=$fgColor divider=$div blockLine=$blk; token colors: $nonzero/65 $sample")
        } catch (e: Throwable) {
            line(s, "FAIL MonarchColorScheme: ${e.javaClass.simpleName}: ${e.message}")
        }

        line(s, "\n== done")
        return s
    }

    private fun uploadToCrashlytics(text: String) {
        try {
            val cr = FirebaseCrashlytics.getInstance()
            text.lines().take(120).forEach { cr.log(it) }
            cr.recordException(RuntimeException("PocketDev diag report"))
        } catch (ignored: Throwable) {
        }
    }
}
