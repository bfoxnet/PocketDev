package com.bfoxnet.pocketdev

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bfoxnet.pocketdev.databinding.ActivityLspBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlin.math.roundToInt

/**
 * Language Servers management page (`LspActivity`).
 */
class LspActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLspBinding
    private lateinit var settings: Settings
    private var filterQuery: String = ""
    private var activeFilterCategory: Int = FILTER_ALL
    private val pendingInstallIds = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLspBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        setupInsets()
        setupToolbar()
        setupListeners()
        applyThemeChrome()
        renderServerList()
    }

    override fun onResume() {
        super.onResume()
        LspManager.syncTerminalInstallations(this)
        pendingInstallIds.removeAll { LspManager.isInstalled(this, it) }
        applyThemeChrome()
        updateSubtitle()
        renderServerList()
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.lspRoot) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, ime.bottom)
            )
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        updateSubtitle()
    }

    private fun updateSubtitle() {
        val total = LspManager.AVAILABLE_SERVERS.size
        val installed = LspManager.AVAILABLE_SERVERS.count { LspManager.isInstalled(this, it.id) }
        binding.toolbar.subtitle = "$installed of $total installed"
    }

    private fun setupListeners() {
        binding.btnInstallPopular.setOnClickListener {
            launchTerminalCommand("lsp-install --popular")
        }

        binding.chipLspAll.setOnClickListener {
            activeFilterCategory = FILTER_ALL
            styleFilterChips()
            renderServerList()
        }
        binding.chipLspInstalled.setOnClickListener {
            activeFilterCategory = FILTER_INSTALLED
            styleFilterChips()
            renderServerList()
        }
        binding.chipLspPopular.setOnClickListener {
            activeFilterCategory = FILTER_POPULAR
            styleFilterChips()
            renderServerList()
        }

        binding.lspFilterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterQuery = s?.toString()?.trim() ?: ""
                renderServerList()
            }
        })
    }

    private fun styleFilterChips() {
        val theme = EditorController.themeById(settings.editorTheme)
        val fg = Color.parseColor(theme.ui.fg)
        val subtle = Color.parseColor(theme.ui.lineNumActive)
        val accent = ModernUiHelper.PRIMARY_COLOR
        val density = resources.displayMetrics.density

        val chips = listOf(
            binding.chipLspAll to (activeFilterCategory == FILTER_ALL),
            binding.chipLspInstalled to (activeFilterCategory == FILTER_INSTALLED),
            binding.chipLspPopular to (activeFilterCategory == FILTER_POPULAR)
        )
        for ((chip, active) in chips) {
            chip.setTextColor(if (active) accent else fg)
            chip.typeface = if (active) ModernUiHelper.appFontBold(this) else ModernUiHelper.appFontMedium(this)
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(if (active) ModernUiHelper.withAlpha(accent, 0x22) else ModernUiHelper.withAlpha(subtle, 0x16))
                setStroke(
                    (1 * density).toInt().coerceAtLeast(1),
                    if (active) ModernUiHelper.withAlpha(accent, 0x77) else ModernUiHelper.withAlpha(subtle, 0x33)
                )
            }
        }
    }

    private fun launchTerminalCommand(cmd: String) {
        val activeDir = ProjectManager.ensureDefaultLocalWorkspace(this)
        val intent = Intent(this, TerminalActivity::class.java).apply {
            putExtra(TerminalActivity.EXTRA_WORKING_DIR, activeDir.absolutePath)
            putExtra(TerminalActivity.EXTRA_AUTORUN_CMD, cmd)
        }
        startActivity(intent)
    }

    private fun applyThemeChrome() {
        val theme = EditorController.themeById(settings.editorTheme)
        val chrome = Color.parseColor(theme.ui.chromeBg)
        val surface = Color.parseColor(theme.ui.bg)
        val fg = Color.parseColor(theme.ui.fg)
        val subtle = Color.parseColor(theme.ui.lineNumActive)

        binding.lspRoot.setBackgroundColor(chrome)
        binding.toolbar.setBackgroundColor(chrome)
        binding.toolbar.setTitleTextColor(fg)
        binding.toolbar.setSubtitleTextColor(subtle)
        binding.toolbar.navigationIcon?.setTint(fg)
        binding.lspScroll.setBackgroundColor(surface)
        binding.lspFilterInput.setTextColor(fg)
        binding.lspFilterInput.setHintTextColor(subtle)
        styleFilterChips()

        window?.let { win ->
            win.statusBarColor = chrome
            win.navigationBarColor = surface
            val controller = WindowCompat.getInsetsController(win, binding.lspRoot)
            controller.isAppearanceLightStatusBars = !theme.dark
            controller.isAppearanceLightNavigationBars = !theme.dark
        }
    }

    private fun renderServerList() {
        val container = binding.lspListContainer
        container.removeAllViews()

        val theme = EditorController.themeById(settings.editorTheme)
        val cardBg = Color.parseColor(theme.ui.chromeBg)
        val fg = Color.parseColor(theme.ui.fg)
        val subtle = Color.parseColor(theme.ui.lineNumActive)
        val accent = ModernUiHelper.PRIMARY_COLOR
        val density = resources.displayMetrics.density

        val popularIds = setOf("pyright", "typescript-language-server", "clangd", "gopls", "rust-analyzer", "kotlin-language-server")

        val servers = LspManager.AVAILABLE_SERVERS.filter { s ->
            val matchesCategory = when (activeFilterCategory) {
                FILTER_INSTALLED -> LspManager.isInstalled(this, s.id)
                FILTER_POPULAR -> s.id in popularIds
                else -> true
            }
            val matchesQuery = if (filterQuery.isEmpty()) true
            else s.name.contains(filterQuery, ignoreCase = true) ||
                s.id.contains(filterQuery, ignoreCase = true) ||
                s.binaryName.contains(filterQuery, ignoreCase = true) ||
                s.alpinePackages.any { it.contains(filterQuery, ignoreCase = true) } ||
                s.extensions.any { it.contains(filterQuery.removePrefix("."), ignoreCase = true) }
            matchesCategory && matchesQuery
        }

        if (servers.isEmpty()) {
            val emptyTv = MaterialTextView(this).apply {
                text = "No matching language servers found."
                textSize = 13f
                typeface = ModernUiHelper.appFontRegular(this@LspActivity)
                setTextColor(subtle)
                gravity = Gravity.CENTER
                setPadding(0, (32 * density).toInt(), 0, (32 * density).toInt())
            }
            container.addView(emptyTv)
            return
        }

        for (server in servers) {
            val installed = LspManager.isInstalled(this, server.id)
            val enabled = LspManager.isEnabled(this, server.id)
            val pending = pendingInstallIds.contains(server.id)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (14 * density).toInt(),
                    (12 * density).toInt(),
                    (14 * density).toInt(),
                    (12 * density).toInt()
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(cardBg)
                    val borderColor = when {
                        installed && enabled -> Color.parseColor("#4CAF50")
                        pending -> accent
                        else -> subtle and 0x44FFFFFF
                    }
                    setStroke((1 * density).toInt().coerceAtLeast(1), borderColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val iconView = ImageView(this).apply {
                setImageDrawable(MaterialFileIcons.createFileDrawable(this@LspActivity, server.sampleFileName, 24))
                layoutParams = LinearLayout.LayoutParams(
                    (26 * density).toInt(),
                    (26 * density).toInt()
                ).apply {
                    marginEnd = (10 * density).toInt()
                }
            }
            topRow.addView(iconView)

            val titleCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = MaterialTextView(this).apply {
                text = "${server.name} (v${server.version})"
                textSize = 14f
                setTextColor(fg)
                typeface = ModernUiHelper.appFontBold(this@LspActivity)
            }
            val cmdView = MaterialTextView(this).apply {
                text = "Extensions: ${server.extensions.joinToString(", ") { ".$it" }}  •  Packages: ${server.alpinePackages.joinToString(", ")}"
                textSize = 11f
                typeface = ModernUiHelper.appFontRegular(this@LspActivity)
                setTextColor(subtle)
            }
            titleCol.addView(nameView)
            titleCol.addView(cmdView)
            topRow.addView(titleCol)

            val statusView = MaterialTextView(this).apply {
                text = when {
                    installed && enabled -> "ACTIVE"
                    installed -> "PAUSED"
                    pending -> "INSTALLING…"
                    else -> "AVAILABLE"
                }
                textSize = 10f
                typeface = ModernUiHelper.appFontBold(this@LspActivity)
                setTextColor(
                    when {
                        installed && enabled -> Color.parseColor("#66BB6A")
                        installed -> Color.parseColor("#FFA726")
                        pending -> accent
                        else -> subtle
                    }
                )
            }
            topRow.addView(statusView)
            card.addView(topRow)

            val descView = MaterialTextView(this).apply {
                text = server.description
                textSize = 12f
                typeface = ModernUiHelper.appFontRegular(this@LspActivity)
                setTextColor(fg)
                setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
            }
            card.addView(descView)

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }

            if (!installed) {
                val installTermBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonStyle
                ).apply {
                    text = if (pending) "Resume Install" else "Install"
                    textSize = 11.5f
                    isAllCaps = false
                    typeface = ModernUiHelper.appFontBold(this@LspActivity)
                    minHeight = (36 * density).toInt()
                    minimumHeight = (36 * density).toInt()
                    setOnClickListener {
                        pendingInstallIds.add(server.id)
                        renderServerList()
                        launchTerminalCommand("lsp-install ${server.id}")
                    }
                }
                btnRow.addView(installTermBtn)
            } else {
                val toggleBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = if (enabled) "Pause" else "Enable"
                    textSize = 11.5f
                    isAllCaps = false
                    typeface = ModernUiHelper.appFontSemiBold(this@LspActivity)
                    minHeight = (36 * density).toInt()
                    minimumHeight = (36 * density).toInt()
                    setOnClickListener {
                        LspManager.setServerEnabled(this@LspActivity, server.id, !enabled)
                        renderServerList()
                    }
                }
                btnRow.addView(toggleBtn)

                val rpcBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "Inspect"
                    textSize = 11.5f
                    isAllCaps = false
                    typeface = ModernUiHelper.appFontSemiBold(this@LspActivity)
                    minHeight = (36 * density).toInt()
                    minimumHeight = (36 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (6 * density).toInt()
                    }
                    setOnClickListener {
                        showJsonRpcDialog(server)
                    }
                }
                btnRow.addView(rpcBtn)

                val uninstallBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "Uninstall"
                    textSize = 11.5f
                    isAllCaps = false
                    typeface = ModernUiHelper.appFontSemiBold(this@LspActivity)
                    minHeight = (36 * density).toInt()
                    minimumHeight = (36 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (6 * density).toInt()
                    }
                    setOnClickListener {
                        LspManager.uninstallServer(this@LspActivity, server.id)
                        launchTerminalCommand("lsp-uninstall ${server.id}")
                    }
                }
                btnRow.addView(uninstallBtn)
            }

            card.addView(btnRow)
            container.addView(card)
        }
    }

    private fun showJsonRpcDialog(server: LspManager.LspServerSpec) {
        val sampleDiags = LspManager.analyzeDocument(
            server,
            server.sampleFileName,
            "funn test() {\n    idjdhdjd.ndndn()\n    runsisiiwiw bla bla\n}\n"
        )
        val payload = LspManager.buildJsonRpcSampleExchange(server, server.sampleFileName, sampleDiags)
        val palette = ModernUiHelper.resolvePalette(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(14))
        }

        val title = TextView(this).apply {
            text = "${server.name} • Protocol Inspector"
            textSize = 15.5f
            typeface = ModernUiHelper.appFontBold(this@LspActivity)
            setTextColor(palette.fgColor)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(title)

        val codeView = TextView(this).apply {
            text = payload
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.fgColor)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.inputBgColor)
                setStroke(dp(1), palette.strokeColor)
            }
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(340)
            )
            addView(codeView)
        }
        root.addView(scroll)

        val sheetDialog = ModernUiHelper.showModernCustomBottomSheet(this, root, palette)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val copyBtn = TextView(this).apply {
            text = "Copy"
            textSize = 12.5f
            typeface = ModernUiHelper.appFontBold(this@LspActivity)
            setTextColor(palette.accentColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText("JSON-RPC", payload))
                Toast.makeText(this@LspActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(copyBtn)

        val closeBtn = TextView(this).apply {
            text = "Close"
            textSize = 12.5f
            typeface = ModernUiHelper.appFontBold(this@LspActivity)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener { sheetDialog.dismiss() }
        }
        btnRow.addView(closeBtn)
        root.addView(btnRow)
    }

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_INSTALLED = 1
        private const val FILTER_POPULAR = 2
    }
}
