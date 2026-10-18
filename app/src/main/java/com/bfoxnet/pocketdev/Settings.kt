package com.bfoxnet.pocketdev

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings backed by SharedPreferences. Persists editor preferences,
 * UI font family, 15+ monospace editor fonts, font ligatures, word-match highlighting,
 * code folding, sticky-scroll headers, Auto-Save & Save-All behavior, Prettier code
 * formatter configuration, Plugins state, active project directory inside the Ubuntu rootfs
 * (`/root/projects/<name>`), Termux terminal style/cursor/toolbar/sticky-Ctrl/wake-lock
 * and extra-key haptic feedback states, default Linux shell (`zsh` with `robbyrussell`
 * theme and plugins, or `bash`), first-run onboarding state, and Language Server Protocol (LSP) states.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pocketdev", Context.MODE_PRIVATE)

    /** Editor color scheme id (applies ONLY to the code editor canvas, not the whole app UI). */
    var editorTheme: String
        get() = prefs.getString(KEY_EDITOR_THEME, "darcula") ?: "darcula"
        set(value) = prefs.edit().putString(KEY_EDITOR_THEME, value).apply()

    /** Editor monospace font family id, resolved against [FONTS]. */
    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, FONTS.first().id) ?: FONTS.first().id
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    /** App UI font family id, resolved against [UI_FONTS]. */
    var uiFontFamily: String
        get() = prefs.getString(KEY_UI_FONT_FAMILY, UI_FONTS.first().id) ?: UI_FONTS.first().id
        set(value) = prefs.edit().putString(KEY_UI_FONT_FAMILY, value).apply()

    /** Editor text size in sp. */
    var fontSizeSp: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 14)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    /** Word wrap toggle. */
    var wordWrap: Boolean
        get() = prefs.getBoolean(KEY_WORD_WRAP, true)
        set(value) = prefs.edit().putBoolean(KEY_WORD_WRAP, value).apply()

    /** Programming font ligatures ("liga" & "calt" OpenType features). Enabled by default. */
    var fontLigatures: Boolean
        get() = prefs.getBoolean(KEY_FONT_LIGATURES, true)
        set(value) = prefs.edit().putBoolean(KEY_FONT_LIGATURES, value).apply()

    /** Highlight all occurrences of the word under the cursor. Enabled by default. */
    var wordMatchHighlightEnabled: Boolean
        get() = prefs.getBoolean(KEY_WORD_MATCH_HIGHLIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_WORD_MATCH_HIGHLIGHT, value).apply()

    /** Enable interactive gutter code folding icons (`▾` / `▸`) in the editor. Enabled by default. */
    var codeFoldingEnabled: Boolean
        get() = prefs.getBoolean(KEY_CODE_FOLDING, true)
        set(value) = prefs.edit().putBoolean(KEY_CODE_FOLDING, value).apply()

    /** Pin the current top-level block signature at the top of the editor (VS Code Sticky Scroll). Enabled by default. */
    var stickyScrollEnabled: Boolean
        get() = prefs.getBoolean(KEY_STICKY_SCROLL, true)
        set(value) = prefs.edit().putBoolean(KEY_STICKY_SCROLL, value).apply()

    /** Automatically saves modified open files after [autoSaveDelayMs] of typing inactivity and on pause/tab switch. */
    var autoSaveEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SAVE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SAVE_ENABLED, value).apply()

    /** Debounce delay in milliseconds before auto-saving modified editor tabs. */
    var autoSaveDelayMs: Int
        get() = prefs.getInt(KEY_AUTO_SAVE_DELAY_MS, 1500)
        set(value) = prefs.edit().putInt(KEY_AUTO_SAVE_DELAY_MS, value.coerceIn(500, 10000)).apply()

    /** When true, pressing the Save button saves all modified open files instead of only the active tab. */
    var saveAllOnSavePress: Boolean
        get() = prefs.getBoolean(KEY_SAVE_ALL_ON_SAVE_PRESS, true)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_ALL_ON_SAVE_PRESS, value).apply()

    // ---------------------------------------------------------------- Prettier Formatter Settings

    /** Whether Prettier is enabled as the default code formatter. */
    var prettierEnabled: Boolean
        get() = prefs.getBoolean(KEY_PRETTIER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PRETTIER_ENABLED, value).apply()

    /** Automatically run Prettier when saving a document. */
    var prettierFormatOnSave: Boolean
        get() = prefs.getBoolean(KEY_PRETTIER_FORMAT_ON_SAVE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRETTIER_FORMAT_ON_SAVE, value).apply()

    /** Prettier `--print-width` (line length that Prettier will wrap on). */
    var prettierPrintWidth: Int
        get() = prefs.getInt(KEY_PRETTIER_PRINT_WIDTH, 80)
        set(value) = prefs.edit().putInt(KEY_PRETTIER_PRINT_WIDTH, value.coerceIn(40, 200)).apply()

    /** Prettier `--tab-width` (spaces per indentation level). */
    var prettierTabWidth: Int
        get() = prefs.getInt(KEY_PRETTIER_TAB_WIDTH, 2)
        set(value) = prefs.edit().putInt(KEY_PRETTIER_TAB_WIDTH, value.coerceIn(1, 8)).apply()

    /** Prettier `--use-tabs` (indent lines with tabs instead of spaces). */
    var prettierUseTabs: Boolean
        get() = prefs.getBoolean(KEY_PRETTIER_USE_TABS, false)
        set(value) = prefs.edit().putBoolean(KEY_PRETTIER_USE_TABS, value).apply()

    /** Prettier `--semi` / `--no-semi` (print semicolons at the ends of statements). */
    var prettierSemi: Boolean
        get() = prefs.getBoolean(KEY_PRETTIER_SEMI, true)
        set(value) = prefs.edit().putBoolean(KEY_PRETTIER_SEMI, value).apply()

    /** Prettier `--single-quote` (use single quotes instead of double quotes). */
    var prettierSingleQuote: Boolean
        get() = prefs.getBoolean(KEY_PRETTIER_SINGLE_QUOTE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRETTIER_SINGLE_QUOTE, value).apply()

    /** Prettier `--jsx-single-quote` (use single quotes instead of double quotes in JSX). */
    var prettierJsxSingleQuote: Boolean
        get() = prefs.getBoolean(KEY_PRETTIER_JSX_SINGLE_QUOTE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRETTIER_JSX_SINGLE_QUOTE, value).apply()

    /** Prettier `--trailing-comma` ("es5", "all", "none"). */
    var prettierTrailingComma: String
        get() = prefs.getString(KEY_PRETTIER_TRAILING_COMMA, "es5") ?: "es5"
        set(value) = prefs.edit().putString(KEY_PRETTIER_TRAILING_COMMA, value).apply()

    /** Prettier `--bracket-spacing` (print spaces between brackets in object literals `{ foo: bar }`). */
    var prettierBracketSpacing: Boolean
        get() = prefs.getBoolean(KEY_PRETTIER_BRACKET_SPACING, true)
        set(value) = prefs.edit().putBoolean(KEY_PRETTIER_BRACKET_SPACING, value).apply()

    /** Prettier `--bracket-same-line` (put `>` of multi-line HTML/JSX element at the end of the last line). */
    var prettierBracketSameLine: Boolean
        get() = prefs.getBoolean(KEY_PRETTIER_BRACKET_SAME_LINE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRETTIER_BRACKET_SAME_LINE, value).apply()

    /** Prettier `--arrow-parens` ("always" or "avoid"). */
    var prettierArrowParens: String
        get() = prefs.getString(KEY_PRETTIER_ARROW_PARENS, "always") ?: "always"
        set(value) = prefs.edit().putString(KEY_PRETTIER_ARROW_PARENS, value).apply()

    /** Prettier `--end-of-line` ("lf", "crlf", "auto"). */
    var prettierEndOfLine: String
        get() = prefs.getString(KEY_PRETTIER_END_OF_LINE, "lf") ?: "lf"
        set(value) = prefs.edit().putString(KEY_PRETTIER_END_OF_LINE, value).apply()

    /** Prettier `--prose-wrap` ("always", "never", "preserve"). */
    var prettierProseWrap: String
        get() = prefs.getString(KEY_PRETTIER_PROSE_WRAP, "preserve") ?: "preserve"
        set(value) = prefs.edit().putString(KEY_PRETTIER_PROSE_WRAP, value).apply()

    fun resetPrettierDefaults() {
        prefs.edit()
            .putBoolean(KEY_PRETTIER_ENABLED, true)
            .putBoolean(KEY_PRETTIER_FORMAT_ON_SAVE, false)
            .putInt(KEY_PRETTIER_PRINT_WIDTH, 80)
            .putInt(KEY_PRETTIER_TAB_WIDTH, 2)
            .putBoolean(KEY_PRETTIER_USE_TABS, false)
            .putBoolean(KEY_PRETTIER_SEMI, true)
            .putBoolean(KEY_PRETTIER_SINGLE_QUOTE, false)
            .putBoolean(KEY_PRETTIER_JSX_SINGLE_QUOTE, false)
            .putString(KEY_PRETTIER_TRAILING_COMMA, "es5")
            .putBoolean(KEY_PRETTIER_BRACKET_SPACING, true)
            .putBoolean(KEY_PRETTIER_BRACKET_SAME_LINE, false)
            .putString(KEY_PRETTIER_ARROW_PARENS, "always")
            .putString(KEY_PRETTIER_END_OF_LINE, "lf")
            .putString(KEY_PRETTIER_PROSE_WRAP, "preserve")
            .apply()
    }

    // ---------------------------------------------------------------- Workspace & Terminal State

    var projectTreeUri: String?
        get() = prefs.getString(KEY_PROJECT_TREE_URI, null)
        set(value) = prefs.edit().putString(KEY_PROJECT_TREE_URI, value).apply()

    var projectMode: String
        get() = prefs.getString(KEY_PROJECT_MODE, "local") ?: "local"
        set(value) = prefs.edit().putString(KEY_PROJECT_MODE, value).apply()

    var localProjectPath: String?
        get() = prefs.getString(KEY_LOCAL_PROJECT_PATH, null)
        set(value) = prefs.edit().putString(KEY_LOCAL_PROJECT_PATH, value).apply()

    var lastOpenedFilePath: String?
        get() = prefs.getString(KEY_LAST_OPENED_FILE_PATH, null)
        set(value) = prefs.edit().putString(KEY_LAST_OPENED_FILE_PATH, value).apply()

    var lastOpenedFileName: String?
        get() = prefs.getString(KEY_LAST_OPENED_FILE_NAME, "Main.kt")
        set(value) = prefs.edit().putString(KEY_LAST_OPENED_FILE_NAME, value).apply()

    var lastWidgetProjectPath: String?
        get() = prefs.getString(KEY_LAST_WIDGET_PROJECT_PATH, null)
        set(value) = prefs.edit().putString(KEY_LAST_WIDGET_PROJECT_PATH, value).apply()

    var githubToken: String
        get() = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_TOKEN, value).apply()

    var githubUsername: String
        get() = prefs.getString(KEY_GITHUB_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_USERNAME, value).apply()

    var githubOAuthClientId: String
        get() = prefs.getString(KEY_GITHUB_OAUTH_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_OAUTH_CLIENT_ID, value).apply()

    var downloadNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOWNLOAD_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DOWNLOAD_NOTIFICATIONS_ENABLED, value).apply()

    var lastWidgetFileName: String?
        get() = prefs.getString(KEY_LAST_WIDGET_FILE_NAME, "")
        set(value) = prefs.edit().putString(KEY_LAST_WIDGET_FILE_NAME, value).apply()

    var lastWidgetFilePath: String?
        get() = prefs.getString(KEY_LAST_WIDGET_FILE_PATH, "")
        set(value) = prefs.edit().putString(KEY_LAST_WIDGET_FILE_PATH, value).apply()

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var terminalStyleIndex: Int
        get() = prefs.getInt(KEY_TERMINAL_STYLE_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_TERMINAL_STYLE_INDEX, value).apply()

    var terminalCursorStyle: Int
        get() = prefs.getInt(KEY_TERMINAL_CURSOR_STYLE, -1)
        set(value) = prefs.edit().putInt(KEY_TERMINAL_CURSOR_STYLE, value).apply()

    var terminalToolbarVisible: Boolean
        get() = prefs.getBoolean(KEY_TERMINAL_TOOLBAR_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_TERMINAL_TOOLBAR_VISIBLE, value).apply()

    var terminalKeepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_TERMINAL_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_TERMINAL_KEEP_SCREEN_ON, value).apply()

    var terminalAutoWakeLock: Boolean
        get() = prefs.getBoolean(KEY_TERMINAL_AUTO_WAKELOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_TERMINAL_AUTO_WAKELOCK, value).apply()

    var terminalStickyCtrl: Boolean
        get() = prefs.getBoolean(KEY_TERMINAL_STICKY_CTRL, true)
        set(value) = prefs.edit().putBoolean(KEY_TERMINAL_STICKY_CTRL, value).apply()

    var hapticFeedbackLevel: Int
        get() = prefs.getInt(KEY_HAPTIC_FEEDBACK_LEVEL, 1)
        set(value) = prefs.edit().putInt(KEY_HAPTIC_FEEDBACK_LEVEL, value.coerceIn(0, 3)).apply()

    var terminalFontSizeSp: Float
        get() = prefs.getFloat(KEY_TERMINAL_FONT_SIZE_SP, 13.5f)
        set(value) = prefs.edit().putFloat(KEY_TERMINAL_FONT_SIZE_SP, value).apply()

    var defaultShell: String
        get() = prefs.getString(KEY_DEFAULT_SHELL, SHELL_ZSH) ?: SHELL_ZSH
        set(value) = prefs.edit().putString(KEY_DEFAULT_SHELL, value).apply()

    var zshTheme: String
        get() = prefs.getString(KEY_ZSH_THEME, DEFAULT_ZSH_THEME) ?: DEFAULT_ZSH_THEME
        set(value) = prefs.edit().putString(KEY_ZSH_THEME, value).apply()

    var vmArch: String
        get() = prefs.getString(KEY_VM_ARCH, UbuntuProotManager.detectHostAbi())
            ?: UbuntuProotManager.detectHostAbi()
        set(value) = prefs.edit().putString(KEY_VM_ARCH, value).apply()

    fun isLspInstalled(serverId: String): Boolean =
        prefs.getBoolean("$KEY_LSP_INSTALLED_PREFIX$serverId", false)

    fun setLspInstalled(serverId: String, installed: Boolean) {
        prefs.edit().putBoolean("$KEY_LSP_INSTALLED_PREFIX$serverId", installed).apply()
    }

    fun isLspEnabled(serverId: String): Boolean =
        prefs.getBoolean("$KEY_LSP_ENABLED_PREFIX$serverId", true)

    fun setLspEnabled(serverId: String, enabled: Boolean) {
        prefs.edit().putBoolean("$KEY_LSP_ENABLED_PREFIX$serverId", enabled).apply()
    }

    fun isPluginEnabled(pluginId: String, defaultEnabled: Boolean = true): Boolean =
        prefs.getBoolean("$KEY_PLUGIN_ENABLED_PREFIX$pluginId", defaultEnabled)

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        prefs.edit().putBoolean("$KEY_PLUGIN_ENABLED_PREFIX$pluginId", enabled).apply()
    }

    companion object {
        private const val KEY_EDITOR_THEME = "editor_theme"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_UI_FONT_FAMILY = "ui_font_family"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_WORD_WRAP = "word_wrap"
        private const val KEY_FONT_LIGATURES = "font_ligatures"
        private const val KEY_WORD_MATCH_HIGHLIGHT = "word_match_highlight"
        private const val KEY_CODE_FOLDING = "code_folding"
        private const val KEY_STICKY_SCROLL = "sticky_scroll"
        private const val KEY_AUTO_SAVE_ENABLED = "auto_save_enabled"
        private const val KEY_AUTO_SAVE_DELAY_MS = "auto_save_delay_ms"
        private const val KEY_SAVE_ALL_ON_SAVE_PRESS = "save_all_on_save_press"
        private const val KEY_PRETTIER_ENABLED = "prettier_enabled"
        private const val KEY_PRETTIER_FORMAT_ON_SAVE = "prettier_format_on_save"
        private const val KEY_PRETTIER_PRINT_WIDTH = "prettier_print_width"
        private const val KEY_PRETTIER_TAB_WIDTH = "prettier_tab_width"
        private const val KEY_PRETTIER_USE_TABS = "prettier_use_tabs"
        private const val KEY_PRETTIER_SEMI = "prettier_semi"
        private const val KEY_PRETTIER_SINGLE_QUOTE = "prettier_single_quote"
        private const val KEY_PRETTIER_JSX_SINGLE_QUOTE = "prettier_jsx_single_quote"
        private const val KEY_PRETTIER_TRAILING_COMMA = "prettier_trailing_comma"
        private const val KEY_PRETTIER_BRACKET_SPACING = "prettier_bracket_spacing"
        private const val KEY_PRETTIER_BRACKET_SAME_LINE = "prettier_bracket_same_line"
        private const val KEY_PRETTIER_ARROW_PARENS = "prettier_arrow_parens"
        private const val KEY_PRETTIER_END_OF_LINE = "prettier_end_of_line"
        private const val KEY_PRETTIER_PROSE_WRAP = "prettier_prose_wrap"
        private const val KEY_PROJECT_TREE_URI = "project_tree_uri"
        private const val KEY_PROJECT_MODE = "project_mode"
        private const val KEY_LOCAL_PROJECT_PATH = "local_project_path"
        private const val KEY_LAST_OPENED_FILE_PATH = "last_opened_file_path"
        private const val KEY_LAST_OPENED_FILE_NAME = "last_opened_file_name"
        private const val KEY_LAST_WIDGET_PROJECT_PATH = "last_widget_project_path"
        private const val KEY_LAST_WIDGET_FILE_NAME = "last_widget_file_name"
        private const val KEY_LAST_WIDGET_FILE_PATH = "last_widget_file_path"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_GITHUB_USERNAME = "github_username"
        private const val KEY_GITHUB_OAUTH_CLIENT_ID = "github_oauth_client_id"
        private const val KEY_DOWNLOAD_NOTIFICATIONS_ENABLED = "download_notifications_enabled"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_TERMINAL_STYLE_INDEX = "terminal_style_index"
        private const val KEY_TERMINAL_CURSOR_STYLE = "terminal_cursor_style"
        private const val KEY_TERMINAL_TOOLBAR_VISIBLE = "terminal_toolbar_visible"
        private const val KEY_TERMINAL_KEEP_SCREEN_ON = "terminal_keep_screen_on"
        private const val KEY_TERMINAL_AUTO_WAKELOCK = "terminal_auto_wakelock"
        private const val KEY_TERMINAL_STICKY_CTRL = "terminal_sticky_ctrl"
        private const val KEY_HAPTIC_FEEDBACK_LEVEL = "haptic_feedback_level"
        private const val KEY_TERMINAL_FONT_SIZE_SP = "terminal_font_size_sp"
        private const val KEY_DEFAULT_SHELL = "default_shell"
        private const val KEY_ZSH_THEME = "zsh_theme"
        private const val KEY_VM_ARCH = "vm_arch"
        private const val KEY_LSP_INSTALLED_PREFIX = "lsp_installed_"
        private const val KEY_LSP_ENABLED_PREFIX = "lsp_enabled_"
        private const val KEY_PLUGIN_ENABLED_PREFIX = "plugin_enabled_"

        const val SHELL_ZSH = "zsh"
        const val SHELL_BASH = "bash"
        const val DEFAULT_ZSH_THEME = "robbyrussell"

        /** Bundled monospace editor fonts: stable id -> resource + display name. */
        val FONTS = listOf(
            Font("jetbrains_mono", R.font.jetbrains_mono, "JetBrains Mono"),
            Font("fira_code", R.font.fira_code, "Fira Code"),
            Font("cascadia_code", R.font.cascadia_code, "Cascadia Code"),
            Font("monaspace", R.font.monaspace, "Monaspace"),
            Font("geist_mono", R.font.geist_mono, "Geist Mono"),
            Font("source_code_pro", R.font.source_code_pro, "Source Code Pro"),
            Font("monolisa", R.font.monolisa, "MonoLisa"),
            Font("intel_one_mono", R.font.intel_one_mono, "Intel One Mono"),
            Font("hack", R.font.hack, "Hack"),
            Font("consolas", R.font.consolas, "Consolas"),
            Font("ubuntu_sans_mono", R.font.ubuntu_sans_mono, "Ubuntu Sans Mono"),
            Font("inconsolata", R.font.inconsolata, "Inconsolata"),
            Font("lilex", R.font.lilex, "Lilex"),
            Font("input_mono", R.font.input_mono, "Input Mono"),
            Font("datatype", R.font.datatype, "Datatype"),
            Font("google_sans_code", R.font.google_sans_code, "Google Sans Code"),
            Font("roboto_mono", R.font.roboto_mono, "Roboto Mono"),
        )

        /**
         * Embedded UI Font System families (4 weights each).
         * Bundled as both raw assets (`assets/fonts/...`) and `res/font/...` binaries so
         * `Typeface.createFromAsset` bypasses OEM/Android system font overrides 100% of the time.
         */
        val UI_FONTS = listOf(
            UiFont(
                id = "plus_jakarta_sans",
                label = "Plus Jakarta Sans",
                regularRes = R.font.plus_jakarta_sans,
                mediumRes = R.font.plus_jakarta_sans_medium,
                semiBoldRes = R.font.plus_jakarta_sans_semibold,
                boldRes = R.font.plus_jakarta_sans_bold,
                ext = "otf"
            ),
            UiFont(
                id = "google_sans",
                label = "Google Sans",
                regularRes = R.font.google_sans,
                mediumRes = R.font.google_sans_medium,
                semiBoldRes = R.font.google_sans_semibold,
                boldRes = R.font.google_sans_bold,
                ext = "otf"
            ),
            UiFont(
                id = "inter",
                label = "Inter",
                regularRes = R.font.inter,
                mediumRes = R.font.inter_medium,
                semiBoldRes = R.font.inter_semibold,
                boldRes = R.font.inter_bold,
                ext = "otf"
            ),
            UiFont(
                id = "geist",
                label = "Geist",
                regularRes = R.font.geist,
                mediumRes = R.font.geist_medium,
                semiBoldRes = R.font.geist_semibold,
                boldRes = R.font.geist_bold,
                ext = "otf"
            ),
            UiFont(
                id = "noto_sans",
                label = "Noto Sans",
                regularRes = R.font.noto_sans,
                mediumRes = R.font.noto_sans_medium,
                semiBoldRes = R.font.noto_sans_semibold,
                boldRes = R.font.noto_sans_bold,
                ext = "ttf"
            ),
            UiFont(
                id = "dm_sans",
                label = "DM Sans",
                regularRes = R.font.dm_sans,
                mediumRes = R.font.dm_sans_medium,
                semiBoldRes = R.font.dm_sans_semibold,
                boldRes = R.font.dm_sans_bold,
                ext = "ttf"
            ),
            UiFont(
                id = "rubik",
                label = "Rubik",
                regularRes = R.font.rubik,
                mediumRes = R.font.rubik_medium,
                semiBoldRes = R.font.rubik_semibold,
                boldRes = R.font.rubik_bold,
                ext = "ttf"
            ),
            UiFont(
                id = "outfit",
                label = "Outfit",
                regularRes = R.font.outfit,
                mediumRes = R.font.outfit_medium,
                semiBoldRes = R.font.outfit_semibold,
                boldRes = R.font.outfit_bold,
                ext = "ttf"
            )
        )

        fun fontById(id: String): Font = FONTS.firstOrNull { it.id == id } ?: FONTS.first()

        fun uiFontById(id: String): UiFont = UI_FONTS.firstOrNull { it.id == id } ?: UI_FONTS.first()

        data class Font(val id: String, val resId: Int, val label: String)

        data class UiFont(
            val id: String,
            val label: String,
            val regularRes: Int,
            val mediumRes: Int,
            val semiBoldRes: Int,
            val boldRes: Int,
            val ext: String = "ttf"
        ) {
            val regularAssetPath: String get() = "fonts/$id.$ext"
            val mediumAssetPath: String get() = "fonts/${id}_medium.$ext"
            val semiBoldAssetPath: String get() = "fonts/${id}_semibold.$ext"
            val boldAssetPath: String get() = "fonts/${id}_bold.$ext"
        }
    }
}
