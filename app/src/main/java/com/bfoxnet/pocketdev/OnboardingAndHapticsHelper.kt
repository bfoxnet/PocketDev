package com.bfoxnet.pocketdev

import android.app.Dialog
import android.content.Context
import android.graphics.Color
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
 * Interactive Welcome Guide & Haptic Feedback helper.
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

            val topHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = TextView(context).apply {
                text = "WELCOME TO POCKETDEV • STEP ${currentStep + 1} OF 3"
                textSize = 10.5f
                typeface = ModernUiHelper.appFontBold(context)
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
                        text = "Choose Your Theme & Editor Font"
                        textSize = 16.5f
                        typeface = ModernUiHelper.appFontBold(context)
                        setTextColor(palette.fgColor)
                        setPadding(0, dp(6), 0, dp(4))
                    }
                    rootContainer.addView(title)

                    val sub = TextView(context).apply {
                        text = "Select a color scheme and monospace editor typeface:"
                        textSize = 12f
                        typeface = ModernUiHelper.appFontRegular(context)
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
                            typeface = if (selected) ModernUiHelper.appFontBold(context) else ModernUiHelper.appFontMedium(context)
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
                        text = "Editor Font"
                        textSize = 11.5f
                        typeface = ModernUiHelper.appFontBold(context)
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
                            typeface = if (selected) ModernUiHelper.appFontBold(context) else ModernUiHelper.appFontMedium(context)
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
                        text = "Linux Development Environment"
                        textSize = 16.5f
                        typeface = ModernUiHelper.appFontBold(context)
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
                        text = if (isInstalled) "✓ Ubuntu 24.04 LTS Environment Ready" else "Ubuntu 24.04 LTS Ready for Setup"
                        textSize = 13f
                        typeface = ModernUiHelper.appFontBold(context)
                        setTextColor(if (isInstalled) Color.parseColor("#66BB6A") else palette.accentColor)
                    }
                    val desc = TextView(context).apply {
                        text = "PocketDev includes an integrated Ubuntu 24.04 LTS environment with Zsh, shell plugins, compilers, and offline backup and restore."
                        textSize = 12f
                        typeface = ModernUiHelper.appFontRegular(context)
                        setTextColor(palette.subColor)
                        setPadding(0, dp(6), 0, 0)
                    }
                    card.addView(statusLine)
                    card.addView(desc)
                    rootContainer.addView(card)
                }

                else -> {
                    val title = TextView(context).apply {
                        text = "Code Intelligence, Build & Git"
                        textSize = 16.5f
                        typeface = ModernUiHelper.appFontBold(context)
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
                        text = "• Project Explorer: Manage files, Git source control, and document symbols from the navigation drawer.\n" +
                            "• Output Panel: Inspect diagnostics, search results, and build output with interactive standard input.\n" +
                            "• Language Servers: Install clangd, pyright, gopls, typescript-language-server, or rust-analyzer."
                        textSize = 12f
                        typeface = ModernUiHelper.appFontRegular(context)
                        setTextColor(palette.fgColor)
                        setLineSpacing(2.5f * density, 1.08f)
                    }
                    card.addView(highlights)
                    rootContainer.addView(card)
                }
            }

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, 0)
            }

            val skipBtn = TextView(context).apply {
                text = "Skip"
                textSize = 12.5f
                typeface = ModernUiHelper.appFontMedium(context)
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
                    typeface = ModernUiHelper.appFontBold(context)
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
                    text = "Set Up Environment"
                    textSize = 12.5f
                    typeface = ModernUiHelper.appFontBold(context)
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
                    text = "Language Servers"
                    textSize = 12.5f
                    typeface = ModernUiHelper.appFontBold(context)
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
                text = if (currentStep < 2) "Next" else "Get Started"
                textSize = 12.5f
                typeface = ModernUiHelper.appFontBold(context)
                setTextColor(Color.WHITE)
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
