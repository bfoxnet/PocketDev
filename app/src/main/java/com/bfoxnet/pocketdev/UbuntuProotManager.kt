package com.bfoxnet.pocketdev

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Real Ubuntu Linux 24.04.5 LTS (`noble`) Rootfs + PRoot Architecture Manager.
 *
 * Self-contained implementation providing:
 * - Real Android-native `proot` + `libtalloc` + `loader` (downloaded from Termux's official
 *   CDN `packages-cf.termux.dev` / Alpine musl PIE `proot` from `dl-cdn.alpinelinux.org`) and
 *   `busybox-static`.
 * - Built-in pure-Kotlin XZ + LZMA2 + ARM64/ARM/x86 BCJ decompressor (`PureKotlinXzDecoder`)
 *   so Termux `.deb` archives containing `data.tar.xz` extract natively in-JVM without relying
 *   on external host `xz` or `busybox` binaries.
 * - Multi-strategy `ProotLaunchMode` (`DIRECT`, `SYSTEM_LINKER`, `MUSL_DIRECT`, `SH_WRAPPER`)
 *   with automatic in-place repair if an older or unusable `proot` binary is encountered.
 * - Official Canonical Ubuntu Base 24.04.5 LTS (`noble`) rootfs tarball downloaded from
 *   `https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/` for the device's native
 *   CPU ABI (`arm64` on `arm64-v8a` devices, `amd64` on `x86_64` devices, `armhf` on 32-bit ARM).
 * - Full POSIX `ustar` / GNU `LongLink` / PAX `.tar.gz`, multi-stream `.apk`, and Debian `.deb`
 *   extractor (`TarGzExtractor`) using `android.system.Os.symlink`, `Os.link`, `Os.chmod`, and `Os.remove`.
 */
object UbuntuProotManager {

    const val PROOT_VERSION: String = "5.3.0"
    const val UBUNTU_VERSION: String = "24.04.5"
    const val UBUNTU_CODENAME: String = "noble"

    enum class ProotLaunchMode {
        /** Execute `$filesDir/usr/bin/proot` directly via kernel `execve`. */
        DIRECT,
        /** Execute PIE ELF `$filesDir/usr/bin/proot` via `/system/bin/linker64` (or `/system/bin/linker`). */
        SYSTEM_LINKER,
        /** Execute Alpine `ld-musl-<arch>.so.1 --library-path <libPath> proot.elf` directly. */
        MUSL_DIRECT,
        /** Execute `#!` wrapper script `$filesDir/usr/bin/proot` via `/system/bin/sh`. */
        SH_WRAPPER
    }

    @Volatile
    var isBootstrapping: Boolean = false
        private set

    @Volatile
    var preferredLaunchMode: ProotLaunchMode? = null

    @Volatile
    var lastVerifyDiagnostics: String = ""
        private set

    @Volatile
    private var cachedUbuntuExtracted: Boolean? = null

    @Volatile
    private var cachedProotUsable: Boolean? = null

    @Volatile
    private var cachedZshInstalled: Boolean? = null

    @Volatile
    private var cachedPluginsProvisioned: Boolean? = null

    @Volatile
    private var cachedMuslLoader: File? = null

    @Volatile
    private var cachedMuslLoaderChecked: Boolean = false

    @Volatile
    private var cachedStaticBindArgs: List<String>? = null

    @Volatile
    private var hostHelpersProvisioned: Boolean = false

    private val bootstrapLock = Any()
    private val repairLock = Any()

    fun invalidateRuntimeCaches() {
        cachedUbuntuExtracted = null
        cachedProotUsable = null
        cachedZshInstalled = null
        cachedPluginsProvisioned = null
        cachedMuslLoader = null
        cachedMuslLoaderChecked = false
        cachedStaticBindArgs = null
    }

    // ---------------------------------------------------------------- Directory Layout

    fun ubuntuRootDir(context: Context): File = File(context.filesDir, "ubuntu")

    fun usrDir(context: Context): File = File(context.filesDir, "usr").apply { mkdirs() }

    fun usrBinDir(context: Context): File = File(usrDir(context), "bin").apply { mkdirs() }

    fun usrLibDir(context: Context): File = File(usrDir(context), "lib").apply { mkdirs() }

    fun usrLibexecProotDir(context: Context): File = File(usrDir(context), "libexec/proot").apply { mkdirs() }

    fun usrEtcDir(context: Context): File = File(usrDir(context), "etc").apply { mkdirs() }

    fun usrTmpDir(context: Context): File = File(usrDir(context), "tmp").apply { mkdirs() }

    fun homeDir(context: Context): File = File(context.filesDir, "home").apply { mkdirs() }

    fun cacheDir(context: Context): File = File(context.filesDir, "cache").apply { mkdirs() }

    fun prootBinaryFile(context: Context): File = File(usrBinDir(context), "proot")

    fun prootElfFile(context: Context): File = File(usrBinDir(context), "proot.elf")

    fun alpineProotSupportDir(context: Context): File = File(usrDir(context), "alpine_proot")

    fun busyboxBinaryFile(context: Context): File = File(usrBinDir(context), "busybox")

    fun prootLoaderFile(context: Context): File = File(usrLibexecProotDir(context), "loader")

    fun prootLoader32File(context: Context): File = File(usrLibexecProotDir(context), "loader32")

    fun systemLinkerPath(): String {
        val abi = detectHostAbi()
        val is64 = abi == "aarch64" || abi == "x86_64"
        return if (is64 && File("/system/bin/linker64").exists()) {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        }
    }

    fun findMuslLoaderFile(context: Context): File? {
        if (cachedMuslLoaderChecked) {
            val cached = cachedMuslLoader
            if (cached == null || cached.exists()) return cached
        }
        val supportDir = alpineProotSupportDir(context)
        if (!supportDir.exists()) {
            cachedMuslLoader = null
            cachedMuslLoaderChecked = true
            return null
        }
        val candidates = listOf(
            File(supportDir, "lib"),
            File(supportDir, "usr/lib")
        )
        for (dir in candidates) {
            val ldMusl = dir.listFiles()?.firstOrNull {
                it.name.startsWith("ld-musl-") && isElfBinary(it)
            }
            if (ldMusl != null) {
                cachedMuslLoader = ldMusl
                cachedMuslLoaderChecked = true
                return ldMusl
            }
            val libcMusl = dir.listFiles()?.firstOrNull {
                it.name.startsWith("libc.musl-") && isElfBinary(it)
            }
            if (libcMusl != null) {
                cachedMuslLoader = libcMusl
                cachedMuslLoaderChecked = true
                return libcMusl
            }
        }
        cachedMuslLoader = null
        cachedMuslLoaderChecked = true
        return null
    }

    // ---------------------------------------------------------------- Architecture Detection

    /**
     * Detects the Android device's primary hardware ABI (`aarch64`, `x86_64`, `arm`, or `x86`).
     */
    fun detectHostAbi(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        for (abi in abis) {
            val lower = abi.lowercase(Locale.US)
            when {
                lower.contains("arm64") || lower.contains("aarch64") -> return "aarch64"
                lower.contains("x86_64") || lower.contains("amd64") -> return "x86_64"
                lower.contains("armeabi") || lower.contains("armv7") || lower == "arm" -> return "arm"
                lower == "x86" || lower.contains("i686") -> return "x86"
            }
        }
        val osArch = (System.getProperty("os.arch") ?: "").lowercase(Locale.US)
        return when {
            osArch.contains("aarch64") || osArch.contains("arm64") -> "aarch64"
            osArch.contains("x86_64") || osArch.contains("amd64") -> "x86_64"
            osArch.contains("arm") -> "arm"
            else -> "aarch64"
        }
    }

    /** Canonical Ubuntu architecture name (`arm64`, `amd64`, `armhf`). */
    fun ubuntuArch(): String = when (detectHostAbi()) {
        "aarch64" -> "arm64"
        "x86_64" -> "amd64"
        "arm" -> "armhf"
        else -> "amd64"
    }

    /** Official Termux package repository architecture (`aarch64`, `x86_64`, `arm`, `i686`). */
    fun termuxArch(): String = when (detectHostAbi()) {
        "aarch64" -> "aarch64"
        "x86_64" -> "x86_64"
        "arm" -> "arm"
        else -> "i686"
    }

    /** Official Alpine Linux CDN architecture (`aarch64`, `x86_64`, `armv7`, `x86`). */
    fun alpineArch(): String = when (detectHostAbi()) {
        "aarch64" -> "aarch64"
        "x86_64" -> "x86_64"
        "arm" -> "armv7"
        else -> "x86"
    }

    /** `skirsten/proot-portable-android-binaries` architecture (`aarch64`, `x86_64`, `armv7`, `x86`). */
    fun skirstenArch(): String = when (detectHostAbi()) {
        "aarch64" -> "aarch64"
        "x86_64" -> "x86_64"
        "arm" -> "armv7"
        else -> "x86"
    }

    // ---------------------------------------------------------------- Installation State & Cleanup

    /**
     * Returns true when the extracted Ubuntu rootfs exists at `$filesDir/ubuntu` containing
     * ELF `/usr/bin/bash`, `/usr/bin/apt-get`, `/usr/bin/dpkg`, and `/etc/debian_version`.
     */
    fun isUbuntuRootfsExtracted(context: Context): Boolean {
        cachedUbuntuExtracted?.let { cached ->
            if (cached) return true
        }
        val root = ubuntuRootDir(context)
        val bashFile = File(root, "usr/bin/bash")
        val aptGetFile = File(root, "usr/bin/apt-get")
        val dpkgFile = File(root, "usr/bin/dpkg")
        val debVerFile = File(root, "etc/debian_version")
        val ok = bashFile.exists() &&
            bashFile.length() > 100_000L &&
            isElfBinary(bashFile) &&
            aptGetFile.exists() &&
            dpkgFile.exists() &&
            debVerFile.exists()
        if (ok) cachedUbuntuExtracted = true
        return ok
    }

    /**
     * Returns true when `proot` exists and is a valid PIE ELF (`e_type == 3`) or a valid
     * `#!` musl wrapper script (and NOT a broken non-PIE `ET_EXEC` `e_type == 2` binary).
     */
    fun isProotUsable(context: Context): Boolean {
        cachedProotUsable?.let { cached ->
            if (cached) return true
        }
        val proot = prootBinaryFile(context)
        if (!proot.exists() || proot.length() < 48L) {
            cachedProotUsable = false
            return false
        }
        val ok = if (isWrapperScript(proot)) {
            val muslLd = findMuslLoaderFile(context)
            val elf = prootElfFile(context)
            muslLd != null && isPiEElf(muslLd) && elf.exists() && isPiEElf(elf)
        } else {
            isPiEElf(proot)
        }
        if (ok) cachedProotUsable = true
        return ok
    }

    /**
     * Returns true when both:
     * 1. A `proot` binary/wrapper is installed at `$filesDir/usr/bin/proot`, AND
     * 2. A real extracted Ubuntu rootfs exists at `$filesDir/ubuntu`.
     */
    fun isUbuntuInstalled(context: Context): Boolean {
        if (cachedUbuntuExtracted == true && cachedProotUsable == true) return true
        val proot = prootBinaryFile(context)
        return proot.exists() &&
            proot.length() > 48L &&
            isUbuntuRootfsExtracted(context)
    }

    /** Returns true when the real Ubuntu `/usr/bin/zsh` (or `/bin/zsh`) ELF binary is installed. */
    fun isZshBinaryInstalled(context: Context): Boolean {
        cachedZshInstalled?.let { cached ->
            if (cached) return true
        }
        val root = ubuntuRootDir(context)
        val usrZsh = File(root, "usr/bin/zsh")
        val binZsh = File(root, "bin/zsh")
        val ok = (usrZsh.exists() && isElfBinary(usrZsh)) || (binZsh.exists() && isElfBinary(binZsh))
        if (ok) cachedZshInstalled = true
        return ok
    }

    /** Returns true when `.zshrc`, `robbyrussell.zsh-theme`, and the Zsh plugins bundle have been provisioned in the Ubuntu rootfs. */
    fun areZshPluginsProvisioned(context: Context): Boolean {
        cachedPluginsProvisioned?.let { cached ->
            if (cached) return true
        }
        val root = ubuntuRootDir(context)
        val ok = File(root, "root/.zshrc").exists() &&
            File(root, "usr/share/zsh/themes/robbyrussell.zsh-theme").exists() &&
            File(root, "usr/share/zsh/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh").exists() &&
            File(root, "usr/share/zsh/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh").exists() &&
            File(root, "usr/share/zsh/plugins/zsh-completions/zsh-completions.plugin.zsh").exists()
        if (ok) cachedPluginsProvisioned = true
        return ok
    }

