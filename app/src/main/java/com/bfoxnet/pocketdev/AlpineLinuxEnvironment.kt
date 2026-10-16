package com.bfoxnet.pocketdev

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Compatibility bridge & Termux `.termux/termux.properties` configuration manager
 * delegating all rootfs and runtime operations to [UbuntuProotManager].
 *
 * All legacy fake shell-script shims have been completely removed.
 */
object AlpineLinuxEnvironment {

    const val ALPINE_VERSION = UbuntuProotManager.UBUNTU_VERSION
    const val ALPINE_BRANCH = UbuntuProotManager.UBUNTU_CODENAME
    const val TERMUX_VERSION = "0.118.3"

    data class TermuxProperties(
        val extraKeys: List<List<String>>,
        val cursorStyle: Int, // 0 = block, 1 = underline, 2 = bar
        val cursorBlinkRate: Int,
        val bellBehavior: String, // "vibrate", "beep", "ignore"
        val marginHorizontalDp: Int,
        val marginVerticalDp: Int,
        val fullscreen: Boolean
    )

    data class ApkPackageSpec(
        val name: String,
        val version: String,
        val description: String
    )

    val CATALOG: List<ApkPackageSpec> = listOf(
        ApkPackageSpec("golang-go", "2:1.22~2build1", "Go programming language compiler, linker, and stdlib"),
        ApkPackageSpec("gopls", "2:0.14.2-1ubuntu1", "Official Go Language Server (LSP)"),
        ApkPackageSpec("python3", "3.12.3-0ubuntu2", "Interactive high-level object-oriented language"),
        ApkPackageSpec("python3-pip", "24.0+dfsg-1ubuntu1", "Python package installer"),
        ApkPackageSpec("nodejs", "18.19.1+dfsg-6ubuntu5", "evented I/O for V8 javascript - runtime executable"),
        ApkPackageSpec("npm", "9.2.0~ds1-2", "package manager for Node.js"),
        ApkPackageSpec("rustc", "1.75.0+dfsg0ubuntu1-0ubuntu7", "Rust systems programming language compiler"),
        ApkPackageSpec("cargo", "1.75.0+dfsg0ubuntu1-0ubuntu7", "Rust package manager"),
        ApkPackageSpec("clang", "1:18.0-59~exp2", "C, C++ and Objective-C compiler (LLVM based)"),
        ApkPackageSpec("clangd", "1:18.0-59~exp2", "Language server that provides IDE-like features to editors"),
        ApkPackageSpec("build-essential", "12.10ubuntu1", "Informational list of build-essential packages (gcc, g++, make)"),
        ApkPackageSpec("openjdk-17-jdk", "17.0.12+7-1ubuntu2~24.04", "OpenJDK Development Kit (JDK)"),
        ApkPackageSpec("git", "1:2.43.0-1ubuntu7.1", "fast, scalable, distributed revision control system"),
        ApkPackageSpec("curl", "8.5.0-2ubuntu10.4", "command line tool for transferring data with URL syntax"),
        ApkPackageSpec("wget", "1.21.4-1ubuntu4.1", "retrieves files from the web"),
        ApkPackageSpec("neovim", "0.9.5-6ubuntu2", "heavily refactored vim fork"),
        ApkPackageSpec("vim", "2:9.1.0016-1ubuntu7.3", "Vi IMproved - enhanced vi editor"),
        ApkPackageSpec("htop", "3.3.0-4build1", "interactive processes viewer"),
        ApkPackageSpec("tmux", "3.4-1build1", "terminal multiplexer"),
        ApkPackageSpec("zsh", "5.9-6ubuntu2", "shell with lots of features")
    )

    fun alpineRootDir(context: Context): File = UbuntuProotManager.ubuntuRootDir(context)
    fun usrDir(context: Context): File = UbuntuProotManager.usrDir(context)
    fun usrBinDir(context: Context): File = UbuntuProotManager.usrBinDir(context)
    fun usrEtcDir(context: Context): File = UbuntuProotManager.usrEtcDir(context)
    fun usrTmpDir(context: Context): File = UbuntuProotManager.usrTmpDir(context)
    fun homeDir(context: Context): File = UbuntuProotManager.homeDir(context)
    fun termuxDir(context: Context): File = File(homeDir(context), ".termux").apply { mkdirs() }
    fun termuxPropertiesFile(context: Context): File = File(termuxDir(context), "termux.properties")

