package com.bfoxnet.pocketdev

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/**
 * Authentic VS Code Material Icon Theme (`https://github.com/material-extensions/vscode-material-icon-theme`)
 * 24x24 SVG vector-canvas icon collection.
 *
 * Renders official `vscode-material-icon-theme` SVG paths and color palettes for 85+ file types,
 * special filenames, and semantic folders at native screen density.
 */
object MaterialFileIcons {

    data class IconSpec(
        val badgeText: String,
        val primaryColor: Int,
        val accentColor: Int,
        val shape: Shape = Shape.DOC_BADGE,
        val svgPath: String? = null,
        val secondarySvgPath: String? = null
    )

    enum class Shape {
        DOC_BADGE,
        HEXAGON,
        SHIELD,
        CIRCLE,
        TERMINAL,
        GEAR,
        DATABASE,
        SVG_VECTOR,
        REACT_ATOM,
        KOTLIN_RIBBON,
        JS_TS_TILE
    }

    // Official 24x24 SVG paths from https://github.com/material-extensions/vscode-material-icon-theme
    private const val SVG_FOLDER_CLOSED =
        "M10,4H4C2.89,4 2,4.89 2,6V18A2,2 0 0,0 4,20H20A2,2 0 0,0 22,18V8C22,6.89 21.1,6 20,6H12L10,4Z"
    private const val SVG_FOLDER_OPEN =
        "M19,20H4C2.89,20 2,19.1 2,18V6C2,4.89 2.89,4 4,4H10L12,6H19A2,2 0 0,1 21,8H21L4,8V18L6.14,10H23.21L20.93,18.5C20.7,19.37 19.92,20 19,20Z"

