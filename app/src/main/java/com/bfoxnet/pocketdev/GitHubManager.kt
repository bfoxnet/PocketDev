package com.bfoxnet.pocketdev

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * GitHub & Git Integration layered over the `git` CLI inside the Ubuntu 24.04 PRoot environment
 * (with HTTPS REST/Zipball fallback when `git` is not yet installed):
 * - **OAuth Device Flow + Personal Access Token (PAT) Login** for private and public repositories.
 * - **Clone Repository Dialog** (browse user's GitHub repos or paste any URL/owner/repo, injects
 *   token credentials seamlessly for private repos).
 * - **Commit, Push & Pull Dialogs** (`git add -A`, `git commit`, `git push`, `git pull --rebase`).
 * - **Branch Switcher & Creator** (`git branch`, `git checkout`, `git checkout -b`).
 * - **Colorized Diff Viewer** (`git diff HEAD` with green `+` additions, red `-` deletions, and `@@` hunks).
 * - **Git-Status Badges in File Tree** (`M` Modified, `A` Added, `U` Untracked, `D` Deleted, `R` Renamed).
 */
object GitHubManager {

    private const val DEFAULT_GITHUB_OAUTH_CLIENT_ID = "178c6fc778ccc68e1d6a"

    enum class GitFileState(val badge: String, val colorHex: String, val label: String) {
        MODIFIED("M", "#FFB74D", "Modified"),
        ADDED("A", "#81C784", "Added"),
        UNTRACKED("U", "#4FC3F7", "Untracked"),
        DELETED("D", "#E57373", "Deleted"),
        RENAMED("R", "#BA68C8", "Renamed")
    }

    data class RepoSummary(
        val fullName: String,
        val cloneUrl: String,
        val isPrivate: Boolean,
        val defaultBranch: String,
        val description: String
    )

    data class GitCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
        val combinedOutput: String
            get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cachedStatusByProject = ConcurrentHashMap<String, Pair<Long, Map<String, GitFileState>>>()

    private fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun runGitCommand(context: Context, projectDir: File, gitArgs: String): GitCommandResult {
        return try {
            val guestDir = UbuntuProotManager.hostDirToGuestPath(context, projectDir)
            val script = "export GIT_TERMINAL_PROMPT=0; cd ${shellSingleQuote(guestDir)} && git $gitArgs"

            val pb = if (UbuntuProotManager.isUbuntuInstalled(context)) {
                val mode = UbuntuProotManager.preferredLaunchMode
                    ?: UbuntuProotManager.availableLaunchModes(context).firstOrNull()
                    ?: UbuntuProotManager.ProotLaunchMode.DIRECT
                val cmdList = UbuntuProotManager.buildProotCommandListForMode(
                    context = context,
                    hostWorkingDir = projectDir,
                    guestScript = script,
                    mode = mode,
                    fastNoRc = true
                )
                ProcessBuilder(cmdList).apply {
                    directory(projectDir)
                    UbuntuProotManager.applyHostProotEnv(context, environment())
                }
            } else {
                ProcessBuilder("/system/bin/sh", "-c", "cd ${shellSingleQuote(projectDir.absolutePath)} && git $gitArgs")
                    .directory(projectDir)
            }

            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val err = proc.errorStream.bufferedReader(Charsets.UTF_8).readText()
            val code = proc.waitFor()
            if (code != 0 && err.isNotBlank()) {
                DiagLogger.log("Git", "git $gitArgs exited $code: ${err.take(200)}", context)
            }
            GitCommandResult(code, out, err)
        } catch (e: Exception) {
            DiagLogger.log("Git", "git $gitArgs failed: ${e.message}", context, e)
            GitCommandResult(-1, "", e.message ?: "Failed to execute git")
        }
    }

    fun isGitRepo(projectDir: File?): Boolean {
        if (projectDir == null || !projectDir.exists()) return false
        return File(projectDir, ".git").exists()
    }

