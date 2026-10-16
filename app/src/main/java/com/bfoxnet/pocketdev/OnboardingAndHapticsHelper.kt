package com.bfoxnet.pocketdev

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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/**
 * First-run Onboarding guide & Extra-Keys Haptic Feedback helper:
 * - Explains the 1-tap Ubuntu 24.04.5 LTS + PRoot + Zsh (`robbyrussell` theme) bootstrap flow,
 *   real stdio LSP servers (`clangd`, `pyright`, `gopls`, `tsserver`, `rust-analyzer`),
 *   Compile/Run toolchains, Environment `.tar.gz` Backup/Restore, and GitHub integration.
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
        onOpenLspManager: () -> Unit
    ) {
        val settings = Settings(context)
        val palette = ModernUiHelper.resolvePalette(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(palette.bgColor)
                setStroke(dp(1), palette.strokeColor)
            }
        }

        val badge = TextView(context).apply {
            text = "WELCOME TO POCKETDEV IDE"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accentColor)
        }
        root.addView(badge)

        val title = TextView(context).apply {
            text = "Ubuntu 24.04 PRoot • Real Stdio LSP • Git & Toolchain"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(palette.fgColor)
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(title)

        val steps = listOf(
            "1. Instant Ubuntu 24.04.5 + Zsh (robbyrussell)" to
                "Tap 'Bootstrap Ubuntu' or run `ubuntu-bootstrap` once. PocketDev downloads the 28.5 MB Ubuntu 24.04 rootfs, configures PRoot + Oh-My-Zsh with the robbyrussell theme, and keeps a persistent shell daemon so `ls` and `pwd` execute in <20 ms.",
            "2. Real ProcessBuilder Stdio LSP & Code Intelligence" to
                "Install language servers (`clangd`, `pyright`, `gopls`, `typescript-language-server`, `rust-analyzer`, `jdtls`) from the LSP Manager. PocketDev spawns a real JSON-RPC 2.0 stdio loop with Hover Docs, Go-to-Definition, Find References, and Symbol Rename.",
            "3. Compile & Run + Format Document" to
                "Tap the ▶ Run button in the top bar (or long-press to customize arguments) to run Python, Node.js, Go, Java, Kotlin, C/C++, or Rust directly into the Build Output tab.",
            "4. Backup/Restore & GitHub Integration" to
                "Export your entire `/ubuntu` rootfs to a `.tar.gz` archive via Settings → Backup/Restore to skip setup on future installs, and sign in to GitHub (OAuth Device Flow or PAT) to clone, diff, commit, branch, pull, and push."
        )

        for ((heading, body) in steps) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(palette.cardColor)
                    setStroke(dp(1), palette.strokeColor)
                }
            }
            val hView = TextView(context).apply {
                text = heading
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.fgColor)
            }
            val bView = TextView(context).apply {
                text = body
                textSize = 11.5f
                setTextColor(palette.subColor)
                setPadding(0, dp(3), 0, 0)
            }
            card.addView(hView)
            card.addView(bView)
            root.addView(card)
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(scroll)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val lspBtn = TextView(context).apply {
            text = "LSP Servers"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accentColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                settings.onboardingCompleted = true
                dialog.dismiss()
                onOpenLspManager()
            }
        }
        btnRow.addView(lspBtn)

        val skipBtn = TextView(context).apply {
            text = "Start Coding"
            textSize = 12.5f
            setTextColor(palette.subColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                settings.onboardingCompleted = true
                dialog.dismiss()
            }
        }
        btnRow.addView(skipBtn)

        if (!UbuntuProotManager.isUbuntuInstalled(context)) {
            val bootBtn = TextView(context).apply {
                text = "Bootstrap Ubuntu"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (palette.isDark) Color.parseColor("#1E1200") else Color.WHITE)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(palette.accentColor)
                }
                setOnClickListener {
                    settings.onboardingCompleted = true
                    dialog.dismiss()
                    onBootstrapNow()
                }
            }
            btnRow.addView(bootBtn)
        }
        root.addView(btnRow)
        dialog.show()
    }
}