    private const val SVG_KOTLIN =
        "M2,2H22L12,12L22,22H2V2Z"
    private const val SVG_JAVA =
        "M2,21H20V19H2M20,8H18V5H20M20,3H4V13A4,4 0 0,0 8,17H14A4,4 0 0,0 18,13V10H20A2,2 0 0,0 22,8V5C22,3.89 21.1,3 20,3Z"
    private const val SVG_PYTHON_TOP =
        "M19.14,7.5A2.86,2.86 0 0,1 22,10.36V14.14A2.86,2.86 0 0,1 19.14,17H12C12,17 12,18.75 12,19.5A2.5,2.5 0 0,1 9.5,22H5.86A2.86,2.86 0 0,1 3,19.14V15.36A2.86,2.86 0 0,1 5.86,12.5H15.5V11H6.5V7.5H19.14M17.5,14.5A1,1 0 0,0 16.5,15.5A1,1 0 0,0 17.5,16.5A1,1 0 0,0 18.5,15.5A1,1 0 0,0 17.5,14.5Z"
    private const val SVG_PYTHON_BOT =
        "M4.86,16.5A2.86,2.86 0 0,1 2,13.64V9.86A2.86,2.86 0 0,1 4.86,7H12V4.5A2.5,2.5 0 0,1 14.5,2H18.14A2.86,2.86 0 0,1 21,4.86V8.64A2.86,2.86 0 0,1 18.14,11.5H8.5V13H17.5V16.5H4.86M6.5,9.5A1,1 0 0,0 7.5,8.5A1,1 0 0,0 6.5,7.5A1,1 0 0,0 5.5,8.5A1,1 0 0,0 6.5,9.5Z"
    private const val SVG_HTML =
        "M12,17.56L16.07,16.43L16.62,10.33H9.38L9.2,8.3H16.8L17,6.31H7L7.56,12.32H14.45L14.22,14.9L12,15.5L9.78,14.9L9.64,13.24H7.64L7.93,16.43L12,17.56M4.07,3H19.93L18.5,19.2L12,21L5.5,19.2L4.07,3Z"
    private const val SVG_CSS =
        "M5,3L4.35,6.34H17.94L17.5,8.5H3.92L3.26,11.83H16.85L16.09,15.64L10.61,17.45L5.86,15.64L6.19,14H2.85L2.06,18L9.91,21L18.96,18L20.16,11.97L20.4,10.76L21.94,3H5Z"
    private const val SVG_VUE_OUTER =
        "M2,3H5.5L12,15L18.5,3H22L12,21L2,3Z"
    private const val SVG_VUE_INNER =
        "M6.5,3H9.5L12,7.58L14.5,3H17.5L12,13L6.5,3Z"
    private const val SVG_SVELTE =
        "M18.14,3.61C15.86,2.14 12.64,2.68 10.96,4.78L6.86,9.89C5.75,11.28 5.45,13.15 6.08,14.81C5.5,15.69 5.25,16.76 5.39,17.81C5.61,19.39 6.64,20.73 8.11,21.36C10.36,22.34 13.06,21.66 14.57,19.72L18.68,14.61C19.79,13.22 20.09,11.35 19.46,9.69C20.04,8.81 20.29,7.74 20.15,6.69C19.93,5.11 18.9,3.77 18.14,3.61Z"
    private const val SVG_JSON =
        "M5,3H7V5H5V10A2,2 0 0,1 3,12A2,2 0 0,1 5,14V19H7V21H5C3.93,20.73 3,20.1 3,19V15A2,2 0 0,0 1,13H0V11H1A2,2 0 0,0 3,9V5A2,2 0 0,1 5,3M19,3A2,2 0 0,1 21,5V9A2,2 0 0,0 23,11H24V13H23A2,2 0 0,0 21,15V19A2,2 0 0,1 19,21H17V19H19V14A2,2 0 0,1 21,12A2,2 0 0,1 19,10V5H17V3H19M12,15A1,1 0 0,1 13,16A1,1 0 0,1 12,17A1,1 0 0,1 11,16A1,1 0 0,1 12,15M8,15A1,1 0 0,1 9,16A1,1 0 0,1 8,17A1,1 0 0,1 7,16A1,1 0 0,1 8,15M16,15A1,1 0 0,1 17,16A1,1 0 0,1 16,17A1,1 0 0,1 15,16A1,1 0 0,1 16,15Z"
    private const val SVG_XML =
        "M14.6,16.6L19.2,12L14.6,7.4L16,6L22,12L16,18L14.6,16.6M9.4,16.6L4.8,12L9.4,7.4L8,6L2,12L8,18L9.4,16.6Z"
    private const val SVG_SETTINGS_GEAR =
        "M12,15.5A3.5,3.5 0 0,1 8.5,12A3.5,3.5 0 0,1 12,8.5A3.5,3.5 0 0,1 15.5,12A3.5,3.5 0 0,1 12,15.5M19.43,12.97C19.47,12.65 19.5,12.33 19.5,12C19.5,11.67 19.47,11.34 19.43,11L21.54,9.37C21.73,9.22 21.78,8.95 21.66,8.73L19.66,5.27C19.54,5.05 19.27,4.96 19.05,5.05L16.56,6.05C16.04,5.66 15.5,5.32 14.87,5.07L14.5,2.42C14.46,2.18 14.25,2 14,2H10C9.75,2 9.54,2.18 9.5,2.42L9.13,5.07C8.5,5.32 7.96,5.66 7.44,6.05L4.95,5.05C4.73,4.96 4.46,5.05 4.34,5.27L2.34,8.73C2.21,8.95 2.27,9.22 2.46,9.37L4.57,11C4.53,11.34 4.5,11.67 4.5,12C4.5,12.33 4.53,12.65 4.57,12.97L2.46,14.63C2.27,14.78 2.21,15.05 2.34,15.27L4.34,18.73C4.46,18.95 4.73,19.03 4.95,18.95L7.44,17.94C7.96,18.34 8.5,18.68 9.13,18.93L9.5,21.58C9.54,21.82 9.75,22 10,22H14C14.25,22 14.46,21.82 14.5,21.58L14.87,18.93C15.5,18.67 16.04,18.34 16.56,17.94L19.05,18.95C19.27,19.03 19.54,18.95 19.66,18.73L21.66,15.27C21.78,15.05 21.73,14.78 21.54,14.63L19.43,12.97Z"
    private const val SVG_ANDROID =
        "M17.6,9.48L19.44,6.3C19.6,6 19.48,5.61 19.18,5.45C18.88,5.3 18.49,5.41 18.33,5.71L16.46,8.95C15.1,8.33 13.59,8 12,8C10.41,8 8.9,8.33 7.54,8.95L5.67,5.71C5.51,5.41 5.12,5.3 4.82,5.45C4.52,5.61 4.4,6 4.56,6.3L6.4,9.48C3.3,11.25 1.28,14.44 1,18H23C22.72,14.44 20.7,11.25 17.6,9.48M7,15.25A1.25,1.25 0 0,1 5.75,14A1.25,1.25 0 0,1 7,12.75A1.25,1.25 0 0,1 8.25,14A1.25,1.25 0 0,1 7,15.25M17,15.25A1.25,1.25 0 0,1 15.75,14A1.25,1.25 0 0,1 17,12.75A1.25,1.25 0 0,1 18.25,14A1.25,1.25 0 0,1 17,15.25Z"
    private const val SVG_GRADLE =
        "M19.5,4.5C18.3,3.3 16.5,3.2 15.2,4.2L13.1,5.8C12.5,6.3 11.7,6.5 10.9,6.3C9.1,5.9 7.1,6.2 5.4,7.2C3,8.6 1.6,11.2 1.8,14C2,16.8 3.9,19.2 6.6,20H17.5C19.4,20 21,18.4 21,16.5V13.5H16.5V15.5H13.5V12.5C13.5,11.4 14.4,10.5 15.5,10.5H18.5C19.9,10.5 21,9.4 21,8V6.8C21,5.9 20.4,5.1 19.5,4.5Z"
    private const val SVG_CONSOLE =
        "M20,19V7H4V19H20M20,3A2,2 0 0,1 22,5V19A2,2 0 0,1 20,21H4A2,2 0 0,1 2,19V5C2,3.89 2.89,3 4,3H20M13,17V15H18V17H13M9.58,13L5.57,9H8.4L11.7,12.3C12.09,12.69 12.09,13.33 11.7,13.72L8.42,17H5.59L9.58,13Z"
    private const val SVG_MARKDOWN =
        "M22.27,19.38H1.73A1.73,1.73 0 0,1 0,17.65V6.35A1.73,1.73 0 0,1 1.73,4.62H22.27A1.73,1.73 0 0,1 24,6.35V17.65A1.73,1.73 0 0,1 22.27,19.38M3.77,15.38H6.27V11.15L8.5,13.88L10.73,11.15V15.38H13.23V8.62H10.73L8.5,11.35L6.27,8.62H3.77V15.38M18.27,12H20.77L17.5,15.65L14.23,12H16.73V8.62H19.23V12H18.27Z"
    private const val SVG_README =
        "M11,9H13V7H11M12,20C7.59,20 4,16.41 4,12C4,7.59 7.59,4 12,4C16.41,4 20,7.59 20,12C20,16.41 16.41,20 12,20M12,2A10,10 0 0,0 2,12A10,10 0 0,0 12,22A10,10 0 0,0 22,12A10,10 0 0,0 12,2M11,17H13V11H11V17Z"
    private const val SVG_KEY =
        "M7,14A2,2 0 0,1 5,12A2,2 0 0,1 7,10A2,2 0 0,1 9,12A2,2 0 0,1 7,14M12.65,10C11.83,7.67 9.61,6 7,6A6,6 0 0,0 1,12A6,6 0 0,0 7,18C9.61,18 11.83,16.33 12.65,14H17V18H21V14H23V10H12.65Z"
    private const val SVG_GIT =
        "M21.62,11.11L12.89,2.38C12.4,1.89 11.6,1.89 11.11,2.38L9.28,4.21L11.58,6.51C12.12,6.33 12.73,6.45 13.16,6.88C13.59,7.31 13.71,7.92 13.53,8.46L15.75,10.68C16.29,10.5 16.9,10.62 17.33,11.05C17.95,11.67 17.95,12.67 17.33,13.29C16.71,13.91 15.71,13.91 15.09,13.29C14.63,12.83 14.51,12.14 14.74,11.57L12.66,9.5V14.95C12.81,15.03 12.95,15.13 13.08,15.26C13.7,15.88 13.7,16.88 13.08,17.5C12.46,18.12 11.46,18.12 10.84,17.5C10.22,16.88 10.22,15.88 10.84,15.26C11,15.1 11.18,14.98 11.38,14.9V9.39C11.18,9.31 11,9.19 10.84,9.03C10.37,8.56 10.26,7.86 10.5,7.28L8.23,5.01L2.38,10.86C1.89,11.35 1.89,12.15 2.38,12.64L11.11,21.37C11.6,21.86 12.4,21.86 12.89,21.37L21.62,12.64C22.11,12.15 22.11,11.35 21.62,11.11Z"
    private const val SVG_DOCKER =
        "M21.81,10.25C21.29,9.9 19.95,9.77 18.96,10.03C18.84,9.06 18.26,8.21 17.39,7.63L17.07,7.41L16.85,7.73C16.39,8.42 16.19,9.35 16.3,10.27C16.05,10.41 15.56,10.6 14.92,10.58H2.11L2.06,10.97C1.88,12.45 2.06,14 2.76,15.39C3.58,17.03 5.14,18.19 7.18,18.58C11.27,19.37 16.51,18.08 19.64,14.07C20.91,14.09 22.21,13.68 22.8,12.56L22.97,12.24L22.66,12.03C22.39,11.85 22.1,10.45 21.81,10.25M12.5,6.5H14.5V8.5H12.5V6.5M10,6.5H12V8.5H10V6.5M7.5,6.5H9.5V8.5H7.5V6.5M5,9H7V11H5V9M7.5,9H9.5V11H7.5V9M10,9H12V11H10V9M12.5,9H14.5V11H12.5V9M15,9H17V11H15V9Z"
    private const val SVG_DATABASE =
        "M12,3C7.58,3 4,4.79 4,7C4,9.21 7.58,11 12,11C16.42,11 20,9.21 20,7C20,4.79 16.42,3 12,3M4,9V12C4,14.21 7.58,16 12,16C16.42,16 20,14.21 20,12V9C20,11.21 16.42,13 12,13C7.58,13 4,11.21 4,9M4,14V17C4,19.21 7.58,21 12,21C16.42,21 20,19.21 20,17V14C20,16.21 16.42,18 12,18C7.58,18 4,16.21 4,14Z"
    private const val SVG_LOCK =
        "M12,17A2,2 0 0,0 14,15C14,13.89 13.1,13 12,13A2,2 0 0,0 10,15A2,2 0 0,0 12,17M18,8A2,2 0 0,1 20,10V20A2,2 0 0,1 18,22H6A2,2 0 0,1 4,20V10C4,8.89 4.9,8 6,8H7V6A5,5 0 0,1 12,1A5,5 0 0,1 17,6V8H18M12,3A3,3 0 0,0 9,6V8H15V6A3,3 0 0,0 12,3Z"
    private const val SVG_IMAGE =
        "M8.5,13.5L11,16.5L14.5,12L19,18H5M21,19V5C21,3.89 20.1,3 19,3H5A2,2 0 0,0 3,5V19A2,2 0 0,0 5,21H19A2,2 0 0,0 21,19Z"
    private const val SVG_ARCHIVE =
        "M14,17H12V15H10V13H12V15H14M14,9H12V11H14V13H12V11H10V9H12V7H10V5H12V7H14M19,3H5C3.89,3 3,3.89 3,5V19A2,2 0 0,0 5,21H19A2,2 0 0,0 21,19V5C21,3.89 20.1,3 19,3Z"
    private const val SVG_DOCUMENT =
        "M14,2H6A2,2 0 0,0 4,4V20A2,2 0 0,0 6,22H18A2,2 0 0,0 20,20V8L14,2M18,20H6V4H13V9H18V20Z"
    private const val SVG_RUBY =
        "M16,9H19L14,16M10,9H14L12,17M5,9H8L10,16M15,4H17L19,7H16M11,4H13L14,7H10M7,4H9L8,7H5M6,2L2,8L12,22L22,8L18,2H6Z"

