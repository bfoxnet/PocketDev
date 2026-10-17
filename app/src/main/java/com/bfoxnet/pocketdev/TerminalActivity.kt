package com.bfoxnet.pocketdev

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import com.bfoxnet.pocketdev.databinding.ActivityTerminalBinding
import com.google.android.material.textview.MaterialTextView
import java.io.File
import kotlin.math.roundToInt

/**
 * Termux (`https://github.com/termux/termux-app` `TermuxActivity`) implementation
 * powered by a real Ubuntu 24.04.5 LTS (`noble`) rootfs + native Android `PRoot` (`UbuntuProotManager`).
 *
 * Upgraded UX:
 * - Includes a compact top session strip (`@+id/terminal_top_bar`) with a 1-tap `← Editor` return chip,
 *   horizontal clickable session tabs (`[1] zsh`), `+ New Session`, and `☰ Drawer` button.
 * - Includes an explicit `[Aa]` button (`@+id/btn_toolbar_drawer`) on the helper keyboard bar to toggle
 *   between the 2-row Extra Keys grid and the full-line Command Text Input bar.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var settings: Settings
    private lateinit var vmManager: QemuVmManager
    private lateinit var termuxProps: AlpineLinuxEnvironment.TermuxProperties

    private var toolbarVisible: Boolean = true
    private var keepScreenOnEnabled: Boolean = false
    private var activeStyleIndex: Int = 0
    private var isTextInputPageActive: Boolean = false
    private var lastToast: Toast? = null

    private var ctrlKeyView: MaterialTextView? = null
    private var altKeyView: MaterialTextView? = null
    private var fnKeyView: MaterialTextView? = null
    private var execWakeLock: PowerManager.WakeLock? = null

    data class TermuxStylePalette(
        val name: String,
        val bgHex: String,
        val cursorHex: String,
        val toolbarBgHex: String,
        val keyBgHex: String,
        val keyFgHex: String,
        val accentHex: String,
        val isDark: Boolean = true
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)
        vmManager = QemuVmManager(this)

        AlpineLinuxEnvironment.provisionEnvironment(this)
        termuxProps = AlpineLinuxEnvironment.loadTermuxProperties(this)

        // Restore persisted terminal states from Settings
        activeStyleIndex = settings.terminalStyleIndex.coerceIn(0, TERMUX_STYLES.lastIndex)
        toolbarVisible = settings.terminalToolbarVisible
        keepScreenOnEnabled = settings.terminalKeepScreenOn
        binding.terminalView.keepScreenOn = keepScreenOnEnabled
        binding.terminalView.setTerminalFontSizeSp(settings.terminalFontSizeSp)
        binding.extraKeysContainer.visibility = if (toolbarVisible) View.VISIBLE else View.GONE

        setupInsets()
        setupDrawerAndToolbarControls()
        setupTerminalViewCallbacks()
        reloadTermuxPropertiesAndStyle()

        // Restore existing running sessions or create initial session in the requested project directory
        val autorunCmd = intent?.getStringExtra(EXTRA_AUTORUN_CMD)
            ?: intent?.getStringExtra(EXTRA_INITIAL_COMMAND)
        val requestedWorkDir = intent?.getStringExtra(EXTRA_WORKING_DIR)
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.isDirectory }
        intent?.removeExtra(EXTRA_AUTORUN_CMD)
        intent?.removeExtra(EXTRA_INITIAL_COMMAND)
        intent?.removeExtra(EXTRA_WORKING_DIR)

        if (persistentSessions.isEmpty()) {
            addNewSession(
                isFailsafe = false,
                sessionName = requestedWorkDir?.name,
                autorunCommand = autorunCmd,
                initialWorkingDir = requestedWorkDir
            )
        } else {
            // Rebind callbacks for all alive sessions to this Activity instance
            persistentSessions.forEach { bindSessionCallbacks(it) }
            val targetIdx = persistentActiveSessionIndex.coerceIn(0, persistentSessions.lastIndex)
            switchToSession(targetIdx)
            if (requestedWorkDir != null) {
                activeSession()?.setWorkingDirectory(requestedWorkDir)
            }
            if (!autorunCmd.isNullOrBlank()) {
                val active = activeSession()
                if (active != null && !active.isExecutingCommand) {
                    binding.terminalView.postDelayed({
                        active.executeCommandDirectly(autorunCmd)
                    }, 120L)
                } else {
                    addNewSession(
                        isFailsafe = false,
                        sessionName = requestedWorkDir?.name,
                        autorunCommand = autorunCmd,
                        initialWorkingDir = requestedWorkDir
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activeStyleIndex = settings.terminalStyleIndex.coerceIn(0, TERMUX_STYLES.lastIndex)
        toolbarVisible = settings.terminalToolbarVisible
        keepScreenOnEnabled = settings.terminalKeepScreenOn
        binding.terminalView.keepScreenOn = keepScreenOnEnabled
        binding.extraKeysContainer.visibility = if (toolbarVisible) View.VISIBLE else View.GONE
        reloadTermuxPropertiesAndStyle()
        activeSession()?.syncWorkspaceDirectory(applicationContext)
    }

    override fun onDestroy() {
        // Detach view reference without destroying running sessions!
        binding.terminalView.session = null
        persistentSessions.forEach { s ->
            s.onTitleChanged = null
            s.onBell = null
            s.onCopyToClipboard = null
            s.onTermuxIpcEvent = null
            s.onSessionFinished = null
            s.onExecutionStateChanged = null
        }
        releaseExecWakeLock()
        super.onDestroy()
    }

    private fun activeSession(): TerminalSession? =
        persistentSessions.getOrNull(persistentActiveSessionIndex)

    private fun updateDrawerHeaderSubtitle() {
        binding.drawerTitle.text = "Ubuntu 24.04 • Zsh (robbyrussell) + PRoot"
        val status = if (UbuntuProotManager.isUbuntuInstalled(this)) "Zsh Ready ✓" else "Run ubuntu-bootstrap"
        binding.drawerSubtitle.text = "Ubuntu ${UbuntuProotManager.ubuntuArch()} (${UbuntuProotManager.detectHostAbi()}) • $status"
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.terminalDrawerLayout) { _, insets ->
            val sysBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val imeBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val bottomPadding = maxOf(sysBars.bottom, imeBars.bottom)

            binding.terminalRoot.setPadding(
                sysBars.left,
                sysBars.top,
                sysBars.right,
                bottomPadding
            )
            binding.terminalSessionDrawer.setPadding(
                sysBars.left,
                sysBars.top,
                0,
                bottomPadding
            )
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupDrawerAndToolbarControls() {
        TooltipCompat.setTooltipText(binding.btnTopEditorBack, "Return to Code Editor")
        TooltipCompat.setTooltipText(binding.btnTopNewSession, "New Terminal Session (Long-press: Named/Failsafe)")
        TooltipCompat.setTooltipText(binding.btnTopDrawer, "Open Session Drawer")
        TooltipCompat.setTooltipText(binding.btnToolbarDrawer, "Switch Between Extra Keys & Command Input Bar")
        TooltipCompat.setTooltipText(binding.btnToolbarBack, "Return to Code Editor")

        binding.btnTopEditorBack.setOnClickListener {
            finish()
        }
        binding.btnTopNewSession.setOnClickListener {
            addNewSession(isFailsafe = false, sessionName = null, autorunCommand = null)
        }
        binding.btnTopNewSession.setOnLongClickListener {
            showCreateNamedSessionDialog()
            true
        }
        binding.btnTopDrawer.setOnClickListener {
            if (binding.terminalDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.terminalDrawerLayout.closeDrawer(GravityCompat.START)
            } else {
                binding.terminalDrawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // Bottom helper bar top-left button toggles between Extra Keys grid (Page 0) and Command Input bar (Page 1)
        binding.btnToolbarDrawer.setOnClickListener { v ->
            OnboardingAndHapticsHelper.performExtraKeyHaptic(this, v)
            showExtraKeysPage(if (isTextInputPageActive) 0 else 1)
        }
        binding.btnToolbarDrawer.setOnLongClickListener {
            if (binding.terminalDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.terminalDrawerLayout.closeDrawer(GravityCompat.START)
            } else {
                binding.terminalDrawerLayout.openDrawer(GravityCompat.START)
            }
            true
        }
        binding.btnToolbarBack.setOnClickListener {
            finish()
        }

        binding.btnTerminalBack.setOnClickListener {
            finish()
        }
        binding.btnDrawerSettings.setOnClickListener {
            binding.terminalDrawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnTerminalSelectAll.setOnClickListener {
            binding.terminalView.selectAllText()
        }
        binding.btnTerminalCopySelection.setOnClickListener {
            binding.terminalView.copySelectedText()
        }
        binding.btnTerminalCancelSelection.setOnClickListener {
            binding.terminalView.clearSelection()
        }

        binding.toggleKeyboardButton.setOnClickListener {
            binding.terminalDrawerLayout.closeDrawer(GravityCompat.START)
            toggleSoftKeyboard()
        }
        binding.toggleKeyboardButton.setOnLongClickListener {
            toggleTerminalToolbar()
            true
        }

        binding.newSessionButton.setOnClickListener {
            binding.terminalDrawerLayout.closeDrawer(GravityCompat.START)
            addNewSession(isFailsafe = false, sessionName = null, autorunCommand = null)
        }
        binding.newSessionButton.setOnLongClickListener {
            showCreateNamedSessionDialog()
            true
        }

        binding.btnSwitchToExtraKeys.setOnClickListener {
            showExtraKeysPage(0)
        }
        binding.terminalToolbarTextInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val text = v.text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    activeSession()?.pasteText(text)
                    activeSession()?.sendEnter()
                    v.text = null
                } else {
                    activeSession()?.sendEnter()
                }
                true
            } else {
                false
            }
        }
    }

    private fun setupTerminalViewCallbacks() {
        binding.terminalView.onModifierReset = {
            updateModifierButtonHighlights()
        }
        binding.terminalView.onLongPressContextMenu = {
            showTermuxContextMenu()
        }
        binding.terminalView.onRequestOpenDrawer = {
            binding.terminalDrawerLayout.openDrawer(GravityCompat.START)
        }
        binding.terminalView.onSelectionModeChanged = { active, selectedText ->
            binding.terminalSelectionBar.visibility = if (active) View.VISIBLE else View.GONE
            if (active) {
                val count = selectedText.length
                binding.terminalSelectionStatus.text =
                    if (count > 0) "$count chars selected" else "Drag handles to select text"
            }
        }
        binding.terminalView.onFontSizeChanged = { newSizeSp ->
            settings.terminalFontSizeSp = newSizeSp
        }
    }

    private fun reloadTermuxPropertiesAndStyle() {
        termuxProps = AlpineLinuxEnvironment.loadTermuxProperties(this)
        if (termuxProps.fullscreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        val density = resources.displayMetrics.density
        val padH = (termuxProps.marginHorizontalDp * density).toInt()
        val padV = (termuxProps.marginVerticalDp * density).toInt()
        binding.terminalView.setPadding(padH, padV, padH, padV)

        val savedCursor = settings.terminalCursorStyle
        binding.terminalView.cursorStyle = if (savedCursor in 0..2) savedCursor else termuxProps.cursorStyle
        binding.terminalView.cursorBlinkRateMs = termuxProps.cursorBlinkRate.toLong()

        val font = Settings.fontById(settings.fontFamily)
        ResourcesCompat.getFont(this, font.resId)?.let {
            binding.terminalView.setTerminalTypeface(it)
        }

        updateDrawerHeaderSubtitle()
        setupExtraKeysBar()
        applyTermuxStylePalette()
    }

    private fun setupExtraKeysBar() {
        val matrix = termuxProps.extraKeys
        val row1 = matrix.getOrNull(0) ?: listOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP")
        val row2 = matrix.getOrNull(1) ?: listOf("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN")

        populateExtraKeyRow(binding.extraKeysRow1, row1)
        populateExtraKeyRow(binding.extraKeysRow2, row2)
    }

    private fun populateExtraKeyRow(rowLayout: LinearLayout, keys: List<String>) {
        rowLayout.removeAllViews()
        val style = TERMUX_STYLES[activeStyleIndex.coerceIn(0, TERMUX_STYLES.lastIndex)]
        val fgColor = Color.parseColor(style.keyFgHex)
        val keyBg = Color.parseColor(style.keyBgHex)
        val density = resources.displayMetrics.density

        for (rawKey in keys) {
            val displayLabel = when (rawKey.uppercase()) {
                "UP" -> "↑"
                "DOWN" -> "↓"
                "LEFT" -> "←"
                "RIGHT" -> "→"
                else -> rawKey
            }
            val btn = MaterialTextView(this).apply {
                text = displayLabel
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(fgColor)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4f * density
                    setColor(keyBg)
                }
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    val m = (1.5f * density).toInt().coerceAtLeast(1)
                    setMargins(m, m, m, m)
                }
                setOnClickListener { v ->
                    OnboardingAndHapticsHelper.performExtraKeyHaptic(this@TerminalActivity, v)
                    handleExtraKeyPress(rawKey.uppercase())
                }
                setOnLongClickListener { v ->
                    OnboardingAndHapticsHelper.performExtraKeyHaptic(this@TerminalActivity, v)
                    handleExtraKeyLongPress(rawKey.uppercase())
                    true
                }
            }
            when (rawKey.uppercase()) {
                "CTRL" -> ctrlKeyView = btn
                "ALT" -> altKeyView = btn
                "FN" -> fnKeyView = btn
            }
            rowLayout.addView(btn)
        }
    }

    private fun handleExtraKeyPress(key: String) {
        val s = activeSession() ?: return
        when (key) {
            "ESC" -> {
                if (binding.terminalView.ctrlDown || binding.terminalView.ctrlStickyLocked) {
                    binding.terminalView.ctrlDown = false
                    binding.terminalView.ctrlStickyLocked = false
                    updateModifierButtonHighlights()
                } else if (binding.terminalView.isSelectingText) {
                    binding.terminalView.clearSelection()
                } else {
                    s.sendInput("\u001b")
                }
            }
            "TAB" -> s.sendTab()
            "CTRL" -> {
                val nextState = !binding.terminalView.ctrlDown
                binding.terminalView.ctrlDown = nextState
                binding.terminalView.ctrlStickyLocked = nextState && settings.terminalStickyCtrl
                updateModifierButtonHighlights()
            }
            "ALT" -> {
                binding.terminalView.altDown = !binding.terminalView.altDown
                updateModifierButtonHighlights()
            }
            "FN" -> {
                binding.terminalView.fnDown = !binding.terminalView.fnDown
                updateModifierButtonHighlights()
            }
            "UP", "↑" -> s.navigateHistory(-1)
            "DOWN", "↓" -> s.navigateHistory(1)
            "LEFT", "←" -> s.moveCursorHorizontal(-1)
            "RIGHT", "→" -> s.moveCursorRightOrAcceptSuggestion()
            "HOME" -> s.moveCursorToHome()
            "END" -> s.moveCursorToEndOrAcceptSuggestion()
            "PGUP" -> s.navigateHistory(-5)
            "PGDN" -> s.navigateHistory(5)
            "ENTER" -> s.sendEnter()
            "DEL", "BKSP" -> s.sendBackspace()
            "DRAWER" -> binding.terminalDrawerLayout.openDrawer(GravityCompat.START)
            "KEYBOARD" -> toggleSoftKeyboard()
            "PASTE" -> binding.terminalView.pasteFromClipboard()
            "COPY" -> binding.terminalView.copySelectedText()
            else -> s.sendInput(key.lowercase())
        }
        binding.terminalView.requestFocus()
    }

    private fun handleExtraKeyLongPress(key: String) {
        val s = activeSession() ?: return
        when (key) {
            "/" -> s.sendInput("\\")
            "-" -> s.sendInput("|")
            ">" -> s.sendInput("<")
            "\"" -> s.sendInput("'")
            "ESC" -> showTermuxContextMenu()
            "TAB" -> showExtraKeysPage(1)
            "PGUP" -> binding.terminalView.copySelectedText()
            "PGDN" -> binding.terminalView.pasteFromClipboard()
            else -> showExtraKeysPage(1)
        }
    }

    private fun showExtraKeysPage(page: Int) {
        isTextInputPageActive = (page == 1)
        if (isTextInputPageActive) {
            binding.extraKeysGridPage.visibility = View.GONE
            binding.extraKeysTextInputPage.visibility = View.VISIBLE
            binding.terminalToolbarTextInput.requestFocus()
        } else {
            binding.extraKeysTextInputPage.visibility = View.GONE
            binding.extraKeysGridPage.visibility = View.VISIBLE
            binding.terminalView.requestFocus()
        }
    }

    private fun updateModifierButtonHighlights() {
        val style = TERMUX_STYLES[activeStyleIndex.coerceIn(0, TERMUX_STYLES.lastIndex)]
        val fg = Color.parseColor(style.keyFgHex)
        val activeColor = Color.parseColor(style.accentHex)
        val ctrlActive = binding.terminalView.ctrlDown
        val ctrlSticky = binding.terminalView.ctrlStickyLocked
        ctrlKeyView?.apply {
            text = if (ctrlActive && ctrlSticky) "CTRL●" else "CTRL"
            setTextColor(if (ctrlActive) activeColor else fg)
        }
        altKeyView?.setTextColor(if (binding.terminalView.altDown) activeColor else fg)
        fnKeyView?.setTextColor(if (binding.terminalView.fnDown) activeColor else fg)
    }

    private fun updateAutoWakeLockState() {
        val anyRunning = persistentSessions.any { it.isExecutingCommand }
        val shouldKeepAwake = keepScreenOnEnabled || (settings.terminalAutoWakeLock && anyRunning)
        binding.terminalView.keepScreenOn = shouldKeepAwake
        if (shouldKeepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (settings.terminalAutoWakeLock && anyRunning) {
            acquireExecWakeLock()
        } else {
            releaseExecWakeLock()
        }
    }

    private fun acquireExecWakeLock() {
        runCatching {
            if (execWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                execWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketDev::TerminalExecWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (execWakeLock?.isHeld == false) {
                execWakeLock?.acquire(30 * 60 * 1000L)
            }
        }
    }

    private fun releaseExecWakeLock() {
        runCatching {
            if (execWakeLock?.isHeld == true) {
                execWakeLock?.release()
            }
        }
    }

    private fun toggleSoftKeyboard() {
        binding.terminalView.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun toggleTerminalToolbar() {
        toolbarVisible = !toolbarVisible
        settings.terminalToolbarVisible = toolbarVisible
        binding.extraKeysContainer.visibility = if (toolbarVisible) View.VISIBLE else View.GONE
        showTermuxToast(if (toolbarVisible) "Enabling terminal toolbar" else "Disabling terminal toolbar")
    }

    private fun showTermuxToast(text: String, longDuration: Boolean = false) {
        if (text.isEmpty()) return
        lastToast?.cancel()
        lastToast = Toast.makeText(this, text, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP, 0, 48)
            show()
        }
    }

    // ---------------------------------------------------------------- Persistent Session Management & Top Tab Strip

    private fun bindSessionCallbacks(session: TerminalSession) {
        session.onTitleChanged = {
            runOnUiThread { renderSessionsDrawerList() }
        }
        session.onBell = {
            runOnUiThread { triggerTerminalBell() }
        }
        session.onCopyToClipboard = { text ->
            runOnUiThread {
                binding.terminalView.copyToClipboard("Termux OSC52", text)
            }
        }
        session.onTermuxIpcEvent = { action, arg ->
            runOnUiThread { handleTermuxIpcEvent(action, arg) }
        }
        session.onSessionFinished = {
            runOnUiThread { closeSession(session) }
        }
        session.onExecutionStateChanged = {
            runOnUiThread {
                updateAutoWakeLockState()
                renderSessionsDrawerList()
            }
        }
    }

    private fun addNewSession(
        isFailsafe: Boolean,
        sessionName: String?,
        autorunCommand: String?,
        initialWorkingDir: File? = null
    ) {
        if (persistentSessions.size >= MAX_SESSIONS) {
            showTermuxToast("Max terminal sessions reached ($MAX_SESSIONS)")
            return
        }
        val defaultTitle = if (isFailsafe) "sh" else "zsh"
        val cleanName = sessionName?.trim()?.takeIf { it.isNotEmpty() }
            ?: initialWorkingDir?.name
        val session = TerminalSession(
            context = applicationContext,
            title = defaultTitle,
            sessionName = cleanName,
            isFailsafe = isFailsafe,
            initialWorkingDir = initialWorkingDir
        )
        bindSessionCallbacks(session)

        persistentSessions.add(session)
        switchToSession(persistentSessions.lastIndex)

        if (!autorunCommand.isNullOrBlank()) {
            binding.terminalView.postDelayed({
                session.executeCommandDirectly(autorunCommand)
            }, 150L)
        } else {
            binding.terminalView.post {
                binding.terminalView.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(binding.terminalView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun switchToSession(index: Int) {
        if (index !in persistentSessions.indices) return
        persistentActiveSessionIndex = index
        val target = persistentSessions[index]
        binding.terminalView.session = target
        renderSessionsDrawerList()
        updateDrawerHeaderSubtitle()
    }

    private fun closeSession(session: TerminalSession) {
        val idx = persistentSessions.indexOf(session)
        if (idx < 0) return
        persistentSessions.removeAt(idx)
        if (persistentSessions.isEmpty()) {
            persistentActiveSessionIndex = -1
            finish()
            return
        }
        switchToSession(idx.coerceAtMost(persistentSessions.lastIndex))
    }

    private fun renderSessionsDrawerList() {
        val drawerList = binding.drawerSessionsList
        val topTabsContainer = binding.terminalSessionTabsContainer
        drawerList.removeAllViews()
        topTabsContainer.removeAllViews()

        val style = TERMUX_STYLES[activeStyleIndex.coerceIn(0, TERMUX_STYLES.lastIndex)]
        val accentColor = Color.parseColor(style.accentHex)
        val keyBgColor = Color.parseColor(style.keyBgHex)
        val keyFgColor = Color.parseColor(style.keyFgHex)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        persistentSessions.forEachIndexed { idx, s ->
            val isActive = idx == persistentActiveSessionIndex
            val displayTitle = buildString {
                append("[${idx + 1}] ")
                append(s.title)
                if (!s.sessionName.isNullOrEmpty()) {
                    append(" (${s.sessionName})")
                }
                if (s.isExecutingCommand) {
                    append(" ●")
                }
            }

            // 1. Render Top Session Tab Pill
            val topTabPill = MaterialTextView(this).apply {
                text = displayTitle
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isActive) accentColor else keyFgColor)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(7).toFloat()
                    setColor(if (isActive) ModernUiHelper.withAlpha(accentColor, 0x24) else keyBgColor)
                    if (isActive) {
                        setStroke(dp(1), ModernUiHelper.withAlpha(accentColor, 0x80))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(28)
                ).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    switchToSession(idx)
                }
                setOnLongClickListener {
                    showRenameSessionDialog(s)
                    true
                }
            }
            topTabsContainer.addView(topTabPill)

            // 2. Render Drawer Row
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10)
                )
                if (isActive) {
                    setBackgroundColor(ModernUiHelper.withAlpha(accentColor, 0x20))
                }
                setOnClickListener {
                    switchToSession(idx)
                    binding.terminalDrawerLayout.closeDrawer(GravityCompat.START)
                }
                setOnLongClickListener {
                    showRenameSessionDialog(s)
                    true
                }
            }

            val indicator = View(this).apply {
                setBackgroundColor(if (isActive) accentColor else Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(
                    dp(3),
                    dp(18)
                ).apply {
                    marginEnd = dp(10)
                }
            }
            row.addView(indicator)

            val labelView = MaterialTextView(this).apply {
                text = displayTitle
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(if (isActive) accentColor else Color.WHITE)
                setTypeface(typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(labelView)

            drawerList.addView(row)
        }
    }

    private fun showCreateNamedSessionDialog() {
        ModernUiHelper.showModernInputDialog(
            context = this,
            title = "Create Named Session",
            subtitle = "Start a new persistent Ubuntu 24.04 Zsh or failsafe sh session",
            hint = "Session name (optional)",
            positiveText = "Create",
            neutralText = "Failsafe",
            palette = currentTerminalDialogPalette(),
            onNeutral = { name ->
                binding.terminalDrawerLayout.closeDrawer(GravityCompat.START)
                addNewSession(isFailsafe = true, sessionName = name.ifEmpty { "failsafe" }, autorunCommand = null)
            },
            onConfirm = { name ->
                binding.terminalDrawerLayout.closeDrawer(GravityCompat.START)
                addNewSession(isFailsafe = false, sessionName = name, autorunCommand = null)
            }
        )
    }

    private fun showRenameSessionDialog(session: TerminalSession) {
        ModernUiHelper.showModernInputDialog(
            context = this,
            title = "Set Session Title",
            subtitle = "PID ${session.getPid()} • ${session.title}",
            initialValue = session.sessionName ?: "",
            hint = "Session name",
            positiveText = "Save",
            neutralText = "Kill Session",
            palette = currentTerminalDialogPalette(),
            onNeutral = {
                session.finishIfRunning()
            },
            onConfirm = { newName ->
                session.sessionName = newName.takeIf { it.isNotEmpty() }
                renderSessionsDrawerList()
            }
        )
    }

    private fun showTermuxContextMenu() {
        val s = activeSession() ?: return
        val hasSelection = binding.terminalView.hasActiveSelection()
        val palette = currentTerminalDialogPalette()

        val options = listOf(
            ModernUiHelper.MenuOption(
                id = 0,
                title = if (hasSelection) "Copy Selected Text" else "Select & Copy",
                subtitle = if (hasSelection) "Copy highlighted text to clipboard" else "Select specific lines or text on terminal",
                iconRes = R.drawable.ic_select_all,
                action = {
                    if (hasSelection) {
                        binding.terminalView.copySelectedText()
                    } else {
                        binding.terminalView.startTextSelection()
                    }
                }
            ),
            ModernUiHelper.MenuOption(
                id = 1,
                title = "Copy",
                subtitle = "Copy terminal output to clipboard",
                iconRes = R.drawable.ic_copy,
                action = {
                    binding.terminalView.copySelectedText()
                }
            ),
            ModernUiHelper.MenuOption(
                id = 2,
                title = "Paste",
                subtitle = "Paste clipboard into session [${persistentActiveSessionIndex + 1}] ${s.title}",
                iconRes = R.drawable.ic_paste,
                action = {
                    binding.terminalView.pasteFromClipboard()
                }
            )
        )

        ModernUiHelper.showModernContextMenu(
            context = this,
            title = "Terminal Actions",
            subtitle = "Session [${persistentActiveSessionIndex + 1}] ${s.title}",
            headerIconRes = R.drawable.ic_terminal,
            palette = palette,
            options = options
        )
    }

    private fun currentTerminalDialogPalette(): ModernUiHelper.Palette {
        val style = TERMUX_STYLES[activeStyleIndex.coerceIn(0, TERMUX_STYLES.lastIndex)]
        return ModernUiHelper.Palette(
            cardBg = Color.parseColor(style.toolbarBgHex),
            surfaceBg = Color.parseColor(style.bgHex),
            fgColor = Color.parseColor(style.keyFgHex),
            subtleColor = Color.parseColor("#9E9E9E"),
            accentColor = Color.parseColor(style.accentHex),
            borderColor = Color.parseColor("#33FFFFFF")
        )
    }

    private fun triggerTerminalBell() {
        if (termuxProps.bellBehavior == "ignore") return
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(35L)
            }
        }
    }

    private fun handleTermuxIpcEvent(action: String, arg: String) {
        when (action) {
            "reload-settings" -> {
                reloadTermuxPropertiesAndStyle()
                showTermuxToast("Termux settings reloaded")
            }
            "clipboard-set" -> {
                val clipFile = File(AlpineLinuxEnvironment.usrTmpDir(this), ".termux_clipboard")
                if (clipFile.exists()) {
                    val content = runCatching { clipFile.readText() }.getOrDefault("")
                    binding.terminalView.copyToClipboard("termux-clipboard", content)
                }
            }
            "open-url" -> {
                if (arg.isNotEmpty()) {
                    runCatching {
                        val uri = if (arg.contains("://")) Uri.parse(arg) else Uri.fromFile(File(arg))
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Open"))
                    }
                }
            }
        }
    }

    private fun applyTermuxStylePalette() {
        val style = TERMUX_STYLES[activeStyleIndex.coerceIn(0, TERMUX_STYLES.lastIndex)]
        val bg = Color.parseColor(style.bgHex)
        val cursor = Color.parseColor(style.cursorHex)
        val toolbarBg = Color.parseColor(style.toolbarBgHex)
        val keyBg = Color.parseColor(style.keyBgHex)
        val keyFg = Color.parseColor(style.keyFgHex)
        val accent = Color.parseColor(style.accentHex)
        val density = resources.displayMetrics.density

        binding.terminalRoot.setBackgroundColor(bg)
        binding.terminalTopBar.setBackgroundColor(toolbarBg)
        binding.btnTopEditorBack.setTextColor(accent)
        binding.btnTopEditorBack.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 7f * density
            setColor(keyBg)
            setStroke((1 * density).toInt().coerceAtLeast(1), ModernUiHelper.withAlpha(accent, 0x55))
        }
        binding.btnTopNewSession.imageTintList = ColorStateList.valueOf(accent)
        binding.btnTopDrawer.imageTintList = ColorStateList.valueOf(keyFg)

        binding.terminalView.setTerminalBackground(bg)
        binding.terminalView.setCursorColor(cursor)
        binding.terminalView.setSelectionAccentColor(accent)
        binding.extraKeysContainer.setBackgroundColor(toolbarBg)
        binding.terminalSelectionBar.setBackgroundColor(toolbarBg)
        binding.terminalSelectionStatus.setTextColor(keyFg)
        binding.btnTerminalSelectAll.setTextColor(accent)
        binding.btnTerminalCopySelection.backgroundTintList = ColorStateList.valueOf(accent)
        binding.btnTerminalCopySelection.setTextColor(bg)
        binding.btnTerminalCancelSelection.imageTintList = ColorStateList.valueOf(keyFg)

        binding.btnToolbarDrawer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4f * density
            setColor(keyBg)
        }
        binding.btnToolbarDrawer.imageTintList = ColorStateList.valueOf(accent)

        binding.btnToolbarBack.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4f * density
            setColor(keyBg)
        }
        binding.btnToolbarBack.imageTintList = ColorStateList.valueOf(keyFg)

        binding.toggleKeyboardButton.setTextColor(accent)
        binding.newSessionButton.setTextColor(accent)
        binding.btnSwitchToExtraKeys.setTextColor(accent)

        setupExtraKeysBar()
        renderSessionsDrawerList()

        window?.let { win ->
            win.statusBarColor = toolbarBg
            win.navigationBarColor = toolbarBg
            val controller = WindowCompat.getInsetsController(win, binding.terminalDrawerLayout)
            controller.isAppearanceLightStatusBars = !style.isDark
            controller.isAppearanceLightNavigationBars = !style.isDark
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            binding.terminalDrawerLayout.isDrawerOpen(GravityCompat.START) -> {
                binding.terminalDrawerLayout.closeDrawer(GravityCompat.START)
            }
            binding.terminalView.isSelectingText -> {
                binding.terminalView.clearSelection()
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    companion object {
        const val EXTRA_AUTORUN_CMD = "extra_autorun_cmd"
        const val EXTRA_INITIAL_COMMAND = "extra_initial_command"
        const val EXTRA_WORKING_DIR = "extra_working_dir"
        const val EXTRA_INITIAL_MODE = "extra_initial_mode"
        private const val MAX_SESSIONS = 8

        val persistentSessions = ArrayList<TerminalSession>()
        var persistentActiveSessionIndex: Int = -1

        val TERMUX_STYLES = listOf(
            TermuxStylePalette("Termux Default Black", "#000000", "#D0D0D0", "#141414", "#212121", "#FFFFFF", "#80DEEA"),
            TermuxStylePalette("Dracula", "#282A36", "#F8F8F2", "#1E1F29", "#343746", "#F8F8F2", "#BD93F9"),
            TermuxStylePalette("Tokyo Night", "#1A1B26", "#C0CAF5", "#16161E", "#24283B", "#C0CAF5", "#7AA2F7"),
            TermuxStylePalette("Nord", "#2E3440", "#D8DEE9", "#242933", "#3B4252", "#ECEFF4", "#88C0D0"),
            TermuxStylePalette("Gruvbox Dark", "#1D2021", "#EBDBB2", "#141617", "#282828", "#EBDBB2", "#FABD2F"),
            TermuxStylePalette("Solarized Dark", "#002B36", "#93A1A1", "#001F27", "#073642", "#93A1A1", "#2AA198"),
            TermuxStylePalette("One Dark", "#282C34", "#528BFF", "#21252B", "#2C313C", "#ABB2BF", "#61AFEF"),
            TermuxStylePalette("Monokai", "#272822", "#F8F8F0", "#1E1F1C", "#3E3D32", "#F8F8F2", "#A6E22E")
        )
    }
}
