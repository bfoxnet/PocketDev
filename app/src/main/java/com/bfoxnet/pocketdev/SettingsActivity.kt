package com.bfoxnet.pocketdev

import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bfoxnet.pocketdev.databinding.ActivitySettingsBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

/**
 * Organized PocketDev Settings screen (with all legacy/dummy QEMU & Alpine references removed):
 * 1. Appearance (Color Scheme, Editor & Terminal Font, Font Size)
 * 2. Code Editor & Auto-Save (Auto-Save Modified Files, Auto-Save Delay, Save All Open Files on Save Press, Font Ligatures, Word Wrap)
 * 3. Terminal & Zsh Shell (Default Linux Shell [Zsh + Plugins / Bash], Terminal Color Theme, Cursor Style, Helper Keyboard Toolbar, Keep Screen On, Edit termux.properties)
 * 4. Ubuntu 24.04 LTS • PRoot & Zsh Environment (Host Architecture, Ubuntu Projects Directory `/root/projects`, Install/Sync Real Zsh & Plugins, Install/Repair Ubuntu 24.04.5 LTS Rootfs + PRoot)
 * 5. Environment & Tools (Language Servers, Open Ubuntu 24.04 Zsh Terminal in Active Project)
 * 6. System & About (Editor Diagnostics, About PocketDev)
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    private val exportEnvBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
            if (uri != null) {
                binding.valueUbuntuSync.text = "Backing up..."
                Toast.makeText(this, "Exporting Ubuntu 24.04 environment backup (.tar.gz)...", Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            UbuntuProotManager.exportEnvironmentBackupToStream(this, out) { msg ->
                                val clean = msg.replace(Regex("\u001b\\[[0-9;]*m"), "").trim()
                                if (clean.isNotEmpty()) {
                                    runOnUiThread { binding.subUbuntuSync.text = clean }
                                }
                            }
                        } ?: false
                    } catch (e: Exception) {
                        DiagLogger.log("Backup", "SAF export failed: ${e.message}", this, e)
                        false
                    }
                    runOnUiThread {
                        refreshSummary()
                        Toast.makeText(
                            this,
                            if (ok) "Environment backup exported to .tar.gz ✓" else "Environment backup failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            }
        }

    private val importEnvBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                binding.valueUbuntuSync.text = "Restoring..."
                Toast.makeText(this, "Restoring Ubuntu 24.04 environment from backup...", Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = try {
                        contentResolver.openInputStream(uri)?.use { ins ->
                            UbuntuProotManager.importEnvironmentBackupFromStream(this, ins) { msg ->
                                val clean = msg.replace(Regex("\u001b\\[[0-9;]*m"), "").trim()
                                if (clean.isNotEmpty()) {
                                    runOnUiThread { binding.subUbuntuSync.text = clean }
                                }
                            }
                        } ?: false
                    } catch (e: Exception) {
                        DiagLogger.log("Restore", "SAF restore failed: ${e.message}", this, e)
                        false
                    }
                    runOnUiThread {
                        refreshSummary()
                        Toast.makeText(
                            this,
                            if (ok) "Ubuntu 24.04 + PRoot + Zsh restored from backup ✓" else "Restore failed — check archive format",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        setupInsets()
        setupListeners()
        applyThemeChrome()
        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        applyThemeChrome()
        refreshSummary()
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { v, insets ->
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

    private fun applyThemeChrome() {
        val theme = EditorController.themeById(settings.editorTheme)
        val surface = Color.parseColor(theme.ui.bg)
        val chrome = Color.parseColor(theme.ui.chromeBg)
        val fg = Color.parseColor(theme.ui.fg)
        val subtle = Color.parseColor(theme.ui.lineNumActive)
        // Lightly orange App UI primary accent color
        val accent = if (theme.dark) Color.parseColor("#FFB74D") else Color.parseColor("#E66000")
        val density = resources.displayMetrics.density

        binding.settingsRoot.setBackgroundColor(chrome)
        binding.settingsAppbar.setBackgroundColor(chrome)
        binding.toolbar.setBackgroundColor(chrome)
        binding.toolbar.setTitleTextColor(fg)
        binding.toolbar.setSubtitleTextColor(subtle)
        binding.toolbar.navigationIcon?.setTint(fg)
        binding.settingsScroll.setBackgroundColor(surface)

        window?.let { win ->
            win.statusBarColor = chrome
            win.navigationBarColor = surface
            val controller = WindowCompat.getInsetsController(win, binding.settingsRoot)
            controller.isAppearanceLightStatusBars = !theme.dark
            controller.isAppearanceLightNavigationBars = !theme.dark
        }

        val headers = listOf(
            binding.headerAppearance,
            binding.headerEditor,
            binding.headerTerminal,
            binding.headerUbuntu,
            binding.headerTools,
            binding.headerSystem
        )
        headers.forEach { it.setTextColor(accent) }

        val cards = listOf(
            binding.cardAppearance,
            binding.cardEditor,
            binding.cardTerminal,
            binding.cardUbuntu,
            binding.cardTools,
            binding.cardSystem
        )
        cards.forEach { card: View ->
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(chrome)
                setStroke((1 * density).toInt().coerceAtLeast(1), subtle and 0x33FFFFFF)
            }
        }

        val titles = listOf(
            binding.titleTheme,
            binding.titleFont,
            binding.titleFontSize,
            binding.titleAutoSaveDelay,
            binding.titleDefaultShell,
            binding.titleTerminalStyle,
            binding.titleTerminalCursor,
            binding.titleTermuxProps,
            binding.titleUbuntuArch,
            binding.titleUbuntuProjectsDir,
            binding.titleZshSetup,
            binding.titleUbuntuSync,
            binding.titleLsp,
            binding.titleTerminal,
            binding.titleDiag,
            binding.titleAbout
        )
        titles.forEach { it.setTextColor(fg) }

        val subtitles = listOf(
            binding.subTheme,
            binding.subFont,
            binding.subFontSize,
            binding.subAutoSaveDelay,
            binding.subDefaultShell,
            binding.subTerminalStyle,
            binding.subTerminalCursor,
            binding.subTermuxProps,
            binding.subUbuntuArch,
            binding.subUbuntuProjectsDir,
            binding.subZshSetup,
            binding.subUbuntuSync,
            binding.subLsp,
            binding.subTerminal,
            binding.subDiag,
            binding.subAbout
        )
        subtitles.forEach { it.setTextColor(subtle) }

        val values = listOf(
            binding.valueTheme,
            binding.valueFont,
            binding.valueFontSize,
            binding.valueAutoSaveDelay,
            binding.valueDefaultShell,
            binding.valueTerminalStyle,
            binding.valueTerminalCursor,
            binding.valueTermuxProps,
            binding.valueUbuntuArch,
            binding.valueUbuntuProjectsDir,
            binding.valueZshSetup,
            binding.valueUbuntuSync,
            binding.valueLsp,
            binding.valueTerminal,
            binding.valueDiag,
            binding.valueAbout
        )
        values.forEach { it.setTextColor(accent) }

        val switchThumbStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, subtle)
        )
        val switchTrackStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                Color.argb(72, Color.red(accent), Color.green(accent), Color.blue(accent)),
                subtle and 0x33FFFFFF
            )
        )

        listOf(
            binding.switchAutoSave,
            binding.switchSaveAllOnSave,
            binding.switchLigatures,
            binding.switchWrap,
            binding.switchTerminalToolbar,
            binding.switchTerminalKeepScreen
        ).forEach { sw ->
            sw.setTextColor(fg)
            sw.thumbTintList = switchThumbStates
            sw.trackTintList = switchTrackStates
        }
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Section 1: Appearance
        binding.rowTheme.setOnClickListener { showThemePicker() }
        binding.rowFont.setOnClickListener { showFontPicker() }
        binding.rowFontSize.setOnClickListener { showFontSizePicker() }

        // Section 2: Code Editor & Auto-Save
        binding.switchAutoSave.isChecked = settings.autoSaveEnabled
        binding.switchAutoSave.setOnCheckedChangeListener { _, checked ->
            settings.autoSaveEnabled = checked
            refreshSummary()
        }

        binding.rowAutoSaveDelay.setOnClickListener { showAutoSaveDelayPicker() }

        binding.switchSaveAllOnSave.isChecked = settings.saveAllOnSavePress
        binding.switchSaveAllOnSave.setOnCheckedChangeListener { _, checked ->
            settings.saveAllOnSavePress = checked
            refreshSummary()
        }

        binding.switchLigatures.isChecked = settings.fontLigatures
        binding.switchLigatures.setOnCheckedChangeListener { _, checked ->
            settings.fontLigatures = checked
        }

        binding.switchWrap.isChecked = settings.wordWrap
        binding.switchWrap.setOnCheckedChangeListener { _, checked ->
            settings.wordWrap = checked
        }

        // Section 3: Terminal & Zsh Shell
        binding.rowDefaultShell.setOnClickListener { showDefaultShellPicker() }
        binding.rowTerminalStyle.setOnClickListener { showTerminalStylePicker() }
        binding.rowTerminalCursor.setOnClickListener { showTerminalCursorPicker() }

        binding.switchTerminalToolbar.isChecked = settings.terminalToolbarVisible
        binding.switchTerminalToolbar.setOnCheckedChangeListener { _, checked ->
            settings.terminalToolbarVisible = checked
        }

        binding.switchTerminalKeepScreen.isChecked = settings.terminalKeepScreenOn
        binding.switchTerminalKeepScreen.setOnCheckedChangeListener { _, checked ->
            settings.terminalKeepScreenOn = checked
        }

        binding.rowTermuxProps.setOnClickListener { showEditTermuxPropertiesDialog() }

        // Section 4: Ubuntu 24.04 LTS • PRoot & Zsh Environment
        binding.rowUbuntuArch.setOnClickListener { showUbuntuArchInfo() }

        binding.rowUbuntuProjectsDir.setOnClickListener {
            val activeProject = ProjectManager.ensureDefaultLocalWorkspace(this)
            startActivity(
                Intent(this, TerminalActivity::class.java).apply {
                    putExtra(TerminalActivity.EXTRA_WORKING_DIR, activeProject.absolutePath)
                }
            )
        }

        binding.rowZshSetup.setOnClickListener {
            UbuntuProotManager.configureUbuntuRootfs(this)
            if (!UbuntuProotManager.isUbuntuInstalled(this)) {
                Toast.makeText(
                    this,
                    "Provisioned Zsh plugins (.zshrc). Opening Terminal to bootstrap Ubuntu & Zsh...",
                    Toast.LENGTH_SHORT
                ).show()
                startActivity(
                    Intent(this, TerminalActivity::class.java).apply {
                        putExtra(
                            TerminalActivity.EXTRA_WORKING_DIR,
                            ProjectManager.ensureDefaultLocalWorkspace(this@SettingsActivity).absolutePath
                        )
                    }
                )
                return@setOnClickListener
            }
            binding.valueZshSetup.text = "Installing..."
            Thread {
                val ok = UbuntuProotManager.installZshInUbuntu(this) { msg ->
                    val clean = msg.replace(Regex("\u001b\\[[0-9;]*m"), "").trim()
                    if (clean.isNotEmpty()) {
                        runOnUiThread { binding.subZshSetup.text = clean }
                    }
                }
                runOnUiThread {
                    refreshSummary()
                    if (ok) {
                        Toast.makeText(this, "Real Zsh + plugins installed and active!", Toast.LENGTH_SHORT).show()
                    } else {
                        startActivity(
                            Intent(this, TerminalActivity::class.java).apply {
                                putExtra(
                                    TerminalActivity.EXTRA_WORKING_DIR,
                                    ProjectManager.ensureDefaultLocalWorkspace(this@SettingsActivity).absolutePath
                                )
                                putExtra(TerminalActivity.EXTRA_AUTORUN_CMD, "zsh-setup")
                            }
                        )
                    }
                }
            }.start()
        }

        binding.rowUbuntuSync.setOnClickListener {
            showUbuntuRootfsMaintenanceDialog()
        }

        // Section 5 & 6: LSP, Terminal, Diagnostics, About
        binding.rowLsp.setOnClickListener {
            startActivity(Intent(this, LspActivity::class.java))
        }
        binding.rowTerminal.setOnClickListener {
            val activeProject = ProjectManager.ensureDefaultLocalWorkspace(this)
            startActivity(
                Intent(this, TerminalActivity::class.java).apply {
                    putExtra(TerminalActivity.EXTRA_WORKING_DIR, activeProject.absolutePath)
                }
            )
        }
        binding.rowDiag.setOnClickListener {
            startActivity(Intent(this, DiagActivity::class.java))
        }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun refreshSummary() {
        binding.valueTheme.text = themeLabel(settings.editorTheme)
        binding.valueFont.text = Settings.fontById(settings.fontFamily).label
        binding.valueFontSize.text = getString(R.string.font_size_value, settings.fontSizeSp)

        binding.switchAutoSave.isChecked = settings.autoSaveEnabled
        binding.rowAutoSaveDelay.alpha = if (settings.autoSaveEnabled) 1f else 0.5f
        binding.valueAutoSaveDelay.text = when (settings.autoSaveDelayMs) {
            800 -> "0.8s (Instant)"
            1500 -> "1.5s (Balanced)"
            3000 -> "3.0s (Relaxed)"
            5000 -> "5.0s"
            else -> "${settings.autoSaveDelayMs} ms"
        }
        binding.switchSaveAllOnSave.isChecked = settings.saveAllOnSavePress

        binding.valueDefaultShell.text =
            if (settings.defaultShell == Settings.SHELL_BASH) "GNU Bash" else "Zsh (robbyrussell)"

        val styleIdx = settings.terminalStyleIndex.coerceIn(0, TerminalActivity.TERMUX_STYLES.lastIndex)
        binding.valueTerminalStyle.text = TerminalActivity.TERMUX_STYLES[styleIdx].name

        val termProps = AlpineLinuxEnvironment.loadTermuxProperties(this)
        val cursorIdx = if (settings.terminalCursorStyle in 0..2) settings.terminalCursorStyle else termProps.cursorStyle
        binding.valueTerminalCursor.text = when (cursorIdx) {
            1 -> "Underline (_)"
            2 -> "Bar (|)"
            else -> "Block (█)"
        }

        binding.switchTerminalToolbar.isChecked = settings.terminalToolbarVisible
        binding.switchTerminalKeepScreen.isChecked = settings.terminalKeepScreenOn

        binding.valueUbuntuArch.text = "${UbuntuProotManager.detectHostAbi()} (${UbuntuProotManager.ubuntuArch()})"

        val projectsCount = ProjectManager.listAppDirProjects(this).size
        val activeProjName = ProjectManager.activeProjectName(this)
        binding.valueUbuntuProjectsDir.text = "/root/projects ($projectsCount)"
        binding.subUbuntuProjectsDir.text =
            "Active: /root/projects/$activeProjName • Tap to open in Terminal"

        val isUbuntuReady = UbuntuProotManager.isUbuntuInstalled(this)
        val isZshBinReady = UbuntuProotManager.isZshBinaryInstalled(this)
        val arePluginsReady = UbuntuProotManager.areZshPluginsProvisioned(this)

        binding.valueZshSetup.text = when {
            isZshBinReady && arePluginsReady -> "Installed ✓"
            arePluginsReady -> "Plugins Ready"
            else -> "Install"
        }
        binding.subZshSetup.text = when {
            isZshBinReady && arePluginsReady ->
                "Real /usr/bin/zsh (robbyrussell theme) + autosuggestions, syntax-highlighting & completions active"
            arePluginsReady ->
                "robbyrussell theme, plugins & .zshrc ready — tap to install /usr/bin/zsh package via Ubuntu APT"
            else ->
                "Tap to provision Zsh & plugins (autosuggestions, syntax-highlighting, completions)"
        }

        binding.valueUbuntuSync.text = if (isUbuntuReady) "Installed ✓" else "Install"
        binding.subUbuntuSync.text = if (isUbuntuReady) {
            "Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} (${UbuntuProotManager.ubuntuArch()}) + PRoot ready"
        } else {
            "Tap to download & extract official Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} rootfs + PRoot"
        }

        val installedCount = LspManager.AVAILABLE_SERVERS.count { LspManager.isInstalled(this, it.id) }
        binding.valueLsp.text = "$installedCount/${LspManager.AVAILABLE_SERVERS.size} active"
        val activeSessions = TerminalActivity.persistentSessions.size
        binding.valueTerminal.text = if (activeSessions > 0) "$activeSessions running" else "Open"
    }

    private fun themeLabel(id: String): String =
        (EditorController.AVAILABLE_THEMES.firstOrNull { it.id == id }?.label ?: id)

    private fun showThemePicker() {
        val themes = EditorController.AVAILABLE_THEMES
        val labels = themes.map { it.label }
        val checked = themes.indexOfFirst { it.id == settings.editorTheme }.coerceAtLeast(0)
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = getString(R.string.pref_theme),
            subtitle = "Editor Syntax Color Scheme (App UI uses lightly orange accent)",
            items = labels,
            checkedIndex = checked
        ) { which ->
            settings.editorTheme = themes[which].id
            applyThemeChrome()
            refreshSummary()
        }
    }

    private fun showFontPicker() {
        val fonts = Settings.FONTS
        val labels = fonts.map { it.label }
        val checked = fonts.indexOfFirst { it.id == settings.fontFamily }.coerceAtLeast(0)
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = getString(R.string.pref_font),
            subtitle = "Monospace typeface for Editor & Terminal",
            items = labels,
            checkedIndex = checked
        ) { which ->
            settings.fontFamily = fonts[which].id
            refreshSummary()
        }
    }

    private fun showFontSizePicker() {
        val sizes = listOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 22, 24)
        val checked = sizes.indexOf(settings.fontSizeSp).coerceAtLeast(0)
        val labels = sizes.map { "$it sp" }
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = getString(R.string.pref_font_size),
            subtitle = "Code editor text scale",
            items = labels,
            checkedIndex = checked
        ) { which ->
            settings.fontSizeSp = sizes[which]
            refreshSummary()
        }
    }

    private fun showAutoSaveDelayPicker() {
        val delays = listOf(800, 1500, 3000, 5000)
        val labels = listOf(
            "Instant (0.8 seconds)",
            "Balanced (1.5 seconds — Recommended)",
            "Relaxed (3.0 seconds)",
            "5.0 seconds"
        )
        val checked = delays.indexOf(settings.autoSaveDelayMs).let { if (it >= 0) it else 1 }
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Auto-Save Delay",
            subtitle = "Automatically writes modified files after typing pauses",
            items = labels,
            checkedIndex = checked
        ) { which ->
            settings.autoSaveDelayMs = delays[which]
            settings.autoSaveEnabled = true
            refreshSummary()
        }
    }

    private fun showDefaultShellPicker() {
        val shells = listOf(Settings.SHELL_ZSH, Settings.SHELL_BASH)
        val labels = listOf(
            "Zsh (robbyrussell theme + zsh-autosuggestions, zsh-syntax-highlighting, zsh-completions)",
            "GNU Bash (/bin/bash)"
        )
        val checked = if (settings.defaultShell == Settings.SHELL_BASH) 1 else 0
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Default Linux Shell",
            subtitle = "Used for Ubuntu 24.04 PRoot terminal sessions",
            items = labels,
            checkedIndex = checked
        ) { which ->
            settings.defaultShell = shells[which]
            UbuntuProotManager.configureUbuntuRootfs(this)
            refreshSummary()
        }
    }

    private fun showTerminalStylePicker() {
        val styles = TerminalActivity.TERMUX_STYLES
        val labels = styles.map { it.name }
        val checked = settings.terminalStyleIndex.coerceIn(0, styles.lastIndex)
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Terminal Color Theme",
            subtitle = "Persists across terminal sessions and navigation",
            items = labels,
            checkedIndex = checked
        ) { which ->
            settings.terminalStyleIndex = which
            refreshSummary()
        }
    }

    private fun showTerminalCursorPicker() {
        val styles = listOf("Block (█)", "Underline (_)", "Bar (|)")
        val termProps = AlpineLinuxEnvironment.loadTermuxProperties(this)
        val current = if (settings.terminalCursorStyle in 0..2) settings.terminalCursorStyle else termProps.cursorStyle
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Terminal Cursor Style",
            subtitle = "Choose terminal prompt cursor shape",
            items = styles,
            checkedIndex = current
        ) { which ->
            settings.terminalCursorStyle = which
            refreshSummary()
        }
    }

    private fun showUbuntuArchInfo() {
        val hostAbi = UbuntuProotManager.detectHostAbi()
        val uArch = UbuntuProotManager.ubuntuArch()
        val labels = listOf(
            "$hostAbi / Ubuntu $uArch (Native Hardware Speed — Active)"
        )
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Host & Rootfs Architecture",
            subtitle = "Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} (${UbuntuProotManager.UBUNTU_CODENAME})",
            items = labels,
            checkedIndex = 0
        ) {
            settings.vmArch = hostAbi
            refreshSummary()
        }
    }

    private fun showUbuntuRootfsMaintenanceDialog() {
        val options = listOf(
            "Verify / Bootstrap Ubuntu 24.04.5 LTS + PRoot + Zsh (robbyrussell)",
            "Backup Environment to .tar.gz Archive (Export Rootfs + PRoot via SAF)",
            "Restore Environment from .tar.gz Backup (Import via SAF — Skips Download)",
            "Reinstall Clean Ubuntu 24.04.5 LTS Rootfs (Preserves /root/projects)",
            "Show Welcome & First-Run Onboarding Guide"
        )
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Ubuntu 24.04.5 LTS • Environment & Backup",
            subtitle = UbuntuProotManager.ubuntuRootDir(this).absolutePath,
            items = options,
            checkedIndex = 0
        ) { which ->
            when (which) {
                0 -> runBootstrapFromSettings(forceReinstall = false)
                1 -> exportEnvBackupLauncher.launch("pocketdev-ubuntu-${UbuntuProotManager.UBUNTU_VERSION}-${UbuntuProotManager.ubuntuArch()}-backup.tar.gz")
                2 -> importEnvBackupLauncher.launch(arrayOf("*/*"))
                3 -> {
                    ModernUiHelper.showModernConfirmDialog(
                        context = this,
                        title = "Reinstall Ubuntu Rootfs?",
                        message = "This will re-extract a clean Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} rootfs while preserving all your projects in /root/projects.",
                        confirmText = "Reinstall",
                        isDestructive = true
                    ) {
                        runBootstrapFromSettings(forceReinstall = true)
                    }
                }
                4 -> {
                    OnboardingAndHapticsHelper.showOnboardingDialog(
                        context = this,
                        onBootstrapNow = { runBootstrapFromSettings(forceReinstall = false) },
                        onOpenLspManager = { startActivity(Intent(this, LspActivity::class.java)) }
                    )
                }
            }
        }
    }

    private fun runBootstrapFromSettings(forceReinstall: Boolean) {
        binding.valueUbuntuSync.text = "Working..."
        Thread {
            val ok = UbuntuProotManager.bootstrapUbuntuRootfs(this, forceReinstall = forceReinstall) { msg ->
                val cleanMsg = msg.replace(Regex("\u001b\\[[0-9;]*m"), "")
                runOnUiThread {
                    binding.subUbuntuSync.text = cleanMsg
                }
            }
            runOnUiThread {
                refreshSummary()
                Toast.makeText(
                    this,
                    if (ok) "Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} + PRoot + Zsh ready!"
                    else "Bootstrap failed — check internet connection",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun showEditTermuxPropertiesDialog() {
        val propFile = AlpineLinuxEnvironment.termuxPropertiesFile(this)
        val palette = ModernUiHelper.resolvePalette(this)
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (18 * density).toInt(),
                (16 * density).toInt(),
                (18 * density).toInt(),
                (14 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(palette.cardBg)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.borderColor)
            }
        }

        val titleView = MaterialTextView(this).apply {
            text = "~/.termux/termux.properties"
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.fgColor)
        }
        rootCard.addView(titleView)

        val subView = MaterialTextView(this).apply {
            text = "Configure extra-keys haptics, sticky Ctrl, wake-lock, editor aids, and ~/.termux/termux.properties"
            textSize = 12f
            setTextColor(palette.subtleColor)
            setPadding(0, (2 * density).toInt(), 0, (8 * density).toInt())
        }
        rootCard.addView(subView)

        val hapticsRow = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            val hLabels = listOf("Off", "Light (Default)", "Medium", "Strong")
            text = "Extra Keys Haptic Feedback: ${hLabels.getOrElse(settings.hapticFeedbackLevel) { "Light" }}"
            isAllCaps = false
            setTextColor(palette.fgColor)
            setOnClickListener {
                settings.hapticFeedbackLevel = (settings.hapticFeedbackLevel + 1) % 4
                text = "Extra Keys Haptic Feedback: ${hLabels.getOrElse(settings.hapticFeedbackLevel) { "Light" }}"
                OnboardingAndHapticsHelper.performExtraKeyHaptic(this@SettingsActivity, this)
            }
        }
        rootCard.addView(hapticsRow)

        val stickyCtrlCheck = CheckBox(this).apply {
            text = "Toggle-Sticky CTRL key (latches until tapped again)"
            textSize = 12f
            setTextColor(palette.fgColor)
            isChecked = settings.terminalStickyCtrl
            setOnCheckedChangeListener { _, checked -> settings.terminalStickyCtrl = checked }
        }
        rootCard.addView(stickyCtrlCheck)

        val autoWakeCheck = CheckBox(this).apply {
            text = "Auto keep-screen-on & wake-lock while terminal command runs"
            textSize = 12f
            setTextColor(palette.fgColor)
            isChecked = settings.terminalAutoWakeLock
            setOnCheckedChangeListener { _, checked -> settings.terminalAutoWakeLock = checked }
        }
        rootCard.addView(autoWakeCheck)

        val wordMatchCheck = CheckBox(this).apply {
            text = "Editor: Word-match highlighting & Sticky-scroll headers"
            textSize = 12f
            setTextColor(palette.fgColor)
            isChecked = settings.wordMatchHighlightEnabled && settings.stickyScrollEnabled
            setOnCheckedChangeListener { _, checked ->
                settings.wordMatchHighlightEnabled = checked
                settings.stickyScrollEnabled = checked
            }
        }
        rootCard.addView(wordMatchCheck)

        val input = EditText(this).apply {
            setText(runCatching { propFile.readText() }.getOrDefault(""))
            setTextColor(palette.fgColor)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            minLines = 8
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(palette.surfaceBg)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.borderColor)
            }
        }
        rootCard.addView(input)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (12 * density).toInt(), 0, 0)
        }

        val cancelBtn = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(palette.subtleColor)
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(cancelBtn)

        val saveBtn = MaterialButton(this).apply {
            text = "Save"
            isAllCaps = false
            cornerRadius = (10 * density).toInt()
            backgroundTintList = ColorStateList.valueOf(palette.accentColor)
            setTextColor(if (EditorController.themeById(settings.editorTheme).dark) Color.parseColor("#2B1700") else Color.WHITE)
            setOnClickListener {
                runCatching { propFile.writeText(input.text?.toString() ?: "") }
                dialog.dismiss()
                refreshSummary()
                Toast.makeText(this@SettingsActivity, "Saved ~/.termux/termux.properties", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(saveBtn)
        rootCard.addView(btnRow)

        dialog.setContentView(rootCard)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}