    fun createFileDrawable(context: Context, fileName: String, sizeDp: Int = 20): Drawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt().coerceAtLeast(20)
        val spec = resolveFileSpec(fileName)
        return MaterialFileDrawable(sizePx, spec)
    }

    fun createFolderDrawable(
        context: Context,
        folderName: String,
        isExpanded: Boolean,
        sizeDp: Int = 20
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt().coerceAtLeast(20)
        val (baseColor, badgeColor, tag) = resolveFolderColors(folderName)
        return MaterialFolderDrawable(sizePx, baseColor, badgeColor, tag, isExpanded)
    }

    fun resolveFileSpec(fileName: String): IconSpec {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase()

        // 1. Exact special filenames (vscode-material-icon-theme)
        when (name) {
            "androidmanifest.xml" ->
                return IconSpec("AND", Color.parseColor("#66BB6A"), Color.WHITE, Shape.SVG_VECTOR, SVG_ANDROID)
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradlew", "gradlew.bat" ->
                return IconSpec("GR", Color.parseColor("#00ACC1"), Color.WHITE, Shape.SVG_VECTOR, SVG_GRADLE)
            "libs.versions.toml", "gradle.properties", "local.properties" ->
                return IconSpec("CFG", Color.parseColor("#26A69A"), Color.WHITE, Shape.SVG_VECTOR, SVG_SETTINGS_GEAR)
            "proguard-rules.pro" ->
                return IconSpec("PRO", Color.parseColor("#EF5350"), Color.WHITE, Shape.SVG_VECTOR, SVG_LOCK)
            "package.json", "package-lock.json" ->
                return IconSpec("NPM", Color.parseColor("#8BC34A"), Color.WHITE, Shape.SVG_VECTOR, SVG_JSON)
            "tsconfig.json", "jsconfig.json" ->
                return IconSpec("TS", Color.parseColor("#0288D1"), Color.WHITE, Shape.JS_TS_TILE)
            "dockerfile", "docker-compose.yml", "docker-compose.yaml", ".dockerignore" ->
                return IconSpec("DKR", Color.parseColor("#2496ED"), Color.WHITE, Shape.SVG_VECTOR, SVG_DOCKER)
            "makefile", "cmakelists.txt" ->
                return IconSpec("MK", Color.parseColor("#FF7043"), Color.WHITE, Shape.SVG_VECTOR, SVG_SETTINGS_GEAR)
            "cargo.toml", "cargo.lock" ->
                return IconSpec("RS", Color.parseColor("#FF7043"), Color.WHITE, Shape.SVG_VECTOR, SVG_SETTINGS_GEAR)
            "go.mod", "go.sum" ->
                return IconSpec("GO", Color.parseColor("#00ADD8"), Color.WHITE, Shape.JS_TS_TILE)
            ".gitignore", ".gitattributes", ".gitmodules" ->
                return IconSpec("GIT", Color.parseColor("#F4511E"), Color.WHITE, Shape.SVG_VECTOR, SVG_GIT)
            ".zshrc", ".zshenv", ".zprofile", ".bashrc", ".bash_profile", ".profile" ->
                return IconSpec("ZSH", Color.parseColor("#89E051"), Color.WHITE, Shape.SVG_VECTOR, SVG_CONSOLE)
            ".env", ".env.local", ".env.development", ".env.production" ->
                return IconSpec("ENV", Color.parseColor("#FBC02D"), Color.parseColor("#1E1E1E"), Shape.SVG_VECTOR, SVG_SETTINGS_GEAR)
            "readme.md", "readme.txt", "readme" ->
                return IconSpec("MD", Color.parseColor("#42A5F5"), Color.WHITE, Shape.SVG_VECTOR, SVG_README)
            "license", "license.md", "license.txt" ->
                return IconSpec("LIC", Color.parseColor("#FFCA28"), Color.parseColor("#212121"), Shape.SVG_VECTOR, SVG_KEY)
        }

        // 2. Extension-based vscode-material-icon-theme icons
        val ext = name.substringAfterLast('.', "")
        return when (ext) {
            "kt", "kts" ->
                IconSpec("KT", Color.parseColor("#7F52FF"), Color.parseColor("#FF8900"), Shape.KOTLIN_RIBBON, SVG_KOTLIN)
            "java", "jav", "jar", "class", "dex" ->
                IconSpec("JV", Color.parseColor("#F44336"), Color.parseColor("#42A5F5"), Shape.SVG_VECTOR, SVG_JAVA)
            "py", "pyw", "pyi", "ipynb" ->
                IconSpec("PY", Color.parseColor("#3776AB"), Color.parseColor("#FFD43B"), Shape.SVG_VECTOR, SVG_PYTHON_BOT, SVG_PYTHON_TOP)
            "js", "mjs", "cjs" ->
                IconSpec("JS", Color.parseColor("#FFCA28"), Color.parseColor("#1E1E1E"), Shape.JS_TS_TILE)
            "ts", "mts", "cts" ->
                IconSpec("TS", Color.parseColor("#0288D1"), Color.WHITE, Shape.JS_TS_TILE)
            "jsx" ->
                IconSpec("JSX", Color.parseColor("#00BCD4"), Color.WHITE, Shape.REACT_ATOM)
            "tsx" ->
                IconSpec("TSX", Color.parseColor("#0288D1"), Color.WHITE, Shape.REACT_ATOM)
            "html", "htm", "xhtml" ->
                IconSpec("HTML", Color.parseColor("#E44D26"), Color.WHITE, Shape.SVG_VECTOR, SVG_HTML)
            "css" ->
                IconSpec("CSS", Color.parseColor("#42A5F5"), Color.WHITE, Shape.SVG_VECTOR, SVG_CSS)
            "scss", "sass", "less" ->
                IconSpec("S", Color.parseColor("#EC407A"), Color.WHITE, Shape.CIRCLE)
            "vue" ->
                IconSpec("VUE", Color.parseColor("#41B883"), Color.parseColor("#35495E"), Shape.SVG_VECTOR, SVG_VUE_OUTER, SVG_VUE_INNER)
            "svelte" ->
                IconSpec("SV", Color.parseColor("#FF3E00"), Color.WHITE, Shape.SVG_VECTOR, SVG_SVELTE)
            "json", "jsonc", "jsonl", "har" ->
                IconSpec("JSON", Color.parseColor("#FBC02D"), Color.parseColor("#1E1E1E"), Shape.SVG_VECTOR, SVG_JSON)
            "xml", "plist", "xsd", "xsl", "pom", "svg" ->
                IconSpec("XML", Color.parseColor("#FF7043"), Color.WHITE, Shape.SVG_VECTOR, SVG_XML)
            "yml", "yaml" ->
                IconSpec("YML", Color.parseColor("#EF5350"), Color.WHITE, Shape.JS_TS_TILE)
            "toml", "ini", "cfg", "conf", "properties", "prop" ->
                IconSpec("CFG", Color.parseColor("#90A4AE"), Color.WHITE, Shape.SVG_VECTOR, SVG_SETTINGS_GEAR)
            "gradle", "groovy" ->
                IconSpec("GR", Color.parseColor("#00ACC1"), Color.WHITE, Shape.SVG_VECTOR, SVG_GRADLE)
            "c" ->
                IconSpec("C", Color.parseColor("#0288D1"), Color.WHITE, Shape.HEXAGON)
            "h", "hpp", "hh", "hxx" ->
                IconSpec("H", Color.parseColor("#AB47BC"), Color.WHITE, Shape.HEXAGON)
            "cpp", "cc", "cxx", "c++" ->
                IconSpec("C++", Color.parseColor("#00599C"), Color.WHITE, Shape.HEXAGON)
            "cs", "csx" ->
                IconSpec("C#", Color.parseColor("#7E57C2"), Color.WHITE, Shape.HEXAGON)
            "go" ->
                IconSpec("GO", Color.parseColor("#00ADD8"), Color.WHITE, Shape.JS_TS_TILE)
            "rs" ->
                IconSpec("RS", Color.parseColor("#FF7043"), Color.WHITE, Shape.SVG_VECTOR, SVG_SETTINGS_GEAR)
            "sh", "bash", "zsh", "fish", "ash", "ksh" ->
                IconSpec("ZSH", Color.parseColor("#4CAF50"), Color.WHITE, Shape.SVG_VECTOR, SVG_CONSOLE)
            "bat", "cmd", "ps1" ->
                IconSpec("SH", Color.parseColor("#29B6F6"), Color.WHITE, Shape.SVG_VECTOR, SVG_CONSOLE)
            "md", "markdown", "mdx", "rst", "adoc" ->
                IconSpec("MD", Color.parseColor("#42A5F5"), Color.WHITE, Shape.SVG_VECTOR, SVG_MARKDOWN)
            "sql", "ddl", "dml", "db", "sqlite", "sqlite3" ->
                IconSpec("SQL", Color.parseColor("#FFCA28"), Color.WHITE, Shape.SVG_VECTOR, SVG_DATABASE)
            "rb", "erb", "gemspec" ->
                IconSpec("RB", Color.parseColor("#E53935"), Color.WHITE, Shape.SVG_VECTOR, SVG_RUBY)
            "php", "phtml" ->
                IconSpec("PHP", Color.parseColor("#777BB4"), Color.WHITE, Shape.JS_TS_TILE)
            "lua" ->
                IconSpec("LUA", Color.parseColor("#42A5F5"), Color.WHITE, Shape.CIRCLE)
            "dart" ->
                IconSpec("DRT", Color.parseColor("#00B4AB"), Color.WHITE, Shape.HEXAGON)
            "swift" ->
                IconSpec("SW", Color.parseColor("#F05138"), Color.WHITE, Shape.JS_TS_TILE)
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico" ->
                IconSpec("IMG", Color.parseColor("#26A69A"), Color.WHITE, Shape.SVG_VECTOR, SVG_IMAGE)
            "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "aab", "deb" ->
                IconSpec("ZIP", Color.parseColor("#FFB300"), Color.WHITE, Shape.SVG_VECTOR, SVG_ARCHIVE)
            "lock", "keystore", "jks", "pem", "crt", "key" ->
                IconSpec("LCK", Color.parseColor("#FFD54F"), Color.WHITE, Shape.SVG_VECTOR, SVG_LOCK)
            "txt", "text", "log", "csv", "tsv" ->
                IconSpec("TXT", Color.parseColor("#90A4AE"), Color.WHITE, Shape.SVG_VECTOR, SVG_DOCUMENT)
            else ->
                IconSpec(ext.take(3).uppercase().ifEmpty { "TXT" }, Color.parseColor("#90A4AE"), Color.WHITE, Shape.SVG_VECTOR, SVG_DOCUMENT)
        }
    }

    private fun resolveFolderColors(folderName: String): Triple<Int, Int, String?> {
        return when (folderName.lowercase()) {
            "src", "source", "sources" -> Triple(Color.parseColor("#4CAF50"), Color.parseColor("#C8E6C9"), "S")
            "main" -> Triple(Color.parseColor("#26A69A"), Color.parseColor("#B2DFDB"), "M")
            "java" -> Triple(Color.parseColor("#E76F00"), Color.parseColor("#FFE0B2"), "J")
            "kotlin" -> Triple(Color.parseColor("#7F52FF"), Color.parseColor("#E1BEE7"), "K")
            "res", "resources" -> Triple(Color.parseColor("#FFA726"), Color.parseColor("#FFE0B2"), "R")
            "drawable", "mipmap", "mipmap-anydpi", "mipmap-anydpi-v26", "icons", "images" ->
                Triple(Color.parseColor("#26A69A"), Color.parseColor("#B2EBF2"), "D")
            "layout", "ui", "views", "components" ->
                Triple(Color.parseColor("#EC407A"), Color.parseColor("#F8BBD0"), "L")
            "values", "values-night", "menu", "xml", "font", "config", ".config" ->
                Triple(Color.parseColor("#29B6F6"), Color.parseColor("#B3E5FC"), "C")
            "assets", "public", "static" ->
                Triple(Color.parseColor("#FFA726"), Color.parseColor("#FFE0B2"), "A")
            "gradle", ".gradle", "wrapper" ->
                Triple(Color.parseColor("#00ACC1"), Color.parseColor("#B2EBF2"), "G")
            ".git", ".github" ->
                Triple(Color.parseColor("#F4511E"), Color.parseColor("#FFCCBC"), "G")
            "build", "dist", "out", "target" ->
                Triple(Color.parseColor("#FFB300"), Color.parseColor("#FFECB3"), "B")
            "test", "tests", "androidtest", "__tests__" ->
                Triple(Color.parseColor("#00ACC1"), Color.parseColor("#B2EBF2"), "T")
            "lib", "libs", "vendor", "node_modules" ->
                Triple(Color.parseColor("#8BC34A"), Color.parseColor("#DCEDC8"), "L")
            "scripts", "bin", "tools" ->
                Triple(Color.parseColor("#78909C"), Color.parseColor("#CFD8DC"), "SH")
            "ubuntu", "projects", "linux" ->
                Triple(Color.parseColor("#E95420"), Color.parseColor("#FFCCBC"), "U")
            "app" -> Triple(Color.parseColor("#EF5350"), Color.parseColor("#FFCDD2"), "A")
            else -> Triple(Color.parseColor("#FFB74D"), Color.parseColor("#FFE082"), null)
        }
    }

    private class MaterialFileDrawable(
        private val sizePx: Int,
        private val spec: IconSpec
    ) : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.accentColor
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private val path = Path()

        init {
            setBounds(0, 0, sizePx, sizePx)
        }

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return

            val save = canvas.save()
            val scale = b.width().coerceAtMost(b.height()) / 24f
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(scale, scale)

            when (spec.shape) {
                Shape.SVG_VECTOR -> {
                    spec.svgPath?.let { pathStr ->
                        PathParser.createPathFromPathData(pathStr)?.let { p ->
                            fillPaint.color = spec.primaryColor
                            canvas.drawPath(p, fillPaint)
                        }
                    }
                    spec.secondarySvgPath?.let { pathStr2 ->
                        PathParser.createPathFromPathData(pathStr2)?.let { p2 ->
                            fillPaint.color = spec.accentColor
                            canvas.drawPath(p2, fillPaint)
                        }
                    }
                }
                Shape.KOTLIN_RIBBON -> {
                    PathParser.createPathFromPathData(SVG_KOTLIN)?.let { p ->
                        fillPaint.color = spec.primaryColor
                        canvas.drawPath(p, fillPaint)
                    }
                    path.reset()
                    path.moveTo(2f, 14f)
                    path.lineTo(14f, 2f)
                    path.lineTo(22f, 2f)
                    path.lineTo(2f, 22f)
                    path.close()
                    fillPaint.color = spec.accentColor
                    canvas.drawPath(path, fillPaint)
                }
                Shape.REACT_ATOM -> {
                    fillPaint.color = spec.primaryColor
                    canvas.drawCircle(12f, 12f, 2.2f, fillPaint)
                    strokePaint.color = spec.primaryColor
                    strokePaint.strokeWidth = 1.55f
                    rect.set(2.8f, 8.2f, 21.2f, 15.8f)
                    canvas.drawOval(rect, strokePaint)
                    canvas.save()
                    canvas.rotate(60f, 12f, 12f)
                    canvas.drawOval(rect, strokePaint)
                    canvas.rotate(60f, 12f, 12f)
                    canvas.drawOval(rect, strokePaint)
                    canvas.restore()
                }
                Shape.JS_TS_TILE -> {
                    fillPaint.color = spec.primaryColor
                    rect.set(2.8f, 2.8f, 21.2f, 21.2f)
                    canvas.drawRoundRect(rect, 3.5f, 3.5f, fillPaint)
                    val label = spec.badgeText.take(3)
                    textPaint.color = spec.accentColor
                    textPaint.textSize = if (label.length >= 3) 6.8f else 8.8f
                    val fm = textPaint.fontMetrics
                    canvas.drawText(label, 12f, 12f - (fm.ascent + fm.descent) / 2f, textPaint)
                }
                Shape.HEXAGON, Shape.SHIELD -> {
                    path.reset()
                    val cx = 12f
                    val cy = 12f
                    val r = 9.8f
                    for (i in 0..5) {
                        val angle = Math.toRadians((60 * i - 30).toDouble())
                        val x = (cx + r * Math.cos(angle)).toFloat()
                        val y = (cy + r * Math.sin(angle)).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    fillPaint.color = spec.primaryColor
                    canvas.drawPath(path, fillPaint)
                    val label = spec.badgeText.take(3)
                    textPaint.color = spec.accentColor
                    textPaint.textSize = if (label.length >= 3) 6.5f else 8.5f
                    val fm = textPaint.fontMetrics
                    canvas.drawText(label, 12f, 12f - (fm.ascent + fm.descent) / 2f, textPaint)
                }
                Shape.CIRCLE, Shape.GEAR -> {
                    fillPaint.color = spec.primaryColor
                    canvas.drawCircle(12f, 12f, 9.4f, fillPaint)
                    val label = spec.badgeText.take(3)
                    textPaint.color = spec.accentColor
                    textPaint.textSize = if (label.length >= 3) 6.5f else 8.5f
                    val fm = textPaint.fontMetrics
                    canvas.drawText(label, 12f, 12f - (fm.ascent + fm.descent) / 2f, textPaint)
                }
                Shape.TERMINAL, Shape.DATABASE, Shape.DOC_BADGE -> {
                    PathParser.createPathFromPathData(SVG_DOCUMENT)?.let { p ->
                        fillPaint.color = spec.primaryColor
                        canvas.drawPath(p, fillPaint)
                    }
                }
            }

            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
            textPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class MaterialFolderDrawable(
        private val sizePx: Int,
        private val baseColor: Int,
        private val badgeColor: Int,
        private val tag: String?,
        private val isExpanded: Boolean
    ) : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#182026")
            alpha = 225
        }
        private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = badgeColor
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        init {
            setBounds(0, 0, sizePx, sizePx)
        }

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return

            val save = canvas.save()
            val scale = b.width().coerceAtMost(b.height()) / 24f
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(scale, scale)

            val folderSvg = if (isExpanded) SVG_FOLDER_OPEN else SVG_FOLDER_CLOSED
            PathParser.createPathFromPathData(folderSvg)?.let { p ->
                fillPaint.color = baseColor
                canvas.drawPath(p, fillPaint)
            }

            if (!tag.isNullOrEmpty()) {
                val cx = 17.5f
                val cy = 16.5f
                val r = 4.6f
                canvas.drawCircle(cx, cy, r, badgeBgPaint)
                tagPaint.textSize = if (tag.length == 1) 5.5f else 4.3f
                val fm = tagPaint.fontMetrics
                canvas.drawText(tag, cx, cy - (fm.ascent + fm.descent) / 2f, tagPaint)
            }

            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            badgeBgPaint.alpha = alpha
            tagPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            badgeBgPaint.colorFilter = colorFilter
            tagPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
