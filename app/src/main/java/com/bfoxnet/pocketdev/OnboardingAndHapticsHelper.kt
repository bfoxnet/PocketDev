package com.bfoxnet.pocketdev

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Interactive 3-Step First-Run Onboarding Wizard & Extra-Keys Haptic Feedback helper:
 * - Step 1: Pick Editor Color Scheme & Monospace Font (live preview).
 * - Step 2: 1-Tap Ubuntu 24.04.5 LTS + PRoot + Zsh (`robbyrussell` theme) setup status.
 * - Step 3: Stdio LSP Servers, 1-Tap Compile/Run with interactive `stdin`, and Git source control.
 * - Provides configurable haptic feedback (`Off`, `Light`, `Medium`, `Strong`) for extra keys
 *   and quick toolbar buttons.
 */
object OnboardingAndHapticsHelper {

    fun performExtraKeyHaptic(context: Context, view: View?) {
        val level = Settings(context).hapticFeedbackLevel
        if (level <= 0) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                val durationMs = when (level) {
                    1 -> 10L
                    2 -> 22L
                    else -> 38L
                }
                val amplitude = when (level) {
                    1 -> 70
                    2 -> 150
                    else -> 255
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            } else {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    fun showOnboardingDialog(
        context: Context,
        onBootstrapNow: () -> Unit,
        onOpenLspManager: () -> Unit,
        onThemeChanged: (() -> Unit)? = null
    ) {
        val settings = Settings(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        var currentStep = 0
        var sheetDialog: Dialog? = null
        val rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(14))
        }

        fun renderStep() {
            rootContainer.removeAllViews()
            val palette = ModernUiHelper.resolvePalette(context)

            // Step indicator dots + badge
            val topHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = TextView(context).apply {
                text = "WELCOME TO POCKETDEV • STEP ${currentStep + 1} OF 3"
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.accentColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topHeader.addView(badge)

            val dotsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (i in 0..2) {
                val dot = View(context).apply {
                    val active = i == currentStep
                    layoutParams = LinearLayout.LayoutParams(if (active) dp(18) else dp(6), dp(6)).apply {
                        marginStart = dp(4)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(3).toFloat()
                        setColor(if (active) palette.accentColor else ModernUiHelper.withAlpha(palette.subColor, 0x55))
                    }
                }
                dotsRow.addView(dot)
            }
            topHeader.addView(dotsRow)
            rootContainer.addView(topHeader)

            when (currentStep) {
                0 -> {
                    val title = TextView(context).apply {
                        text = "1. Pick Your Editor Theme & Font"
                        textSize = 16.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(palette.fgColor)
                        setPadding(0, dp(6), 0, dp(4))
                    }
                    rootContainer.addView(title)

                    val sub = TextView(context).apply {
                        text = "Tap any theme or programming font below to apply it live across the code editor:"
                        textSize = 12f
                        setTextColor(palette.subColor)
                        setPadding(0, 0, 0, dp(10))
                    }
                    rootContainer.addView(sub)

                    val featuredThemes = listOf("darcula", "tokyo_night", "catppuccin_mocha", "one_dark_pro", "dracula", "quietlight")
                    val themeScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                    val themeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    for (tId in featuredThemes) {
                        val tObj = EditorController.themeById(tId)
                        val selected = settings.editorTheme == tObj.id
                        val chip = TextView(context).apply {
                            text = if (selected) "✓ ${tObj.label}" else tObj.label
                            textSize = 12f
                            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                            setTextColor(if (selected) palette.accentColor else palette.fgColor)
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(10).toFloat()
                                setColor(if (selected) ModernUiHelper.withAlpha(palette.accentColor, 0x22) else palette.inputBgColor)
                                setStroke(dp(1), if (selected) palette.accentColor else palette.strokeColor)
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = dp(8) }
                            setOnClickListener {
                                settings.editorTheme = tObj.id
                                onThemeChanged?.invoke()
                                renderStep()
                            }
                        }
                        themeRow.addView(chip)
                    }
                    themeScroll.addView(themeRow)
                    rootContainer.addView(themeScroll)

                    val fontHdr = TextView(context).apply {
                        text = "Programming Font (with OpenType Ligatures)"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(palette.accentColor)
                        setPadding(0, dp(12), 0, dp(6))
                    }
                    rootContainer.addView(fontHdr)

                    val fontScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                    val fontRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    for (fObj in Settings.FONTS) {
                        val selected = settings.fontFamily == fObj.id
                        val chip = TextView(context).apply {
                            text = if (selected) "✓ ${fObj.label}" else fObj.label
                            textSize = 12f
                            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                            setTextColor(if (selected) palette.accentColor else palette.fgColor)
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(10).toFloat()
                                setColor(if (selected) ModernUiHelper.withAlpha(palette.accentColor, 0x22) else palette.inputBgColor)
                                setStroke(dp(1), if (selected) palette.accentColor else palette.strokeColor)
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { marginEnd = dp(8) }
                            setOnClickListener {
                                settings.fontFamily = fObj.id
                                onThemeChanged?.invoke()
                                renderStep()
                            }
                        }
                        fontRow.addView(chip)
                    }
                    fontScroll.addView(fontRow)
                    rootContainer.addView(fontScroll)
                }

                1 -> {
                    val title = TextView(context).apply {
                        text = "2. Ubuntu 24.04 LTS + Zsh (robbyrussell)"
                        textSize = 16.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(palette.fgColor)
                        setPadding(0, dp(6), 0, dp(4))
                    }
                    rootContainer.addView(title)

                    val isInstalled = UbuntuProotManager.isUbuntuInstalled(context)
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                            setColor(palette.inputBgColor)
                            setStroke(dp(1), palette.strokeColor)
                        }
                    }
                    val statusLine = TextView(context).apply {
                        text = if (isInstalled) "✓ Ubuntu 24.04.5 LTS Rootfs + PRoot Installed" else "⚡ Ready for 1-Tap Bootstrap (~28.5 MB)"
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isInstalled) Color.parseColor("#66BB6A") else palette.accentColor)
                    }
                    val desc = TextView(context).apply {
                        text = "PocketDev runs a real Ubuntu 24.04.5 LTS userland via native Android PRoot with Oh-My-Zsh (robbyrussell), syntax-highlighting, autosuggestions, and offline .tar.gz Backup/Restore."
                        textSize = 12f
                        setTextColor(palette.subColor)
                        setPadding(0, dp(6), 0, 0)
                    }
                    card.addView(statusLine)
                    card.addView(desc)
                    rootContainer.addView(card)
                }