    fun isElfBinary(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 16L) return false
        return try {
            file.inputStream().use { ins ->
                val magic = ByteArray(4)
                val n = ins.read(magic)
                n == 4 &&
                    magic[0] == 0x7F.toByte() &&
                    magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** True when the file is a `#!`-style loader wrapper script (the musl-proot form). */
    fun isWrapperScript(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 4L) return false
        return try {
            val head = ByteArray(2)
            file.inputStream().use { ins ->
                var n = 0
                while (n < 2) {
                    val r = ins.read(head, n, 2 - n)
                    if (r < 0) break
                    n += r
                }
            }
            head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True when the file is a PIE ELF executable (`e_type == ET_DYN`, 3), the only ELF form
     * Android 10+ bionic will load from app-private storage.
     */
    fun isPiEElf(file: File): Boolean {
        if (!isElfBinary(file)) return false
        return try {
            val hdr = ByteArray(20)
            file.inputStream().use { ins ->
                var n = 0
                while (n < hdr.size) {
                    val r = ins.read(hdr, n, hdr.size - n)
                    if (r < 0) break
                    n += r
                }
            }
            val eType = ((hdr[17].toInt() and 0xFF) shl 8) or (hdr[16].toInt() and 0xFF)
            eType == 3
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Removes all legacy fake shell-script shims from `$filesDir/usr/bin/`, `$filesDir/usr/etc/zshenv`,
     * `$filesDir/alpine/`, and `$filesDir/vm/` so no simulated script ever intercepts commands.
     */
    fun cleanLegacyFakeShims(context: Context) {
        runCatching {
            val legacyBinNames = listOf(
                "go", "gofmt", "apk", "pkg", "apt", "python3", "python", "pip", "pip3",
                "node", "nodejs", "npm", "tsc", "kotlinc", "kotlin", "java", "javac",
                "rustc", "cargo", "clang", "clang++", "gcc", "g++", "git", "zsh",
                "uname", "lscpu", "free", "df", "mount", "dmesg", "lspci", "lsblk",
                "ifconfig", "ip", "ps", "top", "reboot", "poweroff", "qemu-vm",
                "qemu-system-x86_64", "qemu-system-aarch64", "qemu-img", "qemu-x86_64",
                "qemu-aarch64", "wvdial", "alpine-bootstrap", "neofetch", "fastfetch"
            )
            val usrBin = usrBinDir(context)
            for (name in legacyBinNames) {
                val f = File(usrBin, name)
                if (f.exists() && !isElfBinary(f)) {
                    f.delete()
                }
            }
            val keepHelpers = setOf(
                "proot", "proot.elf", "busybox",
                "ubuntu-bootstrap", "proot-distro", "termux-reload-settings",
                "termux-clipboard-get", "termux-clipboard-set", "termux-open-url",
                "termux-open", "termux-setup-storage", "termux-info"
            )
            usrBin.listFiles()?.forEach { f ->
                if (f.isFile && f.name !in keepHelpers && !isElfBinary(f)) {
                    f.delete()
                }
            }
            File(usrEtcDir(context), "zshenv").delete()
            val oldAlpine = File(context.filesDir, "alpine")
            if (oldAlpine.exists()) {
                oldAlpine.deleteRecursively()
            }
            val oldVm = File(context.filesDir, "vm")
            if (oldVm.exists()) {
                oldVm.deleteRecursively()
            }
        }
    }

    /**
     * Provisions host-side helper scripts into `usrBinDir` and cleans up any legacy fake shims.
     */
    fun provisionHostHelpers(context: Context, force: Boolean = false) {
        if (hostHelpersProvisioned && !force) return
        hostHelpersProvisioned = true
        cleanLegacyFakeShims(context)
        val usrBin = usrBinDir(context)
        val usrTmp = usrTmpDir(context)
        val home = homeDir(context)
        val workspace = ProjectManager.ensureDefaultLocalWorkspace(context)

        // Ensure projects directory exists inside Ubuntu rootfs (/root/projects)
        ProjectManager.projectsRootDir(context)

        writeHelperScript(
            File(usrBin, "termux-reload-settings"),
            """
            #!/system/bin/sh
            printf "\033]777;termux;reload-settings\007"
            printf "Reloaded Termux settings from ~/.termux/termux.properties\n"
            """.trimIndent()
        )
        writeHelperScript(
            File(usrBin, "termux-clipboard-get"),
            """
            #!/system/bin/sh
            CLIP_FILE="${usrTmp.absolutePath}/.termux_clipboard"
            [ -f "${'$'}CLIP_FILE" ] && cat "${'$'}CLIP_FILE"
            """.trimIndent()
        )
        writeHelperScript(
            File(usrBin, "termux-clipboard-set"),
            """
            #!/system/bin/sh
            CLIP_FILE="${usrTmp.absolutePath}/.termux_clipboard"
            if [ $# -gt 0 ]; then
                printf "%s" "$*" > "${'$'}CLIP_FILE"
            else
                cat > "${'$'}CLIP_FILE"
            fi
            printf "\033]777;termux;clipboard-set\007"
            """.trimIndent()
        )
        writeHelperScript(
            File(usrBin, "termux-open-url"),
            """
            #!/system/bin/sh
            if [ -z "$1" ]; then
                printf "Usage: termux-open-url <url>\n"
                exit 1
            fi
            printf "\033]777;termux;open-url;%s\007" "$1"
            """.trimIndent()
        )
        writeHelperScript(
            File(usrBin, "termux-open"),
            """
            #!/system/bin/sh
            if [ -z "$1" ]; then
                printf "Usage: termux-open <file-or-url>\n"
                exit 1
            fi
            printf "\033]777;termux;open-url;%s\007" "$1"
            """.trimIndent()
        )
        writeHelperScript(
            File(usrBin, "termux-setup-storage"),
            """
            #!/system/bin/sh
            mkdir -p "${home.absolutePath}/storage/shared" "${home.absolutePath}/storage/downloads"
            ln -sf "${workspace.absolutePath}" "${home.absolutePath}/storage/project" 2>/dev/null || true
            printf "Storage symlinks created in ~/storage -> %s\n" "${workspace.absolutePath}"
            """.trimIndent()
        )

        if (isUbuntuRootfsExtracted(context)) {
            configureUbuntuRootfs(context)
        }
    }

    private fun writeHelperScript(file: File, content: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content + "\n")
            TarGzExtractor.setModeSafe(file, 493)
        }
    }

    /**
     * Repairs or replaces a broken/unusable `proot` binary in-place without deleting an
     * already-extracted Ubuntu rootfs directory.
     */
    fun repairProotInPlace(
        context: Context,
        onProgress: (String) -> Unit
    ): Boolean {
        synchronized(repairLock) {
            invalidateRuntimeCaches()
            preferredLaunchMode = null
            val prootBin = prootBinaryFile(context)
            if (isProotUsable(context) && verifyProotBinary(context)) {
                return true
            }
            onProgress("\u001b[1;33m==> Repairing PRoot binary for ${detectHostAbi()}...\u001b[0m")
            prootBin.delete()
            prootElfFile(context).delete()
            val ok = ensureProotAndBusybox(context, onProgress)
            if (ok && isUbuntuRootfsExtracted(context)) {
                configureUbuntuRootfs(context)
            }
            return ok
        }
    }

    // ---------------------------------------------------------------- Full Bootstrap (PRoot + Ubuntu 24.04.5)

    fun bootstrapUbuntuRootfs(
        context: Context,
        forceReinstall: Boolean = false,
        onProgress: (String) -> Unit
    ): Boolean {
        val rawCallback = onProgress
        val onProgress: (String) -> Unit = { msg ->
            rawCallback(msg)
            DiagLogger.captureProgressIfDiagnostic(context, "PRoot", msg)
            val pctMatch = Regex("\\((\\d+)%\\)").find(msg)
            val pct = pctMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            DownloadNotificationHelper.showProgress(
                context = context,
                notificationId = DownloadNotificationHelper.NOTIF_ID_BOOTSTRAP,
                title = "Ubuntu $UBUNTU_VERSION + PRoot Setup",
                subtitle = msg,
                progress = pct,
                indeterminate = pct == 0
            )
        }
        synchronized(bootstrapLock) {
            if (isBootstrapping) {
                onProgress("Bootstrap is already running in the background...")
                return false
            }
            isBootstrapping = true
        }

        try {
            invalidateRuntimeCaches()
            cleanLegacyFakeShims(context)
            provisionHostHelpers(context, force = true)

            val hostAbi = detectHostAbi()
            val uArch = ubuntuArch()
            val rootDir = ubuntuRootDir(context)
            val activeTargetSdk = context.applicationInfo.targetSdkVersion

            if (!forceReinstall && isUbuntuRootfsExtracted(context)) {
                val prootUsable = isProotUsable(context) && verifyProotBinary(context)
                if (!prootUsable) {
                    onProgress("\u001b[1;33m[REPAIR]\u001b[0m Installed PRoot binary is not executable on this device — reinstalling PRoot...")
                    prootBinaryFile(context).delete()
                    prootElfFile(context).delete()
                    preferredLaunchMode = null
                    if (ensureProotAndBusybox(context, onProgress)) {
                        configureUbuntuRootfs(context)
                        onProgress("\u001b[1;32m[OK]\u001b[0m PRoot repaired. Ubuntu $UBUNTU_VERSION ($uArch) is ready.")
                        return true
                    }
                    onProgress("\u001b[1;31m[ERROR]\u001b[0m Could not provision a working PRoot binary.")
                    if (lastVerifyDiagnostics.isNotBlank()) {
                        onProgress("  Diagnostics: $lastVerifyDiagnostics")
                    }
                    return false
                }
                configureUbuntuRootfs(context)
                onProgress("\u001b[1;32m[OK]\u001b[0m Ubuntu $UBUNTU_VERSION ($uArch / $hostAbi) + PRoot is installed and ready.")
                return true
            }

            val projectsBackupDir = File(cacheDir(context), "projects_reinstall_backup")
            if (forceReinstall) {
                preferredLaunchMode = null
                prootBinaryFile(context).delete()
                prootElfFile(context).delete()
                val existingProjects = File(rootDir, "root/projects")
                if (existingProjects.exists() && existingProjects.isDirectory) {
                    runCatching {
                        projectsBackupDir.deleteRecursively()
                        existingProjects.copyRecursively(projectsBackupDir, overwrite = true)
                    }
                }
                if (rootDir.exists()) {
                    onProgress("==> Removing existing Ubuntu rootfs directory for clean reinstall (preserving /root/projects)...")
                    rootDir.deleteRecursively()
                }
                if (projectsBackupDir.exists()) {
                    runCatching {
                        val restored = File(rootDir, "root/projects").apply { mkdirs() }
                        projectsBackupDir.copyRecursively(restored, overwrite = true)
                        projectsBackupDir.deleteRecursively()
                    }
                }
            }

            onProgress("\u001b[1;36m==> [1/4] Detecting device architecture: $hostAbi (Ubuntu $uArch)\u001b[0m")
            if (activeTargetSdk > 28 && Build.VERSION.SDK_INT >= 29) {
                onProgress("\u001b[1;33m  [WARN] App is running with targetSdk=$activeTargetSdk. On Android 10+, SELinux blocks executing binaries in app storage when targetSdk > 28. Set targetSdk = 28 in app/build.gradle.kts and uninstall the old app before reinstalling.\u001b[0m")
            }

            onProgress("\u001b[1;36m==> [2/4] Downloading native Android PRoot & BusyBox binaries ($hostAbi)...\u001b[0m")
            val prootOk = ensureProotAndBusybox(context, onProgress)
            if (!prootOk) {
                if (lastVerifyDiagnostics.isNotBlank()) {
                    onProgress("\u001b[1;33m  [DIAG] $lastVerifyDiagnostics\u001b[0m")
                }
                onProgress("\u001b[1;31m[ERROR] Failed to download or verify native PRoot binary for $hostAbi. Check internet connection and run 'ubuntu-bootstrap'.\u001b[0m")
                return false
            }

            onProgress("\u001b[1;36m==> [3/4] Downloading official Canonical Ubuntu Base $UBUNTU_VERSION ($uArch) rootfs...\u001b[0m")
            val cacheTarball = File(cacheDir(context), "ubuntu-base-$UBUNTU_CODENAME-$uArch.tar.gz")
            if (forceReinstall || !cacheTarball.exists() || cacheTarball.length() < 15_000_000L) {
                val downloaded = downloadUbuntuBaseTarball(uArch, cacheTarball, onProgress)
                if (!downloaded) {
                    onProgress("\u001b[1;31m[ERROR] Failed to download Ubuntu Base rootfs from cdimage.ubuntu.com. Check internet connection.\u001b[0m")
                    return false
                }
            } else {
                val mb = String.format(Locale.US, "%.1f", cacheTarball.length() / (1024.0 * 1024.0))
                onProgress("Using cached Ubuntu rootfs archive ($mb MB)...")
            }

            onProgress("\u001b[1;36m==> [4/4] Extracting Ubuntu $UBUNTU_VERSION rootfs into ${rootDir.absolutePath}...\u001b[0m")
            rootDir.mkdirs()
            var lastReportedCount = 0
            val totalFiles = TarGzExtractor.extractTarGz(cacheTarball, rootDir) { count, entry ->
                if (count - lastReportedCount >= 600) {
                    lastReportedCount = count
                    onProgress("  Extracted $count files ($entry)...")
                }
            }
            onProgress("  Extracted $totalFiles files, symlinks, and hardlinks.")

            configureUbuntuRootfs(context)

            if (isUbuntuInstalled(context)) {
                cacheTarball.delete()
                if (!isZshBinaryInstalled(context)) {
                    onProgress("\u001b[1;36m==> [5/5] Installing real Zsh + plugins (zsh-autosuggestions, zsh-syntax-highlighting, zsh-completions)...\u001b[0m")
                    installZshInUbuntu(context, onProgress)
                }
                onProgress("\u001b[1;32m==> Ubuntu $UBUNTU_VERSION ($uArch) + PRoot + Zsh installation complete!\u001b[0m")
                onProgress("    Projects directory: \u001b[1;36m/root/projects\u001b[0m")
                onProgress("    Run \u001b[1;33mapt update && apt install -y golang-go python3 nodejs git build-essential\u001b[0m to install compilers.")
                return true
            } else {
                onProgress("\u001b[1;31m[ERROR] Rootfs extraction incomplete (missing /usr/bin/bash or /usr/bin/apt-get).\u001b[0m")
                return false
            }
        } catch (e: Exception) {
            onProgress("\u001b[1;31m[ERROR] Bootstrap failed: ${e.message}\u001b[0m")
            return false
        } finally {
            DownloadNotificationHelper.complete(
                context = context,
                notificationId = DownloadNotificationHelper.NOTIF_ID_BOOTSTRAP,
                title = if (isUbuntuInstalled(context)) "Ubuntu $UBUNTU_VERSION Ready ✓" else "Ubuntu Setup Finished",
                message = if (isUbuntuInstalled(context)) "Ubuntu $UBUNTU_VERSION + PRoot + Zsh (robbyrussell) is active." else "Check Diagnostics screen for details.",
                success = isUbuntuInstalled(context)
            )
            synchronized(bootstrapLock) {
                isBootstrapping = false
            }
        }
    }

    // ---------------------------------------------------------------- PRoot & BusyBox Downloader

    private fun ensureProotAndBusybox(
        context: Context,
        onProgress: (String) -> Unit
    ): Boolean {
        val prootFile = prootBinaryFile(context)
        if (isProotUsable(context) && verifyProotBinary(context)) {
            onProgress("  Verified existing native PRoot binary (${prootFile.length() / 1024} KB).")
            return true
        }
        prootFile.delete()
        prootElfFile(context).delete()
        preferredLaunchMode = null
        lastVerifyDiagnostics = ""

        ensureBusyboxStatic(context, onProgress)

        // Strategy 1: Official Termux Android-native PIE PRoot + libtalloc + PROOT_LOADER.
        if (installTermuxProotFromCdn(context, onProgress)) {
            onProgress("  \u001b[1;32m✓\u001b[0m Installed official Termux Android PRoot + libtalloc.")
            return true
        }

        // Strategy 2: Alpine Linux PIE PRoot + musl dynamic loader + libtalloc.
        if (installAlpineMuslProot(context, onProgress)) {
            onProgress("  \u001b[1;32m✓\u001b[0m Installed Alpine musl-linked PIE PRoot binary.")
            return true
        }

        // Strategy 3: Portable Android PRoot binary (verified before keeping).
        if (installPortableAndroidProot(context, onProgress)) {
            onProgress("  \u001b[1;32m✓\u001b[0m Installed portable Android PRoot binary.")
            return true
        }

        prootFile.delete()
        return false
    }

    private fun ensureBusyboxStatic(context: Context, onProgress: (String) -> Unit): Boolean {
        val bbFile = busyboxBinaryFile(context)
        if (bbFile.exists() && isElfBinary(bbFile) && bbFile.length() > 200_000L) {
            TarGzExtractor.setModeSafe(bbFile, 493)
            return true
        }
        val aArch = alpineArch()
        val mainBaseUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/$aArch"
        return try {
            val apkName = findAlpineApkFilename(mainBaseUrl, "busybox-static") ?: "busybox-static-1.36.1-r31.apk"
            val apkUrl = "$mainBaseUrl/$apkName"
            onProgress("  Downloading $apkName from dl-cdn.alpinelinux.org...")
            val tmpApk = File(cacheDir(context), apkName)
            if (downloadFile(apkUrl, tmpApk, null)) {
                val extractDir = File(cacheDir(context), "bb_extract").apply {
                    deleteRecursively()
                    mkdirs()
                }
                TarGzExtractor.extractConcatenatedTarGz(tmpApk, extractDir)
                val extractedBb = File(extractDir, "bin/busybox.static")
                if (extractedBb.exists() && isElfBinary(extractedBb)) {
                    extractedBb.copyTo(bbFile, overwrite = true)
                    TarGzExtractor.setModeSafe(bbFile, 493)
                    extractDir.deleteRecursively()
                    tmpApk.delete()
                    onProgress("  \u001b[1;32m✓\u001b[0m Provisioned static BusyBox (${bbFile.length() / 1024} KB).")
                    return true
                }
                extractDir.deleteRecursively()
            }
            tmpApk.delete()
            false
        } catch (_: Exception) {
            false
        }
    }

    private data class DebPkgMetadata(
        val name: String,
        val filename: String,
        val depends: List<String>
    )

    private fun parseTermuxPackageMap(indexText: String): Map<String, DebPkgMetadata> {
        val map = HashMap<String, DebPkgMetadata>()
        val blocks = indexText.split(Regex("\\r?\\n\\r?\\n"))
        for (block in blocks) {
            var pkgName: String? = null
            var filename: String? = null
            val deps = ArrayList<String>()
            for (rawLine in block.lines()) {
                val line = rawLine.trim()
                when {
                    line.startsWith("Package:") -> {
                        pkgName = line.removePrefix("Package:").trim()
                    }
                    line.startsWith("Filename:") -> {
                        filename = line.removePrefix("Filename:").trim()
                    }
                    line.startsWith("Depends:") -> {
                        val depStr = line.removePrefix("Depends:").trim()
                        for (part in depStr.split(',')) {
                            val cleanDep = part.trim()
                                .substringBefore(' ')
                                .substringBefore('(')
                                .substringBefore('|')
                                .trim()
                            if (cleanDep.isNotEmpty()) {
                                deps.add(cleanDep)
                            }
                        }
                    }
                }
            }
            if (!pkgName.isNullOrEmpty() && !filename.isNullOrEmpty()) {
                map[pkgName] = DebPkgMetadata(pkgName, filename, deps)
            }
        }
        return map
    }

    private fun collectDebDependencies(
        pkgMap: Map<String, DebPkgMetadata>,
        rootPackages: List<String>
    ): List<DebPkgMetadata> {
        val visited = LinkedHashSet<String>()
        val ordered = ArrayList<DebPkgMetadata>()

        fun visit(name: String) {
            if (!visited.add(name)) return
            val meta = pkgMap[name] ?: return
            for (dep in meta.depends) {
                visit(dep)
            }
            ordered.add(meta)
        }

        for (root in rootPackages) {
            visit(root)
        }
        return ordered
    }

    private fun installTermuxProotFromCdn(context: Context, onProgress: (String) -> Unit): Boolean {
        val tArch = termuxArch()
        val mirrors = listOf(
            "https://packages-cf.termux.dev/apt/termux-main",
            "https://packages.termux.dev/apt/termux-main",
            "https://grimler.se/termux/termux-main"
        )
        for (mirror in mirrors) {
            try {
                onProgress("  Querying Termux package index ($tArch)...")
                val indexText = fetchTermuxPackagesIndex(mirror, tArch) ?: continue
                val pkgMap = parseTermuxPackageMap(indexText)
                val packagesToInstall = collectDebDependencies(pkgMap, listOf("libtalloc", "proot"))
                if (packagesToInstall.none { it.name == "proot" } || packagesToInstall.none { it.name == "libtalloc" }) {
                    continue
                }

                // Clean any stale wrapper or musl artifacts before extracting Termux bionic PRoot
                runCatching { Os.remove(prootBinaryFile(context).absolutePath) }
                prootBinaryFile(context).delete()
                prootElfFile(context).delete()

                val bb = busyboxBinaryFile(context).takeIf { it.exists() }
                var allExtracted = true
                onProgress("  Downloading Termux ${packagesToInstall.joinToString(" & ") { it.name }} ($tArch)...")
                for (pkg in packagesToInstall) {
                    val debFile = File(cacheDir(context), "${pkg.name}_$tArch.deb")
                    if (!downloadFile("$mirror/${pkg.filename}", debFile, null)) {
                        debFile.delete()
                        allExtracted = false
                        break
                    }
                    val ok = TarGzExtractor.extractDebPackage(
                        debFile = debFile,
                        destRoot = context.filesDir,
                        stripPrefix = "data/data/com.termux/files/",
                        busyboxBin = bb
                    )
                    debFile.delete()
                    if (!ok) {
                        allExtracted = false
                        break
                    }
                }
                if (!allExtracted) continue

                // Ensure all shared libraries in usr/lib have real ELF files for their SONAMEs
                materializeSharedLibraryAliases(usrLibDir(context))

                val prootBin = prootBinaryFile(context)
                val loaderBin = prootLoaderFile(context)
                val loader32Bin = prootLoader32File(context)
                if (prootBin.exists()) TarGzExtractor.setModeSafe(prootBin, 493)
                if (loaderBin.exists()) TarGzExtractor.setModeSafe(loaderBin, 493)
                if (loader32Bin.exists()) TarGzExtractor.setModeSafe(loader32Bin, 493)

                if (prootBin.exists() && isPiEElf(prootBin) && verifyProotBinary(context)) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * Ensures every `lib<name>.so.<major>.<minor>...` ELF file in [libDir] is also available
     * as a real ELF file at `lib<name>.so.<major>` and `lib<name>.so` so dynamic linking never
     * fails due to broken or unresolvable symlinks.
     */
    private fun materializeSharedLibraryAliases(libDir: File) {
        val files = libDir.listFiles() ?: return
        for (f in files) {
            if (!isElfBinary(f) || f.length() < 1024L) continue
            TarGzExtractor.setModeSafe(f, 493)
            val name = f.name
            val soIdx = name.indexOf(".so.")
            if (soIdx > 0) {
                val baseSo = name.substring(0, soIdx + 3) // "libfoo.so"
                val afterSo = name.substring(soIdx + 4)   // "2.4.2"
                val major = afterSo.substringBefore('.')
                val aliasNames = LinkedHashSet<String>()
                if (major.isNotEmpty()) {
                    aliasNames.add("$baseSo.$major") // "libfoo.so.2"
                }
                aliasNames.add(baseSo) // "libfoo.so"
                for (alias in aliasNames) {
                    if (alias == name) continue
                    val target = File(libDir, alias)
                    if (!target.exists() || !isElfBinary(target) || target.length() != f.length()) {
                        runCatching { Os.remove(target.absolutePath) }
                        runCatching {
                            f.copyTo(target, overwrite = true)
                            TarGzExtractor.setModeSafe(target, 493)
                        }
                    }
                }
            }
        }
    }

    private fun fetchTermuxPackagesIndex(mirror: String, tArch: String): String? {
        val gzUrl = "$mirror/dists/stable/main/binary-$tArch/Packages.gz"
        val gzFile = File.createTempFile("termux_pkgs_", ".gz")
        try {
            if (downloadFile(gzUrl, gzFile, null) && gzFile.exists() && gzFile.length() > 1024L) {
                FileInputStream(gzFile).use { fis ->
                    GZIPInputStream(BufferedInputStream(fis, 65536)).use { gis ->
                        return gis.bufferedReader(StandardCharsets.UTF_8).readText()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            gzFile.delete()
        }
        val plainUrl = "$mirror/dists/stable/main/binary-$tArch/Packages"
        return fetchUrlText(plainUrl, maxBytes = 16 * 1024 * 1024)
    }

    private fun installPortableAndroidProot(context: Context, onProgress: (String) -> Unit): Boolean {
        val sArch = skirstenArch()
        val urls = listOf(
            "https://skirsten.github.io/proot-portable-android-binaries/$sArch/proot",
            "https://raw.githubusercontent.com/skirsten/proot-portable-android-binaries/gh-pages/$sArch/proot"
        )
        val prootBin = prootBinaryFile(context)
        for (url in urls) {
            try {
                onProgress("  Downloading portable Android PRoot ($sArch)...")
                val tmp = File(cacheDir(context), "proot_portable_$sArch")
                if (downloadFile(url, tmp, null) && isElfBinary(tmp) && tmp.length() > 50_000L) {
                    if (!isPiEElf(tmp)) {
                        tmp.delete()
                        continue
                    }
                    tmp.copyTo(prootBin, overwrite = true)
                    TarGzExtractor.setModeSafe(prootBin, 493)
                    tmp.delete()
                    if (verifyProotBinary(context)) {
                        return true
                    }
                    prootBin.delete()
                }
                tmp.delete()
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun installAlpineMuslProot(context: Context, onProgress: (String) -> Unit): Boolean {
        val aArch = alpineArch()
        val mainRepos = listOf(
            "https://dl-cdn.alpinelinux.org/alpine/edge/main/$aArch",
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/$aArch",
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/$aArch"
        )
        val prootRepos = listOf(
            "https://dl-cdn.alpinelinux.org/alpine/edge/testing/$aArch",
            "https://dl-cdn.alpinelinux.org/alpine/edge/community/$aArch",
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/community/$aArch",
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/community/$aArch"
        )

        var resolvedMainRepo: String? = null
        var muslApk: String? = null
        var tallocApk: String? = null
        for (repo in mainRepos) {
            val m = findAlpineApkFilename(repo, "musl")
            val t = findAlpineApkFilename(repo, "talloc")
            if (m != null && t != null) {
                resolvedMainRepo = repo
                muslApk = m
                tallocApk = t
                break
            }
        }
        if (resolvedMainRepo == null || muslApk == null || tallocApk == null) return false

        var resolvedProotRepo: String? = null
        var prootApk: String? = null
        for (repo in prootRepos) {
            val p = findAlpineApkFilename(repo, "proot")
            if (p != null) {
                resolvedProotRepo = repo
                prootApk = p
                break
            }
        }
        if (resolvedProotRepo == null || prootApk == null) return false

        return try {
            val prootSupportDir = alpineProotSupportDir(context).apply {
                deleteRecursively()
                mkdirs()
            }
            for ((baseUrl, apkName) in listOf(
                resolvedMainRepo to muslApk,
                resolvedMainRepo to tallocApk,
                resolvedProotRepo to prootApk
            )) {
                val tmp = File(cacheDir(context), apkName)
                onProgress("  Downloading $apkName...")
                if (!downloadFile("$baseUrl/$apkName", tmp, null)) {
                    tmp.delete()
                    return false
                }
                TarGzExtractor.extractConcatenatedTarGz(tmp, prootSupportDir)
                tmp.delete()
            }

            val alpineLibDir = File(prootSupportDir, "lib").apply { mkdirs() }
            val alpineUsrLibDir = File(prootSupportDir, "usr/lib").apply { mkdirs() }

            val anyMuslElf = prootSupportDir.walkTopDown().firstOrNull {
                (it.name.startsWith("ld-musl-") || it.name.startsWith("libc.musl-")) && isElfBinary(it) && it.length() > 100_000L
            } ?: return false

            val muslLd = File(alpineLibDir, "ld-musl-$aArch.so.1")
            val libcMusl = File(alpineLibDir, "libc.musl-$aArch.so.1")
            if (!muslLd.exists() || !isElfBinary(muslLd)) {
                runCatching { Os.remove(muslLd.absolutePath) }
                anyMuslElf.copyTo(muslLd, overwrite = true)
            }
            if (!libcMusl.exists() || !isElfBinary(libcMusl)) {
                runCatching { Os.remove(libcMusl.absolutePath) }
                anyMuslElf.copyTo(libcMusl, overwrite = true)
            }
            TarGzExtractor.setModeSafe(muslLd, 493)
            TarGzExtractor.setModeSafe(libcMusl, 493)

            val anyTallocElf = prootSupportDir.walkTopDown().firstOrNull {
                it.name.startsWith("libtalloc.so") && isElfBinary(it) && it.length() > 8_000L
            }
            if (anyTallocElf != null) {
                for (targetDir in listOf(alpineUsrLibDir, alpineLibDir)) {
                    val so2 = File(targetDir, "libtalloc.so.2")
                    if (!so2.exists() || !isElfBinary(so2)) {
                        runCatching { Os.remove(so2.absolutePath) }
                        anyTallocElf.copyTo(so2, overwrite = true)
                    }
                    TarGzExtractor.setModeSafe(so2, 493)
                }
            }

            val rawProot = prootSupportDir.walkTopDown().firstOrNull {
                it.name == "proot" && isElfBinary(it) && it.length() > 30_000L
            } ?: return false
            TarGzExtractor.setModeSafe(rawProot, 493)

            // Remove Termux's loader files so Alpine's PRoot uses its own matching embedded loader
            prootLoaderFile(context).delete()
            prootLoader32File(context).delete()

            val prootElf = prootElfFile(context)
            runCatching { Os.remove(prootElf.absolutePath) }
            rawProot.copyTo(prootElf, overwrite = true)
            TarGzExtractor.setModeSafe(prootElf, 493)
            val libPath = "${alpineLibDir.absolutePath}:${alpineUsrLibDir.absolutePath}"

            val wrapper = prootBinaryFile(context)
            runCatching { Os.remove(wrapper.absolutePath) }
            wrapper.writeText(
                """
                #!/system/bin/sh
                exec "${muslLd.absolutePath}" --library-path "$libPath" "${prootElf.absolutePath}" "$@"
                """.trimIndent() + "\n"
            )
            TarGzExtractor.setModeSafe(wrapper, 493)
            verifyProotBinary(context)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns the ordered list of [ProotLaunchMode] strategies valid for the currently
     * installed `proot` binary/wrapper on disk.
     */
    fun availableLaunchModes(context: Context): List<ProotLaunchMode> {
        val prootBin = prootBinaryFile(context)
        val muslLd = findMuslLoaderFile(context)
        val prootElf = prootElfFile(context)
        val linkerFile = File(systemLinkerPath())

        val modes = LinkedHashSet<ProotLaunchMode>()
        preferredLaunchMode?.let { modes.add(it) }

        if (prootBin.exists() && isPiEElf(prootBin)) {
            modes.add(ProotLaunchMode.DIRECT)
            if (linkerFile.exists()) {
                modes.add(ProotLaunchMode.SYSTEM_LINKER)
            }
        }
        if (muslLd != null && prootElf.exists() && isPiEElf(prootElf)) {
            modes.add(ProotLaunchMode.MUSL_DIRECT)
        }
        if (prootBin.exists() && isWrapperScript(prootBin)) {
            modes.add(ProotLaunchMode.SH_WRAPPER)
            modes.add(ProotLaunchMode.DIRECT)
        }
        if (modes.isEmpty() && prootBin.exists()) {
            modes.add(ProotLaunchMode.DIRECT)
            if (linkerFile.exists()) {
                modes.add(ProotLaunchMode.SYSTEM_LINKER)
            }
        }
        return modes.toList()
    }

    fun buildLaunchPrefix(context: Context, mode: ProotLaunchMode): List<String>? {
        val prootBin = prootBinaryFile(context)
        return when (mode) {
            ProotLaunchMode.DIRECT -> {
                if (!prootBin.exists()) null else listOf(prootBin.absolutePath)
            }
            ProotLaunchMode.SYSTEM_LINKER -> {
                val linker = File(systemLinkerPath())
                val targetElf = when {
                    prootBin.exists() && isPiEElf(prootBin) -> prootBin
                    else -> null
                }
                if (!linker.exists() || targetElf == null) null
                else listOf(linker.absolutePath, targetElf.absolutePath)
            }
            ProotLaunchMode.MUSL_DIRECT -> {
                val muslLd = findMuslLoaderFile(context) ?: return null
                val prootElf = prootElfFile(context)
                if (!prootElf.exists() || !isElfBinary(prootElf)) return null
                val supportDir = alpineProotSupportDir(context)
                val libPath = "${File(supportDir, "lib").absolutePath}:${File(supportDir, "usr/lib").absolutePath}"
                listOf(muslLd.absolutePath, "--library-path", libPath, prootElf.absolutePath)
            }
            ProotLaunchMode.SH_WRAPPER -> {
                if (!prootBin.exists() || !isWrapperScript(prootBin)) null
                else listOf("/system/bin/sh", prootBin.absolutePath)
            }
        }
    }

    fun verifyProotBinary(context: Context): Boolean {
        val prootBin = prootBinaryFile(context)
        if (!prootBin.exists()) return false
        TarGzExtractor.setModeSafe(prootBin, 493)
        prootElfFile(context).takeIf { it.exists() }?.let { TarGzExtractor.setModeSafe(it, 493) }
        findMuslLoaderFile(context)?.let { TarGzExtractor.setModeSafe(it, 493) }

        // Reject raw ET_EXEC (e_type == 2) ELF binaries at prootBin
        if (isElfBinary(prootBin) && !isPiEElf(prootBin)) {
            lastVerifyDiagnostics = "Rejected non-PIE ELF (e_type=2)"
            return false
        }

        val diagParts = ArrayList<String>()
        for (mode in availableLaunchModes(context)) {
            val prefix = buildLaunchPrefix(context, mode) ?: continue
            val cmd = ArrayList<String>(prefix.size + 1).apply {
                addAll(prefix)
                add("--version")
            }
            try {
                val pb = ProcessBuilder(cmd)
                    .directory(context.filesDir)
                    .redirectErrorStream(true)
                applyHostProotEnv(context, pb.environment())
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                val failedToLink = output.contains("CANNOT LINK EXECUTABLE", ignoreCase = true) ||
                    output.contains("unexpected e_type", ignoreCase = true) ||
                    output.contains("bad ELF magic", ignoreCase = true) ||
                    output.contains("Permission denied", ignoreCase = true) ||
                    output.contains("Error loading shared library", ignoreCase = true)
                if (!failedToLink && (code == 0 || output.contains("PRoot", ignoreCase = true) || output.contains("Visit http://proot", ignoreCase = true))) {
                    preferredLaunchMode = mode
                    lastVerifyDiagnostics = ""
                    return true
                }
                val snippet = output.trim().lineSequence().firstOrNull()?.take(120) ?: "no output"
                diagParts.add("$mode(exit=$code: $snippet)")
            } catch (e: Exception) {
                diagParts.add("$mode(${e.javaClass.simpleName}: ${e.message?.take(100)})")
            }
        }
        if (diagParts.isNotEmpty()) {
            lastVerifyDiagnostics = diagParts.joinToString("; ")
        }
        return false
    }

    // ---------------------------------------------------------------- Ubuntu Base 24.04.5 Tarball Downloader

    private fun downloadUbuntuBaseTarball(
        uArch: String,
        destFile: File,
        onProgress: (String) -> Unit
    ): Boolean {
        val candidateUrls = LinkedHashSet<String>()
        candidateUrls.add("https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.5-base-$uArch.tar.gz")
        candidateUrls.add("https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-$uArch.tar.gz")
        candidateUrls.add("https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.3-base-$uArch.tar.gz")
        candidateUrls.add("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-$uArch.tar.gz")

        runCatching {
            val html = fetchUrlText("https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/", maxBytes = 256 * 1024)
            if (html != null) {
                val regex = Regex("""href="(ubuntu-base-24\.04[^"]*-base-${Regex.escape(uArch)}\.tar\.gz)"""")
                regex.findAll(html).map { it.groupValues[1] }.toList().asReversed().forEach { fname ->
                    candidateUrls.add("https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/$fname")
                }
            }
        }

        candidateUrls.add("https://cdimage.ubuntu.com/ubuntu-base/releases/jammy/release/ubuntu-base-22.04.5-base-$uArch.tar.gz")

        for (url in candidateUrls) {
            onProgress("  Fetching $url ...")
            val tmpFile = File(destFile.parentFile, "${destFile.name}.part")
            val ok = downloadFile(url, tmpFile) { downloadedBytes, totalBytes ->
                val dlMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                if (totalBytes > 0L) {
                    val totMb = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                    val pct = ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L)
                    onProgress("  Downloading Ubuntu rootfs: $dlMb MB / $totMb MB ($pct%)")
                } else {
                    onProgress("  Downloading Ubuntu rootfs: $dlMb MB...")
                }
            }
            if (ok && tmpFile.exists() && tmpFile.length() > 15_000_000L) {
                tmpFile.renameTo(destFile)
                return true
            }
            tmpFile.delete()
        }
        return false
    }

    // ---------------------------------------------------------------- Ubuntu Rootfs Configuration for PRoot

    fun configureUbuntuRootfs(context: Context) {
        val root = ubuntuRootDir(context)
        if (!root.exists()) return
        runCatching {
            val hostAbi = detectHostAbi()

            val resolvFile = File(root, "etc/resolv.conf")
            resolvFile.parentFile?.mkdirs()
            runCatching { Os.remove(resolvFile.absolutePath) }
            resolvFile.writeText(
                """
                nameserver 8.8.8.8
                nameserver 8.8.4.4
                nameserver 1.1.1.1
                """.trimIndent() + "\n"
            )
            TarGzExtractor.setModeSafe(resolvFile, 420)

            File(root, "etc/hostname").writeText("ubuntu\n")
            val hostsFile = File(root, "etc/hosts")
            runCatching { Os.remove(hostsFile.absolutePath) }
            hostsFile.writeText(
                """
                127.0.0.1	localhost ubuntu
                ::1		localhost ip6-localhost ip6-loopback
                """.trimIndent() + "\n"
            )

            val groupFile = File(root, "etc/group")
            if (groupFile.exists()) {
                val existing = groupFile.readText()
                val additions = StringBuilder()
                if (!existing.contains(":3003:")) additions.appendLine("aid_inet:x:3003:root,_apt")
                if (!existing.contains(":3004:")) additions.appendLine("aid_net_raw:x:3004:root,_apt")
                if (!existing.contains(":9997:")) additions.appendLine("aid_everybody:x:9997:root,_apt")
                if (additions.isNotEmpty()) {
                    groupFile.appendText(additions.toString())
                }
            }

            val aptConfDir = File(root, "etc/apt/apt.conf.d").apply { mkdirs() }
            File(aptConfDir, "99proot-android").writeText(
                """
                APT::Sandbox::User "root";
                APT::Sandbox::Seccomp "false";
                Acquire::Languages "none";
                Dpkg::Options {
                   "--force-overwrite";
                   "--force-confdef";
                   "--force-confold";
                };
                """.trimIndent() + "\n"
            )

            val dpkgCfgDir = File(root, "etc/dpkg/dpkg.cfg.d").apply { mkdirs() }
            File(dpkgCfgDir, "99proot-android").writeText(
                """
                force-unsafe-io
                force-overwrite
                force-confdef
                force-confold
                """.trimIndent() + "\n"
            )

            File(root, "var/lib/apt/lists/partial").mkdirs()
            File(root, "var/cache/apt/archives/partial").mkdirs()
            File(root, "tmp").apply {
                mkdirs()
                TarGzExtractor.setModeSafe(this, 511)
            }
            File(root, "var/tmp").apply {
                mkdirs()
                TarGzExtractor.setModeSafe(this, 511)
            }
            File(root, "dev/shm").apply {
                mkdirs()
                TarGzExtractor.setModeSafe(this, 511)
            }

            val policyRc = File(root, "usr/sbin/policy-rc.d")
            policyRc.parentFile?.mkdirs()
            policyRc.writeText("#!/bin/sh\nexit 101\n")
            TarGzExtractor.setModeSafe(policyRc, 493)
            File(root, "var/lib/man-db/auto-update").delete()

            val procDir = File(root, "proc").apply { mkdirs() }
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
            File(procDir, ".version").writeText(
                "Linux version 6.6.30-android15 ($hostAbi) (Ubuntu 13.2.0-23ubuntu4) #1 SMP PREEMPT_DYNAMIC\n"
            )
            File(procDir, ".uptime").writeText("3600.00 7200.00\n")
            File(procDir, ".loadavg").writeText("0.12 0.08 0.05 1/240 1042\n")
            File(procDir, ".stat").writeText(
                buildString {
                    appendLine("cpu  12040 420 6410 384200 310 0 180 0 0 0")
                    for (c in 0 until cores) {
                        appendLine("cpu$c 3010 105 1602 96050 77 0 45 0 0 0")
                    }
                    appendLine("intr 482910 0 0 0 0")
                    appendLine("ctxt 928410")
                    appendLine("btime ${System.currentTimeMillis() / 1000L - 3600L}")
                    appendLine("processes 2048")
                    appendLine("procs_running 1")
                    appendLine("procs_blocked 0")
                }
            )
            File(procDir, ".vmstat").writeText(
                """
                nr_free_pages 262144
                nr_active_anon 65536
                nr_inactive_anon 32768
                nr_active_file 131072
                nr_inactive_file 65536
                pgpgpgin 512000
                pgpgpgout 128000
                pswpin 0
                pswpout 0
                pgfault 1048576
                """.trimIndent() + "\n"
            )
            File(procDir, ".sysctl_entry_cap_last_cap").writeText("40\n")
            File(procDir, ".sysctl_inotify_max_user_watches").writeText("524288\n")

            val rootHome = File(root, "root").apply { mkdirs() }
            File(rootHome, "project").mkdirs()
            File(rootHome, "projects").mkdirs()
            val profileD = File(root, "etc/profile.d").apply { mkdirs() }
            val envScript = """
                export HOME="/root"
                export USER="root"
                export LOGNAME="root"
                export TERM="xterm-256color"
                export COLORTERM="truecolor"
                export LANG="C.UTF-8"
                export LC_ALL="C.UTF-8"
                export DEBIAN_FRONTEND="noninteractive"
                export GOPATH="/root/go"
                export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/go/bin:/root/go/bin:/root/.cargo/bin"
                alias ll='ls -la --color=auto'
                alias la='ls -A --color=auto'
                alias l='ls -CF --color=auto'
                alias gs='git status'
                alias ga='git add'
                alias gc='git commit'
                alias gp='git push'
                alias gl='git log --oneline --graph --decorate'
                alias projects='cd /root/projects'
                alias cls='clear'
            """.trimIndent() + "\n"
            File(profileD, "pocketdev.sh").writeText(envScript)
            File(rootHome, ".bashrc").writeText(envScript)

            configureZshAndPlugins(root, envScript)

            val usrLocalBin = File(root, "usr/local/bin").apply { mkdirs() }

            val zshSetupFile = File(usrLocalBin, "zsh-setup")
            zshSetupFile.writeText(
                """
                #!/bin/bash
                set -e
                export DEBIAN_FRONTEND=noninteractive
                echo -e "\033[1;33m==> Installing real Ubuntu Zsh & plugins (zsh, zsh-autosuggestions, zsh-syntax-highlighting)...\033[0m"
                apt-get update
                apt-get install -y zsh zsh-autosuggestions zsh-syntax-highlighting git
                if [ -x /usr/bin/zsh ] && [ -f /etc/passwd ]; then
                    sed -i 's|^root:\([^:]*\):0:0:\([^:]*\):/root:.*|root:\1:0:0:\2:/root:/usr/bin/zsh|' /etc/passwd || true
                fi
                echo -e "\033[1;32m✓ Zsh ($(/usr/bin/zsh --version 2>/dev/null || echo 'ready')) + plugins configured!\033[0m"
                echo -e "  Default theme: \033[1;32mrobbyrussell\033[0m | Plugins: \033[1;36mzsh-autosuggestions, zsh-syntax-highlighting, zsh-completions, zsh-history-substring-search\033[0m"
                """.trimIndent() + "\n"
            )
            TarGzExtractor.setModeSafe(zshSetupFile, 493)

            val lspInstallFile = File(usrLocalBin, "lsp-install")
            lspInstallFile.writeText(
                """
                #!/bin/bash
                set -e
                export DEBIAN_FRONTEND=noninteractive
                if [ -z "$1" ] || [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
                    echo -e "\033[1;36mUbuntu 24.04 APT Language Server & Toolchain Installer (lsp-install)\033[0m"
                    echo "Usage:"
                    echo "  lsp-install <server-id>    Install real Ubuntu compiler & LSP packages via apt-get"
                    echo "  lsp-install --list         List available Language Server IDs"
                    exit 0
                fi
                if [ "$1" = "--list" ]; then
                    echo "Available Language Servers: gopls, pyright, ts-ls, clangd, rust-analyzer, kotlin-lsp, jdtls, bash-ls, lua-ls, intelephense, html-ls, css-ls, json-ls, yaml-ls, sql-ls"
                    exit 0
                fi
                for srv in "$@"; do
                    case "${'$'}srv" in
                        gopls)
                            apt-get update && apt-get install -y golang-go gopls
                            ;;
                        pyright)
                            apt-get update && apt-get install -y python3 python3-pip python3-venv nodejs npm
                            npm install -g pyright || true
                            ;;
                        ts-ls)
                            apt-get update && apt-get install -y nodejs npm
                            npm install -g typescript typescript-language-server || true
                            ;;
                        rust-analyzer)
                            apt-get update && apt-get install -y rustc cargo build-essential
                            ;;
                        clangd)
                            apt-get update && apt-get install -y clang clangd gcc g++ make build-essential
                            ;;
                        jdtls|kotlin-lsp)
                            apt-get update && apt-get install -y openjdk-17-jdk
                            ;;
                        bash-ls)
                            apt-get update && apt-get install -y bash shellcheck nodejs npm
                            ;;
                        lua-ls)
                            apt-get update && apt-get install -y lua5.4
                            ;;
                        intelephense)
                            apt-get update && apt-get install -y php-cli nodejs npm
                            ;;
                        html-ls|css-ls|json-ls|yaml-ls|sql-ls)
                            apt-get update && apt-get install -y nodejs npm sqlite3
                            ;;
                        *)
                            apt-get update && apt-get install -y "${'$'}srv"
                            ;;
                    esac
                    mkdir -p "/mnt/host/lsp/${'$'}srv"
                    printf '{"id":"%s","installedAt":%s}\n' "${'$'}srv" "${'$'}(date +%s)000" > "/mnt/host/lsp/${'$'}srv/manifest.json"
                done
                """.trimIndent() + "\n"
            )
            TarGzExtractor.setModeSafe(lspInstallFile, 493)

            val lspUninstallFile = File(usrLocalBin, "lsp-uninstall")
            lspUninstallFile.writeText(
                """
                #!/bin/bash
                if [ -z "$1" ]; then
                    echo "Usage: lsp-uninstall <server-id>"
                    exit 1
                fi
                rm -rf "/mnt/host/lsp/$1"
                echo "Removed LSP registration for $1"
                """.trimIndent() + "\n"
            )
            TarGzExtractor.setModeSafe(lspUninstallFile, 493)
        }
    }

    /**
     * Provisions Zsh configuration (`/etc/zsh/zshenv`, `/etc/zsh/zshrc`, `/root/.zshenv`, `/root/.zshrc`)
     * and the core Zsh plugins (`zsh-autosuggestions`, `zsh-syntax-highlighting`, `zsh-completions`,
     * `zsh-history-substring-search`) inside the Ubuntu rootfs.
     */
    private fun configureZshAndPlugins(root: File, envScript: String) {
        val etcZsh = File(root, "etc/zsh").apply { mkdirs() }
        val rootHome = File(root, "root").apply { mkdirs() }
        val pluginsRoot = File(root, "usr/share/zsh/plugins").apply { mkdirs() }

        val autosuggestDir = File(pluginsRoot, "zsh-autosuggestions").apply { mkdirs() }
        val syntaxDir = File(pluginsRoot, "zsh-syntax-highlighting").apply { mkdirs() }
        val completionsDir = File(pluginsRoot, "zsh-completions").apply { mkdirs() }
        val completionsSrcDir = File(completionsDir, "src").apply { mkdirs() }
        val historySearchDir = File(pluginsRoot, "zsh-history-substring-search").apply { mkdirs() }

        // 1. zsh-autosuggestions plugin (delegates to Ubuntu package when installed + provides ZLE fish-like autosuggestions)
        File(autosuggestDir, "zsh-autosuggestions.zsh").writeText(
            """
            # PocketDev Zsh Autosuggestions Plugin
            if [ -f /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh ] && [ "${'$'}{_POCKETDEV_SYS_AUTOSUGGEST:-0}" = "0" ]; then
                _POCKETDEV_SYS_AUTOSUGGEST=1
                source /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh
                return 0
            fi
            typeset -g ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE='fg=242'
            typeset -g ZSH_AUTOSUGGEST_STRATEGY=(history completion)
            """.trimIndent() + "\n"
        )

        // 2. zsh-syntax-highlighting plugin (delegates to Ubuntu package when installed + configures highlighters)
        File(syntaxDir, "zsh-syntax-highlighting.zsh").writeText(
            """
            # PocketDev Zsh Syntax Highlighting Plugin
            typeset -gA ZSH_HIGHLIGHT_STYLES
            ZSH_HIGHLIGHT_HIGHLIGHTERS=(main brackets pattern)
            if [ -f /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ] && [ "${'$'}{_POCKETDEV_SYS_SYNTAX:-0}" = "0" ]; then
                _POCKETDEV_SYS_SYNTAX=1
                source /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh
                return 0
            fi
            """.trimIndent() + "\n"
        )

        // 3. zsh-completions plugin (adds completions for PocketDev & Ubuntu CLI tools)
        File(completionsDir, "zsh-completions.plugin.zsh").writeText(
            """
            # PocketDev Zsh Completions Plugin
            fpath=("/usr/share/zsh/plugins/zsh-completions/src" ${'$'}fpath)
            if (( ${'$'}+functions[compdef] )); then
                _lsp_install_complete() {
                    local -a servers
                    servers=(
                        'gopls:Go Language Server'
                        'pyright:Python Language Server'
                        'ts-ls:TypeScript & JavaScript Language Server'
                        'clangd:C/C++ Language Server'
                        'rust-analyzer:Rust Language Server'
                        'kotlin-lsp:Kotlin Language Server'
                        'jdtls:Java Eclipse JDT Language Server'
                        'bash-ls:Bash/Zsh Language Server'
                        'lua-ls:Lua Language Server'
                        'intelephense:PHP Language Server'
                        'html-ls:HTML Language Server'
                        'css-ls:CSS Language Server'
                        'json-ls:JSON Language Server'
                        'yaml-ls:YAML Language Server'
                        'sql-ls:SQL Language Server'
                        '--list:List available Language Servers'
                    )
                    _describe 'language-server' servers
                }
                compdef _lsp_install_complete lsp-install lsp-uninstall
            fi
            """.trimIndent() + "\n"
        )
        File(completionsSrcDir, "_pocketdev").writeText("#compdef lsp-install lsp-uninstall zsh-setup\n")

        // 4. zsh-history-substring-search plugin (Up/Down arrow history prefix search)
        File(historySearchDir, "zsh-history-substring-search.zsh").writeText(
            """
            # PocketDev Zsh History Search Keybindings
            autoload -Uz up-line-or-beginning-search down-line-or-beginning-search
            zle -N up-line-or-beginning-search
            zle -N down-line-or-beginning-search
            bindkey '^[[A' up-line-or-beginning-search 2>/dev/null || true
            bindkey '^[[B' down-line-or-beginning-search 2>/dev/null || true
            bindkey '^[OA' up-line-or-beginning-search 2>/dev/null || true
            bindkey '^[OB' down-line-or-beginning-search 2>/dev/null || true
            """.trimIndent() + "\n"
        )

        // 5. Official Oh My Zsh `robbyrussell` theme (default Zsh theme)
        val themesDir = File(root, "usr/share/zsh/themes").apply { mkdirs() }
        val ohMyZshThemesDir = File(rootHome, ".oh-my-zsh/themes").apply { mkdirs() }
        val robbyrussellThemeContent = """
            # Oh My Zsh — robbyrussell.zsh-theme (Default Zsh Theme for PocketDev)
            autoload -Uz colors && colors
            setopt PROMPT_SUBST

            git_prompt_info() {
                [ -d .git ] || command git rev-parse --git-dir >/dev/null 2>&1 || return 0
                local ref
                ref=${'$'}(command git symbolic-ref --short HEAD 2>/dev/null) || \
                ref=${'$'}(command git rev-parse --short HEAD 2>/dev/null) || return 0
                if [ -n "${'$'}(command git status --porcelain -uno 2>/dev/null)" ]; then
                    echo " %{${'$'}fg_bold[blue]%}git:(%{${'$'}fg[red]%}${'$'}{ref}%{${'$'}fg_bold[blue]%}) %{${'$'}fg[yellow]%}✗%{${'$'}reset_color%}"
                else
                    echo " %{${'$'}fg_bold[blue]%}git:(%{${'$'}fg[red]%}${'$'}{ref}%{${'$'}fg_bold[blue]%})%{${'$'}reset_color%}"
                fi
            }

            ZSH_THEME_GIT_PROMPT_PREFIX="%{${'$'}fg_bold[blue]%}git:(%{${'$'}fg[red]%}"
            ZSH_THEME_GIT_PROMPT_SUFFIX="%{${'$'}reset_color%} "
            ZSH_THEME_GIT_PROMPT_DIRTY="%{${'$'}fg[blue]%}) %{${'$'}fg[yellow]%}✗"
            ZSH_THEME_GIT_PROMPT_CLEAN="%{${'$'}fg[blue]%})"

            PROMPT='%(?:%{${'$'}fg_bold[green]%}➜ :%{${'$'}fg_bold[red]%}➜ ) %{${'$'}fg[cyan]%}%c%{${'$'}reset_color%}${'$'}(git_prompt_info) '
        """.trimIndent() + "\n"
        File(themesDir, "robbyrussell.zsh-theme").writeText(robbyrussellThemeContent)
        File(ohMyZshThemesDir, "robbyrussell.zsh-theme").writeText(robbyrussellThemeContent)

        val zshenvContent = envScript + """
            export SHELL="/usr/bin/zsh"
            export ZDOTDIR="/root"
            export ZSH_THEME="robbyrussell"
        """.trimIndent() + "\n"
        File(etcZsh, "zshenv").writeText(zshenvContent)
        File(rootHome, ".zshenv").writeText(zshenvContent)

        val zshrcContent = """
            # PocketDev Ubuntu 24.04 LTS — Zsh Configuration (.zshrc)
            [ -f /etc/profile.d/pocketdev.sh ] && source /etc/profile.d/pocketdev.sh

            export ZSH_THEME="${'$'}{ZSH_THEME:-robbyrussell}"
            export HISTFILE="/root/.zsh_history"
            export HISTSIZE=10000
            export SAVEHIST=10000

            setopt APPEND_HISTORY
            setopt INC_APPEND_HISTORY
            setopt SHARE_HISTORY
            setopt HIST_IGNORE_DUPS
            setopt HIST_IGNORE_ALL_DUPS
            setopt HIST_IGNORE_SPACE
            setopt HIST_REDUCE_BLANKS
            setopt AUTO_CD
            setopt INTERACTIVE_COMMENTS
            setopt PROMPT_SUBST
            setopt EXTENDED_GLOB
            setopt NO_BEEP

            # Load robbyrussell theme and interactive ZLE plugins ONLY in interactive shells
            # so non-interactive `zsh -c` commands execute instantaneously.
            if [[ -o interactive ]]; then
                fpath=(/usr/share/zsh/plugins/zsh-completions/src /usr/share/zsh/vendor-completions ${'$'}fpath)
                autoload -Uz compinit
                compinit -C -u -d /tmp/.zcompdump 2>/dev/null || true
                zstyle ':completion:*' menu select
                zstyle ':completion:*' matcher-list 'm:{a-zA-Z}={A-Za-z}' 'r:|[._-]=* r:|=*'

                if [ -f "/usr/share/zsh/themes/${'$'}{ZSH_THEME}.zsh-theme" ]; then
                    source "/usr/share/zsh/themes/${'$'}{ZSH_THEME}.zsh-theme"
                elif [ -f "/usr/share/zsh/themes/robbyrussell.zsh-theme" ]; then
                    source "/usr/share/zsh/themes/robbyrussell.zsh-theme"
                else
                    autoload -Uz colors && colors
                    PROMPT='%(?:%{${'$'}fg_bold[green]%}➜ :%{${'$'}fg_bold[red]%}➜ ) %{${'$'}fg[cyan]%}%c%{${'$'}reset_color%} '
                fi

                [ -f /usr/share/zsh/plugins/zsh-completions/zsh-completions.plugin.zsh ] && source /usr/share/zsh/plugins/zsh-completions/zsh-completions.plugin.zsh
                [ -f /usr/share/zsh/plugins/zsh-history-substring-search/zsh-history-substring-search.zsh ] && source /usr/share/zsh/plugins/zsh-history-substring-search/zsh-history-substring-search.zsh
                [ -f /usr/share/zsh/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh ] && source /usr/share/zsh/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh
                [ -f /usr/share/zsh/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ] && source /usr/share/zsh/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh
            fi
        """.trimIndent() + "\n"

        File(etcZsh, "zshrc").writeText(zshrcContent)
        File(rootHome, ".zshrc").writeText(zshrcContent)
        File(rootHome, ".zsh_history").apply {
            if (!exists()) writeText("")
        }
        cachedPluginsProvisioned = true
    }

    /**
     * Installs the real Ubuntu `zsh`, `zsh-autosuggestions`, and `zsh-syntax-highlighting` packages
     * inside the extracted Ubuntu rootfs via PRoot.
     */
    fun installZshInUbuntu(
        context: Context,
        onProgress: (String) -> Unit
    ): Boolean {
        if (!isUbuntuInstalled(context)) return false
        configureUbuntuRootfs(context)
        if (isZshBinaryInstalled(context)) {
            onProgress("\u001b[1;32m[OK]\u001b[0m Real Ubuntu Zsh (/usr/bin/zsh) and plugins are already installed.")
            return true
        }
        val mode = preferredLaunchMode ?: availableLaunchModes(context).firstOrNull() ?: return false
        val activeProject = ProjectManager.ensureDefaultLocalWorkspace(context)
        val script = "/usr/local/bin/zsh-setup"
        return try {
            val cmdList = buildProotCommandListForMode(
                context = context,
                hostWorkingDir = activeProject,
                guestScript = script,
                mode = mode,
                forceBash = true
            )
            val pb = ProcessBuilder(cmdList)
                .directory(activeProject)
                .redirectErrorStream(true)
            applyHostProotEnv(context, pb.environment())
            val proc = pb.start()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { onProgress("  $it") }
                }
            }
            proc.waitFor()
            isZshBinaryInstalled(context)
        } catch (e: Exception) {
            onProgress("\u001b[1;33m  [Note] Could not auto-install Zsh via apt right now (${e.message}). Run 'zsh-setup' in terminal anytime.\u001b[0m")
            false
        }
    }

    // ---------------------------------------------------------------- PRoot Command Execution Builder

    fun applyHostProotEnv(context: Context, env: MutableMap<String, String>) {
        val usrLib = usrLibDir(context)
        val usrTmp = usrTmpDir(context)
        val home = homeDir(context)
        val prootBin = prootBinaryFile(context)
        val loader = prootLoaderFile(context)
        val loader32 = prootLoader32File(context)

        // Unset Zygote / OEM LD_PRELOAD so it never interferes with PRoot's ptrace or dynamic linker
        env.remove("LD_PRELOAD")
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = usrTmp.absolutePath
        env["PROOT_TMP_DIR"] = usrTmp.absolutePath
        env["PROOT_NO_SECCOMP"] = "1"

        if (isWrapperScript(prootBin)) {
            val alpineSupport = alpineProotSupportDir(context)
            val alpineLib = File(alpineSupport, "lib")
            val alpineUsrLib = File(alpineSupport, "usr/lib")
            env["LD_LIBRARY_PATH"] = "${alpineLib.absolutePath}:${alpineUsrLib.absolutePath}"
            env.remove("PROOT_LOADER")
            env.remove("PROOT_LOADER_32")
        } else {
            env["LD_LIBRARY_PATH"] = usrLib.absolutePath
            if (loader.exists() && loader.length() > 0L) {
                env["PROOT_LOADER"] = loader.absolutePath
            }
            if (loader32.exists() && loader32.length() > 0L) {
                env["PROOT_LOADER_32"] = loader32.absolutePath
            }
        }
    }

    /**
     * Maps any host directory (including projects inside `$ubuntuRoot/root/projects/<name>`
     * or subdirectories inside Ubuntu) to its exact guest path inside Ubuntu PRoot.
     */
    fun hostDirToGuestPath(context: Context, hostDir: File): String {
        val workspace = ProjectManager.ensureDefaultLocalWorkspace(context)
        val ubuntuRoot = ubuntuRootDir(context)
        val home = homeDir(context)

        val dirCanon = canonicalOrAbs(hostDir)
        val rootCanon = canonicalOrAbs(ubuntuRoot)
        val wsCanon = canonicalOrAbs(workspace)
        val homeCanon = canonicalOrAbs(home)

        return when {
            dirCanon == rootCanon -> "/"
            dirCanon.startsWith("$rootCanon/") -> "/" + dirCanon.removePrefix("$rootCanon/")
            dirCanon == wsCanon -> "/root/projects/${workspace.name}"
            dirCanon.startsWith("$wsCanon/") ->
                "/root/projects/${workspace.name}/" + dirCanon.removePrefix("$wsCanon/")
            dirCanon == homeCanon -> "/root"
            dirCanon.startsWith("$homeCanon/") -> "/root/" + dirCanon.removePrefix("$homeCanon/")
            else -> "/root/projects/${workspace.name}"
        }
    }

    fun guestPathToHostDir(context: Context, guestPath: String): File {
        val clean = guestPath.trim()
        val workspace = ProjectManager.ensureDefaultLocalWorkspace(context)
        val ubuntuRoot = ubuntuRootDir(context)

        return when {
            clean == "/root/project" || clean == "/mnt/project" -> workspace
            clean.startsWith("/root/project/") -> File(workspace, clean.removePrefix("/root/project/"))
            clean.startsWith("/mnt/project/") -> File(workspace, clean.removePrefix("/mnt/project/"))
            clean == "/" -> ubuntuRoot
            clean.startsWith("/") -> File(ubuntuRoot, clean.removePrefix("/"))
            else -> workspace
        }
    }

    fun buildProotCommandList(
        context: Context,
        hostWorkingDir: File,
        guestScript: String,
        useLinker64Wrapper: Boolean = false
    ): List<String> {
        val mode = if (useLinker64Wrapper) {
            val prootBin = prootBinaryFile(context)
            when {
                isPiEElf(prootBin) && File(systemLinkerPath()).exists() -> ProotLaunchMode.SYSTEM_LINKER
                findMuslLoaderFile(context) != null && prootElfFile(context).exists() -> ProotLaunchMode.MUSL_DIRECT
                isWrapperScript(prootBin) -> ProotLaunchMode.SH_WRAPPER
                else -> ProotLaunchMode.DIRECT
            }
        } else {
            preferredLaunchMode ?: availableLaunchModes(context).firstOrNull() ?: ProotLaunchMode.DIRECT
        }
        return buildProotCommandListForMode(context, hostWorkingDir, guestScript, mode)
    }

    private fun getStaticProotBindArgs(context: Context): List<String> {
        cachedStaticBindArgs?.let { return it }
        val ubuntuRoot = ubuntuRootDir(context)
        val projectsRoot = ProjectManager.projectsRootDir(context)
        val args = ArrayList<String>(40)
        args.add("--link2symlink")
        args.add("-0")
        args.add("-r")
        args.add(ubuntuRoot.absolutePath)
        args.add("-b")
        args.add("/dev")
        args.add("-b")
        args.add("/dev/urandom:/dev/random")
        args.add("-b")
        args.add("/proc")
        args.add("-b")
        args.add("/proc/self/fd:/dev/fd")
        args.add("-b")
        args.add("/proc/self/fd/0:/dev/stdin")
        args.add("-b")
        args.add("/proc/self/fd/1:/dev/stdout")
        args.add("-b")
        args.add("/proc/self/fd/2:/dev/stderr")
        args.add("-b")
        args.add("/sys")

        val guestTmp = File(ubuntuRoot, "tmp").apply { if (!exists()) mkdirs() }
        args.add("-b")
        args.add("${guestTmp.absolutePath}:/dev/shm")

        val procBinds = listOf(
            ".stat" to "/proc/stat",
            ".version" to "/proc/version",
            ".uptime" to "/proc/uptime",
            ".loadavg" to "/proc/loadavg",
            ".vmstat" to "/proc/vmstat",
            ".sysctl_entry_cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
            ".sysctl_inotify_max_user_watches" to "/proc/sys/fs/inotify/max_user_watches"
        )
        for ((srcName, targetProc) in procBinds) {
            val srcFile = File(ubuntuRoot, "proc/$srcName")
            if (srcFile.exists()) {
                args.add("-b")
                args.add("${srcFile.absolutePath}:$targetProc")
            }
        }

        args.add("-b")
        args.add("${projectsRoot.absolutePath}:/root/projects")
        args.add("-b")
        args.add("${context.filesDir.absolutePath}:/mnt/host")
        if (File("/sdcard").exists()) {
            args.add("-b")
            args.add("/sdcard")
        }
        cachedStaticBindArgs = args
        return args
    }

    fun buildProotCommandListForMode(
        context: Context,
        hostWorkingDir: File,
        guestScript: String,
        mode: ProotLaunchMode,
        forceBash: Boolean = false,
        fastNoRc: Boolean = true
    ): List<String> {
        val ubuntuRoot = ubuntuRootDir(context)
        val workspace = ProjectManager.ensureDefaultLocalWorkspace(context)
        val prootBin = prootBinaryFile(context)
        val guestWorkDir = hostDirToGuestPath(context, hostWorkingDir)

        val cmd = ArrayList<String>(64)
        val launchPrefix = buildLaunchPrefix(context, mode) ?: listOf(prootBin.absolutePath)
        cmd.addAll(launchPrefix)
        cmd.addAll(getStaticProotBindArgs(context))

        // Bind-mount active project to /root/project & /mnt/project
        cmd.add("-b")
        cmd.add("${workspace.absolutePath}:/root/project")
        cmd.add("-b")
        cmd.add("${workspace.absolutePath}:/mnt/project")

        cmd.add("-w")
        cmd.add(guestWorkDir)
        cmd.add("--kill-on-exit")

        val preferZsh = !forceBash && Settings(context).defaultShell == Settings.SHELL_ZSH
        val guestShell = when {
            preferZsh && isZshBinaryInstalled(context) -> {
                if (File(ubuntuRoot, "usr/bin/zsh").exists()) "/usr/bin/zsh" else "/bin/zsh"
            }
            File(ubuntuRoot, "usr/bin/bash").exists() -> "/usr/bin/bash"
            File(ubuntuRoot, "bin/bash").exists() -> "/bin/bash"
            else -> "/bin/sh"
        }

        cmd.add("/usr/bin/env")
        cmd.add("-i")
        cmd.add("HOME=/root")
        cmd.add("USER=root")
        cmd.add("LOGNAME=root")
        cmd.add("SHELL=$guestShell")
        cmd.add("ZDOTDIR=/root")
        cmd.add("ZSH_THEME=${Settings(context).zshTheme}")
        cmd.add("TERM=xterm-256color")
        cmd.add("COLORTERM=truecolor")
        cmd.add("LANG=C.UTF-8")
        cmd.add("LC_ALL=C.UTF-8")
        cmd.add("DEBIAN_FRONTEND=noninteractive")
        cmd.add("TMPDIR=/tmp")
        cmd.add("GOPATH=/root/go")
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/go/bin:/root/go/bin:/root/.cargo/bin")
        cmd.add(guestShell)
        if (fastNoRc) {
            if (guestShell.endsWith("zsh")) {
                cmd.add("-f")
            } else if (guestShell.endsWith("bash")) {
                cmd.add("--norc")
                cmd.add("--noprofile")
            }
        }
        cmd.add("-c")
        cmd.add(guestScript)

        return cmd
    }

    private fun canonicalOrAbs(f: File): String =
        runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)


    // ---------------------------------------------------------------- Environment Backup & Restore (.tar.gz via SAF)

    /**
     * Bundles the entire `$filesDir/ubuntu/` rootfs and `$filesDir/usr/` PRoot environment
     * into a compressed `.tar.gz` archive written to [outputStream], preserving POSIX permissions
     * and symbolic links so it can be restored offline on a fresh install without re-downloading.
     */
    fun exportEnvironmentBackupToStream(
        context: Context,
        outputStream: OutputStream,
        onProgress: (String) -> Unit
    ): Boolean {
        val rootDir = ubuntuRootDir(context)
        val usr = usrDir(context)
        if (!rootDir.exists()) {
            onProgress("Ubuntu rootfs directory does not exist yet — run ubuntu-bootstrap first.")
            return false
        }
        return try {
            onProgress("==> Creating Ubuntu 24.04 + PRoot environment backup (.tar.gz)...")
            DownloadNotificationHelper.showProgress(
                context = context,
                notificationId = DownloadNotificationHelper.NOTIF_ID_BACKUP,
                title = "Backing up Ubuntu Environment...",
                subtitle = "Archiving /ubuntu and /usr into .tar.gz...",
                progress = 10,
                indeterminate = true
            )
            var lastReported = 0
            val totalWritten = TarGzExtractor.writeTarGzArchive(
                baseDir = context.filesDir,
                subDirs = listOf("ubuntu", "usr", "lsp"),
                outputStream = outputStream
            ) { count, relPath ->
                if (count - lastReported >= 400) {
                    lastReported = count
                    val msg = "  Archived $count files ($relPath)..."
                    onProgress(msg)
                    DownloadNotificationHelper.showProgress(
                        context = context,
                        notificationId = DownloadNotificationHelper.NOTIF_ID_BACKUP,
                        title = "Backing up Ubuntu Environment ($count files)",
                        subtitle = relPath,
                        progress = (count / 120).coerceIn(5, 95),
                        indeterminate = false
                    )
                }
            }
            val doneMsg = "✓ Environment backup complete ($totalWritten entries archived)."
            onProgress(doneMsg)
            DiagLogger.log("Backup", doneMsg, context)
            DownloadNotificationHelper.complete(
                context = context,
                notificationId = DownloadNotificationHelper.NOTIF_ID_BACKUP,
                title = "Environment Backup Complete ✓",
                message = "Saved $totalWritten files, symlinks, and packages to .tar.gz",
                success = true
            )
            true
        } catch (e: Exception) {
            val errMsg = "Backup failed: ${e.message}"
            onProgress(errMsg)
            DiagLogger.log("Backup", errMsg, context, e)
            DownloadNotificationHelper.complete(
                context = context,
                notificationId = DownloadNotificationHelper.NOTIF_ID_BACKUP,
                title = "Environment Backup Failed",
                message = e.message ?: "I/O error",
                success = false
            )
            false
        }
    }

    /**
     * Restores a previously exported `.tar.gz` environment bundle from [inputStream] directly
     * into `context.filesDir`, skipping the 28.5 MB rootfs download and PRoot setup entirely.
     */
    fun importEnvironmentBackupFromStream(
        context: Context,
        inputStream: InputStream,
        onProgress: (String) -> Unit
    ): Boolean {
        synchronized(bootstrapLock) {
            if (isBootstrapping) {
                onProgress("Another environment operation is currently running...")
                return false
            }
            isBootstrapping = true
        }
        return try {
            invalidateRuntimeCaches()
            onProgress("==> Restoring Ubuntu 24.04 + PRoot environment from backup archive...")
            DownloadNotificationHelper.showProgress(
                context = context,
                notificationId = DownloadNotificationHelper.NOTIF_ID_BACKUP,
                title = "Restoring Ubuntu Environment...",
                subtitle = "Extracting rootfs & PRoot binaries...",
                progress = 10,
                indeterminate = true
            )
            var lastReported = 0
            val count = GZIPInputStream(BufferedInputStream(inputStream, 65536)).use { gis ->
                TarGzExtractor.extractTarStream(
                    tarInput = gis,
                    destRoot = context.filesDir
                ) { extractedCount, entry ->
                    if (extractedCount - lastReported >= 400) {
                        lastReported = extractedCount
                        val msg = "  Restored $extractedCount files ($entry)..."
                        onProgress(msg)
                        DownloadNotificationHelper.showProgress(
                            context = context,
                            notificationId = DownloadNotificationHelper.NOTIF_ID_BACKUP,
                            title = "Restoring Ubuntu Environment ($extractedCount files)",
                            subtitle = entry,
                            progress = (extractedCount / 120).coerceIn(5, 95),
                            indeterminate = false
                        )
                    }
                }
            }
            val prootBin = prootBinaryFile(context)
            if (prootBin.exists()) TarGzExtractor.setModeSafe(prootBin, 493)
            prootElfFile(context).takeIf { it.exists() }?.let { TarGzExtractor.setModeSafe(it, 493) }
            prootLoaderFile(context).takeIf { it.exists() }?.let { TarGzExtractor.setModeSafe(it, 493) }
            prootLoader32File(context).takeIf { it.exists() }?.let { TarGzExtractor.setModeSafe(it, 493) }
            findMuslLoaderFile(context)?.let { TarGzExtractor.setModeSafe(it, 493) }
            materializeSharedLibraryAliases(usrLibDir(context))

            configureUbuntuRootfs(context)
            invalidateRuntimeCaches()
            ProjectManager.invalidateWorkspaceCache()
            LspManager.syncTerminalInstallations(context)

            val ok = isUbuntuRootfsExtracted(context)
            val msg = if (ok) {
                "✓ Restored $count files! Ubuntu $UBUNTU_VERSION + PRoot + Zsh (robbyrussell) is ready."
            } else {
                "Restore extracted $count files, but /usr/bin/bash was not found in the archive."
            }
            onProgress(msg)
            DiagLogger.log("Restore", msg, context)
            DownloadNotificationHelper.complete(
                context = context,
                notificationId = DownloadNotificationHelper.NOTIF_ID_BACKUP,
                title = if (ok) "Environment Restored ✓" else "Restore Incomplete",
                message = msg,
                success = ok
            )
            ok
        } catch (e: Exception) {
            val errMsg = "Restore failed: ${e.message}"
            onProgress(errMsg)
            DiagLogger.log("Restore", errMsg, context, e)
            DownloadNotificationHelper.complete(
                context = context,
                notificationId = DownloadNotificationHelper.NOTIF_ID_BACKUP,
                title = "Environment Restore Failed",
                message = e.message ?: "Invalid archive",
                success = false
            )
            false
        } finally {
            synchronized(bootstrapLock) {
                isBootstrapping = false
            }
        }
    }

    // ---------------------------------------------------------------- Network Helpers

    /**
     * Resolves the exact `.apk` filename for [packageName] in an Alpine repository without
     * truncating large repositories like `community/aarch64/` or `testing/aarch64/`.
     * Returns `null` if the package does not exist in [repoBaseUrl].
     */
    private fun findAlpineApkFilename(repoBaseUrl: String, packageName: String): String? {
        val pattern = Regex("""href="(${Regex.escape(packageName)}-[0-9][^"]*\.apk)"""")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$repoBaseUrl/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PocketDev/2.0 (Android; Linux PRoot)")
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8), 32768).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val match = pattern.find(line ?: "")
                        if (match != null) {
                            return match.groupValues[1]
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }

        // Fallback: parse APKINDEX.tar.gz if HTML directory listing was unavailable
        val apkIndexFile = File.createTempFile("apkindex_", ".tar.gz")
        try {
            if (downloadFile("$repoBaseUrl/APKINDEX.tar.gz", apkIndexFile, null) && apkIndexFile.length() > 512L) {
                val extractDir = File(apkIndexFile.parentFile, "apkindex_ext_${System.nanoTime()}").apply { mkdirs() }
                try {
                    TarGzExtractor.extractConcatenatedTarGz(apkIndexFile, extractDir)
                    val indexFile = File(extractDir, "APKINDEX")
                    if (indexFile.exists()) {
                        var curPkg: String? = null
                        var curVer: String? = null
                        indexFile.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            for (raw in lines) {
                                when {
                                    raw.isEmpty() -> {
                                        if (curPkg == packageName && !curVer.isNullOrEmpty()) {
                                            return "$packageName-$curVer.apk"
                                        }
                                        curPkg = null
                                        curVer = null
                                    }
                                    raw.startsWith("P:") -> curPkg = raw.removePrefix("P:").trim()
                                    raw.startsWith("V:") -> curVer = raw.removePrefix("V:").trim()
                                }
                            }
                        }
                        if (curPkg == packageName && !curVer.isNullOrEmpty()) {
                            return "$packageName-$curVer.apk"
                        }
                    }
                } finally {
                    extractDir.deleteRecursively()
                }
            }
        } catch (_: Exception) {
        } finally {
            apkIndexFile.delete()
        }

        return null
    }

    private fun fetchUrlText(urlStr: String, maxBytes: Int = 1024 * 1024): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PocketDev/2.0 (Android; Linux PRoot)")
            }
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var read: Int
                while (reader.read(buf).also { read = it } != -1) {
                    sb.append(buf, 0, read)
                    if (sb.length >= maxBytes) break
                }
                sb.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun downloadFile(
        urlStr: String,
        destFile: File,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            destFile.parentFile?.mkdirs()
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 5) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "PocketDev/2.0 (Android; Linux PRoot)")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return false
                    conn.disconnect()
                    currentUrl = loc
                    redirects++
                    continue
                }
                if (code !in 200..299) return false
                break
            }
            val activeConn = conn ?: return false
            val totalLen = activeConn.contentLengthLong
            var downloaded = 0L
            var lastReportTime = 0L

            BufferedInputStream(activeConn.inputStream, 65536).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(65536)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n.toLong()
                        val now = System.currentTimeMillis()
                        if (onProgress != null && (now - lastReportTime >= 400L || downloaded == totalLen)) {
                            lastReportTime = now
                            onProgress(downloaded, totalLen)
                        }
                    }
                }
            }
            downloaded > 0L
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------- Embedded POSIX Tar.gz / Deb / Apk Extractor

    object TarGzExtractor {

        data class DeferredLink(
            val destFile: File,
            val target: String,
            val isSymlink: Boolean
        )

        fun extractTarGz(
            tarGzFile: File,
            destRoot: File,
            stripPrefix: String = "",
            onProgress: ((filesExtracted: Int, currentEntry: String) -> Unit)? = null
        ): Int {
            destRoot.mkdirs()
            setModeSafe(destRoot, 493)
            FileInputStream(tarGzFile).use { fis ->
                BufferedInputStream(fis, 65536).use { bis ->
                    GZIPInputStream(bis, 65536).use { gis ->
                        return extractTarStream(gis, destRoot, stripPrefix, onProgress)
                    }
                }
            }
        }

        fun extractConcatenatedTarGz(
            apkFile: File,
            destRoot: File,
            onProgress: ((filesExtracted: Int, currentEntry: String) -> Unit)? = null
        ): Int {
            destRoot.mkdirs()
            val bytes = apkFile.readBytes()
            var totalExtracted = 0
            var i = 0
            val limit = bytes.size - 18
            while (i <= limit) {
                if (bytes[i] == 0x1F.toByte() &&
                    bytes[i + 1] == 0x8B.toByte() &&
                    bytes[i + 2] == 0x08.toByte() &&
                    (bytes[i + 3].toInt() and 0xE0) == 0
                ) {
                    try {
                        ByteArrayInputStream(bytes, i, bytes.size - i).use { bais ->
                            GZIPInputStream(bais, 32768).use { gis ->
                                val count = extractTarStream(gis, destRoot, "", onProgress)
                                if (count > 0) {
                                    totalExtracted += count
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                i++
            }
            return totalExtracted
        }

        fun extractDebPackage(
            debFile: File,
            destRoot: File,
            stripPrefix: String = "data/data/com.termux/files/",
            busyboxBin: File? = null
        ): Boolean {
            destRoot.mkdirs()
            val bytes = debFile.readBytes()
            if (bytes.size < 68) return false
            val magic = String(bytes, 0, 8, StandardCharsets.US_ASCII)
            if (magic != "!<arch>\n") return false

            var offset = 8
            while (offset + 60 <= bytes.size) {
                val nameRaw = String(bytes, offset, 16, StandardCharsets.US_ASCII).trim().removeSuffix("/")
                val sizeRaw = String(bytes, offset + 48, 10, StandardCharsets.US_ASCII).trim()
                val entrySize = sizeRaw.toLongOrNull()?.toInt() ?: break
                offset += 60
                if (offset + entrySize > bytes.size) break

                if (nameRaw.startsWith("data.tar")) {
                    val payloadFile = File(destRoot.parentFile ?: destRoot, ".tmp_${debFile.name}_$nameRaw")
                    try {
                        FileOutputStream(payloadFile).use { fos ->
                            fos.write(bytes, offset, entrySize)
                        }
                        when {
                            nameRaw.endsWith(".gz") -> {
                                extractTarGz(payloadFile, destRoot, stripPrefix = stripPrefix)
                                return true
                            }
                            nameRaw.endsWith(".xz") -> {
                                // Tier 1: Pure-Kotlin built-in XZ + LZMA2 + ARM64/ARM/x86 BCJ decompressor
                                val extractedViaPureKotlin = runCatching {
                                    val rawTarBytes = PureKotlinXzDecoder.decompress(payloadFile.readBytes())
                                    if (rawTarBytes.size > 512) {
                                        ByteArrayInputStream(rawTarBytes).use { bais ->
                                            val count = extractTarStream(bais, destRoot, stripPrefix = stripPrefix)
                                            count > 0
                                        }
                                    } else {
                                        false
                                    }
                                }.getOrDefault(false)
                                if (extractedViaPureKotlin) return true

                                // Tier 2: Try org.tukaani.xz.XZInputStream if available on classpath
                                val extractedViaTukaani = runCatching {
                                    val xzCls = Class.forName("org.tukaani.xz.XZInputStream")
                                    val ctor = xzCls.getConstructor(InputStream::class.java)
                                    FileInputStream(payloadFile).use { fis ->
                                        BufferedInputStream(fis, 65536).use { bis ->
                                            (ctor.newInstance(bis) as InputStream).use { xzis ->
                                                val count = extractTarStream(xzis, destRoot, stripPrefix = stripPrefix)
                                                count > 0
                                            }
                                        }
                                    }
                                }.getOrDefault(false)
                                if (extractedViaTukaani) return true

                                // Tier 3: Host CLI fallback
                                val rawTarFile = File(payloadFile.parentFile, "${payloadFile.name}.tar")
                                try {
                                    val decompressed = decompressXzFile(payloadFile, rawTarFile, busyboxBin)
                                    if (decompressed && rawTarFile.exists() && rawTarFile.length() > 512L) {
                                        FileInputStream(rawTarFile).use { fis ->
                                            BufferedInputStream(fis, 65536).use { bis ->
                                                extractTarStream(bis, destRoot, stripPrefix = stripPrefix)
                                            }
                                        }
                                        return true
                                    }
                                } finally {
                                    rawTarFile.delete()
                                }
                            }
                            nameRaw == "data.tar" -> {
                                FileInputStream(payloadFile).use { fis ->
                                    extractTarStream(fis, destRoot, stripPrefix = stripPrefix)
                                }
                                return true
                            }
                        }
                    } finally {
                        payloadFile.delete()
                    }
                }

                offset += entrySize
                if (offset % 2 != 0) offset++
            }
            return false
        }

        private fun decompressXzFile(xzFile: File, outTarFile: File, busyboxBin: File?): Boolean {
            val candidates = mutableListOf<List<String>>()
            if (busyboxBin != null && busyboxBin.exists()) {
                candidates.add(listOf(busyboxBin.absolutePath, "unxz", "-c", xzFile.absolutePath))
                candidates.add(listOf(systemLinkerPath(), busyboxBin.absolutePath, "unxz", "-c", xzFile.absolutePath))
            }
            if (File("/system/bin/xz").exists()) {
                candidates.add(listOf("/system/bin/xz", "-dc", xzFile.absolutePath))
            }
            if (File("/system/bin/toybox").exists()) {
                candidates.add(listOf("/system/bin/toybox", "xzcat", xzFile.absolutePath))
            }

            for (cmd in candidates) {
                try {
                    val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
                    FileOutputStream(outTarFile).use { fos ->
                        proc.inputStream.copyTo(fos, 65536)
                    }
                    val code = proc.waitFor()
                    if (code == 0 && outTarFile.exists() && outTarFile.length() > 512L) {
                        return true
                    }
                } catch (_: Exception) {
                }
            }
            return false
        }

        /**
         * Self-contained pure-Kotlin XZ (`0xFD, '7', 'z', 'X', 'Z', 0x00`) + LZMA2 + BCJ
         * (`ARM64=0x0A`, `ARM=0x07`, `x86=0x04`, `Delta=0x03`) stream decompressor so `.deb`
         * packages containing `data.tar.xz` decompress natively in-memory without external tools.
         */
        object PureKotlinXzDecoder {

            private val X86_MASK_TO_ALLOWED_STATUS = booleanArrayOf(true, true, true, false, true, false, false, false)
            private val X86_MASK_TO_BIT_NUMBER = intArrayOf(0, 1, 2, 2, 3, 3, 3, 3)

            fun decompress(data: ByteArray): ByteArray {
                val out = ByteArrayOutputStream(maxOf(65536, data.size * 3))
                var pos = 0
                val n = data.size

                fun readVli(startPos: Int): Pair<Long, Int> {
                    var p = startPos
                    var value = 0L
                    var shift = 0
                    while (p < n) {
                        val b = data[p].toInt() and 0xFF
                        p++
                        value = value or ((b and 0x7F).toLong() shl shift)
                        if ((b and 0x80) == 0) {
                            return value to p
                        }
                        shift += 7
                    }
                    throw IllegalArgumentException("Truncated XZ VLI")
                }

                while (pos < n) {
                    while (pos < n && data[pos].toInt() == 0) {
                        pos++
                    }
                    if (pos >= n) break

                    if (pos + 12 > n ||
                        data[pos] != 0xFD.toByte() ||
                        data[pos + 1] != '7'.code.toByte() ||
                        data[pos + 2] != 'z'.code.toByte() ||
                        data[pos + 3] != 'X'.code.toByte() ||
                        data[pos + 4] != 'Z'.code.toByte() ||
                        data[pos + 5] != 0x00.toByte()
                    ) {
                        throw IllegalArgumentException("Invalid XZ stream header")
                    }

                    val checkType = data[pos + 7].toInt() and 0x0F
                    val checkSize = when (checkType) {
                        0x00 -> 0
                        0x01 -> 4
                        0x04 -> 8
                        0x0A -> 32
                        else -> 0
                    }
                    pos += 12

                    while (pos < n) {
                        val hdrByte = data[pos].toInt() and 0xFF
                        if (hdrByte == 0x00) {
                            // XZ Index + Stream Footer
                            pos += 1
                            val (numRecords, pAfterCount) = readVli(pos)
                            var pIdx = pAfterCount
                            for (r in 0 until numRecords) {
                                val (_, p1) = readVli(pIdx)
                                val (_, p2) = readVli(p1)
                                pIdx = p2
                            }
                            while ((pIdx % 4) != 0) pIdx++
                            pos = pIdx + 4 + 12
                            break
                        }

                        val blockHdrSize = (hdrByte + 1) * 4
                        val blockHdrEnd = pos + blockHdrSize
                        val flags = data[pos + 1].toInt() and 0xFF
                        val numFilters = (flags and 0x03) + 1
                        val hasCompSize = (flags and 0x40) != 0
                        val hasUncompSize = (flags and 0x80) != 0

                        var p = pos + 2
                        if (hasCompSize) {
                            p = readVli(p).second
                        }
                        if (hasUncompSize) {
                            p = readVli(p).second
                        }

                        val filters = ArrayList<Pair<Long, ByteArray>>(numFilters)
                        for (f in 0 until numFilters) {
                            val (fid, p1) = readVli(p)
                            val (propsLenLong, p2) = readVli(p1)
                            val propsLen = propsLenLong.toInt()
                            val props = data.copyOfRange(p2, (p2 + propsLen).coerceAtMost(n))
                            p = p2 + propsLen
                            filters.add(fid to props)
                        }

                        pos = blockHdrEnd
                        val (blockDecoded, nextPos) = decodeLzma2Block(data, pos)
                        pos = nextPos
                        while ((pos % 4) != 0) pos++
                        pos += checkSize

                        var filteredBytes = blockDecoded
                        if (filters.size > 1) {
                            for (idx in (filters.size - 2) downTo 0) {
                                val (fid, props) = filters[idx]
                                filteredBytes = applyBcjOrDeltaFilter(fid, props, filteredBytes)
                            }
                        }
                        out.write(filteredBytes)
                    }
                }

                return out.toByteArray()
            }

            private fun decodeLzma2Block(data: ByteArray, startPos: Int): Pair<ByteArray, Int> {
                var pos = startPos
                var outBuf = ByteArray(131072)
                var outLen = 0
                var dictResetOffset = 0

                fun ensureCapacity(needed: Int) {
                    if (outLen + needed > outBuf.size) {
                        var newCap = outBuf.size * 2
                        while (newCap < outLen + needed) newCap *= 2
                        outBuf = outBuf.copyOf(newCap)
                    }
                }

                var lc = 3
                var lp = 0
                var pb = 2
                var probs = IntArray(1846 + (768 shl 3)) { 1024 }
                var state = 0
                val reps = IntArray(4)

                fun resetState() {
                    state = 0
                    reps[0] = 0
                    reps[1] = 0
                    reps[2] = 0
                    reps[3] = 0
                    val numProbs = 1846 + (768 shl (lc + lp))
                    if (probs.size != numProbs) {
                        probs = IntArray(numProbs) { 1024 }
                    } else {
                        probs.fill(1024)
                    }
                }

                while (pos < data.size) {
                    val ctrl = data[pos].toInt() and 0xFF
                    pos++
                    if (ctrl == 0x00) {
                        break
                    }
                    if (ctrl < 0x80) {
                        if (ctrl != 0x01 && ctrl != 0x02) {
                            throw IllegalArgumentException("Invalid LZMA2 control byte: $ctrl")
                        }
                        if (ctrl == 0x01) {
                            dictResetOffset = outLen
                        }
                        val uSize = (((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)) + 1
                        pos += 2
                        ensureCapacity(uSize)
                        System.arraycopy(data, pos, outBuf, outLen, uSize)
                        outLen += uSize
                        pos += uSize
                    } else {
                        val resetMode = (ctrl ushr 5) and 0x03
                        val uSize = (((ctrl and 0x1F) shl 16) or
                            ((data[pos].toInt() and 0xFF) shl 8) or
                            (data[pos + 1].toInt() and 0xFF)) + 1
                        val cSize = (((data[pos + 2].toInt() and 0xFF) shl 8) or
                            (data[pos + 3].toInt() and 0xFF)) + 1
                        pos += 4
                        if (resetMode >= 2) {
                            val prop = data[pos].toInt() and 0xFF
                            pos++
                            lc = prop % 9
                            val rem = prop / 9
                            lp = rem % 5
                            pb = rem / 5
                        }
                        if (resetMode == 3) {
                            dictResetOffset = outLen
                        }
                        if (resetMode >= 1) {
                            resetState()
                        }

                        ensureCapacity(uSize)
                        val chunkEnd = pos + cSize
                        var rcCode = (((data[pos + 1].toLong() and 0xFFL) shl 24) or
                            ((data[pos + 2].toLong() and 0xFFL) shl 16) or
                            ((data[pos + 3].toLong() and 0xFFL) shl 8) or
                            (data[pos + 4].toLong() and 0xFFL)) and 0xFFFFFFFFL
                        var rcRange = 0xFFFFFFFFL
                        var rcPos = pos + 5

                        fun rcBit(probIdx: Int): Int {
                            if ((rcRange and 0xFF000000L) == 0L) {
                                rcRange = (rcRange shl 8) and 0xFFFFFFFFL
                                val b = if (rcPos < chunkEnd) (data[rcPos].toLong() and 0xFFL) else 0L
                                rcPos++
                                rcCode = ((rcCode shl 8) or b) and 0xFFFFFFFFL
                            }
                            val p = probs[probIdx]
                            val bound = ((rcRange ushr 11) * p.toLong()) and 0xFFFFFFFFL
                            return if (rcCode < bound) {
                                rcRange = bound
                                probs[probIdx] = p + ((2048 - p) ushr 5)
                                0
                            } else {
                                rcRange = (rcRange - bound) and 0xFFFFFFFFL
                                rcCode = (rcCode - bound) and 0xFFFFFFFFL
                                probs[probIdx] = p - (p ushr 5)
                                1
                            }
                        }

                        fun rcBitTree(baseIdx: Int, numBits: Int): Int {
                            var m = 1
                            for (i in 0 until numBits) {
                                m = (m shl 1) or rcBit(baseIdx + m)
                            }
                            return m - (1 shl numBits)
                        }

                        fun rcBitTreeRev(baseIdx: Int, numBits: Int): Int {
                            var m = 1
                            var symbol = 0
                            for (bitIdx in 0 until numBits) {
                                val bit = rcBit(baseIdx + m)
                                m = (m shl 1) or bit
                                symbol = symbol or (bit shl bitIdx)
                            }
                            return symbol
                        }

                        fun rcDirect(numBits: Int): Int {
                            var res = 0
                            for (i in 0 until numBits) {
                                if ((rcRange and 0xFF000000L) == 0L) {
                                    rcRange = (rcRange shl 8) and 0xFFFFFFFFL
                                    val b = if (rcPos < chunkEnd) (data[rcPos].toLong() and 0xFFL) else 0L
                                    rcPos++
                                    rcCode = ((rcCode shl 8) or b) and 0xFFFFFFFFL
                                }
                                rcRange = rcRange ushr 1
                                rcCode = (rcCode - rcRange) and 0xFFFFFFFFL
                                val t = 0L - (rcCode ushr 31)
                                rcCode = (rcCode + (rcRange and t)) and 0xFFFFFFFFL
                                res = (res shl 1) or (((t + 1L) and 1L).toInt())
                            }
                            return res
                        }

                        fun decodeLen(baseProb: Int, posState: Int): Int {
                            if (rcBit(baseProb) == 0) {
                                return rcBitTree(baseProb + 2 + (posState shl 3), 3)
                            }
                            if (rcBit(baseProb + 1) == 0) {
                                return 8 + rcBitTree(baseProb + 130 + (posState shl 3), 3)
                            }
                            return 16 + rcBitTree(baseProb + 258, 8)
                        }

                        val posStateMask = (1 shl pb) - 1
                        val lpMask = (1 shl lp) - 1
                        val targetLen = outLen + uSize

                        while (outLen < targetLen) {
                            val dictPos = outLen - dictResetOffset
                            val posState = dictPos and posStateMask
                            if (rcBit((state shl 4) + posState) == 0) {
                                val prevByte = if (dictPos > 0) (outBuf[outLen - 1].toInt() and 0xFF) else 0
                                val litState = ((dictPos and lpMask) shl lc) + (prevByte ushr (8 - lc))
                                val litBase = 1846 + 768 * litState
                                var symbol = 1
                                if (state >= 7) {
                                    var matchByte = outBuf[outLen - reps[0] - 1].toInt() and 0xFF
                                    while (symbol < 0x100) {
                                        val matchBit = (matchByte ushr 7) and 1
                                        matchByte = (matchByte shl 1) and 0xFF
                                        val bit = rcBit(litBase + ((1 + matchBit) shl 8) + symbol)
                                        symbol = (symbol shl 1) or bit
                                        if (matchBit != bit) break
                                    }
                                }
                                while (symbol < 0x100) {
                                    symbol = (symbol shl 1) or rcBit(litBase + symbol)
                                }
                                outBuf[outLen++] = (symbol and 0xFF).toByte()
                                state = when {
                                    state < 4 -> 0
                                    state < 10 -> state - 3
                                    else -> state - 6
                                }
                            } else {
                                var length: Int
                                if (rcBit(192 + state) == 1) {
                                    if (rcBit(204 + state) == 0) {
                                        if (rcBit(240 + (state shl 4) + posState) == 0) {
                                            state = if (state < 7) 9 else 11
                                            outBuf[outLen] = outBuf[outLen - reps[0] - 1]
                                            outLen++
                                            continue
                                        }
                                    } else {
                                        val dist: Int
                                        if (rcBit(216 + state) == 0) {
                                            dist = reps[1]
                                        } else {
                                            if (rcBit(228 + state) == 0) {
                                                dist = reps[2]
                                            } else {
                                                dist = reps[3]
                                                reps[3] = reps[2]
                                            }
                                            reps[2] = reps[1]
                                        }
                                        reps[1] = reps[0]
                                        reps[0] = dist
                                    }
                                    length = decodeLen(1332, posState) + 2
                                    state = if (state < 7) 8 else 11
                                } else {
                                    reps[3] = reps[2]
                                    reps[2] = reps[1]
                                    reps[1] = reps[0]
                                    length = decodeLen(818, posState) + 2
                                    state = if (state < 7) 7 else 10
                                    val lenToPos = if (length - 2 < 4) length - 2 else 3
                                    val posSlot = rcBitTree(432 + (lenToPos shl 6), 6)
                                    if (posSlot >= 4) {
                                        val numDirect = (posSlot ushr 1) - 1
                                        var dist = (2 or (posSlot and 1)) shl numDirect
                                        if (posSlot < 14) {
                                            dist += rcBitTreeRev(688 + dist - posSlot - 1, numDirect)
                                        } else {
                                            dist += rcDirect(numDirect - 4) shl 4
                                            dist += rcBitTreeRev(802, 4)
                                        }
                                        reps[0] = dist
                                    } else {
                                        reps[0] = posSlot
                                    }
                                }

                                val rep0 = reps[0]
                                for (k in 0 until length) {
                                    if (outLen >= targetLen) break
                                    outBuf[outLen] = outBuf[outLen - rep0 - 1]
                                    outLen++
                                }
                            }
                        }

                        pos = chunkEnd
                    }
                }

                return outBuf.copyOf(outLen) to pos
            }

            private fun applyBcjOrDeltaFilter(fid: Long, props: ByteArray, buf: ByteArray): ByteArray {
                val startOffset = if (props.size >= 4) {
                    (props[0].toInt() and 0xFF) or
                        ((props[1].toInt() and 0xFF) shl 8) or
                        ((props[2].toInt() and 0xFF) shl 16) or
                        ((props[3].toInt() and 0xFF) shl 24)
                } else {
                    0
                }
                when (fid) {
                    0x0AL -> {
                        // Official xz-utils ARM64 BCJ filter (liblzma/simple/arm64.c)
                        val limit = buf.size - 4
                        var i = 0
                        while (i <= limit) {
                            val pc = startOffset + i
                            var instr = (buf[i].toInt() and 0xFF) or
                                ((buf[i + 1].toInt() and 0xFF) shl 8) or
                                ((buf[i + 2].toInt() and 0xFF) shl 16) or
                                ((buf[i + 3].toInt() and 0xFF) shl 24)
                            if ((instr ushr 26) == 0x25) {
                                val src = instr and 0x03FFFFFF
                                val dest = (src - (pc ushr 2)) and 0x03FFFFFF
                                instr = (0x25 shl 26) or dest
                                buf[i] = (instr and 0xFF).toByte()
                                buf[i + 1] = ((instr ushr 8) and 0xFF).toByte()
                                buf[i + 2] = ((instr ushr 16) and 0xFF).toByte()
                                buf[i + 3] = ((instr ushr 24) and 0xFF).toByte()
                            } else if ((instr and 0x9F000000.toInt()) == 0x90000000.toInt()) {
                                val src = ((instr ushr 29) and 3) or ((instr ushr 3) and 0x001FFFFC)
                                if (((src + 0x00020000) and 0x001C0000) == 0) {
                                    val dest = src - (pc ushr 12)
                                    instr = instr and 0x9000001F.toInt()
                                    instr = instr or ((dest and 3) shl 29)
                                    instr = instr or ((dest and 0x0003FFFC) shl 3)
                                    if ((dest and 0x00020000) != 0) {
                                        instr = instr or 0x00E00000
                                    }
                                    buf[i] = (instr and 0xFF).toByte()
                                    buf[i + 1] = ((instr ushr 8) and 0xFF).toByte()
                                    buf[i + 2] = ((instr ushr 16) and 0xFF).toByte()
                                    buf[i + 3] = ((instr ushr 24) and 0xFF).toByte()
                                }
                            }
                            i += 4
                        }
                    }
                    0x07L -> {
                        // ARM BCJ filter
                        val limit = buf.size - 4
                        var i = 0
                        while (i <= limit) {
                            if ((buf[i + 3].toInt() and 0xFF) == 0xEB) {
                                val src = ((buf[i].toInt() and 0xFF) or
                                    ((buf[i + 1].toInt() and 0xFF) shl 8) or
                                    ((buf[i + 2].toInt() and 0xFF) shl 16)) shl 2
                                val dest = (src - (startOffset + i + 8)) ushr 2
                                buf[i] = (dest and 0xFF).toByte()
                                buf[i + 1] = ((dest ushr 8) and 0xFF).toByte()
                                buf[i + 2] = ((dest ushr 16) and 0xFF).toByte()
                            }
                            i += 4
                        }
                    }
                    0x04L -> {
                        // x86 BCJ filter (liblzma/simple/x86.c)
                        if (buf.size >= 5) {
                            val limit = buf.size - 5
                            var prevMask = 0
                            var prevPos = -5
                            var i = 0
                            while (i <= limit) {
                                val b = buf[i].toInt() and 0xFF
                                if (b != 0xE8 && b != 0xE9) {
                                    i++
                                    continue
                                }
                                val offset = i - prevPos
                                prevPos = i
                                if (offset > 5) {
                                    prevMask = 0
                                } else {
                                    for (k in 0 until offset) {
                                        prevMask = prevMask and 0x77
                                        prevMask = (prevMask shl 1) and 0xFF
                                    }
                                }
                                val b4 = buf[i + 4].toInt() and 0xFF
                                if ((b4 == 0x00 || b4 == 0xFF) && X86_MASK_TO_ALLOWED_STATUS[(prevMask ushr 1) and 7]) {
                                    var src = (buf[i + 1].toInt() and 0xFF) or
                                        ((buf[i + 2].toInt() and 0xFF) shl 8) or
                                        ((buf[i + 3].toInt() and 0xFF) shl 16) or
                                        (b4 shl 24)
                                    var dest: Int
                                    while (true) {
                                        dest = src - (startOffset + i + 5)
                                        if (prevMask == 0) break
                                        val idx = X86_MASK_TO_BIT_NUMBER[(prevMask ushr 1) and 7] * 8
                                        val checkB = (dest ushr (24 - idx)) and 0xFF
                                        if (checkB != 0x00 && checkB != 0xFF) break
                                        src = dest xor ((1 shl (32 - idx)) - 1)
                                    }
                                    buf[i + 1] = (dest and 0xFF).toByte()
                                    buf[i + 2] = ((dest ushr 8) and 0xFF).toByte()
                                    buf[i + 3] = ((dest ushr 16) and 0xFF).toByte()
                                    buf[i + 4] = if (((dest ushr 24) and 1) != 0) 0xFF.toByte() else 0x00.toByte()
                                    prevPos = i + 4
                                    i += 5
                                } else {
                                    prevMask = prevMask or 1
                                    i++
                                }
                            }
                        }
                    }
                    0x03L -> {
                        // Delta filter
                        val dist = if (props.isNotEmpty()) ((props[0].toInt() and 0xFF) + 1) else 1
                        for (i in dist until buf.size) {
                            buf[i] = ((buf[i].toInt() + buf[i - dist].toInt()) and 0xFF).toByte()
                        }
                    }
                }
                return buf
            }
        }


        /**
         * Writes a standard POSIX `ustar` + GNU `LongLink` `.tar.gz` archive containing [subDirs]
         * relative to [baseDir], preserving symbolic links (`Os.lstat` / `Os.readlink`), directories,
         * and executable permissions.
         */
        fun writeTarGzArchive(
            baseDir: File,
            subDirs: List<String>,
            outputStream: OutputStream,
            onProgress: ((count: Int, currentPath: String) -> Unit)? = null
        ): Int {
            var count = 0
            val ioBuf = ByteArray(65536)
            GZIPOutputStream(outputStream, 65536).use { gos ->
                fun writeEntry(file: File, relPath: String) {
                    if (relPath.isEmpty() || relPath.startsWith("ubuntu/proc/") || relPath.startsWith("ubuntu/sys/") || relPath.startsWith("ubuntu/dev/")) {
                        return
                    }
                    val lstat = runCatching { Os.lstat(file.absolutePath) }.getOrNull()
                    val isSymlink = lstat != null && ((lstat.st_mode and 0xF000) == 0xA000)
                    val isDir = !isSymlink && file.isDirectory
                    val isReg = !isSymlink && file.isFile

                    if (!isSymlink && !isDir && !isReg) return

                    val entryName = if (isDir && !relPath.endsWith("/")) "$relPath/" else relPath
                    val linkTarget = if (isSymlink) {
                        runCatching { Os.readlink(file.absolutePath) }.getOrDefault("")
                    } else {
                        ""
                    }
                    val size = if (isReg) file.length() else 0L
                    val mode = when {
                        isSymlink -> 511 // 0777
                        isDir -> 493     // 0755
                        lstat != null -> lstat.st_mode and 511
                        file.canExecute() -> 493
                        else -> 420     // 0644
                    }
                    val mtime = (file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()) / 1000L

                    val nameBytes = entryName.toByteArray(StandardCharsets.UTF_8)
                    if (nameBytes.size > 99) {
                        writeLongHeader(gos, 'L', nameBytes)
                    }
                    val linkBytes = linkTarget.toByteArray(StandardCharsets.UTF_8)
                    if (isSymlink && linkBytes.size > 99) {
                        writeLongHeader(gos, 'K', linkBytes)
                    }

                    val typeFlag = when {
                        isDir -> '5'
                        isSymlink -> '2'
                        else -> '0'
                    }
                    val hdr = buildUstarHeader(
                        name = entryName,
                        mode = mode,
                        size = size,
                        mtime = mtime,
                        typeFlag = typeFlag,
                        linkName = linkTarget
                    )
                    gos.write(hdr)

                    if (isReg && size > 0L) {
                        var written = 0L
                        FileInputStream(file).use { fis ->
                            while (written < size) {
                                val toRead = minOf(size - written, ioBuf.size.toLong()).toInt()
                                val n = fis.read(ioBuf, 0, toRead)
                                if (n <= 0) break
                                gos.write(ioBuf, 0, n)
                                written += n.toLong()
                            }
                        }
                        while (written < size) {
                            gos.write(0)
                            written++
                        }
                        val pad = ((512L - (size % 512L)) % 512L).toInt()
                        if (pad > 0) {
                            gos.write(ByteArray(pad))
                        }
                    }

                    count++
                    onProgress?.invoke(count, relPath)

                    if (isDir) {
                        val children = file.listFiles()?.sortedBy { it.name } ?: emptyList()
                        for (child in children) {
                            writeEntry(child, "$relPath/${child.name}")
                        }
                    }
                }

                for (sub in subDirs) {
                    val dir = File(baseDir, sub)
                    if (dir.exists()) {
                        writeEntry(dir, sub)
                    }
                }
                // Two 512-byte zero blocks mark EOF of tar archive
                gos.write(ByteArray(1024))
                gos.flush()
            }
            return count
        }

        private fun writeLongHeader(gos: OutputStream, typeFlag: Char, payloadBytes: ByteArray) {
            val withNull = ByteArray(payloadBytes.size + 1)
            System.arraycopy(payloadBytes, 0, withNull, 0, payloadBytes.size)
            val hdr = buildUstarHeader(
                name = "././@LongLink",
                mode = 420,
                size = withNull.size.toLong(),
                mtime = 0L,
                typeFlag = typeFlag,
                linkName = ""
            )
            gos.write(hdr)
            gos.write(withNull)
            val pad = ((512 - (withNull.size % 512)) % 512)
            if (pad > 0) {
                gos.write(ByteArray(pad))
            }
        }

        private fun buildUstarHeader(
            name: String,
            mode: Int,
            size: Long,
            mtime: Long,
            typeFlag: Char,
            linkName: String
        ): ByteArray {
            val h = ByteArray(512)
            writeTarStringField(h, 0, 100, name)
            writeTarOctalField(h, 100, 8, mode.toLong())
            writeTarOctalField(h, 108, 8, 0L)
            writeTarOctalField(h, 116, 8, 0L)
            writeTarOctalField(h, 124, 12, size)
            writeTarOctalField(h, 136, 12, mtime)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            h[156] = typeFlag.code.toByte()
            writeTarStringField(h, 157, 100, linkName)
            writeTarStringField(h, 257, 6, "ustar")
            h[263] = '0'.code.toByte()
            h[264] = '0'.code.toByte()
            writeTarStringField(h, 265, 32, "root")
            writeTarStringField(h, 297, 32, "root")

            var sum = 0L
            for (b in h) sum += (b.toInt() and 0xFF)
            val chkOct = sum.toString(8).padStart(6, '0')
            for (i in 0 until 6) h[148 + i] = chkOct[i].code.toByte()
            h[154] = 0
            h[155] = ' '.code.toByte()
            return h
        }

        private fun writeTarStringField(buf: ByteArray, offset: Int, len: Int, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val copyLen = minOf(bytes.size, len - 1)
            System.arraycopy(bytes, 0, buf, offset, copyLen)
        }

        private fun writeTarOctalField(buf: ByteArray, offset: Int, len: Int, value: Long) {
            val oct = value.coerceAtLeast(0L).toString(8).padStart(len - 1, '0')
            val start = (oct.length - (len - 1)).coerceAtLeast(0)
            for (i in 0 until (len - 1)) {
                buf[offset + i] = oct[start + i].code.toByte()
            }
            buf[offset + len - 1] = 0
        }

        fun extractTarStream(
            tarInput: InputStream,
            destRoot: File,
            stripPrefix: String = "",
            onProgress: ((filesExtracted: Int, currentEntry: String) -> Unit)? = null
        ): Int {
            val header = ByteArray(512)
            val ioBuf = ByteArray(65536)
            val deferredHardLinks = ArrayList<DeferredLink>()
            val deferredSymLinks = ArrayList<DeferredLink>()

            var nextLongName: String? = null
            var nextLongLink: String? = null
            var count = 0
            val cleanStrip = stripPrefix.removePrefix("./").removePrefix("/")

            while (true) {
                val readBytes = readFully(tarInput, header, 512)
                if (readBytes < 512 || isAllZero(header)) {
                    break
                }

                val typeFlag = header[156].toInt().toChar()
                val entrySize = parseTarSize(header, 124, 12)
                val entryMode = parseTarOctal(header, 100, 8).toInt()

                if (typeFlag == 'L') {
                    val data = readEntryPayload(tarInput, entrySize)
                    nextLongName = trimNulls(String(data, StandardCharsets.UTF_8))
                    continue
                }
                if (typeFlag == 'K') {
                    val data = readEntryPayload(tarInput, entrySize)
                    nextLongLink = trimNulls(String(data, StandardCharsets.UTF_8))
                    continue
                }
                if (typeFlag == 'x' || typeFlag == 'X') {
                    val data = readEntryPayload(tarInput, entrySize)
                    val pax = parsePaxHeaders(String(data, StandardCharsets.UTF_8))
                    pax["path"]?.let { nextLongName = it }
                    pax["linkpath"]?.let { nextLongLink = it }
                    continue
                }
                if (typeFlag == 'g') {
                    skipEntryPayload(tarInput, entrySize)
                    continue
                }

                val nameStr = nextLongName ?: run {
                    val baseName = parseTarString(header, 0, 100)
                    val magic = parseTarString(header, 257, 5)
                    val prefix = if (magic == "ustar") parseTarString(header, 345, 155) else ""
                    if (prefix.isNotEmpty()) "$prefix/$baseName" else baseName
                }
                val linkStr = nextLongLink ?: parseTarString(header, 157, 100)
                nextLongName = null
                nextLongLink = null

                var relPath = nameStr.removePrefix("./").removePrefix("/")
                if (cleanStrip.isNotEmpty()) {
                    if (!relPath.startsWith(cleanStrip)) {
                        skipEntryPayload(tarInput, entrySize)
                        continue
                    }
                    relPath = relPath.removePrefix(cleanStrip).removePrefix("/")
                }
                if (relPath.isEmpty() || relPath == "." || relPath.contains("..")) {
                    skipEntryPayload(tarInput, entrySize)
                    continue
                }

                val destFile = File(destRoot, relPath)

                when (typeFlag) {
                    '5' -> {
                        skipEntryPayload(tarInput, entrySize)
                        destFile.mkdirs()
                        setModeSafe(destFile, 493)
                    }
                    '2' -> {
                        skipEntryPayload(tarInput, entrySize)
                        var cleanTarget = linkStr
                        if (cleanStrip.isNotEmpty()) {
                            val strippedLink = cleanTarget.removePrefix("./").removePrefix("/")
                            if (strippedLink.startsWith(cleanStrip)) {
                                val relTarget = strippedLink.removePrefix(cleanStrip).removePrefix("/")
                                cleanTarget = File(destRoot, relTarget).absolutePath
                            }
                        }
                        createSymlinkSafe(destFile, cleanTarget)
                        deferredSymLinks.add(DeferredLink(destFile, cleanTarget, isSymlink = true))
                        count++
                    }
                    '1' -> {
                        skipEntryPayload(tarInput, entrySize)
                        var cleanTarget = linkStr.removePrefix("./").removePrefix("/")
                        if (cleanStrip.isNotEmpty() && cleanTarget.startsWith(cleanStrip)) {
                            cleanTarget = cleanTarget.removePrefix(cleanStrip).removePrefix("/")
                        }
                        deferredHardLinks.add(DeferredLink(destFile, cleanTarget, isSymlink = false))
                        count++
                    }
                    '0', '\u0000' -> {
                        ensureParentDir(destFile)
                        runCatching { Os.remove(destFile.absolutePath) }
                        FileOutputStream(destFile).use { fos ->
                            var remaining = entrySize
                            while (remaining > 0L) {
                                val toRead = minOf(remaining, ioBuf.size.toLong()).toInt()
                                val n = tarInput.read(ioBuf, 0, toRead)
                                if (n <= 0) break
                                fos.write(ioBuf, 0, n)
                                remaining -= n.toLong()
                            }
                        }
                        val pad = ((512L - (entrySize % 512L)) % 512L)
                        if (pad > 0L) {
                            skipFully(tarInput, pad)
                        }

                        val isExec = (entryMode and 73) != 0 || isExecutablePath(relPath)
                        setModeSafe(destFile, if (isExec) 493 else 420)
                        count++
                        if (count % 150 == 0) {
                            onProgress?.invoke(count, relPath)
                        }
                    }
                    else -> {
                        skipEntryPayload(tarInput, entrySize)
                    }
                }
            }

            for (sym in deferredSymLinks) {
                createSymlinkSafe(sym.destFile, sym.target)
            }

            for (hl in deferredHardLinks) {
                val targetFile = File(destRoot, hl.target)
                if (targetFile.exists()) {
                    ensureParentDir(hl.destFile)
                    runCatching { Os.remove(hl.destFile.absolutePath) }
                    val linked = runCatching {
                        Os.link(targetFile.absolutePath, hl.destFile.absolutePath)
                        true
                    }.getOrDefault(false)
                    if (!linked) {
                        runCatching {
                            targetFile.copyTo(hl.destFile, overwrite = true)
                            setModeSafe(hl.destFile, 493)
                        }
                    }
                }
            }

            onProgress?.invoke(count, "complete")
            return count
        }

        private fun isExecutablePath(relPath: String): Boolean {
            return relPath.startsWith("bin/") ||
                relPath.startsWith("sbin/") ||
                relPath.startsWith("usr/bin/") ||
                relPath.startsWith("usr/sbin/") ||
                relPath.startsWith("usr/games/") ||
                relPath.startsWith("usr/libexec/") ||
                relPath.startsWith("lib/") ||
                relPath.startsWith("lib64/") ||
                relPath.startsWith("usr/lib/") ||
                relPath.startsWith("usr/lib64/") ||
                relPath.startsWith("etc/init.d/") ||
                relPath.startsWith("etc/update-motd.d/") ||
                (relPath.startsWith("var/lib/dpkg/info/") &&
                    (relPath.endsWith(".postinst") ||
                        relPath.endsWith(".preinst") ||
                        relPath.endsWith(".prerm") ||
                        relPath.endsWith(".postrm") ||
                        relPath.endsWith(".config")))
        }

        private fun ensureParentDir(file: File) {
            val parent = file.parentFile ?: return
            if (!parent.exists()) {
                parent.mkdirs()
                setModeSafe(parent, 493)
            }
        }

        fun createSymlinkSafe(destFile: File, linkTarget: String) {
            runCatching {
                ensureParentDir(destFile)
                val lstat = runCatching { Os.lstat(destFile.absolutePath) }.getOrNull()
                if (lstat != null) {
                    val isDir = (lstat.st_mode and 0xF000) == 0x4000
                    if (isDir) {
                        val resolvedDir = File(destFile.parentFile, linkTarget)
                        resolvedDir.mkdirs()
                        destFile.listFiles()?.forEach { child ->
                            val targetChild = File(resolvedDir, child.name)
                            child.renameTo(targetChild)
                        }
                        destFile.deleteRecursively()
                    } else {
                        Os.remove(destFile.absolutePath)
                    }
                }
                Os.symlink(linkTarget, destFile.absolutePath)
            }
        }

        fun setModeSafe(file: File, mode: Int) {
            runCatching {
                Os.chmod(file.absolutePath, mode)
            }.onFailure {
                runCatching {
                    file.setReadable(true, false)
                    file.setWritable(true, true)
                    if ((mode and 73) != 0) {
                        file.setExecutable(true, false)
                    }
                }
            }
        }

        private fun readEntryPayload(input: InputStream, size: Long): ByteArray {
            val len = size.coerceAtLeast(0L).toInt()
            val data = ByteArray(len)
            readFully(input, data, len)
            val pad = ((512L - (size % 512L)) % 512L)
            if (pad > 0L) {
                skipFully(input, pad)
            }
            return data
        }

        private fun skipEntryPayload(input: InputStream, size: Long) {
            val aligned = if (size <= 0L) 0L else size + ((512L - (size % 512L)) % 512L)
            if (aligned > 0L) {
                skipFully(input, aligned)
            }
        }

        private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
            var total = 0
            while (total < len) {
                val n = input.read(buf, total, len - total)
                if (n <= 0) break
                total += n
            }
            return total
        }

        private fun skipFully(input: InputStream, bytesToSkip: Long) {
            var remaining = bytesToSkip
            val scratch = ByteArray(4096)
            while (remaining > 0L) {
                val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (n <= 0) break
                remaining -= n.toLong()
            }
        }

        private fun isAllZero(buf: ByteArray): Boolean {
            for (b in buf) {
                if (b.toInt() != 0) return false
            }
            return true
        }

        private fun parseTarString(header: ByteArray, offset: Int, length: Int): String {
            var end = offset
            val limit = offset + length
            while (end < limit && header[end].toInt() != 0) {
                end++
            }
            return String(header, offset, end - offset, StandardCharsets.UTF_8)
        }

        private fun parseTarOctal(header: ByteArray, offset: Int, length: Int): Long {
            var result = 0L
            val limit = offset + length
            for (i in offset until limit) {
                val b = header[i].toInt() and 0xFF
                if (b == 0 || b == ' '.code) {
                    if (result > 0L) break else continue
                }
                if (b in '0'.code..'7'.code) {
                    result = (result shl 3) or (b - '0'.code).toLong()
                }
            }
            return result
        }

        private fun parseTarSize(header: ByteArray, offset: Int, length: Int): Long {
            if ((header[offset].toInt() and 0x80) != 0) {
                var v = 0L
                for (i in offset + 4 until offset + length) {
                    v = (v shl 8) or (header[i].toLong() and 0xFFL)
                }
                return v
            }
            return parseTarOctal(header, offset, length)
        }

        private fun parsePaxHeaders(paxText: String): Map<String, String> {
            val map = HashMap<String, String>()
            var idx = 0
            val n = paxText.length
            while (idx < n) {
                val spaceIdx = paxText.indexOf(' ', idx)
                if (spaceIdx < 0) break
                val recordLen = paxText.substring(idx, spaceIdx).toIntOrNull()
                if (recordLen == null || recordLen <= 0 || idx + recordLen > n) {
                    val nl = paxText.indexOf('\n', idx)
                    if (nl < 0) break
                    idx = nl + 1
                    continue
                }
                val record = paxText.substring(spaceIdx + 1, idx + recordLen).trimEnd('\n')
                val eqIdx = record.indexOf('=')
                if (eqIdx > 0) {
                    map[record.substring(0, eqIdx)] = record.substring(eqIdx + 1)
                }
                idx += recordLen
            }
            return map
        }

        private fun trimNulls(s: String): String {
            val nul = s.indexOf('\u0000')
            return if (nul >= 0) s.substring(0, nul) else s
        }
    }
}
