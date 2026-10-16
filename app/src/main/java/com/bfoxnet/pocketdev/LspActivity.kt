package com.bfoxnet.pocketdev

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bfoxnet.pocketdev.databinding.ActivityLspBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

/**
 * Dedicated "Language Server Protocol" page with synchronized AppBar and StatusBar colors.
 *
 * Installing any Language Server (`lsp-install <server-id>`) also installs its corresponding
 * Alpine Linux language runtime/compiler packages (`spec.alpinePackages`) via `apk add`.
 */
class LspActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLspBinding
    private lateinit var settings: Settings
    private var filterQuery: String = ""

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
        binding.toolbar.subtitle = "$installed of $total installed (includes language packages)"
    }

    private fun setupListeners() {
        binding.btnInstallPopular.setOnClickListener {
            launchTerminalCommand("lsp-install --popular")
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
        val density = resources.displayMetrics.density

        val servers = LspManager.AVAILABLE_SERVERS.filter { s ->
            if (filterQuery.isEmpty()) true
            else s.name.contains(filterQuery, ignoreCase = true) ||
                s.id.contains(filterQuery, ignoreCase = true) ||
                s.binaryName.contains(filterQuery, ignoreCase = true) ||
                s.alpinePackages.any { it.contains(filterQuery, ignoreCase = true) } ||
                s.extensions.any { it.contains(filterQuery.removePrefix("."), ignoreCase = true) }
        }

        for (server in servers) {
            val installed = LspManager.isInstalled(this, server.id)
            val enabled = LspManager.isEnabled(this, server.id)

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
                    cornerRadius = 10 * density
                    setColor(cardBg)
                    val borderColor = if (installed && enabled) {
                        Color.parseColor("#4CAF50")
                    } else {
                        subtle and 0x44FFFFFF
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

            // Header row: Material Icon + Name/Version + Status Pill
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
                setTypeface(typeface, Typeface.BOLD)
            }
            val cmdView = MaterialTextView(this).apply {
                text = "$ lsp-install ${server.id}  •  Packages: ${server.alpinePackages.joinToString(", ")}"
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
                setTextColor(subtle)
            }
            titleCol.addView(nameView)
            titleCol.addView(cmdView)
            topRow.addView(titleCol)

            val statusView = MaterialTextView(this).apply {
                text = when {
                    installed && enabled -> "ACTIVE"
                    installed -> "PAUSED"
                    else -> "NOT INSTALLED"
                }
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(
                    when {
                        installed && enabled -> Color.parseColor("#66BB6A")
                        installed -> Color.parseColor("#FFA726")
                        else -> subtle
                    }
                )
            }
            topRow.addView(statusView)
            card.addView(topRow)

            // Description
            val descView = MaterialTextView(this).apply {
                text = server.description
                textSize = 12f
                setTextColor(fg)
                setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
            }
            card.addView(descView)

            // Action Buttons Row
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
                    text = "Install LSP + Language Package"
                    textSize = 11f
                    minHeight = (34 * density).toInt()
                    minimumHeight = (34 * density).toInt()
                    setOnClickListener {
                        LspManager.installServer(this@LspActivity, server.id)
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
                    textSize = 11f
                    minHeight = (34 * density).toInt()
                    minimumHeight = (34 * density).toInt()
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
                    text = "JSON-RPC"
                    textSize = 11f
                    minHeight = (34 * density).toInt()
                    minimumHeight = (34 * density).toInt()
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
                    textSize = 11f
                    minHeight = (34 * density).toInt()
                    minimumHeight = (34 * density).toInt()
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
        AlertDialog.Builder(this)
            .setTitle("${server.name} • JSON-RPC 2.0")
            .setMessage(payload)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
