package com.bfoxnet.pocketdev

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import com.bfoxnet.pocketdev.databinding.ActivitySettingsBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlin.math.roundToInt

/**
 * Multi-page Settings experience for PocketDev:
 * - Page 0 (`PAGE_HOME`): Clean category navigation hub (Appearance, Code Editor, Terminal,
 *   Linux Environment, Language Servers, System & About).
 * - Page 1 (`PAGE_APPEARANCE`): Live Code Preview, Color Theme, Editor Font, Font Size slider.
 * - Page 2 (`PAGE_EDITOR`): Auto-Save, Auto-Save Delay, Save All, Ligatures, Word Wrap,
 *   Sticky Scroll, Highlight Matching Words, Code Folding.
 * - Page 3 (`PAGE_TERMINAL`): Default Shell, Color Theme, Cursor Style, Haptic Feedback,
 *   Extra Keys Toolbar, Sticky Ctrl, Wake Lock, Keep Screen On, Terminal Properties.
 * - Page 4 (`PAGE_ENVIRONMENT`): System Architecture, Workspace Directory, Zsh & Plugins,
 *   Runtime & Backups, Language Servers, Open Terminal.
 * - Page 5 (`PAGE_SYSTEM`): Task Notifications, Diagnostics, Welcome Guide, About PocketDev.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings
    private var currentPage: Int = PAGE_HOME

    private val exportEnvBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
            if (uri != null) {
                binding.valueUbuntuSync.text = "Exporting…"
                Toast.makeText(this, "Exporting environment backup…", Toast.LENGTH_SHORT).show()
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
                        DiagLogger.log("Backup", "Export failed: ${e.message}", this, e)
                        false
                    }
                    runOnUiThread {
                        refreshSummary()
                        Toast.makeText(
                            this,
                            if (ok) "Backup exported" else "Backup failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            }
        }

    private val importEnvBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                binding.valueUbuntuSync.text = "Restoring…"
                Toast.makeText(this, "Restoring environment from backup…", Toast.LENGTH_SHORT).show()
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
                        DiagLogger.log("Restore", "Restore failed: ${e.message}", this, e)
                        false
                    }
                    runOnUiThread {
                        refreshSummary()
                        Toast.makeText(
                            this,
                            if (ok) "Environment restored" else "Restore failed",
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

        currentPage = savedInstanceState?.getInt(STATE_CURRENT_PAGE, PAGE_HOME)
            ?: intent?.getIntExtra(EXTRA_INITIAL_PAGE, PAGE_HOME)
            ?: PAGE_HOME

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage != PAGE_HOME) {
                    navigateToPage(PAGE_HOME)
                } else {
                    isEnabled = false
                    finish()
                }
            }
        })

        setupInsets()
        setupListeners()
        applyThemeChrome()
        refreshSummary()
        navigateToPage(currentPage)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_PAGE, currentPage)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        applyThemeChrome()
        refreshSummary()
    }

    private fun navigateToPage(page: Int) {
        currentPage = page
        binding.pageHome.visibility = if (page == PAGE_HOME) View.VISIBLE else View.GONE
        binding.pageAppearance.visibility = if (page == PAGE_APPEARANCE) View.VISIBLE else View.GONE
        binding.pageEditor.visibility = if (page == PAGE_EDITOR) View.VISIBLE else View.GONE
        binding.pageTerminal.visibility = if (page == PAGE_TERMINAL) View.VISIBLE else View.GONE
        binding.pageEnvironment.visibility = if (page == PAGE_ENVIRONMENT) View.VISIBLE else View.GONE
        binding.pageSystem.visibility = if (page == PAGE_SYSTEM) View.VISIBLE else View.GONE

        when (page) {
            PAGE_APPEARANCE -> {
                binding.toolbar.title = "Appearance"
                binding.toolbar.subtitle = "Theme, editor font, and text size"
            }
            PAGE_EDITOR -> {
                binding.toolbar.title = "Code Editor"
                binding.toolbar.subtitle = "Saving, formatting, and editor behavior"
            }
            PAGE_TERMINAL -> {
                binding.toolbar.title = "Terminal"
                binding.toolbar.subtitle = "Shell, color theme, cursor, and extra keys"
            }
            PAGE_ENVIRONMENT -> {
                binding.toolbar.title = "Linux Environment"
                binding.toolbar.subtitle = "Ubuntu 24.04 LTS, workspace, and backups"
            }
            PAGE_SYSTEM -> {
                binding.toolbar.title = "System & About"
                binding.toolbar.subtitle = "Diagnostics, notifications, and version info"
            }
            else -> {
                binding.toolbar.title = getString(R.string.settings_title)
                binding.toolbar.subtitle = null
            }
        }
        binding.settingsScroll.scrollTo(0, 0)
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
        val accent = ModernUiHelper.PRIMARY_COLOR
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

        // Style Live Preview Card
        binding.cardLivePreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(surface)
            setStroke((1.5f * density).toInt().coerceAtLeast(1), ModernUiHelper.withAlpha(accent, 0x77))
        }
        binding.previewBadge.setTextColor(accent)
        binding.previewCodeText.setTextColor(fg)

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
            binding.cardCategories,
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
                cornerRadius = 14f * density
                setColor(chrome)
                setStroke((1 * density).toInt().coerceAtLeast(1), subtle and 0x33FFFFFF)
            }
        }

        val navIcons = listOf(
            binding.iconNavAppearance,
            binding.iconNavEditor,
            binding.iconNavTerminal,
            binding.iconNavEnvironment,
            binding.iconNavLsp,
            binding.iconNavSystem
        )
        navIcons.forEach { it.imageTintList = ColorStateList.valueOf(accent) }

        val navChevrons = listOf(
            binding.chevNavAppearance,
            binding.chevNavEditor,
            binding.chevNavTerminal,
            binding.chevNavEnvironment,
            binding.chevNavLsp,
            binding.chevNavSystem
        )
        navChevrons.forEach { it.imageTintList = ColorStateList.valueOf(subtle) }

        val titles = listOf(
            binding.navTitleAppearance,
            binding.navTitleEditor,
            binding.navTitleTerminal,
            binding.navTitleEnvironment,
            binding.navTitleLsp,
            binding.navTitleSystem,
            binding.titleTheme,
            binding.titleFont,
            binding.titleFontSize,
            binding.titleAutoSaveDelay,
            binding.titleDefaultShell,
            binding.titleTerminalStyle,
            binding.titleTerminalCursor,
            binding.titleHapticFeedback,
            binding.titleTermuxProps,
            binding.titleUbuntuArch,
            binding.titleUbuntuProjectsDir,
            binding.titleZshSetup,
            binding.titleUbuntuSync,
            binding.titleLsp,
            binding.titleTerminal,
            binding.titleDiag,
            binding.titleOnboarding,
            binding.titleAbout
        )
        titles.forEach { it.setTextColor(fg) }

        val subtitles = listOf(
            binding.navSubAppearance,
            binding.navSubEditor,
            binding.navSubTerminal,
            binding.navSubEnvironment,
            binding.navSubLsp,
            binding.navSubSystem,
            binding.subTheme,
            binding.subFont,
            binding.subFontSize,
            binding.subAutoSaveDelay,
            binding.subDefaultShell,
            binding.subTerminalStyle,
            binding.subTerminalCursor,
            binding.subHapticFeedback,
            binding.subTermuxProps,
            binding.subUbuntuArch,
            binding.subUbuntuProjectsDir,
            binding.subZshSetup,
            binding.subUbuntuSync,
            binding.subLsp,
            binding.subTerminal,
            binding.subDiag,
            binding.subOnboarding,
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
            binding.valueHapticFeedback,
            binding.valueTermuxProps,
            binding.valueUbuntuArch,
            binding.valueUbuntuProjectsDir,
            binding.valueZshSetup,
            binding.valueUbuntuSync,
            binding.valueLsp,
            binding.valueTerminal,
            binding.valueDiag,
            binding.valueOnboarding,
            binding.valueAbout
        )
        values.forEach { it.setTextColor(accent) }

        binding.sliderFontSize.thumbTintList = ColorStateList.valueOf(accent)
        binding.sliderFontSize.trackActiveTintList = ColorStateList.valueOf(accent)
        binding.sliderFontSize.trackInactiveTintList = ColorStateList.valueOf(subtle and 0x44FFFFFF)

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
            binding.switchStickyScroll,
            binding.switchWordMatch,
            binding.switchCodeFolding,
            binding.switchTerminalToolbar,
            binding.switchTerminalStickyCtrl,
            binding.switchTerminalAutoWakelock,
            binding.switchTerminalKeepScreen,
            binding.switchNotifications
        ).forEach { sw ->
            sw.setTextColor(fg)
            sw.typeface = ModernUiHelper.appFontSemiBold(this)
            sw.thumbTintList = switchThumbStates
            sw.trackTintList = switchTrackStates
        }

        updateLivePreviewCard()
    }

    private fun updateLivePreviewCard() {
        val font = Settings.fontById(settings.fontFamily)
        val tf = runCatching { ResourcesCompat.getFont(this, font.resId) }.getOrNull() ?: Typeface.MONOSPACE
        binding.previewCodeText.typeface = tf
        binding.previewCodeText.textSize = settings.fontSizeSp.toFloat()
        binding.previewCodeText.fontFeatureSettings =
            if (settings.fontLigatures) "\"liga\" 1, \"calt\" 1" else "\"liga\" 0, \"calt\" 0"
        binding.previewBadge.text =
            "LIVE PREVIEW • ${themeLabel(settings.editorTheme).uppercase()} • ${font.label.uppercase()} (${settings.fontSizeSp} SP)"
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener {
            if (currentPage != PAGE_HOME) {
                navigateToPage(PAGE_HOME)
            } else {
                finish()
            }
        }

        // Category Hub Navigation
        binding.navAppearance.setOnClickListener { navigateToPage(PAGE_APPEARANCE) }
        binding.navEditor.setOnClickListener { navigateToPage(PAGE_EDITOR) }
        binding.navTerminal.setOnClickListener { navigateToPage(PAGE_TERMINAL) }
        binding.navEnvironment.setOnClickListener { navigateToPage(PAGE_ENVIRONMENT) }
        binding.navLsp.setOnClickListener { startActivity(Intent(this, LspActivity::class.java)) }
        binding.navSystem.setOnClickListener { navigateToPage(PAGE_SYSTEM) }

        // Page 1: Appearance
        binding.rowTheme.setOnClickListener { showThemePicker() }
        binding.rowFont.setOnClickListener { showFontPicker() }
        binding.rowFontSize.setOnClickListener { showFontSizePicker() }
        binding.sliderFontSize.value = settings.fontSizeSp.coerceIn(10, 24).toFloat()
        binding.sliderFontSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.fontSizeSp = value.roundToInt().coerceIn(10, 24)
                binding.valueFontSize.text = getString(R.string.font_size_value, settings.fontSizeSp)
                updateLivePreviewCard()
                refreshCategorySummaries()
            }
        }

        // Page 2: Code Editor
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
            updateLivePreviewCard()
        }

        binding.switchWrap.isChecked = settings.wordWrap
        binding.switchWrap.setOnCheckedChangeListener { _, checked ->
            settings.wordWrap = checked
        }

        binding.switchStickyScroll.isChecked = settings.stickyScrollEnabled
        binding.switchStickyScroll.setOnCheckedChangeListener { _, checked ->
            settings.stickyScrollEnabled = checked
        }

        binding.switchWordMatch.isChecked = settings.wordMatchHighlightEnabled
        binding.switchWordMatch.setOnCheckedChangeListener { _, checked ->
            settings.wordMatchHighlightEnabled = checked
        }

        binding.switchCodeFolding.isChecked = settings.codeFoldingEnabled
        binding.switchCodeFolding.setOnCheckedChangeListener { _, checked ->
            settings.codeFoldingEnabled = checked
        }

        // Page 3: Terminal
        binding.rowDefaultShell.setOnClickListener { showDefaultShellPicker() }
        binding.rowTerminalStyle.setOnClickListener { showTerminalStylePicker() }
        binding.rowTerminalCursor.setOnClickListener { showTerminalCursorPicker() }
        binding.rowHapticFeedback.setOnClickListener { showHapticFeedbackPicker() }

        binding.switchTerminalToolbar.isChecked = settings.terminalToolbarVisible
        binding.switchTerminalToolbar.setOnCheckedChangeListener { _, checked ->
            settings.terminalToolbarVisible = checked
        }

        binding.switchTerminalStickyCtrl.isChecked = settings.terminalStickyCtrl
        binding.switchTerminalStickyCtrl.setOnCheckedChangeListener { _, checked ->
            settings.terminalStickyCtrl = checked
        }

        binding.switchTerminalAutoWakelock.isChecked = settings.terminalAutoWakeLock
        binding.switchTerminalAutoWakelock.setOnCheckedChangeListener { _, checked ->
            settings.terminalAutoWakeLock = checked
        }

        binding.switchTerminalKeepScreen.isChecked = settings.terminalKeepScreenOn
        binding.switchTerminalKeepScreen.setOnCheckedChangeListener { _, checked ->
            settings.terminalKeepScreenOn = checked
        }

        binding.rowTermuxProps.setOnClickListener { showEditTermuxPropertiesDialog() }

        // Page 4: Linux Environment
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
                    "Opening terminal to complete environment setup…",
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
            binding.valueZshSetup.text = "Installing…"
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
                        Toast.makeText(this, "Zsh and plugins installed", Toast.LENGTH_SHORT).show()
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

        // Page 5: System & About
        binding.switchNotifications.isChecked = settings.downloadNotificationsEnabled
        binding.switchNotifications.setOnCheckedChangeListener { _, checked ->
            settings.downloadNotificationsEnabled = checked
        }

        binding.rowDiag.setOnClickListener {
            startActivity(Intent(this, DiagActivity::class.java))
        }
        binding.rowOnboarding.setOnClickListener {
            OnboardingAndHapticsHelper.showOnboardingDialog(
                context = this,
                onBootstrapNow = { runBootstrapFromSettings(forceReinstall = false) },
                onOpenLspManager = { startActivity(Intent(this, LspActivity::class.java)) },
                onThemeChanged = {
                    applyThemeChrome()
                    refreshSummary()
                }
            )
        }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun refreshCategorySummaries() {
        val themeName = themeLabel(settings.editorTheme)
        val fontName = Settings.fontById(settings.fontFamily).label
        binding.navSubAppearance.text = "$themeName • $fontName (${settings.fontSizeSp} sp)"

        val autoSaveText = if (settings.autoSaveEnabled) "Auto-save on" else "Auto-save off"
        val wrapText = if (settings.wordWrap) "Word wrap on" else "Word wrap off"
        binding.navSubEditor.text = "$autoSaveText • $wrapText • Ligatures & folding"

        val shellName = if (settings.defaultShell == Settings.SHELL_BASH) "Bash" else "Zsh"
        val styleIdx = settings.terminalStyleIndex.coerceIn(0, TerminalActivity.TERMUX_STYLES.lastIndex)
        val termStyle = TerminalActivity.TERMUX_STYLES[styleIdx].name
        binding.navSubTerminal.text = "$shellName • $termStyle"

        val isUbuntuReady = UbuntuProotManager.isUbuntuInstalled(this)
        val envStatus = if (isUbuntuReady) "Ubuntu 24.04 Ready" else "Setup required"
        binding.navSubEnvironment.text = "$envStatus • /root/projects"

        val installedCount = LspManager.AVAILABLE_SERVERS.count { LspManager.isInstalled(this, it.id) }
        binding.navSubLsp.text = "$installedCount of ${LspManager.AVAILABLE_SERVERS.size} installed"
    }

    private fun refreshSummary() {
        refreshCategorySummaries()

        binding.valueTheme.text = themeLabel(settings.editorTheme)
        binding.valueFont.text = Settings.fontById(settings.fontFamily).label
        binding.valueFontSize.text = getString(R.string.font_size_value, settings.fontSizeSp)
        binding.sliderFontSize.value = settings.fontSizeSp.coerceIn(10, 24).toFloat()

        binding.switchAutoSave.isChecked = settings.autoSaveEnabled
        binding.rowAutoSaveDelay.alpha = if (settings.autoSaveEnabled) 1f else 0.5f
        binding.valueAutoSaveDelay.text = when (settings.autoSaveDelayMs) {
            800 -> "0.8s"
            1500 -> "1.5s"
            3000 -> "3.0s"
            5000 -> "5.0s"
            else -> "${settings.autoSaveDelayMs} ms"
        }
        binding.switchSaveAllOnSave.isChecked = settings.saveAllOnSavePress
        binding.switchLigatures.isChecked = settings.fontLigatures
        binding.switchWrap.isChecked = settings.wordWrap
        binding.switchStickyScroll.isChecked = settings.stickyScrollEnabled
        binding.switchWordMatch.isChecked = settings.wordMatchHighlightEnabled
        binding.switchCodeFolding.isChecked = settings.codeFoldingEnabled

        binding.valueDefaultShell.text =
            if (settings.defaultShell == Settings.SHELL_BASH) "Bash" else "Zsh"

        val styleIdx = settings.terminalStyleIndex.coerceIn(0, TerminalActivity.TERMUX_STYLES.lastIndex)
        binding.valueTerminalStyle.text = TerminalActivity.TERMUX_STYLES[styleIdx].name

        val termProps = AlpineLinuxEnvironment.loadTermuxProperties(this)
        val cursorIdx = if (settings.terminalCursorStyle in 0..2) settings.terminalCursorStyle else termProps.cursorStyle
        binding.valueTerminalCursor.text = when (cursorIdx) {
            1 -> "Underline"
            2 -> "Bar"
            else -> "Block"
        }

        val hapticLabels = listOf("Off", "Light", "Medium", "Strong")
        binding.valueHapticFeedback.text = hapticLabels.getOrElse(settings.hapticFeedbackLevel) { "Light" }

        binding.switchTerminalToolbar.isChecked = settings.terminalToolbarVisible
        binding.switchTerminalStickyCtrl.isChecked = settings.terminalStickyCtrl
        binding.switchTerminalAutoWakelock.isChecked = settings.terminalAutoWakeLock
        binding.switchTerminalKeepScreen.isChecked = settings.terminalKeepScreenOn
        binding.switchNotifications.isChecked = settings.downloadNotificationsEnabled

        binding.valueUbuntuArch.text = "${UbuntuProotManager.detectHostAbi()} (${UbuntuProotManager.ubuntuArch()})"

        val projectsCount = ProjectManager.listAppDirProjects(this).size
        val activeProjName = ProjectManager.activeProjectName(this)
        binding.valueUbuntuProjectsDir.text = "$projectsCount projects"
        binding.subUbuntuProjectsDir.text = "Active: $activeProjName (/root/projects)"

        val isUbuntuReady = UbuntuProotManager.isUbuntuInstalled(this)
        val isZshBinReady = UbuntuProotManager.isZshBinaryInstalled(this)
        val arePluginsReady = UbuntuProotManager.areZshPluginsProvisioned(this)

        binding.valueZshSetup.text = when {
            isZshBinReady && arePluginsReady -> "Active"
            arePluginsReady -> "Ready"
            else -> "Install"
        }
        binding.subZshSetup.text = when {
            isZshBinReady && arePluginsReady ->
                "Zsh with autosuggestions, syntax highlighting, and completions"
            arePluginsReady ->
                "Plugins configured — tap to install Zsh binary"
            else ->
                "Tap to configure Zsh and shell plugins"
        }

        binding.valueUbuntuSync.text = if (isUbuntuReady) "Ready" else "Install"
        binding.subUbuntuSync.text = if (isUbuntuReady) {
            "Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} (${UbuntuProotManager.ubuntuArch()}) installed"
        } else {
            "Tap to install Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} runtime"
        }

        val installedCount = LspManager.AVAILABLE_SERVERS.count { LspManager.isInstalled(this, it.id) }
        binding.valueLsp.text = "$installedCount/${LspManager.AVAILABLE_SERVERS.size}"
        val activeSessions = TerminalActivity.persistentSessions.size
        binding.valueTerminal.text = if (activeSessions > 0) "$activeSessions active" else "Open"

        updateLivePreviewCard()
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
            subtitle = "Select editor and interface color theme",
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
            subtitle = "Monospace typeface for code editor and terminal",
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
            subtitle = "Code editor text size",
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
            "0.8 seconds (Fast)",
            "1.5 seconds (Default)",
            "3.0 seconds (Relaxed)",
            "5.0 seconds"
        )
        val checked = delays.indexOf(settings.autoSaveDelayMs).let { if (it >= 0) it else 1 }
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Auto-Save Delay",
            subtitle = "Save modified files after typing pauses",
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
            "Zsh (with syntax highlighting & autosuggestions)",
            "Bash (/bin/bash)"
        )
        val checked = if (settings.defaultShell == Settings.SHELL_BASH) 1 else 0
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Default Shell",
            subtitle = "Interactive shell for terminal sessions",
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
            subtitle = "Choose terminal color palette",
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
            title = "Cursor Style",
            subtitle = "Select terminal cursor shape",
            items = styles,
            checkedIndex = current
        ) { which ->
            settings.terminalCursorStyle = which
            refreshSummary()
        }
    }

    private fun showHapticFeedbackPicker() {
        val levels = listOf(
            "Off",
            "Light (Default)",
            "Medium",
            "Strong"
        )
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Haptic Feedback",
            subtitle = "Tactile vibration intensity",
            items = levels,
            checkedIndex = settings.hapticFeedbackLevel.coerceIn(0, 3)
        ) { which ->
            settings.hapticFeedbackLevel = which
            OnboardingAndHapticsHelper.performExtraKeyHaptic(this, binding.rowHapticFeedback)
            refreshSummary()
        }
    }

    private fun showUbuntuArchInfo() {
        val hostAbi = UbuntuProotManager.detectHostAbi()
        val uArch = UbuntuProotManager.ubuntuArch()
        val labels = listOf(
            "$hostAbi / Ubuntu $uArch (Native)"
        )
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "System Architecture",
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
            "Install or Verify Environment",
            "Export Backup (.tar.gz)",
            "Restore from Backup (.tar.gz)",
            "Reinstall Environment (Keep Projects)"
        )
        ModernUiHelper.showModernSingleChoiceDialog(
            context = this,
            title = "Runtime & Backups",
            subtitle = "Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} LTS",
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
                        title = "Reinstall Environment?",
                        message = "This will reinstall a clean Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} environment while preserving all projects in /root/projects.",
                        confirmText = "Reinstall",
                        isDestructive = true
                    ) {
                        runBootstrapFromSettings(forceReinstall = true)
                    }
                }
            }
        }
    }

    private fun runBootstrapFromSettings(forceReinstall: Boolean) {
        binding.valueUbuntuSync.text = "Working…"
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
                    if (ok) "Environment ready"
                    else "Setup failed — check network connection",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun showEditTermuxPropertiesDialog() {
        val propFile = AlpineLinuxEnvironment.termuxPropertiesFile(this)
        val palette = ModernUiHelper.resolvePalette(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val rootCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(14))
        }

        val titleView = MaterialTextView(this).apply {
            text = "Terminal Properties"
            textSize = 15.5f
            typeface = ModernUiHelper.appFontBold(this@SettingsActivity)
            setTextColor(palette.fgColor)
        }
        rootCard.addView(titleView)

        val subView = MaterialTextView(this).apply {
            text = "Configure extra keys layout, margins, cursor blink rate, and bell behavior"
            textSize = 11.5f
            typeface = ModernUiHelper.appFontRegular(this@SettingsActivity)
            setTextColor(palette.subtleColor)
            setPadding(0, dp(2), 0, dp(10))
        }
        rootCard.addView(subView)

        val input = EditText(this).apply {
            setText(runCatching { propFile.readText() }.getOrDefault(""))
            setTextColor(palette.fgColor)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            minLines = 8
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(palette.surfaceBg)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.borderColor)
            }
        }
        rootCard.addView(input)

        val sheetDialog = ModernUiHelper.showModernCustomBottomSheet(this, rootCard, palette)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val openInEditorBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Open in Editor"
            textSize = 12f
            isAllCaps = false
            typeface = ModernUiHelper.appFontSemiBold(this@SettingsActivity)
            setTextColor(palette.accentColor)
            setOnClickListener {
                runCatching { propFile.writeText(input.text?.toString() ?: "") }
                sheetDialog.dismiss()
                startActivity(
                    Intent(this@SettingsActivity, MainActivity::class.java).apply {
                        putExtra(ContinueCodingWidgetProvider.EXTRA_WIDGET_FILE_PATH, propFile.absolutePath)
                    }
                )
                finish()
            }
        }
        btnRow.addView(openInEditorBtn)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        btnRow.addView(spacer)

        val cancelBtn = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            textSize = 12f
            isAllCaps = false
            typeface = ModernUiHelper.appFontSemiBold(this@SettingsActivity)
            setTextColor(palette.subtleColor)
            setOnClickListener { sheetDialog.dismiss() }
        }
        btnRow.addView(cancelBtn)

        val saveBtn = MaterialButton(this).apply {
            text = "Save"
            textSize = 12f
            isAllCaps = false
            typeface = ModernUiHelper.appFontBold(this@SettingsActivity)
            cornerRadius = dp(10)
            backgroundTintList = ColorStateList.valueOf(palette.accentColor)
            setTextColor(Color.WHITE)
            setOnClickListener {
                runCatching { propFile.writeText(input.text?.toString() ?: "") }
                sheetDialog.dismiss()
                refreshSummary()
                Toast.makeText(this@SettingsActivity, "Saved terminal properties", Toast.LENGTH_SHORT).show()
            }
        }
        btnRow.addView(saveBtn)
        rootCard.addView(btnRow)
    }

    companion object {
        private const val STATE_CURRENT_PAGE = "state_current_page"
        const val EXTRA_INITIAL_PAGE = "extra_initial_page"

        const val PAGE_HOME = 0
        const val PAGE_APPEARANCE = 1
        const val PAGE_EDITOR = 2
        const val PAGE_TERMINAL = 3
        const val PAGE_ENVIRONMENT = 4
        const val PAGE_SYSTEM = 5
    }
}