                else -> {
                    val title = TextView(context).apply {
                        text = "3. Stdio LSP, Build/Run & Git Sidebar"
                        textSize = 16.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(palette.fgColor)
                        setPadding(0, dp(6), 0, dp(4))
                    }
                    rootContainer.addView(title)

                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                            setColor(palette.inputBgColor)
                            setStroke(dp(1), palette.strokeColor)
                        }
                    }
                    val highlights = TextView(context).apply {
                        text = "• Left Drawer Tabs: Switch seamlessly between File Tree, Git Source Control, and Document Symbols Outline.\n" +
                            "• Draggable Bottom Panel: Inspect LSP diagnostics, project search hits, and streaming Build Output with interactive stdin.\n" +
                            "• Real Stdio LSP: Install clangd, pyright, gopls, tsserver, or rust-analyzer from the LSP Manager."
                        textSize = 12f
                        setTextColor(palette.fgColor)
                        setLineSpacing(2.5f * density, 1.08f)
                    }
                    card.addView(highlights)
                    rootContainer.addView(card)
                }
            }

            // Bottom Navigation Row
            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, 0)
            }

            val skipBtn = TextView(context).apply {
                text = "Skip"
                textSize = 12.5f
                setTextColor(palette.subColor)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    settings.onboardingCompleted = true
                    sheetDialog?.dismiss()
                }
            }
            btnRow.addView(skipBtn)

            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            btnRow.addView(spacer)

            if (currentStep > 0) {
                val backBtn = TextView(context).apply {
                    text = "Back"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(palette.fgColor)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        currentStep--
                        renderStep()
                    }
                }
                btnRow.addView(backBtn)
            }

            if (currentStep == 1 && !UbuntuProotManager.isUbuntuInstalled(context)) {
                val bootBtn = TextView(context).apply {
                    text = "Bootstrap Ubuntu"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(palette.accentColor)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        settings.onboardingCompleted = true
                        sheetDialog?.dismiss()
                        onBootstrapNow()
                    }
                }
                btnRow.addView(bootBtn)
            }

            if (currentStep == 2) {
                val lspBtn = TextView(context).apply {
                    text = "LSP Servers"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(palette.accentColor)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        settings.onboardingCompleted = true
                        sheetDialog?.dismiss()
                        onOpenLspManager()
                    }
                }
                btnRow.addView(lspBtn)
            }

            val nextBtn = TextView(context).apply {
                text = if (currentStep < 2) "Next →" else "Start Coding"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (palette.isDark) Color.parseColor("#1E1200") else Color.WHITE)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(palette.accentColor)
                }
                setOnClickListener {
                    if (currentStep < 2) {
                        currentStep++
                        renderStep()
                    } else {
                        settings.onboardingCompleted = true
                        sheetDialog?.dismiss()
                    }
                }
            }
            btnRow.addView(nextBtn)

            rootContainer.addView(btnRow)
        }

        renderStep()
        val scroll = ScrollView(context).apply { addView(rootContainer) }
        sheetDialog = ModernUiHelper.showModernCustomBottomSheet(
            context = context,
            contentView = scroll,
            palette = ModernUiHelper.resolvePalette(context)
        )
    }
}
