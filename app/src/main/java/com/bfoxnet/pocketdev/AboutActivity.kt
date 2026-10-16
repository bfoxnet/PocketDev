package com.bfoxnet.pocketdev

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bfoxnet.pocketdev.databinding.ActivityAboutBinding

/**
 * Dedicated "About" page for PocketDev with synchronized AppBar and StatusBar colors.
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
        applyThemeChrome()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnOpenLsp.setOnClickListener {
            startActivity(Intent(this, LspActivity::class.java))
        }
        binding.btnOpenDiag.setOnClickListener {
            startActivity(Intent(this, DiagActivity::class.java))
        }
        binding.btnOpenTerminal.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        applyThemeChrome()
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

        binding.aboutRoot.setBackgroundColor(chrome)
        binding.aboutAppbar.setBackgroundColor(chrome)
        binding.toolbar.setBackgroundColor(chrome)
        binding.toolbar.setTitleTextColor(fg)
        binding.toolbar.navigationIcon?.setTint(fg)
        binding.aboutScroll.setBackgroundColor(surface)
        binding.aboutAppTitle.setTextColor(fg)
        binding.aboutVersion.setTextColor(subtle)
        binding.aboutDescription.setTextColor(fg)
        binding.aboutFeaturesText.setTextColor(fg)

        window?.let { win ->
            win.statusBarColor = chrome
            win.navigationBarColor = surface
            val controller = WindowCompat.getInsetsController(win, binding.aboutRoot)
            controller.isAppearanceLightStatusBars = !theme.dark
            controller.isAppearanceLightNavigationBars = !theme.dark
        }
    }
}
