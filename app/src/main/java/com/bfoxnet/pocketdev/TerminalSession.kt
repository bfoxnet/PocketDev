package com.bfoxnet.pocketdev

import android.content.Context
import android.graphics.Color
import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Termux `TerminalSession` + `TerminalEmulator` implementation running a real
 * Ubuntu 24.04.5 LTS (`noble`) rootfs via native Android `PRoot` (`UbuntuProotManager`).
 *
 * Features:
 * - Default Oh My Zsh `robbyrussell` prompt theme (`➜  <dir> git:(<branch>) `) for Zsh sessions.
 * - Instant (<1 ms) fast-path execution for core shell/filesystem commands (`pwd`, `ls`, `ll`, `la`,
 *   `cd`, `echo`, `export`, `unset`, `whoami`, `hostname`, `id`, `true`, `false`) and a persistent
 *   pre-warmed background PRoot Zsh/Bash daemon so external Ubuntu commands execute without
 *   cold-start `ptrace` or `.zshrc` `compinit` overhead.
 * - Automatic first-run bootstrap: if the Ubuntu rootfs or `proot` binary is not yet installed,
 *   the session automatically downloads and extracts `busybox-static`, `proot` + `libtalloc`,
 *   and `ubuntu-base-24.04.5-base-<arm64|amd64>.tar.gz` with live progress in the terminal.
 * - Full 10,000-line scrollback history (`MAX_SCROLLBACK_LINES = 10000`) retained across commands
 *   and across Activity navigation.
 * - Persistent working directory (`cd`) and exported environment variables (`export`) across commands.
 */