    fun getCurrentBranchFast(projectDir: File?): String {
        if (projectDir == null) return "main"
        val headFile = File(projectDir, ".git/HEAD")
        if (headFile.exists()) {
            val raw = runCatching { headFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
            if (raw.startsWith("ref: refs/heads/")) {
                return raw.removePrefix("ref: refs/heads/").trim()
            }
            if (raw.length >= 7) {
                return raw.take(7)
            }
        }
        return "main"
    }

    fun getProjectGitStatusMap(context: Context, projectDir: File?): Map<String, GitFileState> {
        if (projectDir == null || !isGitRepo(projectDir)) return emptyMap()
        val key = projectDir.absolutePath
        val now = System.currentTimeMillis()
        val cached = cachedStatusByProject[key]
        if (cached != null && now - cached.first < 2500L) {
            return cached.second
        }
        Thread {
            val fresh = computeGitStatusSync(context, projectDir)
            cachedStatusByProject[key] = Pair(System.currentTimeMillis(), fresh)
        }.apply { isDaemon = true }.start()
        return cached?.second ?: emptyMap()
    }

    fun invalidateStatusCache() {
        cachedStatusByProject.clear()
    }

    fun computeGitStatusSync(context: Context, projectDir: File): Map<String, GitFileState> {
        if (!isGitRepo(projectDir)) return emptyMap()
        val res = runGitCommand(context, projectDir, "status --porcelain -uall")
        if (!res.isSuccess) return emptyMap()
        val map = HashMap<String, GitFileState>()
        for (rawLine in res.stdout.lineSequence()) {
            if (rawLine.length < 4) continue
            val xy = rawLine.substring(0, 2)
            var relPath = rawLine.substring(3).trim().removeSurrounding("\"")
            if (relPath.contains(" -> ")) {
                relPath = relPath.substringAfter(" -> ").trim().removeSurrounding("\"")
            }
            val state = when {
                xy.contains('?') -> GitFileState.UNTRACKED
                xy.contains('D') -> GitFileState.DELETED
                xy.contains('A') -> GitFileState.ADDED
                xy.contains('R') -> GitFileState.RENAMED
                else -> GitFileState.MODIFIED
            }
            map[relPath] = state
        }
        cachedStatusByProject[projectDir.absolutePath] = Pair(System.currentTimeMillis(), map)
        return map
    }

    fun showGitHubHubDialog(
        context: Context,
        onProjectChanged: (File) -> Unit,
        onTreeRefreshRequested: () -> Unit
    ) {
        val settings = Settings(context)
        val projectDir = ProjectManager.ensureDefaultLocalWorkspace(context)
        val hasRepo = isGitRepo(projectDir)
        val branch = if (hasRepo) getCurrentBranchFast(projectDir) else "not a git repo"
        val authStatus = if (settings.githubToken.isNotBlank()) {
            "Signed in as @${settings.githubUsername.ifBlank { "GitHub User" }}"
        } else {
            "Not signed in (tap Account / Login to connect OAuth or PAT)"
        }

        val options = mutableListOf(
            ModernUiHelper.MenuOption(
                id = 1,
                title = if (settings.githubToken.isNotBlank()) "GitHub Account (@${settings.githubUsername.ifBlank { "connected" }})" else "Sign in to GitHub (OAuth Device Flow / PAT)",
                subtitle = authStatus,
                iconRes = R.drawable.ic_git,
                badgeText = if (settings.githubToken.isNotBlank()) "CONNECTED" else "LOGIN",
                action = {
                    showAuthDialog(context) {
                        showGitHubHubDialog(context, onProjectChanged, onTreeRefreshRequested)
                    }
                }
            ),
            ModernUiHelper.MenuOption(
                id = 2,
                title = "Clone Repository (Public or Private)",
                subtitle = "Browse your GitHub repositories or clone by URL into PocketDev projects",
                iconRes = R.drawable.ic_folder_open,
                action = {
                    showCloneRepoDialog(context, onProjectChanged)
                }
            ),
            ModernUiHelper.MenuOption(
                id = 3,
                title = "Commit & Push Changes",
                subtitle = if (hasRepo) "Stage modified files, write commit message, and push to origin/$branch" else "Initialize git repo or clone a project first",
                iconRes = R.drawable.ic_save,
                badgeText = if (hasRepo) branch else null,
                action = {
                    if (!hasRepo) {
                        initGitRepoInteractive(context, projectDir, onTreeRefreshRequested)
                    } else {
                        showCommitAndPushDialog(context, projectDir, onTreeRefreshRequested)
                    }
                }
            ),
            ModernUiHelper.MenuOption(
                id = 4,
                title = "Diff Viewer (Working Tree Changes)",
                subtitle = "Inspect colorized unified git diff (+ additions / - deletions)",
                iconRes = R.drawable.ic_code,
                action = {
                    showDiffViewerDialog(context, projectDir, null)
                }
            ),
            ModernUiHelper.MenuOption(
                id = 5,
                title = "Branch Switcher & Creator",
                subtitle = if (hasRepo) "Current branch: $branch • Switch or create a new branch" else "Requires an initialized Git repository",
                iconRes = R.drawable.ic_git,
                action = {
                    if (!hasRepo) {
                        initGitRepoInteractive(context, projectDir, onTreeRefreshRequested)
                    } else {
                        showBranchSwitcherDialog(context, projectDir, onTreeRefreshRequested)
                    }
                }
            ),
            ModernUiHelper.MenuOption(
                id = 6,
                title = "Pull Latest Changes (git pull)",
                subtitle = "Fetch and merge/rebase latest commits from remote origin",
                iconRes = R.drawable.ic_refresh,
                action = {
                    if (!hasRepo) {
                        Toast.makeText(context, "Current project is not a Git repository yet.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Pulling latest changes from origin...", Toast.LENGTH_SHORT).show()
                        Thread {
                            val res = runGitCommand(context, projectDir, "pull --rebase")
                            invalidateStatusCache()
                            mainHandler.post {
                                onTreeRefreshRequested()
                                ModernUiHelper.showModernConfirmDialog(
                                    context = context,
                                    title = if (res.isSuccess) "Git Pull Complete ✓" else "Git Pull Output",
                                    message = res.combinedOutput.ifBlank { "Already up to date." },
                                    confirmText = "OK",
                                    cancelText = "Close"
                                ) {}
                            }
                        }.start()
                    }
                }
            )
        )

        if (!hasRepo) {
            options.add(
                ModernUiHelper.MenuOption(
                    id = 7,
                    title = "Initialize Git Repository (git init)",
                    subtitle = "Create a new Git repository in ${projectDir.name}",
                    iconRes = R.drawable.ic_new_file,
                    action = {
                        initGitRepoInteractive(context, projectDir, onTreeRefreshRequested)
                    }
                )
            )
        }

        ModernUiHelper.showModernContextMenu(
            context = context,
            title = "Git & GitHub Integration",
            subtitle = "Project: ${projectDir.name} • Branch: $branch",
            headerIconRes = R.drawable.ic_git,
            options = options
        )
    }

    private fun initGitRepoInteractive(
        context: Context,
        projectDir: File,
        onTreeRefreshRequested: () -> Unit
    ) {
        Thread {
            val res = runGitCommand(
                context,
                projectDir,
                "init -b main && git config user.name ${shellSingleQuote(Settings(context).githubUsername.ifBlank { "PocketDev Developer" })} && git config user.email 'dev@pocketdev.local'"
            )
            invalidateStatusCache()
            mainHandler.post {
                onTreeRefreshRequested()
                Toast.makeText(
                    context,
                    if (res.isSuccess) "Initialized Git repository in ${projectDir.name} ✓"
                    else "git init failed (ensure git is installed in Ubuntu PRoot): ${res.stderr}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    fun showAuthDialog(context: Context, onUpdated: () -> Unit) {
        val settings = Settings(context)
        val palette = ModernUiHelper.resolvePalette(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(palette.bgColor)
                setStroke(dp(1), palette.strokeColor)
            }
        }

        val title = TextView(context).apply {
            text = "GitHub Authentication (OAuth / PAT)"
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(palette.fgColor)
        }
        root.addView(title)

        val statusText = TextView(context).apply {
            text = if (settings.githubToken.isNotBlank()) {
                "✓ Connected as @${settings.githubUsername.ifBlank { "GitHub User" }}"
            } else {
                "Sign in via GitHub OAuth Device Flow (1-tap browser code) or paste a Personal Access Token (ghp_... / github_pat_...) with `repo` scope."
            }
            textSize = 12f
            setTextColor(palette.subColor)
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(statusText)

        val deviceFlowBtn = TextView(context).apply {
            text = "🔑 Start GitHub OAuth Device Flow (Browser Code)"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (palette.isDark) Color.parseColor("#1E1200") else Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.accentColor)
            }
        }
        root.addView(deviceFlowBtn)

        val deviceCodeStatus = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.fgColor)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(deviceCodeStatus)

        val patLabel = TextView(context).apply {
            text = "Or Personal Access Token (PAT)"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accentColor)
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(patLabel)

        val patInput = EditText(context).apply {
            setText(settings.githubToken)
            hint = "ghp_xxxxxxxxxxxxxxxxxxxx"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.fgColor)
            setHintTextColor(palette.subColor)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.inputBgColor)
                setStroke(dp(1), palette.strokeColor)
            }
        }
        root.addView(patInput)

        val clientIdLabel = TextView(context).apply {
            text = "Custom GitHub OAuth Client ID (Optional)"
            textSize = 11f
            setTextColor(palette.subColor)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(clientIdLabel)

        val clientIdInput = EditText(context).apply {
            setText(settings.githubOAuthClientId)
            hint = "Default: $DEFAULT_GITHUB_OAUTH_CLIENT_ID"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.fgColor)
            setHintTextColor(palette.subColor)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.inputBgColor)
                setStroke(dp(1), palette.strokeColor)
            }
        }
        root.addView(clientIdInput)

        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(scroll)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        deviceFlowBtn.setOnClickListener {
            val customClient = clientIdInput.text?.toString()?.trim().orEmpty()
            settings.githubOAuthClientId = customClient
            val clientId = customClient.ifEmpty { DEFAULT_GITHUB_OAUTH_CLIENT_ID }
            deviceCodeStatus.text = "Requesting OAuth device code from github.com..."
            startOAuthDeviceFlow(
                context = context,
                clientId = clientId,
                onUserCodeReady = { userCode, verificationUri ->
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText("GitHub Device Code", userCode))
                    deviceCodeStatus.text = "Code copied to clipboard: $userCode\nOpen $verificationUri and paste $userCode.\nWaiting for authorization..."
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri)))
                    }
                },
                onSuccess = { token, username ->
                    settings.githubToken = token
                    settings.githubUsername = username
                    Toast.makeText(context, "Signed in to GitHub as @$username ✓", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    onUpdated()
                },
                onError = { errMsg ->
                    deviceCodeStatus.text = "OAuth Device Flow info: $errMsg"
                }
            )
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        if (settings.githubToken.isNotBlank()) {
            val signOutBtn = TextView(context).apply {
                text = "Sign Out"
                textSize = 12.5f
                setTextColor(Color.parseColor("#E57373"))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    settings.githubToken = ""
                    settings.githubUsername = ""
                    dialog.dismiss()
                    onUpdated()
                }
            }
            btnRow.addView(signOutBtn)
        }

        val cancelBtn = TextView(context).apply {
            text = "Cancel"
            textSize = 12.5f
            setTextColor(palette.subColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(cancelBtn)

        val savePatBtn = TextView(context).apply {
            text = "Verify & Save Token"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accentColor)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                val tok = patInput.text?.toString()?.trim().orEmpty()
                settings.githubOAuthClientId = clientIdInput.text?.toString()?.trim().orEmpty()
                if (tok.isEmpty()) {
                    Toast.makeText(context, "Enter a GitHub token or use OAuth Device Flow above", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                deviceCodeStatus.text = "Verifying token with api.github.com/user..."
                Thread {
                    val user = fetchGitHubUsername(tok)
                    mainHandler.post {
                        settings.githubToken = tok
                        settings.githubUsername = user ?: "GitHubUser"
                        configureGitCredentialsInRootfs(context, tok, settings.githubUsername)
                        Toast.makeText(
                            context,
                            if (user != null) "Verified GitHub account @$user ✓" else "Saved GitHub token",
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                        onUpdated()
                    }
                }.start()
            }
        }
        btnRow.addView(savePatBtn)
        root.addView(btnRow)
        dialog.show()
    }

    private fun startOAuthDeviceFlow(
        context: Context,
        clientId: String,
        onUserCodeReady: (userCode: String, verificationUri: String) -> Unit,
        onSuccess: (token: String, username: String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val url = URL("https://github.com/login/device/code")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                val body = "client_id=${URLEncoder.encode(clientId, "UTF-8")}&scope=${URLEncoder.encode("repo read:user user:email", "UTF-8")}"
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val respText = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val json = JSONObject(respText)
                val deviceCode = json.optString("device_code", "")
                val userCode = json.optString("user_code", "")
                val verificationUri = json.optString("verification_uri", "https://github.com/login/device")
                val intervalSec = json.optInt("interval", 5).coerceAtLeast(5)
                val expiresIn = json.optInt("expires_in", 600)

                if (deviceCode.isEmpty() || userCode.isEmpty()) {
                    mainHandler.post { onError(json.optString("error_description", "Could not start device flow")) }
                    return@Thread
                }

                mainHandler.post { onUserCodeReady(userCode, verificationUri) }

                val deadline = System.currentTimeMillis() + expiresIn * 1000L
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(intervalSec * 1000L)
                    val pollConn = (URL("https://github.com/login/oauth/access_token").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    }
                    val pollBody = "client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                        "&device_code=${URLEncoder.encode(deviceCode, "UTF-8")}" +
                        "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
                    pollConn.outputStream.use { it.write(pollBody.toByteArray(Charsets.UTF_8)) }
                    val pollJson = JSONObject(pollConn.inputStream.bufferedReader(Charsets.UTF_8).readText())
                    val accessToken = pollJson.optString("access_token", "")
                    if (accessToken.isNotEmpty()) {
                        val user = fetchGitHubUsername(accessToken) ?: "GitHubUser"
                        configureGitCredentialsInRootfs(context, accessToken, user)
                        mainHandler.post { onSuccess(accessToken, user) }
                        return@Thread
                    }
                    val err = pollJson.optString("error", "")
                    if (err == "authorization_pending" || err == "slow_down") {
                        continue
                    } else if (err.isNotEmpty()) {
                        mainHandler.post { onError(pollJson.optString("error_description", err)) }
                        return@Thread
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "Network error during OAuth Device Flow") }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun fetchGitHubUsername(token: String): String? {
        return runCatching {
            val conn = (URL("https://api.github.com/user").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "PocketDev-Android-IDE")
            }
            if (conn.responseCode in 200..299) {
                val obj = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
                obj.optString("login", "").takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
    }

    private fun configureGitCredentialsInRootfs(context: Context, token: String, username: String) {
        Thread {
            runCatching {
                val rootfs = UbuntuProotManager.ubuntuRootDir(context)
                val rootHome = File(rootfs, "root").apply { mkdirs() }
                val credFile = File(rootHome, ".git-credentials")
                credFile.writeText("https://${URLEncoder.encode(username.ifBlank { "x-access-token" }, "UTF-8")}:${URLEncoder.encode(token, "UTF-8")}@github.com\n", Charsets.UTF_8)
                val gitConfig = File(rootHome, ".gitconfig")
                gitConfig.writeText(
                    """
                    [user]
                        name = ${username.ifBlank { "PocketDev Developer" }}
                        email = ${username.ifBlank { "dev" }}@users.noreply.github.com
                    [credential]
                        helper = store
                    [init]
                        defaultBranch = main
                    """.trimIndent() + "\n",
                    Charsets.UTF_8
                )
            }
        }.apply { isDaemon = true }.start()
    }

    fun showCloneRepoDialog(
        context: Context,
        onProjectCloned: (File) -> Unit
    ) {
        val settings = Settings(context)
        val palette = ModernUiHelper.resolvePalette(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(palette.bgColor)
                setStroke(dp(1), palette.strokeColor)
            }
        }

        val title = TextView(context).apply {
            text = "Clone GitHub Repository"
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(palette.fgColor)
        }
        root.addView(title)

        val sub = TextView(context).apply {
            text = "Enter `owner/repo` or full HTTPS URL (private repos supported automatically when signed in):"
            textSize = 11.5f
            setTextColor(palette.subColor)
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(sub)

        val urlInput = EditText(context).apply {
            hint = "e.g. octocat/Hello-World or https://github.com/user/repo.git"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.fgColor)
            setHintTextColor(palette.subColor)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.inputBgColor)
                setStroke(dp(1), palette.strokeColor)
            }
        }
        root.addView(urlInput)

        val repoListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(repoListContainer)

        val statusView = TextView(context).apply {
            textSize = 11.5f
            setTextColor(palette.subColor)
            text = if (settings.githubToken.isNotBlank()) "Loading your GitHub repositories..." else "Tip: Sign in to GitHub to list your private & public repositories here."
            setPadding(0, dp(4), 0, dp(6))
        }
        repoListContainer.addView(statusView)

        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(scroll)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (settings.githubToken.isNotBlank()) {
            Thread {
                val repos = fetchUserRepos(settings.githubToken)
                mainHandler.post {
                    repoListContainer.removeAllViews()
                    if (repos.isEmpty()) {
                        statusView.text = "No repositories returned from API — enter URL or owner/repo above."
                        repoListContainer.addView(statusView)
                    } else {
                        val hdr = TextView(context).apply {
                            text = "Your Recent Repositories (tap to fill):"
                            textSize = 11.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(palette.accentColor)
                            setPadding(0, 0, 0, dp(6))
                        }
                        repoListContainer.addView(hdr)
                        for (repo in repos.take(12)) {
                            val item = TextView(context).apply {
                                val badge = if (repo.isPrivate) "🔒 PRIVATE" else "PUBLIC"
                                text = "${repo.fullName}  [$badge]"
                                textSize = 12.5f
                                setTextColor(palette.fgColor)
                                setPadding(dp(10), dp(8), dp(10), dp(8))
                                setOnClickListener {
                                    urlInput.setText(repo.cloneUrl)
                                }
                            }
                            repoListContainer.addView(item)
                        }
                    }
                }
            }.start()
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val cancelBtn = TextView(context).apply {
            text = "Cancel"
            textSize = 12.5f
            setTextColor(palette.subColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(cancelBtn)

        val cloneBtn = TextView(context).apply {
            text = "Clone into Workspace"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (palette.isDark) Color.parseColor("#1E1200") else Color.WHITE)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener {
                val rawSpec = urlInput.text?.toString()?.trim().orEmpty()
                if (rawSpec.isEmpty()) {
                    Toast.makeText(context, "Enter a repository URL or owner/repo", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                statusView.text = "Cloning $rawSpec..."
                isEnabled = false
                cloneRepositoryAsync(context, rawSpec) { clonedDir, message ->
                    isEnabled = true
                    if (clonedDir != null) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        onProjectCloned(clonedDir)
                    } else {
                        statusView.text = "Clone error: $message"
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        btnRow.addView(cloneBtn)
        root.addView(btnRow)
        dialog.show()
    }

    private fun fetchUserRepos(token: String): List<RepoSummary> {
        return runCatching {
            val conn = (URL("https://api.github.com/user/repos?sort=updated&per_page=15").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "PocketDev-Android-IDE")
            }
            if (conn.responseCode !in 200..299) return emptyList()
            val arr = JSONArray(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            val out = ArrayList<RepoSummary>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    RepoSummary(
                        fullName = o.optString("full_name", ""),
                        cloneUrl = o.optString("clone_url", ""),
                        isPrivate = o.optBoolean("private", false),
                        defaultBranch = o.optString("default_branch", "main"),
                        description = o.optString("description", "")
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun cloneRepositoryAsync(
        context: Context,
        rawSpec: String,
        onComplete: (File?, String) -> Unit
    ) {
        Thread {
            try {
                val settings = Settings(context)
                val token = settings.githubToken.trim()
                val normalizedUrl = when {
                    rawSpec.startsWith("http://") || rawSpec.startsWith("https://") -> rawSpec
                    rawSpec.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) -> "https://github.com/$rawSpec.git"
                    else -> rawSpec
                }
                val repoName = normalizedUrl.removeSuffix("/").removeSuffix(".git").substringAfterLast('/')
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "cloned-repo" }
                val projectsRoot = ProjectManager.projectsRootDir(context)
                var destDir = File(projectsRoot, repoName)
                var suffix = 2
                while (destDir.exists() && (destDir.listFiles()?.isNotEmpty() == true)) {
                    destDir = File(projectsRoot, "${repoName}_$suffix")
                    suffix++
                }
                destDir.mkdirs()

                val authedUrl = if (token.isNotEmpty() && normalizedUrl.startsWith("https://github.com/")) {
                    val pathPart = normalizedUrl.removePrefix("https://github.com/")
                    "https://x-access-token:${URLEncoder.encode(token, "UTF-8")}@github.com/$pathPart"
                } else {
                    normalizedUrl
                }

                val guestDest = UbuntuProotManager.hostDirToGuestPath(context, destDir)
                val res = runGitCommand(context, projectsRoot, "clone ${shellSingleQuote(authedUrl)} ${shellSingleQuote(guestDest)}")
                if (res.isSuccess && File(destDir, ".git").exists()) {
                    mainHandler.post { onComplete(destDir, "Cloned $repoName via git CLI ✓") }
                    return@Thread
                }

                val slugMatch = Regex("github\\.com[:/]([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\\.git)?$").find(normalizedUrl)
                if (slugMatch != null) {
                    val slug = slugMatch.groupValues[1]
                    val zipUrl = URL("https://api.github.com/repos/$slug/zipball")
                    val conn = (zipUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("User-Agent", "PocketDev-Android-IDE")
                        if (token.isNotEmpty()) {
                            setRequestProperty("Authorization", "Bearer $token")
                        }
                    }
                    if (conn.responseCode in 200..299) {
                        ZipInputStream(BufferedInputStream(conn.inputStream)).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val stripped = entry.name.substringAfter('/', "")
                                if (stripped.isNotEmpty()) {
                                    val outFile = File(destDir, stripped)
                                    if (entry.isDirectory) {
                                        outFile.mkdirs()
                                    } else {
                                        outFile.parentFile?.mkdirs()
                                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                        mainHandler.post { onComplete(destDir, "Downloaded $slug into ${destDir.name} ✓") }
                        return@Thread
                    }
                }

                mainHandler.post {
                    onComplete(null, res.combinedOutput.ifBlank { "Clone failed — verify URL or run `apt install git` in terminal." })
                }
            } catch (e: Exception) {
                mainHandler.post { onComplete(null, e.message ?: "Clone failed") }
            }
        }.apply { isDaemon = true }.start()
    }

    fun showCommitAndPushDialog(
        context: Context,
        projectDir: File,
        onTreeRefreshRequested: () -> Unit
    ) {
        val palette = ModernUiHelper.resolvePalette(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(palette.bgColor)
                setStroke(dp(1), palette.strokeColor)
            }
        }

        val branch = getCurrentBranchFast(projectDir)
        val title = TextView(context).apply {
            text = "Commit & Push • $branch"
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(palette.fgColor)
        }
        root.addView(title)

        val statusSummary = TextView(context).apply {
            text = "Inspecting modified files..."
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.subColor)
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(statusSummary)

        val msgInput = EditText(context).apply {
            hint = "Commit message (e.g. feat: add LSP stdio & GitHub integration)"
            textSize = 13f
            setTextColor(palette.fgColor)
            setHintTextColor(palette.subColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.inputBgColor)
                setStroke(dp(1), palette.strokeColor)
            }
        }
        root.addView(msgInput)

        Thread {
            val st = runGitCommand(context, projectDir, "status -s")
            mainHandler.post {
                statusSummary.text = st.stdout.trim().ifEmpty { "Working tree clean (no unstaged changes)." }
            }
        }.start()

        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(scroll)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val cancelBtn = TextView(context).apply {
            text = "Cancel"
            textSize = 12.5f
            setTextColor(palette.subColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(cancelBtn)

        val commitOnlyBtn = TextView(context).apply {
            text = "Commit"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.fgColor)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                val msg = msgInput.text?.toString()?.trim().orEmpty()
                if (msg.isEmpty()) {
                    Toast.makeText(context, "Enter a commit message", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Thread {
                    val res = runGitCommand(context, projectDir, "add -A && git commit -m ${shellSingleQuote(msg)}")
                    invalidateStatusCache()
                    mainHandler.post {
                        onTreeRefreshRequested()
                        Toast.makeText(context, res.combinedOutput.ifBlank { "Committed ✓" }, Toast.LENGTH_LONG).show()
                        if (res.isSuccess) dialog.dismiss()
                    }
                }.start()
            }
        }
        btnRow.addView(commitOnlyBtn)

        val commitPushBtn = TextView(context).apply {
            text = "Commit & Push"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (palette.isDark) Color.parseColor("#1E1200") else Color.WHITE)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener {
                val msg = msgInput.text?.toString()?.trim().orEmpty()
                if (msg.isEmpty()) {
                    Toast.makeText(context, "Enter a commit message", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                statusSummary.text = "Staging, committing, and pushing to origin/$branch..."
                Thread {
                    val res = runGitCommand(
                        context,
                        projectDir,
                        "add -A && (git commit -m ${shellSingleQuote(msg)} || true) && git push -u origin $branch"
                    )
                    invalidateStatusCache()
                    mainHandler.post {
                        onTreeRefreshRequested()
                        Toast.makeText(
                            context,
                            if (res.isSuccess) "Committed & pushed to origin/$branch ✓" else res.combinedOutput,
                            Toast.LENGTH_LONG
                        ).show()
                        if (res.isSuccess) dialog.dismiss() else statusSummary.text = res.combinedOutput
                    }
                }.start()
            }
        }
        btnRow.addView(commitPushBtn)
        root.addView(btnRow)
        dialog.show()
    }

    fun showBranchSwitcherDialog(
        context: Context,
        projectDir: File,
        onTreeRefreshRequested: () -> Unit
    ) {
        Thread {
            val res = runGitCommand(context, projectDir, "branch -a")
            val branches = res.stdout.lineSequence()
                .map { it.trim().removePrefix("* ").trim() }
                .filter { it.isNotEmpty() && !it.contains("->") }
                .toList()
                .ifEmpty { listOf(getCurrentBranchFast(projectDir)) }
            val currentBranch = getCurrentBranchFast(projectDir)

            mainHandler.post {
                val options = ArrayList<ModernUiHelper.MenuOption>()
                for ((idx, b) in branches.withIndex()) {
                    val isCurrent = b == currentBranch
                    options.add(
                        ModernUiHelper.MenuOption(
                            id = idx + 1,
                            title = b,
                            subtitle = if (isCurrent) "Active checked-out branch" else "Tap to checkout `$b`",
                            iconRes = R.drawable.ic_git,
                            badgeText = if (isCurrent) "ACTIVE" else null,
                            action = {
                                val targetBranch = b.removePrefix("remotes/origin/")
                                Thread {
                                    val co = runGitCommand(context, projectDir, "checkout ${shellSingleQuote(targetBranch)}")
                                    invalidateStatusCache()
                                    mainHandler.post {
                                        onTreeRefreshRequested()
                                        Toast.makeText(
                                            context,
                                            if (co.isSuccess) "Switched to branch `$targetBranch` ✓" else co.combinedOutput,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }.start()
                            }
                        )
                    )
                }
                options.add(
                    ModernUiHelper.MenuOption(
                        id = 999,
                        title = "Create & Checkout New Branch...",
                        subtitle = "Run `git checkout -b <branch_name>`",
                        iconRes = R.drawable.ic_new_file,
                        action = {
                            ModernUiHelper.showModernInputDialog(
                                context = context,
                                title = "Create New Git Branch",
                                subtitle = "Branch from `$currentBranch`:",
                                hint = "feature/my-branch",
                                confirmText = "Checkout -b"
                            ) { newBranch ->
                                val clean = newBranch.trim().replace(Regex("[^a-zA-Z0-9._/-]"), "-")
                                if (clean.isNotEmpty()) {
                                    Thread {
                                        val co = runGitCommand(context, projectDir, "checkout -b ${shellSingleQuote(clean)}")
                                        invalidateStatusCache()
                                        mainHandler.post {
                                            onTreeRefreshRequested()
                                            Toast.makeText(
                                                context,
                                                if (co.isSuccess) "Created & switched to `$clean` ✓" else co.combinedOutput,
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }.start()
                                }
                            }
                        }
                    )
                )

                ModernUiHelper.showModernContextMenu(
                    context = context,
                    title = "Git Branches",
                    subtitle = "Current branch: $currentBranch",
                    headerIconRes = R.drawable.ic_git,
                    options = options
                )
            }
        }.start()
    }

    fun showDiffViewerDialog(
        context: Context,
        projectDir: File,
        targetFile: File? = null
    ) {
        Thread {
            val fileArg = if (targetFile != null) {
                val rel = targetFile.absolutePath.removePrefix(projectDir.absolutePath).trimStart('/')
                " -- ${shellSingleQuote(rel)}"
            } else ""
            val diffRes = runGitCommand(context, projectDir, "diff HEAD$fileArg")
            val rawDiff = if (diffRes.stdout.isNotBlank()) {
                diffRes.stdout
            } else {
                val statusRes = runGitCommand(context, projectDir, "status -s$fileArg")
                if (statusRes.stdout.isNotBlank()) {
                    "No unstaged tracked diffs against HEAD, but status reports:\n\n${statusRes.stdout}"
                } else {
                    "Working tree matches HEAD (no uncommitted changes)."
                }
            }

            mainHandler.post {
                val palette = ModernUiHelper.resolvePalette(context)
                val density = context.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).roundToInt()

                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(16), dp(18), dp(14))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(palette.bgColor)
                        setStroke(dp(1), palette.strokeColor)
                    }
                }

                val title = TextView(context).apply {
                    text = if (targetFile != null) "Git Diff • ${targetFile.name}" else "Git Diff • ${projectDir.name}"
                    textSize = 15.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(palette.fgColor)
                    setPadding(0, 0, 0, dp(8))
                }
                root.addView(title)

                val colored = SpannableStringBuilder()
                for (line in rawDiff.lineSequence()) {
                    val start = colored.length
                    colored.append(line).append("\n")
                    val end = colored.length
                    val color = when {
                        line.startsWith("+++") || line.startsWith("---") -> Color.parseColor("#FFB74D")
                        line.startsWith("+") -> Color.parseColor("#81C784")
                        line.startsWith("-") -> Color.parseColor("#E57373")
                        line.startsWith("@@") -> Color.parseColor("#4FC3F7")
                        else -> palette.fgColor
                    }
                    colored.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                val diffTextView = TextView(context).apply {
                    text = colored
                    textSize = 11.5f
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }

                val hScroll = HorizontalScrollView(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(palette.cardColor)
                        setStroke(dp(1), palette.strokeColor)
                    }
                    addView(diffTextView)
                }
                val vScroll = ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(360)
                    )
                    addView(hScroll)
                }
                root.addView(vScroll)

                val dialog = MaterialAlertDialogBuilder(context)
                    .setView(root)
                    .setPositiveButton("Close", null)
                    .create()
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog.show()
            }
        }.start()
    }
}