    fun detectArch(): String = UbuntuProotManager.detectHostAbi()

    fun activeArch(context: Context): String = UbuntuProotManager.detectHostAbi()

    fun loadTermuxProperties(context: Context): TermuxProperties {
        val propFile = termuxPropertiesFile(context)
        if (!propFile.exists()) {
            writeDefaultTermuxProperties(propFile)
        }
        val lines = runCatching { propFile.readLines() }.getOrDefault(emptyList())
        var extraKeysRaw: String? = null
        var cursorStyle = 0
        var cursorBlinkRate = 500
        var bellBehavior = "vibrate"
        var marginH = 3
        var marginV = 0
        var fullscreen = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim()
            val value = line.substring(eqIdx + 1).trim()
            when (key) {
                "extra-keys" -> extraKeysRaw = value
                "terminal-cursor-style" -> {
                    cursorStyle = when (value.lowercase()) {
                        "underline" -> 1
                        "bar" -> 2
                        else -> 0
                    }
                }
                "terminal-cursor-blink-rate" -> cursorBlinkRate = value.toIntOrNull() ?: 500
                "bell-character" -> bellBehavior = value.lowercase()
                "terminal-margin-horizontal" -> marginH = value.toIntOrNull() ?: 3
                "terminal-margin-vertical" -> marginV = value.toIntOrNull() ?: 0
                "fullscreen" -> fullscreen = value.equals("true", ignoreCase = true)
            }
        }

        val parsedKeys = parseExtraKeysMatrix(extraKeysRaw)
        return TermuxProperties(
            extraKeys = parsedKeys,
            cursorStyle = cursorStyle,
            cursorBlinkRate = cursorBlinkRate,
            bellBehavior = bellBehavior,
            marginHorizontalDp = marginH.coerceIn(0, 24),
            marginVerticalDp = marginV.coerceIn(0, 24),
            fullscreen = fullscreen
        )
    }

    private fun parseExtraKeysMatrix(raw: String?): List<List<String>> {
        val defaultMatrix = listOf(
            listOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP"),
            listOf("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN")
        )
        if (raw.isNullOrBlank()) return defaultMatrix
        return try {
            val outer = JSONArray(raw)
            val rows = ArrayList<List<String>>()
            for (i in 0 until outer.length()) {
                val rowArr = outer.optJSONArray(i) ?: continue
                val row = ArrayList<String>()
                for (j in 0 until rowArr.length()) {
                    val item = rowArr.optString(j)
                    if (item.isNotEmpty()) {
                        row.add(item)
                    }
                }
                if (row.isNotEmpty()) rows.add(row)
            }
            if (rows.isEmpty()) defaultMatrix else rows
        } catch (_: Exception) {
            defaultMatrix
        }
    }

    private fun writeDefaultTermuxProperties(propFile: File) {
        runCatching {
            propFile.parentFile?.mkdirs()
            propFile.writeText(
                """
                # ~/.termux/termux.properties
                # Termux + Ubuntu 24.04 LTS (PRoot) terminal configuration
                # Run `termux-reload-settings` after editing this file.

                extra-keys = [['ESC','/','-','HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]

                # Cursor style: block, bar, or underline
                terminal-cursor-style = block
                terminal-cursor-blink-rate = 500

                # Bell behavior: vibrate, beep, or ignore
                bell-character = vibrate

                # Terminal margins in dp
                terminal-margin-horizontal = 3
                terminal-margin-vertical = 0

                # Fullscreen mode
                fullscreen = false
                """.trimIndent() + "\n"
            )
        }
    }

    fun addPackagesToWorld(context: Context, packages: List<String>) {
        // No fake scripts created; real packages are installed via `apt-get install` inside Ubuntu PRoot.
    }

    fun provisionEnvironment(context: Context) {
        Thread {
            runCatching {
                loadTermuxProperties(context)
                UbuntuProotManager.provisionHostHelpers(context)
            }
        }.apply { isDaemon = true }.start()
    }

    fun rebuildApkDatabase(alpineRoot: File, arch: String = detectArch()) {
        // Managed natively by dpkg/apt inside Ubuntu PRoot.
    }

    fun syncOfficialAlpineMinirootfs(
        context: Context,
        onProgress: (String) -> Unit
    ): Boolean {
        return UbuntuProotManager.bootstrapUbuntuRootfs(context, forceReinstall = false, onProgress = onProgress)
    }
}