class TerminalSession(
    private val context: Context,
    var title: String = "zsh",
    var sessionName: String? = null,
    val isFailsafe: Boolean = false,
    val mode: String = if (isFailsafe) "sh" else "ubuntu",
    val id: String = UUID.randomUUID().toString(),
    initialWorkingDir: File? = null
) {

    data class StyledSpan(
        val text: String,
        val fgColor: Int,
        val bold: Boolean,
        val bgColor: Int = Color.TRANSPARENT,
        val underline: Boolean = false
    )

    data class WrappedLine(
        val spans: List<StyledSpan>
    )

    data class RenderSnapshot(
        val lines: List<WrappedLine>,
        val cursorRow: Int,
        val cursorCol: Int
    )

    var onUpdate: (() -> Unit)? = null
    var onTitleChanged: ((TerminalSession) -> Unit)? = null
    var onSessionFinished: (() -> Unit)? = null
    var onBell: (() -> Unit)? = null
    var onCopyToClipboard: ((String) -> Unit)? = null
    var onTermuxIpcEvent: ((String, String) -> Unit)? = null
    var onExecutionStateChanged: ((Boolean) -> Unit)? = null

    private val bufferLock = Any()
    private val historyLines = ArrayList<List<StyledSpan>>(1024)
    private var currentOutputLine = ArrayList<StyledSpan>()
    private val lastCommandOutput = StringBuilder()

    // Live interactive input buffer on the active prompt line
    private val inputBuffer = StringBuilder()
    private var inputCursorPos: Int = 0
    private val commandHistory = ArrayList<String>()
    private var historyIndex: Int = 0

    @Volatile
    private var lastExitCode: Int = 0

    @Volatile
    private var runningProcess: Process? = null

    @Volatile
    private var runningProcessStdin: OutputStreamWriter? = null

    // Persistent warm PRoot shell process to eliminate cold-spawn overhead
    private val warmShellLock = Any()

    @Volatile
    private var warmProotProcess: Process? = null

    @Volatile
    private var warmProotStdin: OutputStreamWriter? = null

    @Volatile
    private var activeCommandLatch: CountDownLatch? = null

    @Volatile
    private var warmReadyLatch: CountDownLatch? = null

    @Volatile
    private var sessionClosed: Boolean = false

    @Volatile
    var isExecutingCommand: Boolean = false
        private set(value) {
            val changed = field != value
            field = value
            if (changed) {
                onExecutionStateChanged?.invoke(value)
            }
        }

    @Volatile
    var bracketedPasteEnabled: Boolean = false
        private set

    private val sessionPid: Int = (10000..32000).random()

    private val ubuntuRoot: File = UbuntuProotManager.ubuntuRootDir(context)
    private val usrDir: File = UbuntuProotManager.usrDir(context)
    private val usrBinDir: File = UbuntuProotManager.usrBinDir(context)
    private val usrTmpDir: File = UbuntuProotManager.usrTmpDir(context)
    private val homeDir: File = UbuntuProotManager.homeDir(context)
    private var currentDir: File =
        initialWorkingDir?.takeIf { it.exists() && it.isDirectory }
            ?: ProjectManager.ensureDefaultLocalWorkspace(context)

    private val sessionEnvFile: File = File(usrTmpDir, "session_${id.take(8)}.env")
    private val sessionCwdFile: File = File(usrTmpDir, "session_${id.take(8)}.cwd")
    private val sessionCmdFile: File = File(usrTmpDir, "session_${id.take(8)}_cmd.sh")
    private val sessionEnvVars = LinkedHashMap<String, String>()

    // Cached UI prompt & input highlighting to avoid disk I/O during cursor blink / scroll frames
    private var cachedPromptKey: String = ""
    private var cachedPromptSpans: List<StyledSpan> = emptyList()
    private var cachedInputKey: String = ""
    private var cachedHighlightedInput: List<StyledSpan> = emptyList()
    private var cachedGhostSuggestion: String = ""
    private var cachedGitDirPath: String = ""
    private var cachedGitHeadMtime: Long = -1L
    private var cachedGitBranch: String? = null

    // Current SGR state for ANSI escape parser
    private var curFgColor: Int = DEFAULT_FG
    private var curBgColor: Int = Color.TRANSPARENT
    private var curBold: Boolean = false
    private var curUnderline: Boolean = false
    private var curInverse: Boolean = false

    init {
        Thread {
            runCatching {
                UbuntuProotManager.provisionHostHelpers(context)
                ensureExecutableIndexLoaded(ubuntuRoot, usrBinDir)
            }
            if (!isFailsafe && !UbuntuProotManager.isBootstrapping) {
                if (!UbuntuProotManager.isUbuntuInstalled(context) || !UbuntuProotManager.isProotUsable(context)) {
                    startAutoBootstrapInSession()
                } else {
                    if (Settings(context).defaultShell == Settings.SHELL_ZSH &&
                        !UbuntuProotManager.isZshBinaryInstalled(context)
                    ) {
                        startAutoZshSetupInSession()
                    } else {
                        prewarmProotShellAsync()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startAutoZshSetupInSession() {
        isExecutingCommand = true
        Thread {
            try {
                appendAnsiOutput("\u001b[1;33m==> Provisioning real Ubuntu Zsh (robbyrussell theme) + plugins (zsh-autosuggestions, zsh-syntax-highlighting, zsh-completions)...\u001b[0m\n")
                UbuntuProotManager.installZshInUbuntu(context) { msg ->
                    appendAnsiOutput("$msg\n")
                }
                ensureExecutableIndexLoaded(ubuntuRoot, usrBinDir, forceReload = true)
                prewarmProotShellAsync()
            } finally {
                isExecutingCommand = false
                synchronized(bufferLock) {
                    if (currentOutputLine.isNotEmpty()) {
                        historyLines.add(ArrayList(currentOutputLine))
                        currentOutputLine.clear()
                    }
                    invalidatePromptCacheLocked()
                }
                onUpdate?.invoke()
            }
        }.start()
    }

    /** Switches this session's current working directory to [dir] (e.g. when opening Terminal for a specific project/folder). */
    fun setWorkingDirectory(dir: File) {
        if (dir.exists() && dir.isDirectory) {
            synchronized(bufferLock) {
                currentDir = dir
                invalidatePromptCacheLocked()
            }
            onUpdate?.invoke()
        }
    }

    fun getWorkingDirectory(): File = currentDir

    private fun startAutoBootstrapInSession() {
        isExecutingCommand = true
        Thread {
            try {
                appendAnsiOutput("\u001b[1;36mPocketDev Linux Environment — First-Run Setup (Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} + PRoot)\u001b[0m\n")
                val ok = UbuntuProotManager.bootstrapUbuntuRootfs(context, forceReinstall = false) { msg ->
                    appendAnsiOutput("$msg\n")
                }
                if (ok && UbuntuProotManager.isUbuntuInstalled(context)) {
                    ensureExecutableIndexLoaded(ubuntuRoot, usrBinDir, forceReload = true)
                    prewarmProotShellAsync()
                    runProotCommandInternal("uname -srm && . /etc/os-release && printf 'Loaded %s (%s)\\n' \"\$PRETTY_NAME\" \"\$(dpkg --print-architecture)\"")
                }
            } finally {
                isExecutingCommand = false
                synchronized(bufferLock) {
                    if (currentOutputLine.isNotEmpty()) {
                        historyLines.add(ArrayList(currentOutputLine))
                        currentOutputLine.clear()
                    }
                    invalidatePromptCacheLocked()
                }
                onUpdate?.invoke()
            }
        }.start()
    }

    fun getPid(): Int = sessionPid

    fun isRunning(): Boolean = !sessionClosed

    private fun canonicalOrAbs(f: File): String =
        runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /** Updates current working directory if the active app-internal project was switched. */
    fun syncWorkspaceDirectory(ctx: Context) {
        val activeWorkspace = ProjectManager.ensureDefaultLocalWorkspace(ctx)
        val workspaceRootParent = ProjectManager.projectsRootDir(ctx)
        val curCanon = currentDir.absolutePath
        val parentCanon = workspaceRootParent.absolutePath
        val activeCanon = activeWorkspace.absolutePath
        if (!currentDir.exists() ||
            (curCanon.startsWith(parentCanon) && !curCanon.startsWith(activeCanon))
        ) {
            synchronized(bufferLock) {
                currentDir = activeWorkspace
                invalidatePromptCacheLocked()
            }
            onUpdate?.invoke()
        }
    }

    fun reset() {
        synchronized(bufferLock) {
            curFgColor = DEFAULT_FG
            curBgColor = Color.TRANSPARENT
            curBold = false
            curUnderline = false
            curInverse = false
            bracketedPasteEnabled = false
            lastExitCode = 0
            invalidatePromptCacheLocked()
        }
        onUpdate?.invoke()
    }

    fun finishIfRunning() {
        sessionClosed = true
        activeCommandLatch?.countDown()
        runCatching { runningProcess?.destroyForcibly() }
        runCatching { warmProotProcess?.destroyForcibly() }
        runningProcess = null
        runningProcessStdin = null
        warmProotProcess = null
        warmProotStdin = null
        isExecutingCommand = false
        runCatching { sessionEnvFile.delete() }
        runCatching { sessionCwdFile.delete() }
        runCatching { sessionCmdFile.delete() }
        onSessionFinished?.invoke()
    }

    fun lineCount(): Int = synchronized(bufferLock) { historyLines.size + 1 }

    private fun invalidatePromptCacheLocked() {
        cachedPromptKey = ""
        cachedInputKey = ""
    }

    fun sendChar(ch: Char) {
        if (isExecutingCommand) {
            synchronized(bufferLock) {
                inputBuffer.append(ch)
            }
            onUpdate?.invoke()
            return
        }
        synchronized(bufferLock) {
            inputBuffer.insert(inputCursorPos, ch)
            inputCursorPos++
        }
        onUpdate?.invoke()
    }

    fun sendInput(str: String) {
        if (isExecutingCommand) {
            if (str == "\n" || str == "\r") {
                sendEnter()
            } else {
                synchronized(bufferLock) {
                    inputBuffer.append(str)
                }
                onUpdate?.invoke()
            }
            return
        }
        when (str) {
            "\u001b[A" -> { navigateHistory(-1); return }
            "\u001b[B" -> { navigateHistory(1); return }
            "\u001b[D" -> { moveCursorHorizontal(-1); return }
            "\u001b[C" -> { moveCursorRightOrAcceptSuggestion(); return }
            "\u001b[H" -> { moveCursorToHome(); return }
            "\u001b[F" -> { moveCursorToEndOrAcceptSuggestion(); return }
            "\u001b[5~" -> { navigateHistory(-5); return }
            "\u001b[6~" -> { navigateHistory(5); return }
        }
        synchronized(bufferLock) {
            inputBuffer.insert(inputCursorPos, str)
            inputCursorPos += str.length
        }
        onUpdate?.invoke()
    }

    fun pasteText(rawText: String) {
        if (rawText.isEmpty()) return
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        if (isExecutingCommand) {
            runCatching {
                runningProcessStdin?.write(normalized)
                runningProcessStdin?.flush()
            }
            appendAnsiOutput(normalized)
            return
        }
        if (!normalized.contains('\n')) {
            synchronized(bufferLock) {
                inputBuffer.insert(inputCursorPos, normalized)
                inputCursorPos += normalized.length
            }
            onUpdate?.invoke()
            return
        }
        val parts = normalized.split('\n')
        for (i in parts.indices) {
            val part = parts[i]
            synchronized(bufferLock) {
                inputBuffer.insert(inputCursorPos, part)
                inputCursorPos += part.length
            }
            if (i < parts.lastIndex) {
                sendEnter()
            }
        }
        onUpdate?.invoke()
    }

    fun getCurrentInputText(): String = synchronized(bufferLock) { inputBuffer.toString() }

    fun getLastCommandOutputText(): String = synchronized(bufferLock) { lastCommandOutput.toString().trim() }

    fun getTranscriptText(): String = synchronized(bufferLock) {
        buildString {
            for (line in historyLines) {
                appendLine(line.joinToString("") { it.text })
            }
            if (currentOutputLine.isNotEmpty()) {
                appendLine(currentOutputLine.joinToString("") { it.text })
            }
            if (inputBuffer.isNotEmpty()) {
                val prompt = buildPromptSpans().joinToString("") { it.text }
                append(prompt).append(inputBuffer)
            }
        }.trim()
    }

    fun getCopyableText(): String = synchronized(bufferLock) {
        when {
            inputBuffer.isNotEmpty() -> inputBuffer.toString()
            lastCommandOutput.isNotBlank() -> lastCommandOutput.toString().trim()
            else -> getTranscriptText()
        }
    }

    fun extractUrls(): List<String> {
        val transcript = getTranscriptText()
        if (transcript.isEmpty()) return emptyList()
        val urlRegex = Regex("""(?:https?|ftp|ssh|git)://[^\s'"<>)\]]+""")
        val found = LinkedHashSet<String>()
        for (m in urlRegex.findAll(transcript)) {
            found.add(m.value.trimEnd('.', ',', ';', ':'))
        }
        return found.toList()
    }

    fun sendBackspace() {
        synchronized(bufferLock) {
            if (isExecutingCommand) {
                if (inputBuffer.isNotEmpty()) {
                    inputBuffer.deleteCharAt(inputBuffer.lastIndex)
                }
            } else if (inputCursorPos > 0) {
                inputBuffer.deleteCharAt(inputCursorPos - 1)
                inputCursorPos--
            }
        }
        onUpdate?.invoke()
    }

    fun moveCursorHorizontal(delta: Int) {
        if (isExecutingCommand) return
        synchronized(bufferLock) {
            inputCursorPos = (inputCursorPos + delta).coerceIn(0, inputBuffer.length)
        }
        onUpdate?.invoke()
    }

    fun moveCursorRightOrAcceptSuggestion() {
        if (isExecutingCommand) return
        synchronized(bufferLock) {
            if (inputCursorPos < inputBuffer.length) {
                inputCursorPos++
            } else {
                val ghost = getOrComputeAutosuggestionLocked(inputBuffer.toString())
                if (ghost.isNotEmpty()) {
                    inputBuffer.append(ghost)
                    inputCursorPos = inputBuffer.length
                }
            }
        }
        onUpdate?.invoke()
    }

    fun moveCursorToHome() {
        if (isExecutingCommand) return
        synchronized(bufferLock) { inputCursorPos = 0 }
        onUpdate?.invoke()
    }

    fun moveCursorToEnd() {
        if (isExecutingCommand) return
        synchronized(bufferLock) { inputCursorPos = inputBuffer.length }
        onUpdate?.invoke()
    }

    fun moveCursorToEndOrAcceptSuggestion() {
        if (isExecutingCommand) return
        synchronized(bufferLock) {
            if (inputCursorPos < inputBuffer.length) {
                inputCursorPos = inputBuffer.length
            } else {
                val ghost = getOrComputeAutosuggestionLocked(inputBuffer.toString())
                if (ghost.isNotEmpty()) {
                    inputBuffer.append(ghost)
                    inputCursorPos = inputBuffer.length
                }
            }
        }
        onUpdate?.invoke()
    }

    fun navigateHistory(delta: Int) {
        if (isExecutingCommand) return
        synchronized(bufferLock) {
            if (commandHistory.isEmpty()) return
            historyIndex = (historyIndex + delta).coerceIn(0, commandHistory.size)
            inputBuffer.clear()
            if (historyIndex < commandHistory.size) {
                inputBuffer.append(commandHistory[historyIndex])
            }
            inputCursorPos = inputBuffer.length
        }
        onUpdate?.invoke()
    }

    fun sendTab() {
        if (isExecutingCommand) return
        synchronized(bufferLock) {
            val typed = inputBuffer.toString()
            if (inputCursorPos == typed.length && typed.isNotEmpty()) {
                val before = typed.substring(0, inputCursorPos)
                val token = before.substringAfterLast(' ')
                if (token.isNotEmpty()) {
                    val candidates = collectCompletionCandidatesLocked(before, token)
                    val match = candidates.firstOrNull()
                    if (match != null && match.length > token.length) {
                        val suffix = match.substring(token.length)
                        inputBuffer.insert(inputCursorPos, suffix)
                        inputCursorPos += suffix.length
                        onUpdate?.invoke()
                        return
                    }
                }
                val ghost = getOrComputeAutosuggestionLocked(typed)
                if (ghost.isNotEmpty()) {
                    inputBuffer.append(ghost)
                    inputCursorPos = inputBuffer.length
                    onUpdate?.invoke()
                    return
                }
            }

            val before = inputBuffer.substring(0, inputCursorPos)
            val token = before.substringAfterLast(' ')
            if (token.isNotEmpty()) {
                val candidates = collectCompletionCandidatesLocked(before, token)
                val match = candidates.firstOrNull()
                if (match != null && match.length > token.length) {
                    val suffix = match.substring(token.length)
                    inputBuffer.insert(inputCursorPos, suffix)
                    inputCursorPos += suffix.length
                }
            } else {
                inputBuffer.insert(inputCursorPos, "  ")
                inputCursorPos += 2
            }
        }
        onUpdate?.invoke()
    }

    private fun collectCompletionCandidatesLocked(beforeCursor: String, token: String): List<String> {
        val candidates = LinkedHashSet<String>()
        val parts = beforeCursor.trimStart().split(Regex("\\s+"))
        if (parts.size <= 1 && !beforeCursor.endsWith(' ')) {
            KNOWN_COMMANDS.filter { it.startsWith(token) }.forEach { candidates.add(it) }
            cachedExecutableNames.filter { it.startsWith(token) }.sorted().forEach { candidates.add(it) }
        } else {
            val firstCmd = parts.firstOrNull() ?: ""
            when (firstCmd) {
                "apt", "apt-get" -> {
                    if (parts.size == 2 && !beforeCursor.endsWith(' ')) {
                        listOf("update", "upgrade", "install", "remove", "purge", "autoremove", "search", "show", "clean")
                            .filter { it.startsWith(token) }.forEach { candidates.add(it) }
                    } else {
                        AlpineLinuxEnvironment.CATALOG.map { it.name }
                            .filter { it.startsWith(token) }.forEach { candidates.add(it) }
                    }
                }
                "go" -> listOf("run", "build", "version", "mod", "fmt", "test", "env", "vet", "help")
                    .filter { it.startsWith(token) }.forEach { candidates.add(it) }
                "git" -> listOf("status", "add", "commit", "push", "pull", "log", "diff", "branch", "checkout", "clone", "init")
                    .filter { it.startsWith(token) }.forEach { candidates.add(it) }
                "lsp-install", "lsp-uninstall" -> LspManager.AVAILABLE_SERVERS.map { it.id }
                    .plus(listOf("--list", "--help"))
                    .filter { it.startsWith(token) }.forEach { candidates.add(it) }
                "proot-distro" -> listOf("list", "login", "install", "reset", "ubuntu")
                    .filter { it.startsWith(token) }.forEach { candidates.add(it) }
                "ubuntu-bootstrap" -> listOf("--status", "--reinstall")
                    .filter { it.startsWith(token) }.forEach { candidates.add(it) }
            }
        }

        val slashIdx = token.lastIndexOf('/')
        val baseDir = if (slashIdx >= 0) {
            val dirPart = token.substring(0, slashIdx)
            resolveGuestPathToHostFile(dirPart.ifEmpty { "/" })
        } else {
            currentDir
        }
        val namePrefix = if (slashIdx >= 0) token.substring(slashIdx + 1) else token
        val pathPrefix = if (slashIdx >= 0) token.substring(0, slashIdx + 1) else ""
        baseDir.listFiles()
            ?.filter { it.name.startsWith(namePrefix) }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                val suffix = if (f.isDirectory) "/" else ""
                candidates.add("$pathPrefix${f.name}$suffix")
            }

        return candidates.toList()
    }

    fun sendControlChar(ch: Char) {
        when (ch) {
            'c' -> {
                killRunningCommandForCtrlC()
                synchronized(bufferLock) {
                    lastExitCode = 130
                    invalidatePromptCacheLocked()
                    commitPromptLineToHistory("^C")
                    inputBuffer.clear()
                    inputCursorPos = 0
                }
                onUpdate?.invoke()
            }
            'l' -> {
                synchronized(bufferLock) {
                    historyLines.clear()
                    currentOutputLine.clear()
                }
                onUpdate?.invoke()
            }
            'd' -> {
                if (isExecutingCommand) {
                    runCatching { runningProcessStdin?.close() }
                    runningProcessStdin = null
                } else if (inputBuffer.isEmpty()) {
                    finishIfRunning()
                }
            }
            'a' -> moveCursorToHome()
            'e' -> moveCursorToEnd()
            'u' -> {
                if (!isExecutingCommand) {
                    synchronized(bufferLock) {
                        if (inputCursorPos > 0) {
                            inputBuffer.delete(0, inputCursorPos)
                            inputCursorPos = 0
                        }
                    }
                    onUpdate?.invoke()
                }
            }
            'k' -> {
                if (!isExecutingCommand) {
                    synchronized(bufferLock) {
                        if (inputCursorPos < inputBuffer.length) {
                            inputBuffer.delete(inputCursorPos, inputBuffer.length)
                        }
                    }
                    onUpdate?.invoke()
                }
            }
            'w' -> {
                if (!isExecutingCommand) {
                    synchronized(bufferLock) {
                        var pos = inputCursorPos
                        while (pos > 0 && inputBuffer[pos - 1].isWhitespace()) pos--
                        while (pos > 0 && !inputBuffer[pos - 1].isWhitespace()) pos--
                        if (pos < inputCursorPos) {
                            inputBuffer.delete(pos, inputCursorPos)
                            inputCursorPos = pos
                        }
                    }
                    onUpdate?.invoke()
                }
            }
        }
    }

    private fun killRunningCommandForCtrlC() {
        activeCommandLatch?.countDown()
        val curProc = runningProcess
        val warmProc = warmProotProcess
        runCatching { curProc?.destroyForcibly() }
        runningProcess = null
        runningProcessStdin = null
        if (curProc != null && curProc === warmProc) {
            synchronized(warmShellLock) {
                warmProotProcess = null
                warmProotStdin = null
            }
            prewarmProotShellAsync()
        }
        isExecutingCommand = false
    }

    fun sendEnter() {
        if (isExecutingCommand) {
            val lineToSend: String
            synchronized(bufferLock) {
                lineToSend = inputBuffer.toString()
                inputBuffer.clear()
                inputCursorPos = 0
            }
            appendAnsiOutput("$lineToSend\n")
            runCatching {
                runningProcessStdin?.write("$lineToSend\n")
                runningProcessStdin?.flush()
            }
            return
        }
        val cmd: String
        synchronized(bufferLock) {
            cmd = inputBuffer.toString()
            commitPromptLineToHistory("")
            inputBuffer.clear()
            inputCursorPos = 0
            if (cmd.isNotBlank()) {
                commandHistory.add(cmd)
                historyIndex = commandHistory.size
            }
        }
        onUpdate?.invoke()
        executeCommandLineAsync(cmd)
    }

    fun executeCommandDirectly(cmd: String) {
        synchronized(bufferLock) {
            inputBuffer.clear()
            inputBuffer.append(cmd)
            inputCursorPos = cmd.length
            commitPromptLineToHistory("")
            inputBuffer.clear()
            inputCursorPos = 0
            commandHistory.add(cmd)
            historyIndex = commandHistory.size
        }
        onUpdate?.invoke()
        executeCommandLineAsync(cmd)
    }

    private fun commitPromptLineToHistory(suffix: String) {
        val spans = buildPromptSpans().toMutableList()
        val typed = inputBuffer.toString()
        if (typed.isNotEmpty()) {
            spans.addAll(getOrHighlightInputLocked(typed))
        }
        if (suffix.isNotEmpty()) {
            spans.add(StyledSpan(suffix, Color.parseColor("#FF7B72"), true))
        }
        historyLines.add(spans)
        trimHistoryIfNeeded()
    }

    /**
     * Builds the active shell prompt spans:
     * - When Zsh is the default shell (`Settings.SHELL_ZSH`), uses the official Oh My Zsh
     *   `robbyrussell` theme (`➜  <dir> git:(<branch>) `).
     * - When Bash is selected (`Settings.SHELL_BASH`), uses `root@ubuntu:<dir># `.
     */
    private fun buildPromptSpans(): List<StyledSpan> {
        if (isFailsafe) {
            return listOf(StyledSpan("$ ", DEFAULT_FG, true))
        }
        val defaultShell = Settings(context).defaultShell
        val key = "${currentDir.absolutePath}|$lastExitCode|$defaultShell"
        if (key == cachedPromptKey && cachedPromptSpans.isNotEmpty()) {
            return cachedPromptSpans
        }

        val rootPath = ubuntuRoot.absolutePath
        val rootHomePath = File(ubuntuRoot, "root").absolutePath
        val homePath = homeDir.absolutePath
        val curPath = currentDir.absolutePath

        val isZsh = defaultShell == Settings.SHELL_ZSH
        val spans = if (isZsh) {
            // Oh My Zsh `robbyrussell` theme:
            // PROMPT="%(?:%{$fg_bold[green]%}➜ :%{$fg_bold[red]%}➜ ) %{$fg[cyan]%}%c%{$reset_color%} $(git_prompt_info)"
            val arrowColor = if (lastExitCode == 0) Color.parseColor("#3FB950") else Color.parseColor("#FF7B72")
            val shortDir = when {
                curPath == rootHomePath || curPath == homePath -> "~"
                curPath == rootPath -> "/"
                else -> currentDir.name.ifEmpty { "/" }
            }
            val list = ArrayList<StyledSpan>(6)
            list.add(StyledSpan("➜  ", arrowColor, true))
            list.add(StyledSpan(shortDir, Color.parseColor("#39C5CF"), true))
            val gitBranch = resolveGitBranchFast(currentDir)
            if (!gitBranch.isNullOrEmpty()) {
                list.add(StyledSpan(" git:(", Color.parseColor("#58A6FF"), true))
                list.add(StyledSpan(gitBranch, Color.parseColor("#FF7B72"), true))
                list.add(StyledSpan(") ", Color.parseColor("#58A6FF"), true))
            } else {
                list.add(StyledSpan(" ", DEFAULT_FG, false))
            }
            list
        } else {
            val workspace = ProjectManager.ensureDefaultLocalWorkspace(context)
            val wsPath = workspace.absolutePath
            val dirName = when {
                curPath == homePath || curPath == rootHomePath -> "~"
                curPath == rootPath -> "/"
                curPath.startsWith("$rootHomePath/") -> "~/" + curPath.removePrefix("$rootHomePath/")
                curPath == wsPath -> "~/projects/${workspace.name}"
                curPath.startsWith("$wsPath/") -> "~/projects/${workspace.name}/" + curPath.removePrefix("$wsPath/")
                curPath.startsWith("$rootPath/") -> "/" + curPath.removePrefix("$rootPath/")
                curPath.startsWith("$homePath/") -> "~/" + curPath.removePrefix("$homePath/")
                else -> currentDir.name.ifEmpty { "/" }
            }
            listOf(
                StyledSpan("root@ubuntu", Color.parseColor("#FFB74D"), true),
                StyledSpan(":", DEFAULT_FG, false),
                StyledSpan(dirName, Color.parseColor("#58A6FF"), true),
                StyledSpan("# ", Color.parseColor("#3FB950"), true)
            )
        }

        cachedPromptKey = key
        cachedPromptSpans = spans
        return spans
    }

    /**
     * Resolves the active Git branch by reading `.git/HEAD` directly in memory (< 0.05 ms)
     * without spawning any external `git` subprocess.
     */
    private fun resolveGitBranchFast(dir: File): String? {
        val dirPath = dir.absolutePath
        var cur: File? = dir
        val stopPath = ubuntuRoot.absolutePath
        while (cur != null) {
            val headFile = File(cur, ".git/HEAD")
            if (headFile.exists() && headFile.isFile) {
                val mtime = headFile.lastModified()
                if (dirPath == cachedGitDirPath && mtime == cachedGitHeadMtime) {
                    return cachedGitBranch
                }
                val raw = runCatching { headFile.readText().trim() }.getOrNull() ?: return null
                val branch = if (raw.startsWith("ref: refs/heads/")) {
                    raw.removePrefix("ref: refs/heads/").trim()
                } else if (raw.startsWith("ref: ")) {
                    raw.substringAfterLast('/').trim()
                } else {
                    raw.take(7)
                }
                cachedGitDirPath = dirPath
                cachedGitHeadMtime = mtime
                cachedGitBranch = branch
                return branch
            }
            if (cur.absolutePath == stopPath || cur.absolutePath == context.filesDir.absolutePath) break
            cur = cur.parentFile
        }
        cachedGitDirPath = dirPath
        cachedGitHeadMtime = -1L
        cachedGitBranch = null
        return null
    }

    // ---------------------------------------------------------------- Syntax Highlighting & Autosuggestions

    private fun getOrHighlightInputLocked(input: String): List<StyledSpan> {
        if (input.isEmpty()) return emptyList()
        val key = "${currentDir.absolutePath}|$input"
        if (key == cachedInputKey) return cachedHighlightedInput
        updateInputCachesLocked(input, key)
        return cachedHighlightedInput
    }

    private fun getOrComputeAutosuggestionLocked(input: String): String {
        if (input.isEmpty()) return ""
        val key = "${currentDir.absolutePath}|$input"
        if (key == cachedInputKey) return cachedGhostSuggestion
        updateInputCachesLocked(input, key)
        return cachedGhostSuggestion
    }

    private fun updateInputCachesLocked(input: String, key: String) {
        cachedInputKey = key
        cachedHighlightedInput = highlightInputLocked(input)
        cachedGhostSuggestion = computeAutosuggestionLocked(input)
    }

    private fun highlightInputLocked(input: String): List<StyledSpan> {
        if (input.isEmpty()) return emptyList()
        val spans = ArrayList<StyledSpan>()
        var i = 0
        val n = input.length
        var expectCommand = true

        while (i < n) {
            if (input[i].isWhitespace()) {
                val start = i
                while (i < n && input[i].isWhitespace()) i++
                spans.add(StyledSpan(input.substring(start, i), DEFAULT_FG, false))
                continue
            }

            if (input[i] in "|;&><") {
                val start = i
                while (i < n && input[i] in "|;&><") i++
                val op = input.substring(start, i)
                spans.add(StyledSpan(op, Color.parseColor("#FFA657"), true))
                if (op.contains('|') || op.contains(';') || op.contains('&')) {
                    expectCommand = true
                }
                continue
            }

            if (input[i] == '"' || input[i] == '\'') {
                val quote = input[i]
                val start = i
                i++
                while (i < n && input[i] != quote) {
                    if (input[i] == '\\' && i + 1 < n) i += 2 else i++
                }
                if (i < n && input[i] == quote) i++
                spans.add(StyledSpan(input.substring(start, i), Color.parseColor("#E3B341"), false))
                expectCommand = false
                continue
            }

            val start = i
            while (i < n && !input[i].isWhitespace() && input[i] !in "|;&><") {
                i++
            }
            val token = input.substring(start, i)

            if (expectCommand) {
                if (token.contains('=') && !token.startsWith('=')) {
                    spans.add(StyledSpan(token, Color.parseColor("#D2A8FF"), false))
                } else {
                    val isValid = isKnownExecutableCommand(token)
                    val color = if (isValid) Color.parseColor("#3FB950") else Color.parseColor("#FF7B72")
                    spans.add(StyledSpan(token, color, true))
                    if (token !in setOf("sudo", "time", "env", "nohup", "command", "builtin", "exec", "proot")) {
                        expectCommand = false
                    }
                }
            } else {
                when {
                    token.startsWith("$") -> {
                        spans.add(StyledSpan(token, Color.parseColor("#D2A8FF"), false))
                    }
                    token.startsWith("-") -> {
                        spans.add(StyledSpan(token, Color.parseColor("#79C0FF"), false))
                    }
                    isExistingPath(token) -> {
                        spans.add(StyledSpan(token, Color.parseColor("#58A6FF"), false, underline = true))
                    }
                    else -> {
                        spans.add(StyledSpan(token, DEFAULT_FG, false))
                    }
                }
            }
        }
        return spans
    }

    private fun isKnownExecutableCommand(cmd: String): Boolean {
        if (cmd in KNOWN_COMMANDS || cmd in SHELL_ALIASES.keys || cmd in cachedExecutableNames) return true
        if (cmd.startsWith("./") || cmd.startsWith("/")) {
            val target = resolveGuestPathToHostFile(cmd)
            return target.exists()
        }
        return false
    }

    private fun isExistingPath(token: String): Boolean {
        val clean = token.removePrefix("./")
        if (clean.isEmpty()) return false
        val target = resolveGuestPathToHostFile(token)
        return target.exists()
    }

    private fun resolveGuestPathToHostFile(pathToken: String): File {
        val workspace = ProjectManager.ensureDefaultLocalWorkspace(context)
        val projectsRoot = ProjectManager.projectsRootDir(context)
        return when {
            pathToken == "/" -> ubuntuRoot
            pathToken == "~" || pathToken == "/root" -> File(ubuntuRoot, "root")
            pathToken == "~/projects" || pathToken == "/root/projects" -> projectsRoot
            pathToken.startsWith("~/projects/") -> File(projectsRoot, pathToken.removePrefix("~/projects/"))
            pathToken.startsWith("/root/projects/") -> File(projectsRoot, pathToken.removePrefix("/root/projects/"))
            pathToken == "~/project" || pathToken == "/root/project" || pathToken == "/mnt/project" -> workspace
            pathToken.startsWith("~/project/") -> File(workspace, pathToken.removePrefix("~/project/"))
            pathToken.startsWith("/root/project/") -> File(workspace, pathToken.removePrefix("/root/project/"))
            pathToken.startsWith("/mnt/project/") -> File(workspace, pathToken.removePrefix("/mnt/project/"))
            pathToken.startsWith("~/") -> File(File(ubuntuRoot, "root"), pathToken.removePrefix("~/"))
            pathToken.startsWith("/") -> {
                val f = File(ubuntuRoot, pathToken.removePrefix("/"))
                if (f.exists()) f else File(pathToken)
            }
            pathToken.startsWith("./") -> File(currentDir, pathToken.removePrefix("./"))
            else -> File(currentDir, pathToken)
        }
    }

    private fun computeAutosuggestionLocked(typed: String): String {
        if (typed.isEmpty()) return ""

        for (i in commandHistory.indices.reversed()) {
            val hist = commandHistory[i]
            if (hist.startsWith(typed) && hist.length > typed.length) {
                return hist.substring(typed.length)
            }
        }

        for (preset in PRESET_SUGGESTIONS) {
            if (preset.startsWith(typed) && preset.length > typed.length) {
                return preset.substring(typed.length)
            }
        }

        val lastSpace = typed.lastIndexOf(' ')
        if (lastSpace >= 0 && lastSpace + 1 < typed.length) {
            val argPrefix = typed.substring(lastSpace + 1)
            if (!argPrefix.startsWith("-")) {
                val slashIdx = argPrefix.lastIndexOf('/')
                val baseDir = if (slashIdx >= 0) {
                    resolveGuestPathToHostFile(argPrefix.substring(0, slashIdx).ifEmpty { "/" })
                } else {
                    currentDir
                }
                val namePrefix = if (slashIdx >= 0) argPrefix.substring(slashIdx + 1) else argPrefix
                val match = baseDir.listFiles()
                    ?.filter { it.name.startsWith(namePrefix) }
                    ?.minByOrNull { it.name }
                if (match != null && match.name.length > namePrefix.length) {
                    val suffix = if (match.isDirectory) "/" else ""
                    return match.name.substring(namePrefix.length) + suffix
                }
            }
        }

        return ""
    }

    // ---------------------------------------------------------------- Instant Built-In & Fast-Path Execution

    private fun hasComplexShellSyntax(cmd: String): Boolean {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            if (c == '\\' && !inSingle && i + 1 < cmd.length) {
                i += 2
                continue
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble
            } else if (!inSingle && !inDouble) {
                if (c == '|' || c == '>' || c == '<' || c == ';' || c == '&' || c == '`' || c == '(' || c == ')') {
                    return true
                }
            } else if (inDouble) {
                if (c == '`' || (c == '$' && i + 1 < cmd.length && cmd[i + 1] == '(')) {
                    return true
                }
            }
            i++
        }
        return false
    }

    private fun tokenizeSimpleCommand(cmd: String): List<String> {
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            when {
                c == '\\' && !inSingle && i + 1 < cmd.length -> {
                    sb.append(cmd[i + 1])
                    i += 2
                    continue
                }
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    /**
     * Executes lightweight shell built-ins and core directory/inspection commands (`pwd`, `ls`,
     * `ll`, `la`, `l`, `cd`, `whoami`, `hostname`, `id`, `true`, `false`, `export`, `unset`, `echo`)
     * directly in-memory in < 1 ms so common interactive commands never suffer subprocess delay.
     */
    private fun tryExecuteInstantFastPath(trimmed: String): Boolean {
        if (hasComplexShellSyntax(trimmed)) return false
        val rawTokens = tokenizeSimpleCommand(trimmed)
        if (rawTokens.isEmpty()) return true

        // Expand standard aliases (ll, la, l, cls, projects)
        val first = rawTokens[0]
        val tokens = if (first in SHELL_ALIASES) {
            val expanded = SHELL_ALIASES.getValue(first).split(' ')
            expanded + rawTokens.drop(1)
        } else {
            rawTokens
        }

        val cmd = tokens[0]
        val args = tokens.drop(1)

        when (cmd) {
            "pwd" -> {
                val guestPwd = if (isFailsafe) {
                    currentDir.absolutePath
                } else {
                    UbuntuProotManager.hostDirToGuestPath(context, currentDir)
                }
                appendAnsiOutput("$guestPwd\n")
                synchronized(bufferLock) {
                    lastExitCode = 0
                    invalidatePromptCacheLocked()
                }
                onUpdate?.invoke()
                return true
            }
            "cd" -> {
                val targetArg = args.firstOrNull { !it.startsWith("-") } ?: ""
                val workspace = ProjectManager.ensureDefaultLocalWorkspace(context)
                val nextDir = when {
                    targetArg.isEmpty() || targetArg == "~" -> {
                        if (UbuntuProotManager.isUbuntuInstalled(context)) File(ubuntuRoot, "root") else homeDir
                    }
                    targetArg == "~/project" || targetArg == "/root/project" || targetArg == "/mnt/project" -> workspace
                    targetArg == "/" -> ubuntuRoot
                    targetArg == ".." -> {
                        val curCanon = canonicalOrAbs(currentDir)
                        val rootCanon = canonicalOrAbs(ubuntuRoot)
                        if (curCanon == rootCanon) ubuntuRoot else (currentDir.parentFile ?: currentDir)
                    }
                    else -> resolveGuestPathToHostFile(targetArg)
                }
                synchronized(bufferLock) {
                    if (nextDir.exists() && nextDir.isDirectory) {
                        val wsCanon = canonicalOrAbs(workspace)
                        currentDir = if (canonicalOrAbs(nextDir) == wsCanon) workspace else nextDir
                        lastExitCode = 0
                    } else {
                        appendAnsiOutput("\u001b[1;31mcd: no such file or directory: $targetArg\u001b[0m\n")
                        lastExitCode = 1
                    }
                    invalidatePromptCacheLocked()
                }
                onUpdate?.invoke()
                return true
            }
            "ls" -> {
                return executeFastLs(args)
            }
            "whoami" -> {
                appendAnsiOutput("root\n")
                synchronized(bufferLock) { lastExitCode = 0; invalidatePromptCacheLocked() }
                onUpdate?.invoke()
                return true
            }
            "hostname" -> {
                appendAnsiOutput("ubuntu\n")
                synchronized(bufferLock) { lastExitCode = 0; invalidatePromptCacheLocked() }
                onUpdate?.invoke()
                return true
            }
            "id" -> {
                if (args.isEmpty()) {
                    appendAnsiOutput("uid=0(root) gid=0(root) groups=0(root),3003(aid_inet),3004(aid_net_raw),9997(aid_everybody)\n")
                    synchronized(bufferLock) { lastExitCode = 0; invalidatePromptCacheLocked() }
                    onUpdate?.invoke()
                    return true
                }
                return false
            }
            "true", ":" -> {
                synchronized(bufferLock) { lastExitCode = 0; invalidatePromptCacheLocked() }
                onUpdate?.invoke()
                return true
            }
            "false" -> {
                synchronized(bufferLock) { lastExitCode = 1; invalidatePromptCacheLocked() }
                onUpdate?.invoke()
                return true
            }
            "export" -> {
                if (args.isEmpty()) {
                    return false
                }
                for (arg in args) {
                    val eq = arg.indexOf('=')
                    if (eq > 0) {
                        val k = arg.substring(0, eq).trim()
                        val v = arg.substring(eq + 1)
                        sessionEnvVars[k] = v
                    }
                }
                persistSessionEnvFile()
                synchronized(bufferLock) { lastExitCode = 0; invalidatePromptCacheLocked() }
                onUpdate?.invoke()
                return true
            }
            "unset" -> {
                for (arg in args) {
                    sessionEnvVars.remove(arg.trim())
                }
                persistSessionEnvFile()
                synchronized(bufferLock) { lastExitCode = 0; invalidatePromptCacheLocked() }
                onUpdate?.invoke()
                return true
            }
            "echo" -> {
                if (trimmed.contains('$')) return false
                var noNewline = false
                val startIdx = if (args.firstOrNull() == "-n") {
                    noNewline = true
                    1
                } else if (args.firstOrNull()?.startsWith("-") == true) {
                    return false
                } else {
                    0
                }
                val text = args.drop(startIdx).joinToString(" ")
                appendAnsiOutput(if (noNewline) text else "$text\n")
                synchronized(bufferLock) { lastExitCode = 0; invalidatePromptCacheLocked() }
                onUpdate?.invoke()
                return true
            }
            else -> return false
        }
    }

    private fun executeFastLs(args: List<String>): Boolean {
        var showAll = false
        var showAlmostAll = false
        var longFormat = false
        var humanReadable = false
        var classify = false
        var slashDirs = false
        var dirOnly = false
        var printPwdHeader = false
        val targetArgs = ArrayList<String>()

        for (arg in args) {
            if (arg.startsWith("--")) {
                when {
                    arg == "--all" -> showAll = true
                    arg == "--almost-all" -> showAlmostAll = true
                    arg == "--human-readable" -> humanReadable = true
                    arg == "--classify" -> classify = true
                    arg == "--directory" -> dirOnly = true
                    arg.startsWith("--color") -> {}
                    else -> return false
                }
            } else if (arg.startsWith("-") && arg.length > 1) {
                val flags = arg.substring(1)
                if (flags == "pwd") {
                    printPwdHeader = true
                    continue
                }
                for (ch in flags) {
                    when (ch) {
                        'a' -> showAll = true
                        'A' -> showAlmostAll = true
                        'l' -> longFormat = true
                        'h' -> humanReadable = true
                        'F' -> classify = true
                        'p' -> slashDirs = true
                        'C', '1' -> {}
                        'd' -> dirOnly = true
                        else -> return false
                    }
                }
            } else {
                targetArgs.add(arg)
            }
        }

        val paths = if (targetArgs.isEmpty()) listOf(".") else targetArgs
        val out = StringBuilder()
        var exitStatus = 0
        val dateFmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)

        if (printPwdHeader) {
            val guestPwd = if (isFailsafe) {
                currentDir.absolutePath
            } else {
                UbuntuProotManager.hostDirToGuestPath(context, currentDir)
            }
            out.append("\u001b[1;36m").append(guestPwd).append("\u001b[0m\n")
        }

        for ((idx, rawPath) in paths.withIndex()) {
            val target = if (rawPath == ".") currentDir else resolveGuestPathToHostFile(rawPath)
            if (!target.exists()) {
                out.append("\u001b[1;31mls: cannot access '$rawPath': No such file or directory\u001b[0m\n")
                exitStatus = 2
                continue
            }

            if (paths.size > 1 && !dirOnly) {
                if (idx > 0) out.append('\n')
                out.append("$rawPath:\n")
            }

            val entries: List<Pair<String, File>> = if (!target.isDirectory || dirOnly) {
                listOf(rawPath to target)
            } else {
                val list = ArrayList<Pair<String, File>>()
                if (showAll) {
                    list.add("." to target)
                    list.add(".." to (target.parentFile ?: target))
                }
                val children = target.listFiles()?.sortedBy { it.name.lowercase(Locale.US) } ?: emptyList()
                for (child in children) {
                    if (!showAll && !showAlmostAll && child.name.startsWith(".")) continue
                    list.add(child.name to child)
                }
                list
            }

            if (longFormat) {
                for ((displayName, f) in entries) {
                    val isDir = f.isDirectory
                    val isExec = !isDir && f.canExecute()
                    val perms = buildString {
                        append(if (isDir) 'd' else '-')
                        append(if (f.canRead()) 'r' else '-')
                        append(if (f.canWrite()) 'w' else '-')
                        append(if (isDir || isExec) 'x' else '-')
                        append(if (isDir) "r-xr-x" else if (isExec) "r-xr-x" else "r--r--")
                    }
                    val sizeStr = if (humanReadable) {
                        formatHumanSize(if (isDir) 4096L else f.length())
                    } else {
                        (if (isDir) 4096L else f.length()).toString().padStart(8)
                    }
                    val timeStr = dateFmt.format(Date(f.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()))
                    val styledName = formatLsEntryName(displayName, f, classify, slashDirs)
                    out.append("$perms 1 root root $sizeStr $timeStr $styledName\n")
                }
            } else {
                if (entries.isNotEmpty()) {
                    val formatted = entries.map { (displayName, f) ->
                        formatLsEntryName(displayName, f, classify, slashDirs)
                    }
                    out.append(formatted.joinToString("  ")).append('\n')
                }
            }
        }

        if (out.isNotEmpty()) {
            appendAnsiOutput(out.toString())
        }
        synchronized(bufferLock) {
            lastExitCode = exitStatus
            invalidatePromptCacheLocked()
        }
        onUpdate?.invoke()
        return true
    }

    private fun formatLsEntryName(
        displayName: String,
        file: File,
        classify: Boolean,
        slashDirs: Boolean
    ): String {
        val isDir = file.isDirectory
        val isExec = !isDir && file.canExecute()
        val ext = file.extension.lowercase(Locale.US)
        val suffix = when {
            isDir && (classify || slashDirs) -> "/"
            isExec && classify -> "*"
            else -> ""
        }
        return when {
            isDir -> "\u001b[1;34m$displayName\u001b[0m$suffix"
            isExec -> "\u001b[1;32m$displayName\u001b[0m$suffix"
            ext in setOf("zip", "tar", "gz", "tgz", "xz", "bz2", "deb", "apk") ->
                "\u001b[1;31m$displayName\u001b[0m$suffix"
            else -> "$displayName$suffix"
        }
    }

    private fun formatHumanSize(bytes: Long): String {
        val unit = 1024.0
        val str = when {
            bytes < 1024L -> "${bytes}B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1fK", bytes / unit)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1fM", bytes / (unit * unit))
            else -> String.format(Locale.US, "%.1fG", bytes / (unit * unit * unit))
        }
        return str.padStart(6)
    }

    private fun persistSessionEnvFile() {
        runCatching {
            val sb = StringBuilder()
            for ((k, v) in sessionEnvVars) {
                val escaped = v.replace("'", "'\\''")
                sb.appendLine("export $k='$escaped'")
            }
            sessionEnvFile.writeText(sb.toString())
        }
    }

    // ---------------------------------------------------------------- Command Execution (Warm PRoot Daemon + One-Shot Fallback)

    private fun executeCommandLineAsync(rawCmd: String) {
        val trimmed = rawCmd.trim()
        if (trimmed.isEmpty()) {
            synchronized(bufferLock) {
                invalidatePromptCacheLocked()
            }
            onUpdate?.invoke()
            return
        }
        synchronized(bufferLock) {
            lastCommandOutput.clear()
        }

        if (trimmed == "reset") {
            synchronized(bufferLock) {
                historyLines.clear()
                currentOutputLine.clear()
            }
            reset()
            onUpdate?.invoke()
            return
        }
        if (trimmed == "clear" || trimmed == "cls") {
            synchronized(bufferLock) {
                historyLines.clear()
                currentOutputLine.clear()
                lastExitCode = 0
                invalidatePromptCacheLocked()
            }
            onUpdate?.invoke()
            return
        }
        if (trimmed == "exit" || trimmed == "logout") {
            finishIfRunning()
            return
        }

        // Instant in-memory execution (<1 ms) for pwd, ls, cd, echo, export, whoami, etc.
        if (tryExecuteInstantFastPath(trimmed)) {
            return
        }

        val parts = trimmed.split(Regex("\\s+"))

        // Handle `ubuntu-bootstrap` / `proot-distro`
        if (parts[0] == "ubuntu-bootstrap" || parts[0] == "alpine-bootstrap" || parts[0] == "proot-distro") {
            isExecutingCommand = true
            Thread {
                try {
                    val sub = parts.getOrNull(1) ?: ""
                    when {
                        parts[0] == "proot-distro" && sub == "list" -> {
                            val installed = UbuntuProotManager.isUbuntuInstalled(context)
                            val badge = if (installed) "\u001b[1;32m[installed]\u001b[0m" else "\u001b[1;33m[not installed — run: ubuntu-bootstrap]\u001b[0m"
                            appendAnsiOutput("\u001b[1;36mSupported PRoot distributions:\u001b[0m\n")
                            appendAnsiOutput("  * \u001b[1;32mubuntu\u001b[0m (Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} • ${UbuntuProotManager.ubuntuArch()}) $badge\n")
                        }
                        sub == "--status" || sub == "status" -> {
                            if (UbuntuProotManager.isUbuntuInstalled(context)) {
                                appendAnsiOutput("\u001b[1;32m[OK]\u001b[0m Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} (${UbuntuProotManager.ubuntuArch()}) + PRoot is installed at ${ubuntuRoot.absolutePath}\n")
                                runProotCommandInternal("uname -a && cat /etc/os-release")
                            } else {
                                appendAnsiOutput("\u001b[1;33m[NOT INSTALLED]\u001b[0m Run 'ubuntu-bootstrap' to download and extract Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} + PRoot.\n")
                            }
                        }
                        else -> {
                            val force = trimmed.contains("--reinstall") || trimmed.contains("reset")
                            val ok = UbuntuProotManager.bootstrapUbuntuRootfs(context, forceReinstall = force) { msg ->
                                appendAnsiOutput("$msg\n")
                            }
                            if (ok && UbuntuProotManager.isUbuntuInstalled(context)) {
                                ensureExecutableIndexLoaded(ubuntuRoot, usrBinDir, forceReload = true)
                                prewarmProotShellAsync()
                                runProotCommandInternal("uname -srm && . /etc/os-release && echo \"\$PRETTY_NAME ready.\"")
                            }
                        }
                    }
                } finally {
                    isExecutingCommand = false
                    synchronized(bufferLock) {
                        if (currentOutputLine.isNotEmpty()) {
                            historyLines.add(ArrayList(currentOutputLine))
                            currentOutputLine.clear()
                        }
                        invalidatePromptCacheLocked()
                    }
                    onUpdate?.invoke()
                }
            }.start()
            return
        }

        val isDownloadCmd = parts[0] in setOf("apt", "apt-get", "lsp-install", "zsh-setup", "wget", "curl", "pip", "pip3", "npm", "cargo")
        isExecutingCommand = true
        if (isDownloadCmd) {
            DownloadNotificationHelper.showProgress(
                context = context,
                notificationId = DownloadNotificationHelper.NOTIF_ID_LSP,
                title = "Terminal Package / Tool Task",
                subtitle = trimmed,
                progress = 25,
                indeterminate = true
            )
        }
        Thread {
            try {
                if (!isFailsafe && UbuntuProotManager.isUbuntuInstalled(context)) {
                    runProotCommandInternal(trimmed)
                } else {
                    runHostFallbackShellInternal(trimmed)
                }

                // Sync LSP installations & executable index if apt / lsp-install / zsh-setup ran
                if (parts[0] in setOf("apt", "apt-get", "dpkg", "lsp-install", "lsp-uninstall", "zsh-setup")) {
                    UbuntuProotManager.invalidateRuntimeCaches()
                    ensureExecutableIndexLoaded(ubuntuRoot, usrBinDir, forceReload = true)
                    LspManager.syncTerminalInstallations(context)
                }
                if (isDownloadCmd) {
                    DownloadNotificationHelper.complete(
                        context = context,
                        notificationId = DownloadNotificationHelper.NOTIF_ID_LSP,
                        title = if (lastExitCode == 0) "Terminal Task Complete ✓" else "Terminal Task Finished (exit $lastExitCode)",
                        message = trimmed,
                        success = lastExitCode == 0
                    )
                }
            } catch (e: Exception) {
                appendAnsiOutput("\u001b[1;31merror: ${e.message}\u001b[0m\n")
            } finally {
                runningProcess = null
                runningProcessStdin = null
                isExecutingCommand = false
                synchronized(bufferLock) {
                    if (currentOutputLine.isNotEmpty()) {
                        historyLines.add(ArrayList(currentOutputLine))
                        currentOutputLine.clear()
                    }
                    invalidatePromptCacheLocked()
                }
                onUpdate?.invoke()
            }
        }.start()
    }

    /**
     * Pre-warms a persistent background PRoot shell process (`warmProotProcess`) so subsequent
     * commands run inside an already-initialized Ubuntu PRoot environment without cold-spawn delay.
     */
    private fun prewarmProotShellAsync() {
        if (sessionClosed || isFailsafe || !UbuntuProotManager.isUbuntuInstalled(context)) return
        Thread {
            synchronized(warmShellLock) {
                if (sessionClosed) return@Thread
                val existing = warmProotProcess
                if (existing != null && existing.isAlive) return@Thread
                startWarmProotShellLocked()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startWarmProotShellLocked(): Boolean {
        if (sessionClosed || isFailsafe || !UbuntuProotManager.isUbuntuInstalled(context)) return false
        val modes = UbuntuProotManager.availableLaunchModes(context)
        val guestSessionCwdPath = "/mnt/host/usr/tmp/${sessionCwdFile.name}"
        val initLoopScript = buildString {
            appendLine("export PROMPT='' PS1='' PS2=''")
            appendLine("if [ -f /etc/profile.d/pocketdev.sh ]; then . /etc/profile.d/pocketdev.sh >/dev/null 2>&1; fi")
            appendLine("if [ -n \"\${ZSH_VERSION:-}\" ]; then setopt INTERACTIVE_COMMENTS AUTO_CD EXTENDED_GLOB NO_BEEP SH_WORD_SPLIT 2>/dev/null || true; fi")
            appendLine("printf \"\\033]777;pocketdev;ready\\007\"")
            appendLine("while IFS= read -r __PDEV_LINE; do")
            appendLine("  eval \"\$__PDEV_LINE\"")
            appendLine("  __PDEV_RET=\$?")
            appendLine("  pwd > \"$guestSessionCwdPath\" 2>/dev/null")
            appendLine("  printf \"\\033]777;pocketdev;done;%d\\007\" \"\$__PDEV_RET\"")
            appendLine("done")
        }

        for (mode in modes) {
            val proc = startProotProcessForMode(initLoopScript, mode, fastNoRc = true) ?: continue
            val writer = runCatching { OutputStreamWriter(proc.outputStream, Charsets.UTF_8) }.getOrNull()
            if (writer == null) {
                runCatching { proc.destroyForcibly() }
                continue
            }
            val readyLatch = CountDownLatch(1)
            warmReadyLatch = readyLatch
            warmProotProcess = proc
            warmProotStdin = writer

            Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                        val buf = CharArray(512)
                        var read: Int
                        while (reader.read(buf).also { read = it } != -1) {
                            val chunk = String(buf, 0, read)
                            if (warmReadyLatch != null && ! chunk.contains("\u001b]777;pocketdev;ready") &&
                                (chunk.contains("CANNOT LINK EXECUTABLE", ignoreCase = true) ||
                                    chunk.contains("Permission denied", ignoreCase = true) ||
                                    chunk.contains("bad ELF magic", ignoreCase = true))
                            ) {
                                break
                            }
                            appendAnsiOutput(chunk)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    warmReadyLatch?.countDown()
                    synchronized(warmShellLock) {
                        if (warmProotProcess === proc) {
                            warmProotProcess = null
                            warmProotStdin = null
                        }
                    }
                    activeCommandLatch?.countDown()
                }
            }.apply { isDaemon = true }.start()

            val becameReady = runCatching { readyLatch.await(1800L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            warmReadyLatch = null
            if (becameReady && proc.isAlive) {
                UbuntuProotManager.preferredLaunchMode = mode
                return true
            }
            runCatching { proc.destroyForcibly() }
            warmProotProcess = null
            warmProotStdin = null
        }
        return false
    }

    /**
     * Executes [guestCommand] inside the real Ubuntu 24.04.5 LTS rootfs via the pre-warmed
     * persistent PRoot shell (or falls back to a fast one-shot `proot` invocation if needed).
     */
    private fun runProotCommandInternal(guestCommand: String) {
        val guestSessionEnvPath = "/mnt/host/usr/tmp/${sessionEnvFile.name}"
        val guestSessionCwdPath = "/mnt/host/usr/tmp/${sessionCwdFile.name}"
        val guestSessionCmdPath = "/mnt/host/usr/tmp/${sessionCmdFile.name}"
        val guestWorkDir = UbuntuProotManager.hostDirToGuestPath(context, currentDir)

        // Strip leading `sudo ` when already root (-0) inside PRoot if `sudo` package is not installed yet
        val sudoExists = File(ubuntuRoot, "usr/bin/sudo").exists()
        val effectiveCmd = if (!sudoExists && guestCommand.startsWith("sudo ")) {
            guestCommand.removePrefix("sudo ").trimStart()
        } else {
            guestCommand
        }

        // Fast path: execute inside the persistent pre-warmed PRoot shell daemon
        if (tryRunInWarmProotShell(effectiveCmd, guestWorkDir, guestSessionEnvPath, guestSessionCwdPath, guestSessionCmdPath)) {
            updateCurrentDirFromGuestPwd()
            return
        }

        // Fallback: one-shot PRoot process (using fastNoRc = true so `.zshrc` `compinit` never blocks)
        val wrappedGuestScript = buildString {
            appendLine("if [ -f /etc/profile.d/pocketdev.sh ]; then . /etc/profile.d/pocketdev.sh >/dev/null 2>&1; fi")
            appendLine("if [ -n \"\${ZSH_VERSION:-}\" ]; then setopt INTERACTIVE_COMMENTS AUTO_CD EXTENDED_GLOB NO_BEEP SH_WORD_SPLIT 2>/dev/null || true; fi")
            appendLine("if [ -f \"$guestSessionEnvPath\" ]; then . \"$guestSessionEnvPath\" >/dev/null 2>&1; fi")
            appendLine(effectiveCmd)
            appendLine("__RET=$?")
            appendLine("pwd > \"$guestSessionCwdPath\" 2>/dev/null")
            appendLine("exit \$__RET")
        }

        if (!UbuntuProotManager.isProotUsable(context)) {
            UbuntuProotManager.repairProotInPlace(context) { msg ->
                appendAnsiOutput("$msg\n")
            }
        }

        var launchedMode: UbuntuProotManager.ProotLaunchMode? = null
        var proc: Process? = null

        for (mode in UbuntuProotManager.availableLaunchModes(context)) {
            val candidate = startProotProcessForMode(wrappedGuestScript, mode, fastNoRc = true)
            if (candidate != null) {
                proc = candidate
                launchedMode = mode
                break
            }
        }

        if (proc == null) {
            val repaired = UbuntuProotManager.repairProotInPlace(context) { msg ->
                appendAnsiOutput("$msg\n")
            }
            if (repaired) {
                for (mode in UbuntuProotManager.availableLaunchModes(context)) {
                    val candidate = startProotProcessForMode(wrappedGuestScript, mode, fastNoRc = true)
                    if (candidate != null) {
                        proc = candidate
                        launchedMode = mode
                        break
                    }
                }
            }
        }

        if (proc == null) {
            appendAnsiOutput("\u001b[1;31mPRoot execution error: unable to start proot binary. Try running 'ubuntu-bootstrap --reinstall'.\u001b[0m\n")
            lastExitCode = 127
            return
        }

        runningProcess = proc
        runningProcessStdin = runCatching { OutputStreamWriter(proc.outputStream) }.getOrNull()

        val outputTracker = StringBuilder()
        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
            val buf = CharArray(512)
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                val chunk = String(buf, 0, read)
                outputTracker.append(chunk)
                appendAnsiOutput(chunk)
            }
        }
        val exitCode = proc.waitFor()
        lastExitCode = exitCode

        val launchFailedAtRuntime = (exitCode == 126 || exitCode == 127) && (
            outputTracker.contains("Permission denied", ignoreCase = true) ||
                outputTracker.contains("CANNOT LINK EXECUTABLE", ignoreCase = true) ||
                outputTracker.contains("unexpected e_type", ignoreCase = true) ||
                outputTracker.contains("bad ELF magic", ignoreCase = true) ||
                outputTracker.contains("not found", ignoreCase = true)
            )

        if (launchFailedAtRuntime) {
            val fallbackModes = UbuntuProotManager.availableLaunchModes(context).filter { it != launchedMode }
            var recovered = false
            for (altMode in fallbackModes) {
                val retryProc = startProotProcessForMode(wrappedGuestScript, altMode, fastNoRc = true) ?: continue
                runningProcess = retryProc
                runningProcessStdin = runCatching { OutputStreamWriter(retryProc.outputStream) }.getOrNull()
                val retryOut = StringBuilder()
                BufferedReader(InputStreamReader(retryProc.inputStream)).use { reader ->
                    val buf = CharArray(512)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1) {
                        val chunk = String(buf, 0, read)
                        retryOut.append(chunk)
                        appendAnsiOutput(chunk)
                    }
                }
                val retryCode = retryProc.waitFor()
                lastExitCode = retryCode
                if (retryCode != 126 && retryCode != 127) {
                    UbuntuProotManager.preferredLaunchMode = altMode
                    recovered = true
                    break
                }
            }
            if (!recovered) {
                val repaired = UbuntuProotManager.repairProotInPlace(context) { msg ->
                    appendAnsiOutput("$msg\n")
                }
                if (repaired) {
                    val repairedMode = UbuntuProotManager.preferredLaunchMode
                        ?: UbuntuProotManager.availableLaunchModes(context).firstOrNull()
                    if (repairedMode != null) {
                        val retryProc = startProotProcessForMode(wrappedGuestScript, repairedMode, fastNoRc = true)
                        if (retryProc != null) {
                            runningProcess = retryProc
                            runningProcessStdin = runCatching { OutputStreamWriter(retryProc.outputStream) }.getOrNull()
                            BufferedReader(InputStreamReader(retryProc.inputStream)).use { reader ->
                                val buf = CharArray(512)
                                var read: Int
                                while (reader.read(buf).also { read = it } != -1) {
                                    appendAnsiOutput(String(buf, 0, read))
                                }
                            }
                            lastExitCode = retryProc.waitFor()
                        }
                    }
                }
            }
        } else if (launchedMode != null) {
            UbuntuProotManager.preferredLaunchMode = launchedMode
        }

        updateCurrentDirFromGuestPwd()
        prewarmProotShellAsync()
    }

    private fun tryRunInWarmProotShell(
        effectiveCmd: String,
        guestWorkDir: String,
        guestSessionEnvPath: String,
        guestSessionCwdPath: String,
        guestSessionCmdPath: String
    ): Boolean {
        val proc: Process
        val stdin: OutputStreamWriter
        synchronized(warmShellLock) {
            val curProc = warmProotProcess
            val curStdin = warmProotStdin
            if (curProc == null || !curProc.isAlive || curStdin == null) {
                if (!startWarmProotShellLocked()) return false
            }
            proc = warmProotProcess ?: return false
            stdin = warmProotStdin ?: return false
        }

        return try {
            val escapedWorkDir = guestWorkDir.replace("\"", "\\\"")
            sessionCmdFile.writeText(effectiveCmd + "\n")

            val latch = CountDownLatch(1)
            activeCommandLatch = latch
            runningProcess = proc
            runningProcessStdin = stdin

            val dispatchLine = "cd \"$escapedWorkDir\" 2>/dev/null; if [ -f \"$guestSessionEnvPath\" ]; then . \"$guestSessionEnvPath\" >/dev/null 2>&1; fi; . \"$guestSessionCmdPath\"\n"
            stdin.write(dispatchLine)
            stdin.flush()

            while (!latch.await(150L, TimeUnit.MILLISECONDS)) {
                if (!proc.isAlive || sessionClosed) {
                    break
                }
            }
            activeCommandLatch = null
            true
        } catch (_: Exception) {
            activeCommandLatch = null
            synchronized(warmShellLock) {
                runCatching { warmProotProcess?.destroyForcibly() }
                warmProotProcess = null
                warmProotStdin = null
            }
            false
        }
    }

    private fun updateCurrentDirFromGuestPwd() {
        if (sessionCwdFile.exists()) {
            val guestPwd = runCatching { sessionCwdFile.readText().trim() }.getOrNull()
            if (!guestPwd.isNullOrEmpty()) {
                val mappedHostDir = UbuntuProotManager.guestPathToHostDir(context, guestPwd)
                if (mappedHostDir.exists() && mappedHostDir.isDirectory) {
                    val ws = ProjectManager.ensureDefaultLocalWorkspace(context)
                    synchronized(bufferLock) {
                        currentDir = if (canonicalOrAbs(mappedHostDir) == canonicalOrAbs(ws)) ws else mappedHostDir
                        invalidatePromptCacheLocked()
                    }
                }
            }
        }
    }

    private fun startProotProcessForMode(
        wrappedGuestScript: String,
        mode: UbuntuProotManager.ProotLaunchMode,
        fastNoRc: Boolean = true
    ): Process? {
        return try {
            val cmdList = UbuntuProotManager.buildProotCommandListForMode(
                context = context,
                hostWorkingDir = currentDir,
                guestScript = wrappedGuestScript,
                mode = mode,
                fastNoRc = fastNoRc
            )
            val pb = ProcessBuilder(cmdList)
                .directory(currentDir)
                .redirectErrorStream(true)
            UbuntuProotManager.applyHostProotEnv(context, pb.environment())
            pb.start()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Executes commands in Android's host `/system/bin/sh` ONLY when in failsafe mode
     * or while Ubuntu PRoot has not been bootstrapped yet.
     */
    private fun runHostFallbackShellInternal(rawCommand: String) {
        val wrapped = buildString {
            appendLine(rawCommand)
            appendLine("__RET=$?")
            appendLine("pwd > \"${sessionCwdFile.absolutePath}\" 2>/dev/null")
            appendLine("exit \$__RET")
        }
        val pb = ProcessBuilder("/system/bin/sh", "-c", wrapped)
            .directory(currentDir)
            .redirectErrorStream(true)
        val env = pb.environment()
        val existingPath = env["PATH"] ?: "/system/bin:/system/xbin"
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = usrTmpDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["PATH"] = "$existingPath:${usrBinDir.absolutePath}"

        val proc = pb.start()
        runningProcess = proc
        runningProcessStdin = runCatching { OutputStreamWriter(proc.outputStream) }.getOrNull()

        val tracker = StringBuilder()
        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
            val buf = CharArray(512)
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                val chunk = String(buf, 0, read)
                tracker.append(chunk)
                appendAnsiOutput(chunk)
            }
        }
        lastExitCode = proc.waitFor()

        if (sessionCwdFile.exists()) {
            val newCwd = runCatching { sessionCwdFile.readText().trim() }.getOrNull()
            if (!newCwd.isNullOrEmpty()) {
                val f = File(newCwd)
                if (f.exists() && f.isDirectory) {
                    val ws = ProjectManager.ensureDefaultLocalWorkspace(context)
                    synchronized(bufferLock) {
                        currentDir = if (canonicalOrAbs(f) == canonicalOrAbs(ws)) ws else f
                        invalidatePromptCacheLocked()
                    }
                }
            }
        }

        if (!isFailsafe && !UbuntuProotManager.isUbuntuInstalled(context) &&
            (tracker.contains("inaccessible or not found") || tracker.contains("not found"))
        ) {
            appendAnsiOutput("\u001b[1;33m==> Tip: Ubuntu ${UbuntuProotManager.UBUNTU_VERSION} + PRoot is not installed yet. Run 'ubuntu-bootstrap' (with internet enabled) to install the real Ubuntu Linux rootfs.\u001b[0m\n")
        }
    }

    // ---------------------------------------------------------------- ANSI Escape Sequence Parser

    fun appendAnsiOutput(chunk: String) {
        if (chunk.contains("[DIAG]", ignoreCase = true) || chunk.contains("[ERROR]", ignoreCase = true) || chunk.contains("==>")) {
            DiagLogger.captureProgressIfDiagnostic(context, "Terminal", chunk)
        }
        var bellTriggered = false
        var titleUpdated = false
        var oscClipboardText: String? = null
        var commandDoneSignaled = false
        val ipcEvents = ArrayList<Pair<String, String>>()

        synchronized(bufferLock) {
            var i = 0
            val n = chunk.length
            val seg = StringBuilder()

            fun flushSegment() {
                if (seg.isNotEmpty()) {
                    val str = seg.toString()
                    val effectiveFg = if (curInverse) (if (curBgColor != Color.TRANSPARENT) curBgColor else Color.BLACK) else curFgColor
                    val effectiveBg = if (curInverse) curFgColor else curBgColor
                    currentOutputLine.add(StyledSpan(str, effectiveFg, curBold, effectiveBg, curUnderline))
                    lastCommandOutput.append(str)
                    seg.clear()
                }
            }

            while (i < n) {
                val c = chunk[i]
                when {
                    c == '\u001b' && i + 1 < n && chunk[i + 1] == '[' -> {
                        flushSegment()
                        var j = i + 2
                        while (j < n && chunk[j] !in 'A'..'Z' && chunk[j] !in 'a'..'z' && chunk[j] != '~' && chunk[j] != '@') j++
                        if (j < n) {
                            val cmdChar = chunk[j]
                            val params = chunk.substring(i + 2, j)
                            handleCsiSequence(cmdChar, params)
                            i = j + 1
                            continue
                        }
                    }
                    c == '\u001b' && i + 1 < n && chunk[i + 1] == ']' -> {
                        flushSegment()
                        var j = i + 2
                        while (j < n && chunk[j] != '\u0007' && !(chunk[j] == '\u001b' && j + 1 < n && chunk[j + 1] == '\\')) {
                            j++
                        }
                        if (j < n) {
                            val oscBody = chunk.substring(i + 2, j)
                            when {
                                oscBody == "777;pocketdev;ready" -> {
                                    warmReadyLatch?.countDown()
                                }
                                oscBody.startsWith("777;pocketdev;done;") -> {
                                    val codeStr = oscBody.removePrefix("777;pocketdev;done;").trim()
                                    lastExitCode = codeStr.toIntOrNull() ?: 0
                                    invalidatePromptCacheLocked()
                                    commandDoneSignaled = true
                                }
                                oscBody.startsWith("0;") || oscBody.startsWith("2;") -> {
                                    val newTitle = oscBody.substring(2).trim()
                                    if (newTitle.isNotEmpty()) {
                                        title = newTitle
                                        titleUpdated = true
                                    }
                                }
                                oscBody.startsWith("52;") -> {
                                    val b64 = oscBody.substringAfterLast(';', "")
                                    if (b64.isNotEmpty()) {
                                        runCatching {
                                            oscClipboardText = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                                        }
                                    }
                                }
                                oscBody.startsWith("777;termux;") -> {
                                    val payload = oscBody.removePrefix("777;termux;")
                                    val action = payload.substringBefore(';')
                                    val arg = payload.substringAfter(';', "")
                                    ipcEvents.add(action to arg)
                                }
                            }
                            i = if (chunk[j] == '\u0007') j + 1 else j + 2
                            continue
                        }
                    }
                    c == '\u0007' -> {
                        flushSegment()
                        bellTriggered = true
                    }
                    c == '\r' -> {
                        flushSegment()
                        if (i + 1 < n && chunk[i + 1] == '\n') {
                            // Standard CRLF
                        } else {
                            currentOutputLine.clear()
                        }
                    }
                    c == '\n' -> {
                        flushSegment()
                        lastCommandOutput.append('\n')
                        historyLines.add(ArrayList(currentOutputLine))
                        currentOutputLine.clear()
                        trimHistoryIfNeeded()
                    }
                    c == '\b' -> {
                        if (seg.isNotEmpty()) {
                            seg.deleteCharAt(seg.lastIndex)
                        }
                    }
                    else -> seg.append(c)
                }
                i++
            }
            flushSegment()
        }

        if (commandDoneSignaled) {
            activeCommandLatch?.countDown()
        }
        if (bellTriggered) onBell?.invoke()
        if (titleUpdated) onTitleChanged?.invoke(this)
        oscClipboardText?.let { onCopyToClipboard?.invoke(it) }
        for ((act, arg) in ipcEvents) {
            onTermuxIpcEvent?.invoke(act, arg)
        }
        onUpdate?.invoke()
    }

    private fun handleCsiSequence(cmd: Char, params: String) {
        if (params.startsWith("?")) {
            val privCode = params.removePrefix("?").toIntOrNull()
            if (privCode == 2004) {
                bracketedPasteEnabled = (cmd == 'h')
            }
            return
        }
        when (cmd) {
            'J' -> {
                if (params == "3") {
                    historyLines.clear()
                    currentOutputLine.clear()
                } else {
                    currentOutputLine.clear()
                }
            }
            'K' -> {
                currentOutputLine.clear()
            }
            'm' -> {
                val codes = if (params.isEmpty()) listOf(0) else params.split(';').mapNotNull { it.toIntOrNull() }
                var idx = 0
                while (idx < codes.size) {
                    when (val code = codes[idx]) {
                        0 -> {
                            curFgColor = DEFAULT_FG
                            curBgColor = Color.TRANSPARENT
                            curBold = false
                            curUnderline = false
                            curInverse = false
                        }
                        1 -> curBold = true
                        4 -> curUnderline = true
                        7 -> curInverse = true
                        22 -> curBold = false
                        24 -> curUnderline = false
                        27 -> curInverse = false
                        in 30..37 -> curFgColor = ANSI_COLORS[code - 30]
                        39 -> curFgColor = DEFAULT_FG
                        in 40..47 -> curBgColor = ANSI_COLORS[code - 40]
                        49 -> curBgColor = Color.TRANSPARENT
                        in 90..97 -> curFgColor = ANSI_BRIGHT_COLORS[code - 90]
                        in 100..107 -> curBgColor = ANSI_BRIGHT_COLORS[code - 100]
                        38 -> {
                            if (idx + 2 < codes.size && codes[idx + 1] == 5) {
                                curFgColor = xterm256ToColor(codes[idx + 2])
                                idx += 2
                            } else if (idx + 4 < codes.size && codes[idx + 1] == 2) {
                                curFgColor = Color.rgb(
                                    codes[idx + 2].coerceIn(0, 255),
                                    codes[idx + 3].coerceIn(0, 255),
                                    codes[idx + 4].coerceIn(0, 255)
                                )
                                idx += 4
                            }
                        }
                        48 -> {
                            if (idx + 2 < codes.size && codes[idx + 1] == 5) {
                                curBgColor = xterm256ToColor(codes[idx + 2])
                                idx += 2
                            } else if (idx + 4 < codes.size && codes[idx + 1] == 2) {
                                curBgColor = Color.rgb(
                                    codes[idx + 2].coerceIn(0, 255),
                                    codes[idx + 3].coerceIn(0, 255),
                                    codes[idx + 4].coerceIn(0, 255)
                                )
                                idx += 4
                            }
                        }
                    }
                    idx++
                }
            }
        }
    }

    private fun xterm256ToColor(index: Int): Int {
        val c = index.coerceIn(0, 255)
        if (c < 8) return ANSI_COLORS[c]
        if (c < 16) return ANSI_BRIGHT_COLORS[c - 8]
        if (c < 232) {
            val cube = c - 16
            val r = (cube / 36) % 6
            val g = (cube / 6) % 6
            val b = cube % 6
            val levels = intArrayOf(0, 95, 135, 175, 215, 255)
            return Color.rgb(levels[r], levels[g], levels[b])
        }
        val gray = 8 + (c - 232) * 10
        return Color.rgb(gray, gray, gray)
    }

    private fun trimHistoryIfNeeded() {
        while (historyLines.size > MAX_SCROLLBACK_LINES) {
            historyLines.removeAt(0)
        }
    }

    fun snapshotLines(maxCols: Int): RenderSnapshot = synchronized(bufferLock) {
        val cols = maxCols.coerceAtLeast(20)
        val wrapped = ArrayList<WrappedLine>(historyLines.size + 4)

        for (line in historyLines) {
            wrapSpanLine(line, cols, wrapped)
        }
        if (currentOutputLine.isNotEmpty()) {
            wrapSpanLine(currentOutputLine, cols, wrapped)
        }

        var cursorRow: Int
        var cursorCol: Int

        if (!isExecutingCommand) {
            val promptSpans = buildPromptSpans().toMutableList()
            val promptLen = promptSpans.sumOf { it.text.length }
            val typed = inputBuffer.toString()
            if (typed.isNotEmpty()) {
                promptSpans.addAll(getOrHighlightInputLocked(typed))
                val ghost = getOrComputeAutosuggestionLocked(typed)
                if (ghost.isNotEmpty()) {
                    promptSpans.add(StyledSpan(ghost, GHOST_FG, false))
                }
            }
            val totalCursorOffset = promptLen + inputCursorPos
            val startRow = wrapped.size
            wrapSpanLine(promptSpans, cols, wrapped)
            cursorRow = startRow + (totalCursorOffset / cols)
            cursorCol = totalCursorOffset % cols
        } else {
            val activeSpans = ArrayList<StyledSpan>()
            if (inputBuffer.isNotEmpty()) {
                activeSpans.add(StyledSpan(inputBuffer.toString(), DEFAULT_FG, false))
                val startRow = wrapped.size
                wrapSpanLine(activeSpans, cols, wrapped)
                cursorRow = startRow + (inputBuffer.length / cols)
                cursorCol = inputBuffer.length % cols
            } else {
                cursorRow = max(0, wrapped.size - 1)
                cursorCol = currentOutputLine.sumOf { it.text.length } % cols
            }
        }

        RenderSnapshot(wrapped, cursorRow, cursorCol)
    }

    private fun wrapSpanLine(
        spans: List<StyledSpan>,
        maxCols: Int,
        out: MutableList<WrappedLine>
    ) {
        if (spans.isEmpty()) {
            out.add(WrappedLine(emptyList()))
            return
        }
        var currentRow = ArrayList<StyledSpan>()
        var col = 0
        for (span in spans) {
            var text = span.text
            while (text.isNotEmpty()) {
                val room = maxCols - col
                val chunk = text.take(room)
                currentRow.add(StyledSpan(chunk, span.fgColor, span.bold, span.bgColor, span.underline))
                col += chunk.length
                text = text.drop(chunk.length)
                if (col >= maxCols && text.isNotEmpty()) {
                    out.add(WrappedLine(currentRow))
                    currentRow = ArrayList()
                    col = 0
                }
            }
        }
        out.add(WrappedLine(currentRow))
    }

    companion object {
        private const val MAX_SCROLLBACK_LINES = 10000
        private val DEFAULT_FG = Color.parseColor("#E6EDF3")
        private val GHOST_FG = Color.parseColor("#6E7681")

        @Volatile
        private var cachedExecutableNames: Set<String> = emptySet()

        @Volatile
        private var executableIndexLoaded: Boolean = false

        private fun ensureExecutableIndexLoaded(ubuntuRoot: File, usrBinDir: File, forceReload: Boolean = false) {
            if (executableIndexLoaded && !forceReload) return
            executableIndexLoaded = true
            val names = HashSet<String>(512)
            listOf(
                File(ubuntuRoot, "usr/bin"),
                File(ubuntuRoot, "usr/local/bin"),
                File(ubuntuRoot, "bin"),
                usrBinDir
            ).forEach { dir ->
                dir.list()?.forEach { names.add(it) }
            }
            cachedExecutableNames = names
        }

        private val SHELL_ALIASES = mapOf(
            "ll" to "ls -la",
            "la" to "ls -A",
            "l" to "ls -CF",
            "projects" to "cd /root/projects",
            "cls" to "clear"
        )

        private val KNOWN_COMMANDS = setOf(
            "ls", "ll", "la", "l", "cd", "pwd", "cat", "echo", "printf", "grep", "egrep", "fgrep", "find",
            "mkdir", "rmdir", "rm", "cp", "mv", "touch", "chmod", "chown", "head", "tail",
            "wc", "sort", "uniq", "sed", "awk", "cut", "tr", "xargs", "tar", "gzip", "gunzip",
            "zip", "unzip", "curl", "wget", "ping", "ps", "top", "htop", "kill", "df", "du",
            "free", "uname", "lscpu", "lsblk", "dmesg", "ip", "mount", "dpkg", "apt", "apt-get",
            "apt-cache", "whoami", "hostname", "id", "date", "uptime", "env", "export", "unset", "alias",
            "unalias", "source", ".", "which", "type", "clear", "cls", "reset", "exit", "history",
            "true", "false", "test", "sleep", "sh", "bash", "dash", "zsh", "sudo", "su",
            "go", "gofmt", "python", "python3", "pip", "pip3", "node", "npm", "npx", "tsc",
            "kotlin", "kotlinc", "java", "javac", "rustc", "cargo", "gcc", "g++", "clang",
            "clang++", "make", "cmake", "git", "vim", "neovim", "nano", "tmux",
            "proot", "proot-distro", "ubuntu-bootstrap", "zsh-setup", "lsp-install", "lsp-uninstall",
            "termux-reload-settings", "termux-clipboard-get", "termux-clipboard-set",
            "termux-open-url", "termux-open", "termux-setup-storage"
        )

        private val PRESET_SUGGESTIONS = listOf(
            "apt update && apt install -y golang-go",
            "apt update && apt install -y python3 python3-pip",
            "apt update && apt install -y nodejs npm",
            "apt update && apt install -y build-essential clang git",
            "apt update",
            "apt install -y ",
            "apt search ",
            "dpkg -l",
            "cat /etc/os-release",
            "uname -a",
            "go run src/Server.go",
            "go build src/Server.go",
            "go version",
            "python3 --version",
            "node --version",
            "git status",
            "ubuntu-bootstrap --status",
            "ubuntu-bootstrap --reinstall",
            "proot-distro list",
            "lsp-install gopls",
            "lsp-install --list",
            "ls -la"
        )

        private val ANSI_COLORS = intArrayOf(
            Color.parseColor("#484F58"), // 30 Black
            Color.parseColor("#FF7B72"), // 31 Red
            Color.parseColor("#3FB950"), // 32 Green
            Color.parseColor("#D29922"), // 33 Yellow
            Color.parseColor("#58A6FF"), // 34 Blue
            Color.parseColor("#BC8CFF"), // 35 Magenta
            Color.parseColor("#39C5CF"), // 36 Cyan
            Color.parseColor("#B1BAC4")  // 37 White
        )

        private val ANSI_BRIGHT_COLORS = intArrayOf(
            Color.parseColor("#6E7681"), // 90 Bright Black
            Color.parseColor("#FFA198"), // 91 Bright Red
            Color.parseColor("#56D364"), // 92 Bright Green
            Color.parseColor("#E3B341"), // 93 Bright Yellow
            Color.parseColor("#79C0FF"), // 94 Bright Blue
            Color.parseColor("#D2A8FF"), // 95 Bright Magenta
            Color.parseColor("#56D4DD"), // 96 Bright Cyan
            Color.parseColor("#FFFFFF")  // 97 Bright White
        )

        fun provisionShellEnvironment(context: Context) {
            Thread {
                runCatching {
                    AlpineLinuxEnvironment.provisionEnvironment(context)
                }
            }.apply { isDaemon = true }.start()
        }
    }
}
