package com.bfoxnet.pocketdev

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings backed by SharedPreferences. Persists editor preferences,
 * font ligatures, word-match highlighting, code folding, sticky-scroll headers,
 * Auto-Save & Save-All behavior, active project directory inside the Ubuntu rootfs
 * (`/root/projects/<name>`), Termux terminal style/cursor/toolbar/sticky-Ctrl/wake-lock
 * and extra-key haptic feedback states, default Linux shell (`zsh` with `robbyrussell`
 * theme and plugins, or `bash`), first-run onboarding state, and Language Server Protocol (LSP) states.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pocketdev", Context.MODE_PRIVATE)

    /** Editor color scheme id. */
    var editorTheme: String
        get() = prefs.getString(KEY_EDITOR_THEME, "darcula") ?: "darcula"
        set(value) = prefs.edit().putString(KEY_EDITOR_THEME, value).apply()

    /** Font family id, resolved against [FONTS]. */
    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, FONTS.first().id) ?: FONTS.first().id
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

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

    /** Enable code folding ranges & block lines in the editor. Enabled by default. */
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

    /** Persistent SAF DocumentTree URI string (legacy import fallback). */
    var projectTreeUri: String?
        get() = prefs.getString(KEY_PROJECT_TREE_URI, null)
        set(value) = prefs.edit().putString(KEY_PROJECT_TREE_URI, value).apply()

    /** Active project root mode: always defaults to "local" (Ubuntu rootfs projects dir). */
    var projectMode: String
        get() = prefs.getString(KEY_PROJECT_MODE, "local") ?: "local"
        set(value) = prefs.edit().putString(KEY_PROJECT_MODE, value).apply()

    /** Active project directory path inside the Ubuntu directory (`$filesDir/ubuntu/root/projects/<name>`). */
    var localProjectPath: String?
        get() = prefs.getString(KEY_LOCAL_PROJECT_PATH, null)
        set(value) = prefs.edit().putString(KEY_LOCAL_PROJECT_PATH, value).apply()

    /** Last opened file path for the "Continue coding" home-screen widget. */
    var lastOpenedFilePath: String?
        get() = prefs.getString(KEY_LAST_OPENED_FILE_PATH, null)
        set(value) = prefs.edit().putString(KEY_LAST_OPENED_FILE_PATH, value).apply()

    /** Last opened file display name for the "Continue coding" home-screen widget. */
    var lastOpenedFileName: String?
        get() = prefs.getString(KEY_LAST_OPENED_FILE_NAME, "Main.kt")
        set(value) = prefs.edit().putString(KEY_LAST_OPENED_FILE_NAME, value).apply()

    /** Last opened project path for the "Continue coding" home-screen widget. */
    var lastWidgetProjectPath: String?
        get() = prefs.getString(KEY_LAST_WIDGET_PROJECT_PATH, null)
        set(value) = prefs.edit().putString(KEY_LAST_WIDGET_PROJECT_PATH, value).apply()

    /** GitHub OAuth/PAT token for push/pull in the Ubuntu PRoot terminal. */
    var githubToken: String
        get() = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_TOKEN, value).apply()

    /** GitHub username for the authenticated account. */
    var githubUsername: String
        get() = prefs.getString(KEY_GITHUB_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_USERNAME, value).apply()

    /** Optional GitHub OAuth App Client ID used for the Device Flow sign-in. */
    var githubOAuthClientId: String
        get() = prefs.getString(KEY_GITHUB_OAUTH_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB_OAUTH_CLIENT_ID, value).apply()

    /** Whether DiagLogger may post "task finished" notifications while downloads/builds run. */
    var downloadNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOWNLOAD_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DOWNLOAD_NOTIFICATIONS_ENABLED, value).apply()

    /** Last opened file display name for the "Continue coding" home-screen widget. */
    var lastWidgetFileName: String?
        get() = prefs.getString(KEY_LAST_WIDGET_FILE_NAME, "")
        set(value) = prefs.edit().putString(KEY_LAST_WIDGET_FILE_NAME, value).apply()

    /** Last opened file absolute path for the "Continue coding" home-screen widget. */
    var lastWidgetFilePath: String?
        get() = prefs.getString(KEY_LAST_WIDGET_FILE_PATH, "")
        set(value) = prefs.edit().putString(KEY_LAST_WIDGET_FILE_PATH, value).apply()

    /** True once the user has completed or dismissed the first-run onboarding guide. */
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    /** Persisted Termux color style palette index (`0..7`). */
    var terminalStyleIndex: Int
        get() = prefs.getInt(KEY_TERMINAL_STYLE_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_TERMINAL_STYLE_INDEX, value).apply()

    /** Persisted Termux cursor style (`0 = block`, `1 = underline`, `2 = bar`). */
    var terminalCursorStyle: Int
        get() = prefs.getInt(KEY_TERMINAL_CURSOR_STYLE, -1)
        set(value) = prefs.edit().putInt(KEY_TERMINAL_CURSOR_STYLE, value).apply()

    /** Persisted Termux helper Extra Keys toolbar visibility. */
    var terminalToolbarVisible: Boolean
        get() = prefs.getBoolean(KEY_TERMINAL_TOOLBAR_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_TERMINAL_TOOLBAR_VISIBLE, value).apply()

    /** Persisted Termux Keep Screen On toggle. */
    var terminalKeepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_TERMINAL_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_TERMINAL_KEEP_SCREEN_ON, value).apply()

    /** Auto keep-screen-on & CPU wake-lock while a terminal command is actively running. Enabled by default. */
    var terminalAutoWakeLock: Boolean
        get() = prefs.getBoolean(KEY_TERMINAL_AUTO_WAKELOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_TERMINAL_AUTO_WAKELOCK, value).apply()

    /** Toggle-sticky Ctrl key mode (stays latched across keys until toggled off, rather than hold-only). */
    var terminalStickyCtrl: Boolean
        get() = prefs.getBoolean(KEY_TERMINAL_STICKY_CTRL, true)
        set(value) = prefs.edit().putBoolean(KEY_TERMINAL_STICKY_CTRL, value).apply()

    /** Extra keys haptic feedback intensity: 0 = Off, 1 = Light, 2 = Medium, 3 = Strong. */
    var hapticFeedbackLevel: Int
        get() = prefs.getInt(KEY_HAPTIC_FEEDBACK_LEVEL, 1)
        set(value) = prefs.edit().putInt(KEY_HAPTIC_FEEDBACK_LEVEL, value.coerceIn(0, 3)).apply()

    /** Persisted terminal font size in sp. */
    var terminalFontSizeSp: Float
        get() = prefs.getFloat(KEY_TERMINAL_FONT_SIZE_SP, 13.5f)
        set(value) = prefs.edit().putFloat(KEY_TERMINAL_FONT_SIZE_SP, value).apply()

    /** Default interactive shell inside Ubuntu PRoot ("zsh" with robbyrussell theme + plugins, or "bash"). */
    var defaultShell: String
        get() = prefs.getString(KEY_DEFAULT_SHELL, SHELL_ZSH) ?: SHELL_ZSH
        set(value) = prefs.edit().putString(KEY_DEFAULT_SHELL, value).apply()

    /** Default Zsh theme inside Ubuntu PRoot (defaults to Oh My Zsh's "robbyrussell"). */
    var zshTheme: String
        get() = prefs.getString(KEY_ZSH_THEME, DEFAULT_ZSH_THEME) ?: DEFAULT_ZSH_THEME
        set(value) = prefs.edit().putString(KEY_ZSH_THEME, value).apply()

    /** Detected host hardware ABI for Ubuntu PRoot ("aarch64", "x86_64", "arm"). */
    var vmArch: String
        get() = prefs.getString(KEY_VM_ARCH, UbuntuProotManager.detectHostAbi())
            ?: UbuntuProotManager.detectHostAbi()
        set(value) = prefs.edit().putString(KEY_VM_ARCH, value).apply()

    /** Returns true if the user has installed the given Language Server Protocol [serverId]. */
    fun isLspInstalled(serverId: String): Boolean =
        prefs.getBoolean("$KEY_LSP_INSTALLED_PREFIX$serverId", false)

    fun setLspInstalled(serverId: String, installed: Boolean) {
        prefs.edit().putBoolean("$KEY_LSP_INSTALLED_PREFIX$serverId", installed).apply()
    }

    /** Returns true if the installed Language Server Protocol [serverId] is enabled for the editor. */
    fun isLspEnabled(serverId: String): Boolean =
        prefs.getBoolean("$KEY_LSP_ENABLED_PREFIX$serverId", true)

    fun setLspEnabled(serverId: String, enabled: Boolean) {
        prefs.edit().putBoolean("$KEY_LSP_ENABLED_PREFIX$serverId", enabled).apply()
    }

    companion object {
        private const val KEY_EDITOR_THEME = "editor_theme"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_WORD_WRAP = "word_wrap"
        private const val KEY_FONT_LIGATURES = "font_ligatures"
        private const val KEY_WORD_MATCH_HIGHLIGHT = "word_match_highlight"
        private const val KEY_CODE_FOLDING = "code_folding"
        private const val KEY_STICKY_SCROLL = "sticky_scroll"
        private const val KEY_AUTO_SAVE_ENABLED = "auto_save_enabled"
        private const val KEY_AUTO_SAVE_DELAY_MS = "auto_save_delay_ms"
        private const val KEY_SAVE_ALL_ON_SAVE_PRESS = "save_all_on_save_press"
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

        const val SHELL_ZSH = "zsh"
        const val SHELL_BASH = "bash"
        const val DEFAULT_ZSH_THEME = "robbyrussell"

        /** Bundled monospace fonts: stable id -> resource + display name. */
        val FONTS = listOf(
            Font("jetbrains_mono", R.font.jetbrains_mono, "JetBrains Mono"),
            Font("fira_code", R.font.fira_code, "Fira Code"),
            Font("google_sans_code", R.font.google_sans_code, "Google Sans Code"),
            Font("roboto_mono", R.font.roboto_mono, "Roboto Mono"),
        )

        fun fontById(id: String): Font = FONTS.firstOrNull { it.id == id } ?: FONTS.first()

        data class Font(val id: String, val resId: Int, val label: String)
    }
}
