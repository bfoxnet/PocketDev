package com.bfoxnet.pocketdev

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bfoxnet.pocketdev.databinding.ActivityAboutBinding

/**
 * Dedicated "About" page for PocketDev with structured Runtime & Capabilities cards
 * and synchronized AppBar and StatusBar colors.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        setupInsets()
        populateRuntimeDetails()
        applyThemeChrome()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnOpenDiag.setOnClickListener {
            startActivity(Intent(this, DiagActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        populateRuntimeDetails()
        applyThemeChrome()
    }

    private fun populateRuntimeDetails() {
        val abi = UbuntuProotManager.detectHostAbi()
        val uArch = UbuntuProotManager.ubuntuArch()
        val ubuntuInstalled = if (UbuntuProotManager.isUbuntuInstalled(this)) "Installed ✓" else "Not Bootstrapped"
        val lspInstalled = LspManager.AVAILABLE_SERVERS.count { LspManager.isInstalled(this, it.id) }
        binding.aboutRuntimeDetails.text = buildString {
            appendLine("Host Device   : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("Hardware ABI  : $abi (Ubuntu $uArch rootfs)")
            appendLine("Ubuntu 24.04  : $ubuntuInstalled • Shell: ${settings.defaultShell}")
            append("LSP Servers   : $lspInstalled / ${LspManager.AVAILABLE_SERVERS.size} active • Editor: Sora 0.23.6")
        }
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.aboutRoot) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
    }

    private fun applyThemeChrome() {
        val theme = EditorController.themeById(settings.editorTheme)
        val chrome = Color.parseColor(theme.ui.chromeBg)
        val surface = Color.parseColor(theme.ui.bg)
        val fg = Color.parseColor(theme.ui.fg)
        val subtle = Color.parseColor(theme.ui.lineNumActive)
        val accent = if (theme.dark) Color.parseColor("#FFB74D") else Color.parseColor("#E66000")
        val density = resources.displayMetrics.density

        binding.aboutRoot.setBackgroundColor(chrome)
        binding.aboutAppbar.setBackgroundColor(chrome)
        binding.toolbar.setBackgroundColor(chrome)
        binding.toolbar.setTitleTextColor(fg)
        binding.toolbar.navigationIcon?.setTint(fg)
        binding.aboutScroll.setBackgroundColor(surface)
        binding.aboutAppTitle.setTextColor(fg)
        binding.aboutVersion.setTextColor(accent)
        binding.aboutDescription.setTextColor(subtle)

        for (card in listOf(binding.aboutRuntimeCard, binding.aboutFeaturesCard)) {
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(chrome)
                setStroke((1 * density).toInt().coerceAtLeast(1), subtle and 0x33FFFFFF)
            }
        }
        binding.aboutRuntimeHeader.setTextColor(accent)
        binding.aboutRuntimeDetails.setTextColor(fg)
        binding.aboutFeaturesHeader.setTextColor(accent)
        binding.aboutFeaturesText.setTextColor(fg)
        binding.btnOpenDiag.setTextColor(accent)

        window?.let { win ->
            win.statusBarColor = chrome
            win.navigationBarColor = surface
            val controller = WindowCompat.getInsetsController(win, binding.aboutRoot)
            controller.isAppearanceLightStatusBars = !theme.dark
            controller.isAppearanceLightNavigationBars = !theme.dark
        }
    }
}
