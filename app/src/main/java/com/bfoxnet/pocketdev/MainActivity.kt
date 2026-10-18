package com.bfoxnet.pocketdev

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bfoxnet.pocketdev.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textview.MaterialTextView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.text.ContentIO
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.subscribeAlways
import java.io.File
import kotlin.math.roundToInt

/**
 * Main Editor Activity for PocketDev.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editorController: EditorController
    private lateinit var settings: Settings

    private val openTabs = ArrayList<OpenedFile>()
    private var activeTabIndex: Int = -1
    private var suppressModifiedEvent: Boolean = false
    private var suppressFileTabCallback: Boolean = false

    private val expandedFolderKeys = HashSet<String>()
    private var treeFilterQuery: String = ""
    private var activeSidebarTab: Int = SIDEBAR_TAB_FILES
    private var suppressSidebarTabCallback: Boolean = false
    private var lastTreeSignature: Long = 0L
    private val treeWatcherHandler = Handler(Looper.getMainLooper())
    private val treeWatcherRunnable = object : Runnable {
        override fun run() {
            if (binding.mainDrawerLayout.isDrawerOpen(GravityCompat.START) && activeSidebarTab == SIDEBAR_TAB_FILES) {
                val currentSig = ProjectManager.computeTreeSignature(this@MainActivity)
                if (currentSig != lastTreeSignature) {
                    lastTreeSignature = currentSig
                    refreshFileTree()
                }
                treeWatcherHandler.postDelayed(this, 2000L)
            }
        }
    }

    private var isEditDockMode: Boolean = false
    private var dockHiddenManually: Boolean = false

    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private val autoSaveRunnable = Runnable {
        if (settings.autoSaveEnabled) {
            performAutoSaveModifiedTabs()
        }
    }

    private var searchCaseSensitive = false
    private var searchRegex = false
    private var isProjectSearchMode = false
    private var isReplaceRowExpanded = false

    private var activeBottomTab: Int = TAB_CODE_ANALYSIS
    private var bottomPanelExpanded: Boolean = false
    private var bottomPanelTallMode: Boolean = false
    private var bottomPanelCustomHeightPx: Int = 0
    private var suppressBottomTabCallback: Boolean = false
    private var latestDiagnostics: List<LspManager.LspDiagnostic> = emptyList()
    private var lastLspAnalyzedFileKey: String = ""
    private var lastLspAnalyzedText: String = ""

    private val currentFile: OpenedFile?
        get() = openTabs.getOrNull(activeTabIndex)

    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importSingleFileIntoAppProject(it) }
        }

    private val importFolderToAppDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                Toast.makeText(this, "Importing folder…", Toast.LENGTH_SHORT).show()
                Thread {
                    val importedDir = ProjectManager.importSafTreeIntoAppDir(this, treeUri)
                    runOnUiThread {
                        if (importedDir != null) {
                            switchActiveAppProject(importedDir)
                            Toast.makeText(
                                this,
                                "Imported ${importedDir.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }

    private val saveAsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { target ->
                val tab = currentFile ?: return@let
                tab.uri = target
                tab.displayName = target.queryDisplayName(contentResolver)
                writeTabToStorage(tab)
                editorController.setLanguageFor(tab.displayName)
                renderTabs()
                runLspAnalysisForCurrentFile()
                updateStatusLine()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModernUiHelper.enableHighRefreshRate(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        TerminalSession.provisionShellEnvironment(this)
        LspManager.syncTerminalInstallations(this)

        setupInsets()

        editorController = EditorController(this, binding.editor)
        editorController.setup()
        binding.editorFoldGutter.attachToEditor(binding.editor, editorController)
        binding.editorFoldGutter.onFoldStateChanged = { _, _ ->
            updateStickyScrollHeader()
            updateStatusLine()
        }

        setupAppBar()
        setupEmptyStateActions()
        setupKeyboardDock()
        setupSearchBar()
        setupProjectSidebar()
        setupBottomSheetPanel()
        setupEditorEvents()
        applyThemeChrome()

        LspManager.onStdioDiagnosticsPublished = { fileName, _ ->
            if (currentFile?.displayName == fileName) {
                runLspAnalysisForCurrentFile(force = true, notifyStdioServer = false)
            }
        }

        val widgetProjPath = intent?.getStringExtra(ContinueCodingWidgetProvider.EXTRA_WIDGET_PROJECT_PATH)
        val widgetFilePath = intent?.getStringExtra(ContinueCodingWidgetProvider.EXTRA_WIDGET_FILE_PATH)
        if (!widgetProjPath.isNullOrBlank()) {
            val projDir = File(widgetProjPath)
            if (projDir.exists() && projDir.isDirectory) {
                ProjectManager.switchToAppDirProject(this, projDir)
            }
        }

        val incoming: Uri? = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (incoming != null) {
            importSingleFileIntoAppProject(incoming)
        } else {
            openInitialWorkspaceTabs()
            if (!widgetFilePath.isNullOrBlank()) {
                val wf = File(widgetFilePath)
                if (wf.exists() && wf.isFile) {
                    openLocalFileInTab(wf, switchImmediately = true)
                }
            }
        }
        refreshFileTree()

        if (!settings.onboardingCompleted) {
            binding.editor.postDelayed({
                if (!isFinishing && !settings.onboardingCompleted) {
                    runCatching {
                        OnboardingAndHapticsHelper.showOnboardingDialog(
                            context = this,
                            onBootstrapNow = {
                                openTerminalInDirectory(
                                    targetDir = ProjectManager.ensureDefaultLocalWorkspace(this),
                                    autorunCommand = "ubuntu-bootstrap"
                                )
                            },
                            onOpenLspManager = {
                                startActivity(Intent(this, LspActivity::class.java))
                            },
                            onThemeChanged = {
                                editorController.applyEditorTheme(settings.editorTheme)
                                editorController.applyFontSettings()
                                applyThemeChrome()
                            }
                        )
                    }
                }
            }, 350L)
        }
    }

    private fun openInitialWorkspaceTabs() {
        val workspaceRoot = ProjectManager.ensureDefaultLocalWorkspace(this)
        val mainKt = File(workspaceRoot, "src/Main.kt")
        val readmeMd = File(workspaceRoot, "README.md")
        if (mainKt.exists()) {
            openLocalFileInTab(mainKt)
        }
        if (readmeMd.exists()) {
            openLocalFileInTab(readmeMd, switchImmediately = openTabs.isEmpty())
        }
        if (openTabs.isEmpty()) {
            val firstFile = ProjectManager.collectAllProjectFiles(this, maxFiles = 10).firstOrNull()?.localFile
            if (firstFile != null) {
                openLocalFileInTab(firstFile)
            } else {
                createNewUntitledTab()
            }
        }
    }

    private fun importSingleFileIntoAppProject(uri: Uri) {
        try {
            val name = uri.queryDisplayName(contentResolver)
            val workspaceRoot = ProjectManager.ensureDefaultLocalWorkspace(this)
            val targetFile = File(workspaceRoot, name)
            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            ProjectManager.ensureProjectFilesExecutable(workspaceRoot)
            refreshFileTree()
            openLocalFileInTab(targetFile, switchImmediately = true)
        } catch (e: Exception) {
            openUriInTab(uri)
        }
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.mainDrawerLayout) { _, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            val imeBars = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPadding = maxOf(sysBars.bottom, imeBars.bottom)

            binding.editorContentRoot.setPadding(
                sysBars.left,
                sysBars.top,
                sysBars.right,
                bottomPadding
            )
            binding.projectSidebar.setPadding(
                sysBars.left,
                sysBars.top,
                0,
                bottomPadding
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupAppBar() {
        updateToolbarProjectTitle()
        TooltipCompat.setTooltipText(binding.btnNewTab, "New File")

        binding.editorToolbar.setNavigationOnClickListener {
            if (binding.mainDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.mainDrawerLayout.closeDrawer(GravityCompat.START)
            } else {
                openProjectSidebar()
            }
        }

        binding.editorToolbar.setOnClickListener {
            showAppDirProjectManagerDialog()
        }

        binding.editorToolbar.setOnMenuItemClickListener { item ->
            handleMenuItemClick(item)
        }

        binding.btnNewTab.setOnClickListener {
            createNewUntitledTab()
        }

        binding.fileTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (suppressFileTabCallback) return
                val idx = tab?.position ?: return
                if (idx in openTabs.indices && idx != activeTabIndex) {
                    switchToTab(idx)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabReselected(tab: TabLayout.Tab?) {
                if (suppressFileTabCallback) return
                val idx = tab?.position ?: return
                if (idx in openTabs.indices) {
                    showTabContextMenu(idx)
                }
            }
        })
    }

    private fun updateToolbarProjectTitle() {
        val activeDir = ProjectManager.ensureDefaultLocalWorkspace(this)
        val projName = activeDir.name
        val branchInfo = if (GitHubManager.isGitRepo(activeDir)) {
            "⎇ ${GitHubManager.getCurrentBranchFast(activeDir)}"
        } else {
            ProjectManager.guestPathForDir(this, activeDir)
        }
        binding.editorToolbar.title = "$projName ▾"
        binding.editorToolbar.subtitle = branchInfo
    }

    private fun setupEmptyStateActions() {
        binding.btnEmptyNewFile.setOnClickListener {
            createNewUntitledTab()
        }
        binding.btnEmptyOpenTree.setOnClickListener {
            showOpenProjectDialog()
        }
        binding.btnEmptyCloneRepo.setOnClickListener {
            GitHubManager.showCloneRepoDialog(this) { clonedDir ->
                switchActiveAppProject(clonedDir)
            }
        }
    }

    private fun updateEmptyStateVisibility() {
        val hasTabs = openTabs.isNotEmpty()
        if (hasTabs) {
            ModernUiHelper.animateCrossFade(binding.editor, binding.editorEmptyState, 180L)
        } else {
            ModernUiHelper.animateCrossFade(binding.editorEmptyState, binding.editor, 180L)
            binding.stickyScrollBar.visibility = View.GONE
        }
    }

    private fun handleMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_run -> {
                compileAndRunCurrentFile()
                true
            }
            R.id.action_run_config -> {
                showRunConfigForCurrentFile()
                true
            }
            R.id.action_format_document -> {
                formatCurrentDocument()
                true
            }
            R.id.action_lsp_refactor -> {
                showLspCodeActionsDialog()
                true
            }
            R.id.action_github -> {
                showGitHubIntegrationDialog()
                true
            }
            R.id.action_undo -> {
                binding.editor.undo()
                true
            }
            R.id.action_redo -> {
                binding.editor.redo()
                true
            }
            R.id.action_save -> {
                saveDocument()
                true
            }
            R.id.action_terminal -> {
                openTerminalInDirectory(ProjectManager.ensureDefaultLocalWorkspace(this))
                true
            }
            R.id.action_search -> {
                toggleFindBar()
                true
            }
            R.id.action_project_search -> {
                showFindBar(projectScope = true)
                true
            }
            R.id.action_open -> {
                openFileLauncher.launch(arrayOf("*/*"))
                true
            }
            R.id.action_open_folder -> {
                showOpenProjectDialog()
                true
            }
            R.id.action_theme -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBottomSheetPanel() {
        TooltipCompat.setTooltipText(binding.btnTabDiagnostics, "Problems")
        TooltipCompat.setTooltipText(binding.btnTabSearchResults, "Search")
        TooltipCompat.setTooltipText(binding.btnTabBuildOutput, "Output")
        TooltipCompat.setTooltipText(binding.btnToggleBottomPanel, "Toggle Panel")
        TooltipCompat.setTooltipText(binding.btnBottomSheetStop, "Stop Process")
        TooltipCompat.setTooltipText(binding.btnBottomSheetClear, "Clear")
        TooltipCompat.setTooltipText(binding.btnBottomSheetExpand, "Resize Panel")
        TooltipCompat.setTooltipText(binding.btnBottomSheetClose, "Close Panel")

        val tabTitles = listOf(
            getString(R.string.tab_code_analysis),
            getString(R.string.tab_search_results),
            getString(R.string.tab_build_output)
        )
        suppressBottomTabCallback = true
        binding.bottomSheetTabs.removeAllTabs()
        for (title in tabTitles) {
            binding.bottomSheetTabs.addTab(binding.bottomSheetTabs.newTab().setText(title))
        }
        binding.bottomSheetTabs.getTabAt(activeBottomTab)?.select()
        suppressBottomTabCallback = false

        binding.bottomSheetTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (suppressBottomTabCallback) return
                val idx = tab?.position ?: return
                selectBottomPanelTab(idx, expand = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        val density = resources.displayMetrics.density
        var dragStartRawY = 0f
        var dragStartHeightPx = 0
        binding.bottomSheetDragHandleArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawY = event.rawY
                    dragStartHeightPx = binding.bottomPanelContainer.height
                        .takeIf { it > 0 } ?: (200 * density).roundToInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (dragStartRawY - event.rawY).roundToInt()
                    val minHeightPx = (110 * density).roundToInt()
                    val maxHeightPx = (resources.displayMetrics.heightPixels * 0.72f).roundToInt()
                    val newHeightPx = (dragStartHeightPx + deltaY).coerceIn(minHeightPx, maxHeightPx)
                    bottomPanelCustomHeightPx = newHeightPx
                    binding.bottomPanelContainer.layoutParams =
                        binding.bottomPanelContainer.layoutParams.apply {
                            height = newHeightPx
                        }
                    binding.bottomPanelContainer.requestLayout()
                    true
                }
                else -> false
            }
        }

        val toggleDiagTab = View.OnClickListener {
            if (bottomPanelExpanded && activeBottomTab == TAB_CODE_ANALYSIS) {
                bottomPanelExpanded = false
                updateBottomPanelVisibility()
            } else {
                selectBottomPanelTab(TAB_CODE_ANALYSIS, expand = true)
            }
        }
        binding.btnTabDiagnostics.setOnClickListener(toggleDiagTab)
        binding.diagnosticsCountBadge.setOnClickListener(toggleDiagTab)
        binding.btnTabSearchResults.setOnClickListener {
            if (bottomPanelExpanded && activeBottomTab == TAB_SEARCH_RESULTS) {
                bottomPanelExpanded = false
                updateBottomPanelVisibility()
            } else {
                selectBottomPanelTab(TAB_SEARCH_RESULTS, expand = true)
            }
        }
        binding.btnTabBuildOutput.setOnClickListener {
            if (bottomPanelExpanded && activeBottomTab == TAB_BUILD_OUTPUT) {
                bottomPanelExpanded = false
                updateBottomPanelVisibility()
            } else {
                selectBottomPanelTab(TAB_BUILD_OUTPUT, expand = true)
            }
        }
        binding.btnToggleBottomPanel.setOnClickListener {
            bottomPanelExpanded = !bottomPanelExpanded
            updateBottomPanelVisibility()
        }
        binding.btnBottomSheetStop.setOnClickListener {
            if (BuildRunManager.isRunning()) {
                BuildRunManager.stopCurrentBuild()
                appendBuildOutputLine("■ Process stopped", isStatusSummary = true, isError = true)
                val baseTitle = getString(R.string.tab_build_output)
                binding.bottomSheetTabs.getTabAt(TAB_BUILD_OUTPUT)?.text = "$baseTitle (Stopped)"
                binding.btnBottomSheetStop.visibility = View.GONE
            }
        }
        binding.btnBottomSheetClear.setOnClickListener {
            clearActiveBottomSheetTab()
        }
        binding.btnBottomSheetExpand.setOnClickListener {
            bottomPanelTallMode = !bottomPanelTallMode
            val targetHeightDp = if (bottomPanelTallMode) 330 else 200
            bottomPanelCustomHeightPx = (targetHeightDp * density).roundToInt()
            ModernUiHelper.animateViewHeight(binding.bottomPanelContainer, bottomPanelCustomHeightPx, 220L)
        }
        binding.btnBottomSheetClose.setOnClickListener {
            bottomPanelExpanded = false
            updateBottomPanelVisibility()
        }

        val sendStdinAction = {
            val rawLine = binding.buildStdinInput.text?.toString() ?: ""
            if (BuildRunManager.sendStdinToCurrentBuild(rawLine)) {
                appendBuildOutputLine("› $rawLine")
                binding.buildStdinInput.text = null
            } else {
                Toast.makeText(this, "No active process waiting for input", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnBuildStdinSend.setOnClickListener { sendStdinAction() }
        binding.buildStdinInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                sendStdinAction()
                true
            } else {
                false
            }
        }

        binding.lspStatusBadge.setOnClickListener {
            val fileName = currentFile?.displayName ?: ""
            val activeServer = LspManager.activeServerForFile(this, fileName)
            if (activeServer != null) {
                selectBottomPanelTab(
                    TAB_CODE_ANALYSIS,
                    expand = !bottomPanelExpanded || activeBottomTab != TAB_CODE_ANALYSIS
                )
            } else {
                val spec = LspManager.serverSpecForFile(fileName)
                if (spec != null) {
                    promptInstallLspViaTerminal(spec)
                } else {
                    startActivity(Intent(this, LspActivity::class.java))
                }
            }
        }
    }

    private fun clearActiveBottomSheetTab() {
        when (activeBottomTab) {
            TAB_SEARCH_RESULTS -> {
                binding.projectSearchResultsContainer.removeAllViews()
                binding.bottomSheetTabs.getTabAt(TAB_SEARCH_RESULTS)?.text = getString(R.string.tab_search_results)
            }
            TAB_CODE_ANALYSIS -> {
                runLspAnalysisForCurrentFile()
            }
            TAB_BUILD_OUTPUT -> {
                BuildRunManager.stopCurrentBuild()
                binding.btnBottomSheetStop.visibility = View.GONE
                binding.buildOutputContainer.removeAllViews()
                binding.bottomSheetTabs.getTabAt(TAB_BUILD_OUTPUT)?.text = getString(R.string.tab_build_output)
            }
        }
    }

    private fun promptInstallLspViaTerminal(spec: LspManager.LspServerSpec) {
        ModernUiHelper.showModernContextMenu(
            context = this,
            title = "Install ${spec.name}?",
            subtitle = "Packages: ${spec.alpinePackages.joinToString(", ")}",
            headerIconRes = R.drawable.ic_lsp,
            options = listOf(
                ModernUiHelper.MenuOption(
                    id = 0,
                    title = "Install Language Server",
                    subtitle = "Install ${spec.id} in terminal",
                    iconRes = R.drawable.ic_terminal,
                    action = {
                        openTerminalInDirectory(
                            targetDir = ProjectManager.ensureDefaultLocalWorkspace(this),
                            autorunCommand = "lsp-install ${spec.id}"
                        )
                    }
                ),
                ModernUiHelper.MenuOption(
                    id = 1,
                    title = "Language Servers",
                    subtitle = "Manage all language servers",
                    iconRes = R.drawable.ic_lsp,
                    action = {
                        startActivity(Intent(this, LspActivity::class.java))
                    }
                )
            )
        )
    }

    private fun selectBottomPanelTab(tabIndex: Int, expand: Boolean) {
        activeBottomTab = tabIndex.coerceIn(0, 2)
        if (expand) bottomPanelExpanded = true
        updateBottomPanelVisibility()
    }

    private fun updateBottomPanelVisibility() {
        ModernUiHelper.animatePanelVisibility(binding.bottomPanelContainer, bottomPanelExpanded, slideOffsetDp = 16f, durationMs = 210L)
        binding.btnToggleBottomPanel.animate()
            .rotation(if (bottomPanelExpanded) 180f else 0f)
            .setDuration(200L)
            .setInterpolator(ModernUiHelper.SPRING_INTERPOLATOR)
            .start()

        suppressBottomTabCallback = true
        binding.bottomSheetTabs.getTabAt(activeBottomTab)?.let { tab ->
            if (!tab.isSelected) tab.select()
        }
        suppressBottomTabCallback = false

        binding.lspDiagnosticsScroll.visibility = if (activeBottomTab == TAB_CODE_ANALYSIS) View.VISIBLE else View.GONE
        binding.projectSearchResultsScroll.visibility = if (activeBottomTab == TAB_SEARCH_RESULTS) View.VISIBLE else View.GONE
        binding.buildOutputPanel.visibility = if (activeBottomTab == TAB_BUILD_OUTPUT) View.VISIBLE else View.GONE

        val fgColor = editorController.currentForegroundColor()
        val subtleColor = editorController.currentSubtleColor()
        val accentColor = ModernUiHelper.PRIMARY_COLOR

        binding.btnTabDiagnostics.imageTintList = ColorStateList.valueOf(
            if (bottomPanelExpanded && activeBottomTab == TAB_CODE_ANALYSIS) accentColor else subtleColor
        )
        binding.btnTabSearchResults.imageTintList = ColorStateList.valueOf(
            if (bottomPanelExpanded && activeBottomTab == TAB_SEARCH_RESULTS) accentColor else subtleColor
        )
        binding.btnTabBuildOutput.imageTintList = ColorStateList.valueOf(
            if (bottomPanelExpanded && activeBottomTab == TAB_BUILD_OUTPUT) accentColor else subtleColor
        )
        binding.btnToggleBottomPanel.imageTintList = ColorStateList.valueOf(fgColor)
    }

    private fun applyThemeChrome() {
        val chromeBg = editorController.currentChromeColor()
        val surfaceBg = editorController.currentSurfaceColor()
        val symbolBg = editorController.currentSymbolBarColor()
        val fgColor = editorController.currentForegroundColor()
        val subtleColor = editorController.currentSubtleColor()
        val isDark = editorController.isCurrentThemeDark()
        val accentColor = ModernUiHelper.PRIMARY_COLOR
        val iconTint = ColorStateList.valueOf(fgColor)
        val subtleTint = ColorStateList.valueOf(subtleColor)
        val density = resources.displayMetrics.density

        binding.editorContentRoot.setBackgroundColor(chromeBg)

        binding.editorAppbar.setBackgroundColor(chromeBg)
        binding.editorToolbar.setBackgroundColor(chromeBg)
        binding.editorToolbar.setTitleTextColor(fgColor)
        binding.editorToolbar.setSubtitleTextColor(subtleColor)
        binding.editorToolbar.navigationIcon?.setTint(fgColor)
        binding.editorToolbar.overflowIcon?.setTint(fgColor)
        val menu = binding.editorToolbar.menu
        for (i in 0 until menu.size()) {
            menu.getItem(i)?.icon?.setTint(fgColor)
        }

        binding.fileTabBarRow.setBackgroundColor(chromeBg)
        binding.fileTabLayout.setBackgroundColor(chromeBg)
        binding.fileTabLayout.setSelectedTabIndicatorColor(accentColor)
        binding.fileTabLayout.setTabTextColors(subtleColor, fgColor)
        binding.btnNewTab.imageTintList = iconTint

        binding.editorEmptyState.setBackgroundColor(surfaceBg)
        binding.emptyStateIcon.imageTintList = ColorStateList.valueOf(accentColor)
        binding.emptyStateTitle.setTextColor(fgColor)
        binding.emptyStateSubtitle.setTextColor(subtleColor)

        binding.findBar.setBackgroundColor(chromeBg)
        binding.searchInput.setTextColor(fgColor)
        binding.searchInput.setHintTextColor(subtleColor)
        binding.replaceInput.setTextColor(fgColor)
        binding.replaceInput.setHintTextColor(subtleColor)
        binding.searchStatus.setTextColor(subtleColor)
        binding.btnToggleReplaceRow.imageTintList = iconTint
        binding.btnSearchClose.imageTintList = iconTint
        binding.btnSearchPrev.imageTintList = iconTint
        binding.btnSearchNext.imageTintList = iconTint
        updateSearchFilterButtonPills()

        binding.projectSidebar.setBackgroundColor(chromeBg)
        binding.sidebarHeader.setBackgroundColor(chromeBg)
        binding.projectTitleText.setTextColor(fgColor)
        binding.projectModeSubtitle.setTextColor(subtleColor)
        binding.sidebarModeTabs.setBackgroundColor(chromeBg)
        binding.sidebarModeTabs.setSelectedTabIndicatorColor(accentColor)
        binding.sidebarModeTabs.setTabTextColors(subtleColor, fgColor)
        binding.treeFilterInput.setTextColor(fgColor)
        binding.treeFilterInput.setHintTextColor(subtleColor)
        binding.btnSelectProjectFolder.imageTintList = iconTint
        binding.btnLocalWorkspace.imageTintList = iconTint
        binding.btnTreeNewFile.imageTintList = iconTint
        binding.btnTreeRefresh.imageTintList = iconTint

        binding.bottomPanelContainer.setBackgroundColor(surfaceBg)
        binding.bottomSheetDragHandleArea.setBackgroundColor(chromeBg)
        binding.bottomSheetDragPill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 3f * density
            setColor(ModernUiHelper.withAlpha(subtleColor, 0x80))
        }
        binding.bottomSheetHeader.setBackgroundColor(chromeBg)
        binding.bottomSheetTabs.setBackgroundColor(chromeBg)
        binding.bottomSheetTabs.setSelectedTabIndicatorColor(accentColor)
        binding.bottomSheetTabs.setTabTextColors(subtleColor, fgColor)
        binding.btnBottomSheetStop.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
        binding.btnBottomSheetClear.imageTintList = subtleTint
        binding.btnBottomSheetExpand.imageTintList = subtleTint
        binding.btnBottomSheetClose.imageTintList = iconTint
        binding.buildStdinRow.setBackgroundColor(chromeBg)
        binding.buildStdinInput.setTextColor(fgColor)
        binding.buildStdinInput.setHintTextColor(subtleColor)
        binding.btnBuildStdinSend.imageTintList = ColorStateList.valueOf(accentColor)

        binding.stickyScrollBar.setBackgroundColor(chromeBg)
        binding.stickyScrollText.setTextColor(fgColor)
        binding.stickyScrollLineBadge.setTextColor(accentColor)
        binding.editor.setBackgroundColor(editorController.currentEditorBackgroundColor())
        ModernUiHelper.installUiFontInterceptor(
            this,
            binding.mainDrawerLayout,
            skipViewIds = setOf(binding.editor.id, binding.stickyScrollText.id, binding.buildStdinInput.id)
        )
        binding.statusBar.setBackgroundColor(chromeBg)
        binding.positionText.setTextColor(subtleColor)
        binding.encodingStatusText.setTextColor(subtleColor)
        binding.languageStatusText.setTextColor(subtleColor)

        binding.keyboardDockContainer.setBackgroundColor(symbolBg)
        binding.symbolInput.setBackgroundColor(symbolBg)
        binding.symbolInput.setTextColor(fgColor)
        binding.quickActionBar.setBackgroundColor(symbolBg)
        styleDockModeButton()

        renderTabs()
        setupQuickActionRow()
        updateBottomPanelVisibility()
        updateToolbarProjectTitle()

        window?.let { win ->
            win.statusBarColor = chromeBg
            win.navigationBarColor = chromeBg
            val controller = WindowCompat.getInsetsController(win, binding.mainDrawerLayout)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    override fun onResume() {
        super.onResume()
        LspManager.syncTerminalInstallations(this)
        editorController.applyEditorTheme(settings.editorTheme)
        editorController.applyFontSettings()
        editorController.editor.setWordwrap(settings.wordWrap)
        binding.editorFoldGutter.refreshFoldableLines(force = true)
        applyThemeChrome()
        refreshFileTree()
        runLspAnalysisForCurrentFile()
        updateStatusLine()
    }

    override fun onPause() {
        treeWatcherHandler.removeCallbacks(treeWatcherRunnable)
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        if (settings.autoSaveEnabled) {
            performAutoSaveModifiedTabs()
        }
        super.onPause()
    }

    private fun openTerminalInDirectory(
        targetDir: File = ProjectManager.ensureDefaultLocalWorkspace(this),
        autorunCommand: String? = null
    ) {
        val dir = if (targetDir.exists() && targetDir.isDirectory) {
            targetDir
        } else {
            targetDir.parentFile ?: ProjectManager.ensureDefaultLocalWorkspace(this)
        }
        startActivity(
            Intent(this, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_WORKING_DIR, dir.absolutePath)
                if (!autorunCommand.isNullOrBlank()) {
                    putExtra(TerminalActivity.EXTRA_AUTORUN_CMD, autorunCommand)
                }
            }
        )
    }

    private fun setupKeyboardDock() {
        val displays = arrayOf(
            "TAB", "{", "}", "(", ")", "[", "]", ";", "=", "\"", "'", "<", ">", "/",
            "|", "\\", ":", "+", "-", "*", "%", "!", "#", "&", "$", "@", "~", "^", ",", "."
        )
        val inserts = arrayOf(
            "    ", "{", "}", "(", ")", "[", "]", ";", "=", "\"", "'", "<", ">", "/",
            "|", "\\", ":", "+", "-", "*", "%", "!", "#", "&", "$", "@", "~", "^", ",", "."
        )
        val symbols = binding.symbolInput
        symbols.addSymbols(displays, inserts)
        symbols.bindEditor(binding.editor)
        symbols.setTextColor(editorController.currentForegroundColor())

        TooltipCompat.setTooltipText(
            binding.btnToggleDockMode,
            "Switch Symbols / Quick Actions"
        )
        binding.btnToggleDockMode.setOnClickListener { v ->
            OnboardingAndHapticsHelper.performExtraKeyHaptic(this, v)
            isEditDockMode = !isEditDockMode
            updateKeyboardDockModeUi()
        }

        setupQuickActionRow()
        updateKeyboardDockModeUi()
        binding.keyboardDockContainer.visibility = if (dockHiddenManually) View.GONE else View.VISIBLE
    }

    private fun updateKeyboardDockModeUi() {
        if (isEditDockMode) {
            ModernUiHelper.animateCrossFade(binding.quickActionBar, binding.symbolInput, 170L)
        } else {
            ModernUiHelper.animateCrossFade(binding.symbolInput, binding.quickActionBar, 170L)
        }
        binding.btnToggleDockMode.text = if (isEditDockMode) "EDIT ⇄" else "SYM ⇄"
        styleDockModeButton()
    }

    private fun styleDockModeButton() {
        val accentColor = ModernUiHelper.PRIMARY_COLOR
        val density = resources.displayMetrics.density
        binding.btnToggleDockMode.setTextColor(accentColor)
        binding.btnToggleDockMode.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(ModernUiHelper.withAlpha(accentColor, 0x20))
            setStroke((1 * density).toInt().coerceAtLeast(1), ModernUiHelper.withAlpha(accentColor, 0x66))
        }
    }

    private fun setupQuickActionRow() {
        val container = binding.quickActionContainer
        container.removeAllViews()

        val fgColor = editorController.currentForegroundColor()
        val iconTint = ColorStateList.valueOf(fgColor)
        val density = resources.displayMetrics.density

        data class QuickToolIcon(
            val iconRes: Int,
            val tooltip: String,
            val action: () -> Unit
        )

        val tools = listOf(
            QuickToolIcon(R.drawable.ic_lsp, "Code Actions") { showLspCodeActionsDialog() },
            QuickToolIcon(R.drawable.ic_format_align, "Format") { formatCurrentDocument() },
            QuickToolIcon(R.drawable.ic_comment, "Toggle Comment") {
                editorController.toggleLineComment(currentFile?.displayName ?: "")
            },
            QuickToolIcon(R.drawable.ic_indent, "Indent") { editorController.indentLineOrSelection() },
            QuickToolIcon(R.drawable.ic_outdent, "Outdent") { editorController.outdentLineOrSelection() },
            QuickToolIcon(R.drawable.ic_move_up, "Move Line Up") { editorController.moveLineUp() },
            QuickToolIcon(R.drawable.ic_move_down, "Move Line Down") { editorController.moveLineDown() },
            QuickToolIcon(R.drawable.ic_copy_up, "Duplicate Line Up") { editorController.copyLineUp() },
            QuickToolIcon(R.drawable.ic_copy_down, "Duplicate Line Down") { editorController.copyLineDown() },
            QuickToolIcon(R.drawable.ic_delete, "Delete Line") { editorController.deleteCurrentLine() },
            QuickToolIcon(R.drawable.ic_undo, "Undo") { binding.editor.undo() },
            QuickToolIcon(R.drawable.ic_redo, "Redo") { binding.editor.redo() }
        )

        for (tool in tools) {
            val btn = ImageButton(this, null, 0, R.style.PocketDev_IconButton).apply {
                setImageResource(tool.iconRes)
                imageTintList = iconTint
                contentDescription = tool.tooltip
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    (8 * density).toInt(),
                    (8 * density).toInt(),
                    (8 * density).toInt(),
                    (8 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    (38 * density).toInt(),
                    (36 * density).toInt()
                ).apply {
                    marginEnd = (4 * density).toInt()
                }
                setOnClickListener { v ->
                    OnboardingAndHapticsHelper.performExtraKeyHaptic(this@MainActivity, v)
                    tool.action()
                }
            }
            TooltipCompat.setTooltipText(btn, tool.tooltip)
            container.addView(btn)
        }
    }

    private fun runLspAnalysisForCurrentFile(
        force: Boolean = false,
        notifyStdioServer: Boolean = true
    ) {
        val fileName = currentFile?.displayName ?: "untitled.txt"
        val fileKey = currentFile?.localFile?.absolutePath ?: fileName
        val text = binding.editor.text.toString()

        if (!force && fileKey == lastLspAnalyzedFileKey && text == lastLspAnalyzedText) {
            return
        }
        lastLspAnalyzedFileKey = fileKey
        lastLspAnalyzedText = text

        val activeServer = LspManager.activeServerForFile(this, fileName)
        if (activeServer == null) {
            if (latestDiagnostics.isNotEmpty() || force) {
                latestDiagnostics = emptyList()
                editorController.clearLspDiagnostics()
                renderLspDiagnosticsPanel(emptyList())
            }
            updateLspStatusBadgeUi(null, fileName)
            updateCodeAnalysisTabTitle(0)
            return
        }

        val diags = LspManager.analyzeDocumentWithStdio(
            context = this,
            server = activeServer,
            fileName = fileName,
            text = text,
            localFile = currentFile?.localFile,
            notifyServer = notifyStdioServer
        )
        val diagsChanged = force || diags != latestDiagnostics
        latestDiagnostics = diags
        if (diagsChanged) {
            editorController.applyLspDiagnosticsToEditor(diags)
            renderLspDiagnosticsPanel(diags)
        }
        updateLspStatusBadgeUi(activeServer, fileName)
        updateCodeAnalysisTabTitle(diags.size)
    }

    private fun updateCodeAnalysisTabTitle(issueCount: Int) {
        val baseTitle = getString(R.string.tab_code_analysis)
        val label = if (issueCount > 0) "$baseTitle ($issueCount)" else baseTitle
        binding.bottomSheetTabs.getTabAt(TAB_CODE_ANALYSIS)?.text = label

        val density = resources.displayMetrics.density
        if (issueCount <= 0) {
            binding.diagnosticsCountBadge.visibility = View.GONE
        } else {
            val hasErr = latestDiagnostics.any { it.severity == LspManager.Severity.ERROR }
            val hasWarn = latestDiagnostics.any { it.severity == LspManager.Severity.WARNING }
            val pillColor = when {
                hasErr -> Color.parseColor("#FF5252")
                hasWarn -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#4FC3F7")
            }
            binding.diagnosticsCountBadge.text = issueCount.toString()
            binding.diagnosticsCountBadge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(pillColor)
            }
            binding.diagnosticsCountBadge.visibility = View.VISIBLE
        }
    }

    private fun updateLspStatusBadgeUi(activeServer: LspManager.LspServerSpec?, fileName: String) {
        val subtleColor = editorController.currentSubtleColor()
        if (activeServer == null) {
            val candidate = LspManager.serverSpecForFile(fileName)
            if (candidate != null) {
                binding.lspStatusBadge.text = "+ Install ${candidate.id}"
                binding.lspStatusBadge.setTextColor(ModernUiHelper.PRIMARY_COLOR)
            } else {
                binding.lspStatusBadge.text = "Plain Text"
                binding.lspStatusBadge.setTextColor(subtleColor)
            }
            return
        }

        val errors = latestDiagnostics.count { it.severity == LspManager.Severity.ERROR }
        val warnings = latestDiagnostics.count { it.severity == LspManager.Severity.WARNING }
        when {
            errors > 0 -> {
                binding.lspStatusBadge.text = "● ${activeServer.id}: $errors Err, $warnings Warn"
                binding.lspStatusBadge.setTextColor(Color.parseColor("#FF5252"))
            }
            warnings > 0 -> {
                binding.lspStatusBadge.text = "▲ ${activeServer.id}: $warnings Warn"
                binding.lspStatusBadge.setTextColor(Color.parseColor("#FFB300"))
            }
            else -> {
                binding.lspStatusBadge.text = "✓ ${activeServer.id}"
                binding.lspStatusBadge.setTextColor(Color.parseColor("#66BB6A"))
            }
        }
    }

    private fun renderLspDiagnosticsPanel(diags: List<LspManager.LspDiagnostic>) {
        val container = binding.lspDiagnosticsContainer
        container.removeAllViews()

        val fgColor = editorController.currentForegroundColor()
        val subtleColor = editorController.currentSubtleColor()
        val surfaceBg = editorController.currentSurfaceColor()
        val density = resources.displayMetrics.density

        if (diags.isEmpty()) {
            val emptyView = MaterialTextView(this).apply {
                text = "No problems detected in ${currentFile?.displayName ?: "file"}."
                textSize = 12f
                typeface = ModernUiHelper.appFontRegular(this@MainActivity)
                setTextColor(subtleColor)
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }
            container.addView(emptyView)
            return
        }

        for (d in diags.take(50)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (36 * density).toInt()
                setPadding(
                    (6 * density).toInt(),
                    (5 * density).toInt(),
                    (6 * density).toInt(),
                    (5 * density).toInt()
                )
                setOnClickListener {
                    jumpToPosition(d.line + 1, d.startCol)
                }
            }

            val badgeColor = when (d.severity) {
                LspManager.Severity.ERROR -> Color.parseColor("#FF5252")
                LspManager.Severity.WARNING -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#4FC3F7")
            }

            val badgeView = MaterialTextView(this).apply {
                text = "${d.severity.label.uppercase()} Ln ${d.line + 1}:${d.startCol + 1}"
                textSize = 10.5f
                typeface = ModernUiHelper.appFontBold(this@MainActivity)
                setTextColor(badgeColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
            }
            row.addView(badgeView)

            val msgView = MaterialTextView(this).apply {
                text = d.message
                textSize = 11.5f
                typeface = ModernUiHelper.appFontRegular(this@MainActivity)
                setTextColor(fgColor)
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(msgView)

            if (d.quickFixReplacement != null) {
                val fixChip = MaterialTextView(this).apply {
                    text = "Quick Fix"
                    textSize = 10.5f
                    typeface = ModernUiHelper.appFontBold(this@MainActivity)
                    setTextColor(Color.parseColor("#66BB6A"))
                    setPadding(
                        (8 * density).toInt(),
                        (3 * density).toInt(),
                        (8 * density).toInt(),
                        (3 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6 * density
                        setColor(surfaceBg)
                        setStroke((1 * density).toInt().coerceAtLeast(1), Color.parseColor("#66BB6A"))
                    }
                    setOnClickListener {
                        editorController.applyLspQuickFix(d)
                        runLspAnalysisForCurrentFile()
                    }
                }
                row.addView(fixChip)
            }

            container.addView(row)
        }
    }

    private fun saveCurrentTabBufferState() {
        val current = currentFile ?: return
        current.content = binding.editor.text.toString()
        runCatching {
            current.cursorLine = binding.editor.cursor.leftLine
            current.cursorColumn = binding.editor.cursor.leftColumn
        }
    }

    private fun switchToTab(index: Int) {
        if (index !in openTabs.indices) {
            updateEmptyStateVisibility()
            return
        }
        if (activeTabIndex in openTabs.indices && activeTabIndex != index) {
            saveCurrentTabBufferState()
            if (settings.autoSaveEnabled) {
                openTabs.getOrNull(activeTabIndex)?.takeIf { it.modified }?.let {
                    writeTabToStorage(it, showToast = false)
                }
            }
        }
        activeTabIndex = index
        val target = openTabs[index]
        updateEmptyStateVisibility()

        suppressModifiedEvent = true
        try {
            editorController.setLanguageFor(target.displayName)
            binding.editor.setText(target.content)
            runCatching {
                val safeLine = target.cursorLine.coerceIn(0, (binding.editor.text.lineCount - 1).coerceAtLeast(0))
                val maxCol = binding.editor.text.getLineString(safeLine).length
                val safeCol = target.cursorColumn.coerceIn(0, maxCol)
                binding.editor.setSelection(safeLine, safeCol)
            }
        } finally {
            suppressModifiedEvent = false
        }

        binding.editor.alpha = 0.84f
        binding.editor.animate()
            .alpha(1f)
            .setDuration(150L)
            .setInterpolator(ModernUiHelper.SPRING_INTERPOLATOR)
            .start()
        binding.editorFoldGutter.refreshFoldableLines(force = true)
        renderTabs()
        runLspAnalysisForCurrentFile(force = true, notifyStdioServer = true)
        updateStatusLine()
        updateStickyScrollHeader()
        if (activeSidebarTab == SIDEBAR_TAB_OUTLINE && binding.mainDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            renderSidebarOutlinePage()
        }
        ContinueCodingWidgetProvider.recordActiveSessionAndRefresh(
            context = this,
            projectDir = ProjectManager.ensureDefaultLocalWorkspace(this),
            activeFile = target
        )
    }

    private fun renderTabs() {
        val tabLayout = binding.fileTabLayout
        suppressFileTabCallback = true
        try {
            tabLayout.removeAllTabs()

            val density = resources.displayMetrics.density
            val fgColor = editorController.currentForegroundColor()
            val subtleColor = editorController.currentSubtleColor()
            val accentColor = ModernUiHelper.PRIMARY_COLOR

            openTabs.forEachIndexed { idx, fileTab ->
                val isActive = idx == activeTabIndex
                val m3Tab = tabLayout.newTab()

                val customTabView = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        (6 * density).toInt(),
                        (4 * density).toInt(),
                        (4 * density).toInt(),
                        (4 * density).toInt()
                    )
                    setOnClickListener {
                        if (idx == activeTabIndex) {
                            showTabContextMenu(idx)
                        } else {
                            switchToTab(idx)
                        }
                    }
                    setOnLongClickListener {
                        showTabContextMenu(idx)
                        true
                    }
                }

                val iconView = ImageView(this).apply {
                    setImageDrawable(MaterialFileIcons.createFileDrawable(this@MainActivity, fileTab.displayName, 16))
                    layoutParams = LinearLayout.LayoutParams(
                        (16 * density).toInt(),
                        (16 * density).toInt()
                    ).apply {
                        marginEnd = (6 * density).toInt()
                    }
                }
                customTabView.addView(iconView)

                val titleView = MaterialTextView(this).apply {
                    text = fileTab.displayName
                    textSize = 12.5f
                    setSingleLine(true)
                    setTextColor(if (isActive) fgColor else subtleColor)
                    typeface = if (isActive) ModernUiHelper.appFontBold(this@MainActivity) else ModernUiHelper.appFontMedium(this@MainActivity)
                }
                customTabView.addView(titleView)

                if (fileTab.modified) {
                    val modifiedDot = MaterialTextView(this).apply {
                        text = " ●"
                        textSize = 11f
                        setTextColor(accentColor)
                        typeface = ModernUiHelper.appFontBold(this@MainActivity)
                    }
                    customTabView.addView(modifiedDot)
                }

                val closeBtn = ImageButton(this, null, 0, R.style.PocketDev_IconButton).apply {
                    setImageResource(R.drawable.ic_close)
                    imageTintList = ColorStateList.valueOf(if (isActive) fgColor else subtleColor)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    contentDescription = "Close ${fileTab.displayName}"
                    setPadding(
                        (6 * density).toInt(),
                        (6 * density).toInt(),
                        (6 * density).toInt(),
                        (6 * density).toInt()
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        (28 * density).toInt(),
                        (28 * density).toInt()
                    ).apply {
                        marginStart = (4 * density).toInt()
                    }
                    setOnClickListener { closeTabAt(idx) }
                }
                customTabView.addView(closeBtn)

                m3Tab.customView = customTabView
                tabLayout.addTab(m3Tab, isActive)
            }
        } finally {
            suppressFileTabCallback = false
        }
    }

    private fun showTabContextMenu(index: Int) {
        val tab = openTabs.getOrNull(index) ?: return
        val options = ArrayList<ModernUiHelper.MenuOption>()
        options.add(
            ModernUiHelper.MenuOption(
                id = 0,
                title = "Save",
                iconRes = R.drawable.ic_save,
                action = {
                    if (index != activeTabIndex) switchToTab(index)
                    saveDocument()
                }
            )
        )
        tab.localFile?.let { file ->
            options.add(
                ModernUiHelper.MenuOption(
                    id = 1,
                    title = "Rename",
                    iconRes = R.drawable.ic_edit,
                    action = {
                        val node = ProjectManager.FileNode(
                            name = file.name,
                            relativePath = file.name,
                            isDirectory = false,
                            depth = 0,
                            localFile = file
                        )
                        promptRenameNode(node)
                    }
                )
            )
        }
        options.add(
            ModernUiHelper.MenuOption(
                id = 2,
                title = "Close Tab",
                iconRes = R.drawable.ic_close,
                action = { closeTabAt(index) }
            )
        )
        options.add(
            ModernUiHelper.MenuOption(
                id = 3,
                title = "Close Other Tabs",
                iconRes = R.drawable.ic_clear_all,
                action = {
                    saveCurrentTabBufferState()
                    val keep = openTabs[index]
                    openTabs.clear()
                    openTabs.add(keep)
                    activeTabIndex = -1
                    switchToTab(0)
                }
            )
        )
        options.add(
            ModernUiHelper.MenuOption(
                id = 4,
                title = "Close All Tabs",
                iconRes = R.drawable.ic_delete,
                isDestructive = true,
                action = {
                    saveCurrentTabBufferState()
                    openTabs.clear()
                    activeTabIndex = -1
                    renderTabs()
                    updateEmptyStateVisibility()
                }
            )
        )

        ModernUiHelper.showModernContextMenu(
            context = this,
            title = tab.displayName,
            subtitle = tab.localFile?.parentFile?.name ?: "Editor Tab",
            headerIconRes = R.drawable.ic_new_file,
            options = options
        )
    }

    private fun closeTabAt(index: Int) {
        if (index !in openTabs.indices) return
        saveCurrentTabBufferState()
        openTabs.removeAt(index)
        if (openTabs.isEmpty()) {
            activeTabIndex = -1
            renderTabs()
            updateEmptyStateVisibility()
            return
        }
        val nextIndex = when {
            activeTabIndex >= openTabs.size -> openTabs.lastIndex
            index < activeTabIndex -> activeTabIndex - 1
            else -> activeTabIndex
        }
        activeTabIndex = -1
        switchToTab(nextIndex.coerceIn(0, openTabs.lastIndex))
    }

    private fun createNewUntitledTab() {
        if (openTabs.isNotEmpty()) {
            saveCurrentTabBufferState()
        }
        val untitledCount = openTabs.count { it.displayName.startsWith("untitled") } + 1
        val name = if (untitledCount == 1) "untitled.kt" else "untitled_$untitledCount.kt"
        val workspaceRoot = ProjectManager.ensureDefaultLocalWorkspace(this)
        val localTarget = File(workspaceRoot, name)
        val newTab = OpenedFile(uri = null, displayName = name, content = "", localFile = localTarget)
        openTabs.add(newTab)
        switchToTab(openTabs.lastIndex)
    }

    private fun openLocalFileInTab(file: File, switchImmediately: Boolean = true, jumpLine: Int = -1, jumpCol: Int = 0) {
        val existingIdx = openTabs.indexOfFirst { it.matchesTarget(null, file) }
        if (existingIdx >= 0) {
            if (switchImmediately) {
                switchToTab(existingIdx)
                if (jumpLine > 0) jumpToPosition(jumpLine, jumpCol)
            }
            return
        }
        try {
            val content = file.readText(Charsets.UTF_8)
            val tab = OpenedFile(
                uri = null,
                displayName = file.name,
                content = content,
                localFile = file
            )
            openTabs.add(tab)
            if (switchImmediately) {
                switchToTab(openTabs.lastIndex)
                if (jumpLine > 0) jumpToPosition(jumpLine, jumpCol)
            } else {
                renderTabs()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUriInTab(uri: Uri, displayNameOverride: String? = null, jumpLine: Int = -1, jumpCol: Int = 0) {
        val existingIdx = openTabs.indexOfFirst { it.matchesTarget(uri, null) }
        if (existingIdx >= 0) {
            switchToTab(existingIdx)
            if (jumpLine > 0) jumpToPosition(jumpLine, jumpCol)
            return
        }
        val name = displayNameOverride ?: uri.queryDisplayName(contentResolver)
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val content = ContentIO.createFrom(stream).toString()
                val tab = OpenedFile(uri = uri, displayName = name, content = content)
                openTabs.add(tab)
                switchToTab(openTabs.lastIndex)
                if (jumpLine > 0) jumpToPosition(jumpLine, jumpCol)
            } ?: run { Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun jumpToPosition(line1Based: Int, col0Based: Int) {
        runCatching {
            val line0 = (line1Based - 1).coerceIn(0, (binding.editor.text.lineCount - 1).coerceAtLeast(0))
            val maxCol = binding.editor.text.getLineString(line0).length
            binding.editor.setSelection(line0, col0Based.coerceIn(0, maxCol))
            binding.editor.ensureSelectionVisible()
        }
    }

    // ---------------------------------------------------------------- 3-Tab Project Sidebar Drawer (Files, Git, Outline)

    private fun setupProjectSidebar() {
        TooltipCompat.setTooltipText(binding.btnSelectProjectFolder, "Open Project")
        TooltipCompat.setTooltipText(binding.btnTreeNewFile, "New")
        TooltipCompat.setTooltipText(binding.btnTreeRefresh, "Refresh")

        val sidebarTabLabels = listOf("Files", "Git", "Outline")
        suppressSidebarTabCallback = true
        binding.sidebarModeTabs.removeAllTabs()
        for (lbl in sidebarTabLabels) {
            binding.sidebarModeTabs.addTab(binding.sidebarModeTabs.newTab().setText(lbl))
        }
        binding.sidebarModeTabs.getTabAt(activeSidebarTab)?.select()
        suppressSidebarTabCallback = false

        binding.sidebarModeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (suppressSidebarTabCallback) return
                val pos = tab?.position ?: return
                selectSidebarTab(pos)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.mainDrawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                refreshActiveSidebarPage()
                treeWatcherHandler.removeCallbacks(treeWatcherRunnable)
                treeWatcherHandler.postDelayed(treeWatcherRunnable, 2000L)
            }

            override fun onDrawerClosed(drawerView: View) {
                treeWatcherHandler.removeCallbacks(treeWatcherRunnable)
            }
        })

        binding.sidebarProjectSelector.setOnClickListener {
            showAppDirProjectManagerDialog()
        }
        binding.btnSelectProjectFolder.setOnClickListener {
            showOpenProjectDialog()
        }
        binding.btnLocalWorkspace.setOnClickListener {
            val defaultDir = ProjectManager.switchToLocalWorkspace(this)
            switchActiveAppProject(defaultDir)
        }
        binding.btnTreeRefresh.setOnClickListener {
            refreshActiveSidebarPage()
        }
        binding.btnTreeNewFile.setOnClickListener {
            val activeName = ProjectManager.activeProjectName(this)
            ModernUiHelper.showModernContextMenu(
                context = this,
                title = "New",
                subtitle = activeName,
                headerIconRes = R.drawable.ic_add,
                options = listOf(
                    ModernUiHelper.MenuOption(
                        id = 0,
                        title = "New File",
                        subtitle = "Create a new file in $activeName",
                        iconRes = R.drawable.ic_new_file,
                        action = { promptCreateInProject(parentNode = null, isDirectory = false) }
                    ),
                    ModernUiHelper.MenuOption(
                        id = 1,
                        title = "New Folder",
                        subtitle = "Create a new folder in $activeName",
                        iconRes = R.drawable.ic_folder,
                        action = { promptCreateInProject(parentNode = null, isDirectory = true) }
                    ),
                    ModernUiHelper.MenuOption(
                        id = 2,
                        title = "Create New Project",
                        subtitle = "Create a new project workspace",
                        iconRes = R.drawable.ic_add,
                        action = { promptCreateNewAppDirProject() }
                    ),
                    ModernUiHelper.MenuOption(
                        id = 3,
                        title = "Open Project",
                        subtitle = "Switch to an existing project",
                        iconRes = R.drawable.ic_folder_open,
                        action = { showOpenProjectDialog() }
                    ),
                    ModernUiHelper.MenuOption(
                        id = 4,
                        title = "Import Folder",
                        subtitle = "Import a folder from device storage",
                        iconRes = R.drawable.ic_folder,
                        action = { importFolderToAppDirLauncher.launch(null) }
                    ),
                    ModernUiHelper.MenuOption(
                        id = 5,
                        title = "Open in Terminal",
                        subtitle = "Launch terminal in $activeName",
                        iconRes = R.drawable.ic_terminal,
                        action = {
                            closeProjectSidebar()
                            openTerminalInDirectory(ProjectManager.ensureDefaultLocalWorkspace(this))
                        }
                    )
                )
            )
        }
        binding.treeFilterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                treeFilterQuery = s?.toString()?.trim() ?: ""
                refreshFileTree()
            }
        })
    }

    private fun selectSidebarTab(tabIdx: Int) {
        activeSidebarTab = tabIdx.coerceIn(0, 2)
        suppressSidebarTabCallback = true
        binding.sidebarModeTabs.getTabAt(activeSidebarTab)?.let { tab ->
            if (!tab.isSelected) tab.select()
        }
        suppressSidebarTabCallback = false
        val sidebarPages = listOf(binding.sidebarFilesPage, binding.sidebarGitPage, binding.sidebarOutlinePage)
        val targetSidebarPage = when (activeSidebarTab) {
            SIDEBAR_TAB_GIT -> binding.sidebarGitPage
            SIDEBAR_TAB_OUTLINE -> binding.sidebarOutlinePage
            else -> binding.sidebarFilesPage
        }
        ModernUiHelper.animatePageSwitch(sidebarPages, targetSidebarPage, forward = true, durationMs = 190L)
        refreshActiveSidebarPage()
    }

    private fun refreshActiveSidebarPage() {
        when (activeSidebarTab) {
            SIDEBAR_TAB_FILES -> refreshFileTree()
            SIDEBAR_TAB_GIT -> renderSidebarGitPage()
            SIDEBAR_TAB_OUTLINE -> renderSidebarOutlinePage()
        }
    }

    private fun renderSidebarGitPage() {
        val container = binding.sidebarGitContainer
        container.removeAllViews()

        val activeDir = ProjectManager.ensureDefaultLocalWorkspace(this)
        val palette = ModernUiHelper.resolvePalette(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val hasRepo = GitHubManager.isGitRepo(activeDir)
        val branch = if (hasRepo) GitHubManager.getCurrentBranchFast(activeDir) else "uninitialized"

        val branchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.surfaceBg)
                setStroke(dp(1), palette.borderColor)
            }
        }

        val branchText = MaterialTextView(this).apply {
            text = if (hasRepo) "⎇ $branch ▾" else "No Repository"
            textSize = 12.5f
            typeface = ModernUiHelper.appFontBold(this@MainActivity)
            setTextColor(palette.accentColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (hasRepo) {
                    GitHubManager.showBranchSwitcherDialog(this@MainActivity, activeDir) {
                        updateToolbarProjectTitle()
                        renderSidebarGitPage()
                    }
                }
            }
        }
        branchRow.addView(branchText)

        val accountChip = MaterialTextView(this).apply {
            text = if (settings.githubToken.isNotBlank()) "@${settings.githubUsername.ifBlank { "Connected" }}" else "Sign In"
            textSize = 11f
            typeface = ModernUiHelper.appFontSemiBold(this@MainActivity)
            setTextColor(palette.fgColor)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(ModernUiHelper.withAlpha(palette.accentColor, 0x22))
            }
            setOnClickListener {
                GitHubManager.showAuthDialog(this@MainActivity) {
                    renderSidebarGitPage()
                }
            }
        }
        branchRow.addView(accountChip)
        container.addView(branchRow)

        if (!hasRepo) {
            val emptyInfo = MaterialTextView(this).apply {
                text = "Initialize a Git repository in ${activeDir.name} or clone a repository to track changes and push commits."
                textSize = 12f
                typeface = ModernUiHelper.appFontRegular(this@MainActivity)
                setTextColor(palette.subtleColor)
                setPadding(0, dp(12), 0, dp(12))
            }
            container.addView(emptyInfo)

            val initBtn = MaterialButton(this).apply {
                text = "Initialize Repository"
                textSize = 12f
                isAllCaps = false
                typeface = ModernUiHelper.appFontBold(this@MainActivity)
                backgroundTintList = ColorStateList.valueOf(palette.accentColor)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    Thread {
                        GitHubManager.runGitCommand(
                            this@MainActivity,
                            activeDir,
                            "init -b main && git config user.name 'PocketDev' && git config user.email 'dev@pocketdev.local'"
                        )
                        GitHubManager.invalidateStatusCache()
                        runOnUiThread {
                            updateToolbarProjectTitle()
                            renderSidebarGitPage()
                        }
                    }.start()
                }
            }
            container.addView(initBtn)

            val cloneBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Clone Repository"
                textSize = 12f
                isAllCaps = false
                typeface = ModernUiHelper.appFontSemiBold(this@MainActivity)
                setTextColor(palette.fgColor)
                setOnClickListener {
                    GitHubManager.showCloneRepoDialog(this@MainActivity) { clonedDir ->
                        switchActiveAppProject(clonedDir)
                    }
                }
            }
            container.addView(cloneBtn)
            return
        }

        val commitInput = EditText(this).apply {
            hint = "Commit message…"
            textSize = 12.5f
            typeface = ModernUiHelper.appFontRegular(this@MainActivity)
            setTextColor(palette.fgColor)
            setHintTextColor(palette.subtleColor)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(palette.surfaceBg)
                setStroke(dp(1), palette.borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }
        container.addView(commitInput)

        val actionRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val commitBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Commit"
            textSize = 11.5f
            isAllCaps = false
            typeface = ModernUiHelper.appFontSemiBold(this@MainActivity)
            setTextColor(palette.fgColor)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) }
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                val msg = commitInput.text?.toString()?.trim().orEmpty()
                if (msg.isEmpty()) {
                    GitHubManager.showCommitAndPushDialog(this@MainActivity, activeDir) {
                        renderSidebarGitPage()
                    }
                    return@setOnClickListener
                }
                Thread {
                    val res = GitHubManager.runGitCommand(
                        this@MainActivity,
                        activeDir,
                        "add -A && git commit -m '${msg.replace("'", "'\\''")}'"
                    )
                    GitHubManager.invalidateStatusCache()
                    runOnUiThread {
                        commitInput.text = null
                        Toast.makeText(this@MainActivity, res.combinedOutput.ifBlank { "Committed" }, Toast.LENGTH_SHORT).show()
                        renderSidebarGitPage()
                    }
                }.start()
            }
        }
        actionRow1.addView(commitBtn)

        val pushBtn = MaterialButton(this).apply {
            text = "Commit & Push"
            textSize = 11.5f
            isAllCaps = false
            typeface = ModernUiHelper.appFontBold(this@MainActivity)
            backgroundTintList = ColorStateList.valueOf(palette.accentColor)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1.2f)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                val msg = commitInput.text?.toString()?.trim().orEmpty()
                if (msg.isEmpty()) {
                    GitHubManager.showCommitAndPushDialog(this@MainActivity, activeDir) {
                        renderSidebarGitPage()
                    }
                } else {
                    Thread {
                        val res = GitHubManager.runGitCommand(
                            this@MainActivity,
                            activeDir,
                            "add -A && (git commit -m '${msg.replace("'", "'\\''")}' || true) && git push -u origin $branch"
                        )
                        GitHubManager.invalidateStatusCache()
                        runOnUiThread {
                            commitInput.text = null
                            Toast.makeText(
                                this@MainActivity,
                                if (res.isSuccess) "Pushed to origin/$branch" else res.combinedOutput,
                                Toast.LENGTH_LONG
                            ).show()
                            renderSidebarGitPage()
                        }
                    }.start()
                }
            }
        }
        actionRow1.addView(pushBtn)
        container.addView(actionRow1)

        val actionRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        val pullBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Pull"
            textSize = 11f
            isAllCaps = false
            typeface = ModernUiHelper.appFontSemiBold(this@MainActivity)
            setTextColor(palette.fgColor)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) }
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Pulling latest changes…", Toast.LENGTH_SHORT).show()
                Thread {
                    val res = GitHubManager.runGitCommand(this@MainActivity, activeDir, "pull --rebase")
                    GitHubManager.invalidateStatusCache()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, res.combinedOutput.ifBlank { "Up to date" }, Toast.LENGTH_SHORT).show()
                        renderSidebarGitPage()
                    }
                }.start()
            }
        }
        actionRow2.addView(pullBtn)

        val diffBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Diff"
            textSize = 11f
            isAllCaps = false
            typeface = ModernUiHelper.appFontSemiBold(this@MainActivity)
            setTextColor(palette.fgColor)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) }
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                GitHubManager.showDiffViewerDialog(this@MainActivity, activeDir, null)
            }
        }
        actionRow2.addView(diffBtn)

        val cloneMoreBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Clone"
            textSize = 11f
            isAllCaps = false
            typeface = ModernUiHelper.appFontSemiBold(this@MainActivity)
            setTextColor(palette.fgColor)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                GitHubManager.showCloneRepoDialog(this@MainActivity) { clonedDir ->
                    switchActiveAppProject(clonedDir)
                }
            }
        }
        actionRow2.addView(cloneMoreBtn)
        container.addView(actionRow2)

        val changesHeader = MaterialTextView(this).apply {
            text = "CHANGES"
            textSize = 10.5f
            typeface = ModernUiHelper.appFontBold(this@MainActivity)
            setTextColor(palette.accentColor)
            setPadding(0, dp(14), 0, dp(6))
        }
        container.addView(changesHeader)

        val filesListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(filesListLayout)

        Thread {
            val statusMap = GitHubManager.computeGitStatusSync(this, activeDir)
            runOnUiThread {
                filesListLayout.removeAllViews()
                if (statusMap.isEmpty()) {
                    val cleanTv = MaterialTextView(this).apply {
                        text = "Working tree clean."
                        textSize = 12f
                        typeface = ModernUiHelper.appFontRegular(this@MainActivity)
                        setTextColor(palette.subtleColor)
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    filesListLayout.addView(cleanTv)
                } else {
                    for ((relPath, state) in statusMap.entries.sortedBy { it.key }) {
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = dp(38)
                            setPadding(dp(6), dp(6), dp(6), dp(6))
                            setOnClickListener {
                                val target = File(activeDir, relPath)
                                GitHubManager.showDiffViewerDialog(this@MainActivity, activeDir, target)
                            }
                        }
                        val badge = MaterialTextView(this).apply {
                            text = state.badge
                            textSize = 11f
                            typeface = ModernUiHelper.appFontBold(this@MainActivity)
                            setTextColor(Color.parseColor(state.colorHex))
                            setPadding(0, 0, dp(8), 0)
                        }
                        row.addView(badge)

                        val pathTv = MaterialTextView(this).apply {
                            text = relPath
                            textSize = 12f
                            typeface = ModernUiHelper.appFontMedium(this@MainActivity)
                            setTextColor(palette.fgColor)
                            setSingleLine(true)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        row.addView(pathTv)
                        filesListLayout.addView(row)
                    }
                }
            }
        }.start()
    }

    private fun renderSidebarOutlinePage() {
        val container = binding.sidebarOutlineContainer
        container.removeAllViews()

        val palette = ModernUiHelper.resolvePalette(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val active = currentFile
        if (active == null) {
            val emptyTv = MaterialTextView(this).apply {
                text = "Open a file to view its classes and functions."
                textSize = 12f
                typeface = ModernUiHelper.appFontRegular(this@MainActivity)
                setTextColor(palette.subtleColor)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            container.addView(emptyTv)
            return
        }

        val text = binding.editor.text.toString()
        val declRegex = Regex(
            "^(\\s*)(?:(?:public|private|protected|internal|open|abstract|final|static|suspend|inline|override|data|sealed|export|async|pub)\\s+)*(class|interface|object|enum|struct|trait|fun|def|fn|function|func)\\s+([A-Za-z_][A-Za-z0-9_]*)"
        )

        data class OutlineSymbol(val kind: String, val name: String, val line1: Int, val indent: Int)
        val symbols = ArrayList<OutlineSymbol>()

        text.lineSequence().forEachIndexed { idx, rawLine ->
            val m = declRegex.find(rawLine)
            if (m != null) {
                val leadingSpaces = m.groupValues[1].length
                val kw = m.groupValues[2]
                val symName = m.groupValues[3]
                symbols.add(OutlineSymbol(kw, symName, idx + 1, (leadingSpaces / 4).coerceIn(0, 4)))
            }
        }

        if (symbols.isEmpty()) {
            val emptyTv = MaterialTextView(this).apply {
                this.text = "No symbols detected in ${active.displayName}."
                textSize = 12f
                typeface = ModernUiHelper.appFontRegular(this@MainActivity)
                setTextColor(palette.subtleColor)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            container.addView(emptyTv)
            return
        }

        for (sym in symbols) {
            val isType = sym.kind in setOf("class", "interface", "object", "enum", "struct", "trait")
            val badgeText = if (isType) "C" else "ƒ"
            val badgeColor = if (isType) ModernUiHelper.PRIMARY_LIGHT_COLOR else Color.parseColor("#4FC3F7")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(38)
                setPadding(dp(12 + sym.indent * 14), dp(6), dp(12), dp(6))
                setOnClickListener {
                    jumpToPosition(sym.line1, 0)
                    closeProjectSidebar()
                }
            }

            val kindBadge = MaterialTextView(this).apply {
                this.text = badgeText
                textSize = 11f
                typeface = ModernUiHelper.appFontBold(this@MainActivity)
                setTextColor(badgeColor)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginEnd = dp(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(5).toFloat()
                    setColor(ModernUiHelper.withAlpha(badgeColor, 0x22))
                }
            }
            row.addView(kindBadge)

            val nameTv = MaterialTextView(this).apply {
                this.text = sym.name
                textSize = 12.5f
                typeface = if (isType) ModernUiHelper.appFontBold(this@MainActivity) else ModernUiHelper.appFontMedium(this@MainActivity)
                setTextColor(palette.fgColor)
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameTv)

            val lineTv = MaterialTextView(this).apply {
                this.text = ":${sym.line1}"
                textSize = 11f
                typeface = ModernUiHelper.appFontRegular(this@MainActivity)
                setTextColor(palette.subtleColor)
            }
            row.addView(lineTv)

            container.addView(row)
        }
    }

    /**
     * Dedicated "Open Project" dialog listing all existing workspace projects, plus
     * options to open a custom path, import a folder, or create a new project.
     */
    private fun showOpenProjectDialog() {
        val activeDir = ProjectManager.ensureDefaultLocalWorkspace(this)
        val projects = ProjectManager.listAppDirProjects(this)
        val options = ArrayList<ModernUiHelper.MenuOption>()

        projects.forEachIndexed { idx, dir ->
            val isCurrent = dir.absolutePath == activeDir.absolutePath
            val guestPath = ProjectManager.guestPathForDir(this, dir)
            val itemCount = dir.listFiles()?.size ?: 0
            options.add(
                ModernUiHelper.MenuOption(
                    id = idx,
                    title = dir.name,
                    subtitle = "$guestPath • $itemCount items",
                    iconRes = R.drawable.ic_folder,
                    badgeText = if (isCurrent) "ACTIVE" else null,
                    isSelected = isCurrent,
                    action = {
                        switchActiveAppProject(dir)
                        Toast.makeText(this, "Opened ${dir.name}", Toast.LENGTH_SHORT).show()
                    }
                )
            )
        }

        options.add(
            ModernUiHelper.MenuOption(
                id = 200,
                title = "Open by Path…",
                subtitle = "Enter a project name or folder path in workspace",
                iconRes = R.drawable.ic_folder_search,
                action = { promptOpenProjectByPath() }
            )
        )

        options.add(
            ModernUiHelper.MenuOption(
                id = 201,
                title = "Import Folder",
                subtitle = "Import a folder from device storage",
                iconRes = R.drawable.ic_folder_open,
                action = { importFolderToAppDirLauncher.launch(null) }
            )
        )

        options.add(
            ModernUiHelper.MenuOption(
                id = 202,
                title = "Create New Project",
                subtitle = "Create a new project workspace",
                iconRes = R.drawable.ic_add,
                action = { promptCreateNewAppDirProject() }
            )
        )

        ModernUiHelper.showModernContextMenu(
            context = this,
            title = "Open Project",
            subtitle = "${projects.size} project(s) in workspace",
            headerIconRes = R.drawable.ic_folder_open,
            options = options
        )
    }

    private fun promptOpenProjectByPath() {
        val activeDir = ProjectManager.ensureDefaultLocalWorkspace(this)
        ModernUiHelper.showModernInputDialog(
            context = this,
            title = "Open Project by Path",
            subtitle = "Enter a project name or path inside /root/projects",
            initialValue = ProjectManager.guestPathForDir(this, activeDir),
            hint = "/root/projects/my-project",
            positiveText = "Open"
        ) { rawPath ->
            if (rawPath.isNotBlank()) {
                val opened = ProjectManager.openProjectByPath(this, rawPath)
                if (opened != null) {
                    switchActiveAppProject(opened)
                    Toast.makeText(this, "Opened ${opened.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Project folder not found: $rawPath", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Project Manager bottom sheet with clean, production-grade labels.
     */
    private fun showAppDirProjectManagerDialog() {
        val activeDir = ProjectManager.ensureDefaultLocalWorkspace(this)
        val projects = ProjectManager.listAppDirProjects(this)
        val options = ArrayList<ModernUiHelper.MenuOption>()

        projects.forEachIndexed { idx, dir ->
            val isCurrent = dir.absolutePath == activeDir.absolutePath
            val guestPath = ProjectManager.guestPathForDir(this, dir)
            options.add(
                ModernUiHelper.MenuOption(
                    id = idx,
                    title = dir.name,
                    subtitle = "$guestPath • ${dir.listFiles()?.size ?: 0} items",
                    iconRes = R.drawable.ic_folder,
                    badgeText = if (isCurrent) "ACTIVE" else null,
                    isSelected = isCurrent,
                    action = {
                        switchActiveAppProject(dir)
                        Toast.makeText(this, "Opened ${dir.name}", Toast.LENGTH_SHORT).show()
                    }
                )
            )
        }

        options.add(
            ModernUiHelper.MenuOption(
                id = 99,
                title = "Open Project",
                subtitle = "Browse or open an existing project",
                iconRes = R.drawable.ic_folder_open,
                action = { showOpenProjectDialog() }
            )
        )

        options.add(
            ModernUiHelper.MenuOption(
                id = 100,
                title = "Create New Project",
                subtitle = "Create a new project workspace",
                iconRes = R.drawable.ic_add,
                action = { promptCreateNewAppDirProject() }
            )
        )

        options.add(
            ModernUiHelper.MenuOption(
                id = 101,
                title = "Import Folder",
                subtitle = "Import a folder from device storage",
                iconRes = R.drawable.ic_folder,
                action = { importFolderToAppDirLauncher.launch(null) }
            )
        )

        options.add(
            ModernUiHelper.MenuOption(
                id = 102,
                title = "Clone Repository",
                subtitle = "Clone a Git repository from URL or GitHub",
                iconRes = R.drawable.ic_git,
                action = {
                    GitHubManager.showCloneRepoDialog(this) { clonedDir ->
                        switchActiveAppProject(clonedDir)
                    }
                }
            )
        )

        options.add(
            ModernUiHelper.MenuOption(
                id = 103,
                title = "Open in Terminal",
                subtitle = "Launch terminal in ${activeDir.name}",
                iconRes = R.drawable.ic_terminal,
                action = {
                    closeProjectSidebar()
                    openTerminalInDirectory(activeDir)
                }
            )
        )

        ModernUiHelper.showModernContextMenu(
            context = this,
            title = "Projects",
            subtitle = "Active: ${activeDir.name}",
            headerIconRes = R.drawable.ic_folder_open,
            options = options
        )
    }

    private fun promptCreateNewAppDirProject() {
        ModernUiHelper.showModernInputDialog(
            context = this,
            title = "Create New Project",
            subtitle = "Enter a name for your new project",
            hint = "my-project",
            positiveText = "Create",
            neutralText = "Create & Open Terminal",
            onNeutral = { name ->
                if (name.isNotEmpty()) {
                    val createdDir = ProjectManager.createNewAppDirProject(this, name)
                    if (createdDir != null) {
                        switchActiveAppProject(createdDir)
                        closeProjectSidebar()
                        openTerminalInDirectory(createdDir)
                    }
                }
            }
        ) { name ->
            if (name.isNotEmpty()) {
                val createdDir = ProjectManager.createNewAppDirProject(this, name)
                if (createdDir != null) {
                    switchActiveAppProject(createdDir)
                    Toast.makeText(
                        this,
                        "Created ${createdDir.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun switchActiveAppProject(projectDir: File) {
        ProjectManager.switchToAppDirProject(this, projectDir)
        expandedFolderKeys.clear()
        openTabs.clear()
        activeTabIndex = -1
        openInitialWorkspaceTabs()
        updateToolbarProjectTitle()
        refreshActiveSidebarPage()
    }

    private fun openProjectSidebar() {
        refreshActiveSidebarPage()
        binding.mainDrawerLayout.openDrawer(GravityCompat.START)
    }

    private fun closeProjectSidebar() {
        binding.mainDrawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun refreshFileTree() {
        val activeDir = ProjectManager.ensureDefaultLocalWorkspace(this)
        val activeName = activeDir.name
        binding.projectTitleText.text = "$activeName ▾"
        binding.projectModeSubtitle.text = ProjectManager.guestPathForDir(this, activeDir)
        lastTreeSignature = ProjectManager.computeTreeSignature(this)
        updateToolbarProjectTitle()

        val container = binding.fileTreeContainer
        container.removeAllViews()

        val fgColor = editorController.currentForegroundColor()
        val subtleColor = editorController.currentSubtleColor()
        val accentColor = ModernUiHelper.PRIMARY_COLOR
        val activeFile = currentFile
        val density = resources.displayMetrics.density

        val nodesToRender = ArrayList<ProjectManager.FileNode>()
        if (treeFilterQuery.isNotEmpty()) {
            val all = ProjectManager.collectAllProjectFiles(this, maxFiles = 500)
            nodesToRender.addAll(
                all.filter {
                    it.name.contains(treeFilterQuery, ignoreCase = true) ||
                        it.relativePath.contains(treeFilterQuery, ignoreCase = true)
                }
            )
        } else {
            val roots = ProjectManager.listRootNodes(this)
            if (expandedFolderKeys.isEmpty()) {
                roots.firstOrNull { it.isDirectory && it.name == "src" }?.let {
                    expandedFolderKeys.add(it.uniqueKey)
                }
            }
            collectVisibleTreeNodes(roots, nodesToRender)
        }

        val gitStatusMap = GitHubManager.getProjectGitStatusMap(this, activeDir)

        for (node in nodesToRender) {
            val isExpanded = expandedFolderKeys.contains(node.uniqueKey)
            val isCurrentOpenFile = !node.isDirectory && activeFile != null &&
                activeFile.matchesTarget(node.safDocUri, node.localFile)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (38 * density).toInt()
                val depthToUse = if (treeFilterQuery.isNotEmpty()) 0 else node.depth
                val indentPx = (depthToUse * 16 + 10) * density
                setPadding(
                    indentPx.toInt(),
                    (6 * density).toInt(),
                    (10 * density).toInt(),
                    (6 * density).toInt()
                )
                if (isCurrentOpenFile) {
                    setBackgroundColor(ModernUiHelper.withAlpha(accentColor, 0x22))
                }
                setOnClickListener {
                    if (node.isDirectory) {
                        if (isExpanded) {
                            expandedFolderKeys.remove(node.uniqueKey)
                        } else {
                            expandedFolderKeys.add(node.uniqueKey)
                        }
                        refreshFileTree()
                    } else {
                        if (node.localFile != null) {
                            openLocalFileInTab(node.localFile)
                        } else if (node.safDocUri != null) {
                            openUriInTab(node.safDocUri, node.name)
                        }
                        closeProjectSidebar()
                    }
                }
                setOnLongClickListener {
                    showNodeContextMenu(node)
                    true
                }
            }

            if (treeFilterQuery.isEmpty() && node.depth > 0) {
                val guideBar = View(this).apply {
                    setBackgroundColor(ModernUiHelper.withAlpha(subtleColor, 0x38))
                    layoutParams = LinearLayout.LayoutParams(
                        (1.5f * density).toInt().coerceAtLeast(1),
                        (20 * density).toInt()
                    ).apply {
                        marginEnd = (6 * density).toInt()
                    }
                }
                row.addView(guideBar)
            }

            val chevron = MaterialTextView(this).apply {
                text = when {
                    !node.isDirectory -> " "
                    isExpanded -> "▾"
                    else -> "▸"
                }
                textSize = 12f
                setTextColor(subtleColor)
                layoutParams = LinearLayout.LayoutParams(
                    (14 * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(chevron)

            val iconDrawable = if (node.isDirectory) {
                MaterialFileIcons.createFolderDrawable(this, node.name, isExpanded, 18)
            } else {
                MaterialFileIcons.createFileDrawable(this, node.name, 18)
            }
            val iconView = ImageView(this).apply {
                setImageDrawable(iconDrawable)
                layoutParams = LinearLayout.LayoutParams(
                    (18 * density).toInt(),
                    (18 * density).toInt()
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
            }
            row.addView(iconView)

            val labelView = MaterialTextView(this).apply {
                text = if (treeFilterQuery.isNotEmpty()) node.relativePath else node.name
                textSize = 13f
                setTextColor(if (isCurrentOpenFile) accentColor else fgColor)
                typeface = if (isCurrentOpenFile) ModernUiHelper.appFontBold(this@MainActivity) else ModernUiHelper.appFontMedium(this@MainActivity)
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(labelView)

            val gitStatus = gitStatusMap[node.relativePath]
            if (gitStatus != null) {
                val gitBadge = MaterialTextView(this).apply {
                    text = gitStatus.badge
                    textSize = 10.5f
                    typeface = ModernUiHelper.appFontBold(this@MainActivity)
                    setTextColor(Color.parseColor(gitStatus.colorHex))
                    setPadding((5 * density).toInt(), 0, (5 * density).toInt(), 0)
                }
                row.addView(gitBadge)
            }

            val moreBtn = ImageButton(this, null, 0, R.style.PocketDev_IconButton).apply {
                setImageResource(R.drawable.ic_more_vert)
                imageTintList = ColorStateList.valueOf(subtleColor)
                alpha = 0.75f
                setPadding(
                    (5 * density).toInt(),
                    (5 * density).toInt(),
                    (5 * density).toInt(),
                    (5 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    (28 * density).toInt(),
                    (28 * density).toInt()
                )
                setOnClickListener {
                    showNodeContextMenu(node)
                }
            }
            row.addView(moreBtn)

            container.addView(row)
        }
    }

    private fun collectVisibleTreeNodes(
        nodes: List<ProjectManager.FileNode>,
        out: MutableList<ProjectManager.FileNode>
    ) {
        for (n in nodes) {
            out.add(n)
            if (n.isDirectory && expandedFolderKeys.contains(n.uniqueKey)) {
                val children = ProjectManager.listChildNodes(this, n)
                collectVisibleTreeNodes(children, out)
            }
        }
    }

    private fun showNodeContextMenu(node: ProjectManager.FileNode) {
        val options = if (node.isDirectory) {
            listOf(
                ModernUiHelper.MenuOption(
                    id = 0,
                    title = "New File",
                    subtitle = "Create a new file in ${node.name}",
                    iconRes = R.drawable.ic_new_file,
                    action = { promptCreateInProject(node, isDirectory = false) }
                ),
                ModernUiHelper.MenuOption(
                    id = 1,
                    title = "New Folder",
                    subtitle = "Create a subfolder in ${node.name}",
                    iconRes = R.drawable.ic_folder,
                    action = { promptCreateInProject(node, isDirectory = true) }
                ),
                ModernUiHelper.MenuOption(
                    id = 2,
                    title = "Open in Terminal",
                    subtitle = "Launch terminal in ${node.name}",
                    iconRes = R.drawable.ic_terminal,
                    action = {
                        closeProjectSidebar()
                        openTerminalInDirectory(node.localFile ?: ProjectManager.ensureDefaultLocalWorkspace(this))
                    }
                ),
                ModernUiHelper.MenuOption(
                    id = 3,
                    title = "Rename",
                    subtitle = "Rename folder",
                    iconRes = R.drawable.ic_edit,
                    action = { promptRenameNode(node) }
                ),
                ModernUiHelper.MenuOption(
                    id = 4,
                    title = "Delete",
                    subtitle = "Remove folder and its contents",
                    iconRes = R.drawable.ic_delete,
                    isDestructive = true,
                    action = { promptDeleteNode(node) }
                )
            )
        } else {
            listOf(
                ModernUiHelper.MenuOption(
                    id = 0,
                    title = "Open",
                    subtitle = "Open in editor",
                    iconRes = R.drawable.ic_new_file,
                    action = {
                        node.localFile?.let { openLocalFileInTab(it) }
                        node.safDocUri?.let { openUriInTab(it, node.name) }
                        closeProjectSidebar()
                    }
                ),
                ModernUiHelper.MenuOption(
                    id = 10,
                    title = "Run",
                    subtitle = "Run with ${BuildRunManager.defaultRunConfigForFile(node.name).displayName}",
                    iconRes = R.drawable.ic_play,
                    action = {
                        node.localFile?.let { f ->
                            openLocalFileInTab(f, switchImmediately = true)
                            closeProjectSidebar()
                            compileAndRunCurrentFile()
                        }
                    }
                ),
                ModernUiHelper.MenuOption(
                    id = 11,
                    title = "View Diff",
                    subtitle = "Inspect working tree changes",
                    iconRes = R.drawable.ic_git,
                    action = {
                        closeProjectSidebar()
                        GitHubManager.showDiffViewerDialog(
                            context = this,
                            projectDir = ProjectManager.ensureDefaultLocalWorkspace(this),
                            targetFile = node.localFile
                        )
                    }
                ),
                ModernUiHelper.MenuOption(
                    id = 1,
                    title = "Open in Terminal",
                    subtitle = "Launch terminal in parent folder",
                    iconRes = R.drawable.ic_terminal,
                    action = {
                        closeProjectSidebar()
                        val dir = node.localFile?.parentFile ?: ProjectManager.ensureDefaultLocalWorkspace(this)
                        openTerminalInDirectory(dir)
                    }
                ),
                ModernUiHelper.MenuOption(
                    id = 2,
                    title = "Rename",
                    subtitle = "Rename file",
                    iconRes = R.drawable.ic_edit,
                    action = { promptRenameNode(node) }
                ),
                ModernUiHelper.MenuOption(
                    id = 3,
                    title = "Delete",
                    subtitle = "Permanently remove file",
                    iconRes = R.drawable.ic_delete,
                    isDestructive = true,
                    action = { promptDeleteNode(node) }
                )
            )
        }

        ModernUiHelper.showModernContextMenu(
            context = this,
            title = node.name,
            subtitle = node.relativePath,
            headerIconRes = if (node.isDirectory) R.drawable.ic_folder else R.drawable.ic_edit,
            options = options
        )
    }

    private fun promptDeleteNode(node: ProjectManager.FileNode) {
        val itemType = if (node.isDirectory) "Folder" else "File"
        val details = if (node.isDirectory) {
            "Delete the folder '${node.name}' and all of its contents? This action cannot be undone."
        } else {
            "Delete '${node.name}'? This action cannot be undone."
        }

        ModernUiHelper.showModernConfirmDialog(
            context = this,
            title = "Delete $itemType?",
            message = details,
            confirmText = "Delete",
            iconRes = R.drawable.ic_delete,
            isDestructive = true
        ) {
            node.localFile?.let { deletedTarget ->
                if (node.isDirectory) {
                    val prefix = deletedTarget.absolutePath
                    val tabsToClose = openTabs.mapIndexedNotNull { idx, tab ->
                        val tf = tab.localFile
                        if (tf != null && (tf.absolutePath == prefix || tf.absolutePath.startsWith("$prefix/"))) idx else null
                    }.sortedDescending()
                    for (idx in tabsToClose) {
                        closeTabAt(idx)
                    }
                    expandedFolderKeys.removeIf { it == prefix || it.startsWith("$prefix/") }
                } else {
                    val openIdx = openTabs.indexOfFirst { it.matchesTarget(null, deletedTarget) }
                    if (openIdx >= 0) closeTabAt(openIdx)
                }
            }
            val deleted = ProjectManager.deleteNode(this, node)
            refreshFileTree()
            if (deleted) {
                Toast.makeText(this, "Deleted ${node.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not delete ${node.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptRenameNode(node: ProjectManager.FileNode) {
        val oldFile = node.localFile
        ModernUiHelper.showModernInputDialog(
            context = this,
            title = if (node.isDirectory) "Rename Folder" else "Rename File",
            subtitle = node.relativePath,
            initialValue = node.name,
            hint = if (node.isDirectory) "folder_name" else "FileName.kt",
            positiveText = "Rename"
        ) { newName ->
            if (newName.isNotEmpty() && newName != node.name) {
                val renamedNode = ProjectManager.renameNode(this, node, newName)
                if (renamedNode != null && renamedNode.localFile != null) {
                    val newFile = renamedNode.localFile
                    if (oldFile != null) {
                        if (node.isDirectory) {
                            val oldPrefix = oldFile.absolutePath
                            val newPrefix = newFile.absolutePath
                            val updatedKeys = expandedFolderKeys.map { k ->
                                if (k == oldPrefix || k.startsWith("$oldPrefix/")) {
                                    newPrefix + k.removePrefix(oldPrefix)
                                } else {
                                    k
                                }
                            }
                            expandedFolderKeys.clear()
                            expandedFolderKeys.addAll(updatedKeys)

                            openTabs.forEach { tab ->
                                val tf = tab.localFile
                                if (tf != null && tf.absolutePath.startsWith("$oldPrefix/")) {
                                    val relInside = tf.absolutePath.removePrefix("$oldPrefix/")
                                    tab.localFile = File(newFile, relInside)
                                }
                            }
                        } else {
                            openTabs.forEachIndexed { idx, tab ->
                                if (tab.matchesTarget(null, oldFile)) {
                                    tab.localFile = newFile
                                    tab.displayName = newFile.name
                                    if (idx == activeTabIndex) {
                                        editorController.setLanguageFor(newFile.name, forceReload = true)
                                        runLspAnalysisForCurrentFile()
                                        updateStatusLine()
                                    }
                                }
                            }
                        }
                    }
                    renderTabs()
                    refreshFileTree()
                    Toast.makeText(this, "Renamed to ${renamedNode.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not rename to $newName", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun promptCreateInProject(parentNode: ProjectManager.FileNode?, isDirectory: Boolean) {
        ModernUiHelper.showModernInputDialog(
            context = this,
            title = if (isDirectory) "New Folder" else "New File",
            subtitle = parentNode?.relativePath ?: ProjectManager.activeProjectName(this),
            hint = if (isDirectory) "folder_name" else "NewFile.kt",
            positiveText = "Create"
        ) { name ->
            if (name.isNotEmpty()) {
                val created = ProjectManager.createInProject(this, parentNode, name, isDirectory)
                if (created != null) {
                    parentNode?.let { expandedFolderKeys.add(it.uniqueKey) }
                    refreshFileTree()
                    if (!isDirectory) {
                        created.localFile?.let { openLocalFileInTab(it) }
                        created.safDocUri?.let { openUriInTab(it, created.name) }
                        closeProjectSidebar()
                    }
                } else {
                    Toast.makeText(this, "Could not create $name", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupSearchBar() {
        TooltipCompat.setTooltipText(binding.btnToggleReplaceRow, "Toggle Replace")
        TooltipCompat.setTooltipText(binding.btnSearchCase, "Match Case")
        TooltipCompat.setTooltipText(binding.btnSearchRegex, "Regular Expression")
        TooltipCompat.setTooltipText(binding.btnSearchProjectMode, "Find in Project")

        binding.btnToggleReplaceRow.setOnClickListener {
            isReplaceRowExpanded = !isReplaceRowExpanded
            updateSearchModeUi()
        }
        binding.btnSearchClose.setOnClickListener { hideFindBar() }
        binding.btnSearchNext.setOnClickListener { binding.editor.searcher.gotoNext() }
        binding.btnSearchPrev.setOnClickListener { binding.editor.searcher.gotoPrevious() }
        binding.btnSearchCase.setOnClickListener {
            searchCaseSensitive = !searchCaseSensitive
            updateSearchFilterButtonPills()
            commitSearch()
        }
        binding.btnSearchRegex.setOnClickListener {
            searchRegex = !searchRegex
            updateSearchFilterButtonPills()
            commitSearch()
        }
        binding.btnSearchProjectMode.setOnClickListener {
            isProjectSearchMode = !isProjectSearchMode
            updateSearchModeUi()
            commitSearch()
        }
        binding.btnReplaceOne.setOnClickListener {
            val replacement = binding.replaceInput.text?.toString() ?: ""
            runCatching {
                if (binding.editor.searcher.hasQuery()) {
                    binding.editor.searcher.replaceThis(replacement)
                }
            }
        }
        binding.btnReplaceAll.setOnClickListener {
            val replacement = binding.replaceInput.text?.toString() ?: ""
            runCatching {
                if (binding.editor.searcher.hasQuery()) {
                    binding.editor.searcher.replaceAll(replacement)
                }
            }
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = commitSearch()
        })
    }

    private fun updateSearchFilterButtonPills() {
        val fgColor = editorController.currentForegroundColor()
        val subtleColor = editorController.currentSubtleColor()
        val accentColor = ModernUiHelper.PRIMARY_COLOR
        val density = resources.displayMetrics.density

        fun styleFilterToggle(btn: ImageButton, active: Boolean) {
            btn.imageTintList = ColorStateList.valueOf(if (active) accentColor else subtleColor)
            btn.background = if (active) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * density
                    setColor(ModernUiHelper.withAlpha(accentColor, 0x24))
                    setStroke((1 * density).toInt().coerceAtLeast(1), ModernUiHelper.withAlpha(accentColor, 0x70))
                }
            } else {
                null
            }
        }

        styleFilterToggle(binding.btnSearchCase, searchCaseSensitive)
        styleFilterToggle(binding.btnSearchRegex, searchRegex)
        styleFilterToggle(binding.btnSearchProjectMode, isProjectSearchMode)
        binding.btnToggleReplaceRow.imageTintList = ColorStateList.valueOf(
            if (isReplaceRowExpanded && !isProjectSearchMode) accentColor else fgColor
        )
    }

    private fun updateSearchModeUi() {
        val showReplace = isReplaceRowExpanded && !isProjectSearchMode
        ModernUiHelper.animatePanelVisibility(binding.replaceRow, showReplace, slideOffsetDp = 8f, durationMs = 170L)
        binding.btnToggleReplaceRow.visibility = if (isProjectSearchMode) View.GONE else View.VISIBLE
        binding.btnToggleReplaceRow.setImageResource(
            if (showReplace) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
        updateSearchFilterButtonPills()
        if (isProjectSearchMode) {
            selectBottomPanelTab(TAB_SEARCH_RESULTS, expand = true)
        }
    }

    private fun toggleFindBar() {
        if (binding.findBar.visibility == View.VISIBLE) {
            hideFindBar()
        } else {
            showFindBar(projectScope = false)
        }
    }

    private fun showFindBar(projectScope: Boolean) {
        isProjectSearchMode = projectScope
        updateSearchModeUi()
        ModernUiHelper.animatePanelVisibility(binding.findBar, true, slideOffsetDp = -10f, durationMs = 190L)
        binding.searchInput.requestFocus()
        commitSearch()
    }

    private fun hideFindBar() {
        ModernUiHelper.animatePanelVisibility(binding.findBar, false, slideOffsetDp = -10f, durationMs = 170L)
        binding.editor.searcher.stopSearch()
        binding.searchInput.setText("")
    }

    private fun commitSearch() {
        val query = binding.searchInput.text?.toString() ?: ""
        if (query.isEmpty()) {
            binding.editor.searcher.stopSearch()
            binding.searchStatus.text = getString(R.string.search_no_match)
            binding.projectSearchResultsContainer.removeAllViews()
            return
        }

        try {
            binding.editor.searcher.search(
                query,
                EditorSearcher.SearchOptions(searchCaseSensitive, searchRegex)
            )
        } catch (e: Exception) {
            updateSearchStatus()
        }

        if (isProjectSearchMode) {
            saveCurrentTabBufferState()
            val snapshotTabs = ArrayList(openTabs)
            Thread {
                val summary = ProjectSearchEngine.searchAcrossProject(
                    context = this,
                    query = query,
                    caseSensitive = searchCaseSensitive,
                    useRegex = searchRegex,
                    openTabs = snapshotTabs
                )
                runOnUiThread {
                    renderProjectSearchResults(summary)
                }
            }.start()
        }
    }

    private fun renderProjectSearchResults(summary: ProjectSearchEngine.SearchSummary) {
        val container = binding.projectSearchResultsContainer
        container.removeAllViews()

        val fgColor = editorController.currentForegroundColor()
        val density = resources.displayMetrics.density

        binding.searchStatus.text = String.format(
            "%d results (%.1f ms)",
            summary.matches.size,
            summary.elapsedMillis
        )

        val baseTitle = getString(R.string.tab_search_results)
        binding.bottomSheetTabs.getTabAt(TAB_SEARCH_RESULTS)?.text =
            if (summary.matches.isNotEmpty()) "$baseTitle (${summary.matches.size})" else baseTitle

        for (match in summary.matches.take(150)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (34 * density).toInt()
                setPadding(
                    (6 * density).toInt(),
                    (5 * density).toInt(),
                    (6 * density).toInt(),
                    (5 * density).toInt()
                )
                setOnClickListener {
                    if (match.localFile != null) {
                        openLocalFileInTab(
                            match.localFile,
                            switchImmediately = true,
                            jumpLine = match.lineNumber,
                            jumpCol = match.columnNumber
                        )
                    } else if (match.safUri != null) {
                        openUriInTab(
                            match.safUri,
                            displayNameOverride = match.fileName,
                            jumpLine = match.lineNumber,
                            jumpCol = match.columnNumber
                        )
                    }
                }
            }

            val icon = ImageView(this).apply {
                setImageDrawable(MaterialFileIcons.createFileDrawable(this@MainActivity, match.fileName, 15))
                layoutParams = LinearLayout.LayoutParams(
                    (15 * density).toInt(),
                    (15 * density).toInt()
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
            }
            row.addView(icon)

            val textView = MaterialTextView(this).apply {
                text = "${match.relativePath}:${match.lineNumber}  ${match.linePreview}"
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setTextColor(fgColor)
                setSingleLine(true)
            }
            row.addView(textView)

            container.addView(row)
        }
    }

    private fun updateSearchStatus() {
        if (isProjectSearchMode) return
        val searcher = binding.editor.searcher
        if (!searcher.hasQuery()) {
            binding.searchStatus.text = getString(R.string.search_no_match)
            return
        }
        val count = searcher.getMatchedPositionCount()
        if (count == 0) {
            binding.searchStatus.text = getString(R.string.search_no_match)
        } else {
            binding.searchStatus.text =
                getString(R.string.search_matches, searcher.getCurrentMatchedPositionIndex() + 1, count)
        }
    }

    private fun setupEditorEvents() {
        binding.stickyScrollBar.setOnClickListener {
            val line1 = (binding.stickyScrollBar.tag as? Int) ?: return@setOnClickListener
            jumpToPosition(line1, 0)
        }
        binding.editor.subscribeAlways<SelectionChangeEvent> {
            updateStatusLine()
            editorController.updateWordMatchHighlight(binding.findBar.visibility == View.VISIBLE)
            updateStickyScrollHeader()
        }
        binding.editor.subscribeAlways<ScrollEvent> {
            updateStickyScrollHeader()
            binding.editorFoldGutter.postInvalidateOnAnimation()
        }
        binding.editor.subscribeAlways<ContentChangeEvent> { event ->
            val isCharAddedOrRemoved = event.action == ContentChangeEvent.ACTION_INSERT ||
                event.action == ContentChangeEvent.ACTION_DELETE
            if (!suppressModifiedEvent && isCharAddedOrRemoved) {
                if (event.action == ContentChangeEvent.ACTION_INSERT &&
                    event.changedText.length == 1 &&
                    PluginManager.isEnabled(this, "auto-close-pairs")
                ) {
                    val ch = event.changedText[0]
                    val closing = when (ch) {
                        '(' -> ")"
                        '[' -> "]"
                        '"' -> "\""
                        '\'' -> "'"
                        '`' -> "`"
                        else -> null
                    }
                    if (closing != null) {
                        val line = event.changeEnd.line
                        val col = event.changeEnd.column
                        val lineStr = runCatching { binding.editor.text.getLineString(line) }.getOrDefault("")
                        val nextCh = lineStr.getOrNull(col)
                        if (nextCh == null || nextCh.isWhitespace() || nextCh in listOf(')', ']', '}', ',', ';')) {
                            suppressModifiedEvent = true
                            try {
                                binding.editor.text.insert(line, col, closing)
                                binding.editor.setSelection(line, col)
                            } finally {
                                suppressModifiedEvent = false
                            }
                        }
                    }
                }

                val currentBuffer = binding.editor.text.toString()
                val tab = currentFile
                if (tab != null && tab.content != currentBuffer) {
                    tab.content = currentBuffer
                    if (!tab.modified) {
                        tab.modified = true
                        renderTabs()
                    }
                }
                if (settings.autoSaveEnabled) {
                    autoSaveHandler.removeCallbacks(autoSaveRunnable)
                    autoSaveHandler.postDelayed(autoSaveRunnable, settings.autoSaveDelayMs.toLong())
                }
                binding.editorFoldGutter.refreshFoldableLines(force = false)
                runLspAnalysisForCurrentFile(force = false, notifyStdioServer = true)
            }
            updateStatusLine()
        }
        binding.editor.subscribeAlways<PublishSearchResultEvent> { updateSearchStatus() }
    }

    private fun performAutoSaveModifiedTabs() {
        saveCurrentTabBufferState()
        var anySaved = false
        for (tab in openTabs) {
            if (!tab.modified) continue
            if (tab.localFile == null && tab.uri == null) {
                val workspaceRoot = ProjectManager.ensureDefaultLocalWorkspace(this)
                tab.localFile = File(workspaceRoot, tab.displayName)
            }
            if (writeTabToStorage(tab, showToast = false)) {
                anySaved = true
            }
        }
        if (anySaved && binding.mainDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            refreshActiveSidebarPage()
        }
    }

    private fun saveDocument() {
        val current = currentFile ?: return
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        saveCurrentTabBufferState()

        if (settings.saveAllOnSavePress) {
            val targets = openTabs.filter { it.modified || it === current }
            var savedCount = 0
            for (tab in targets) {
                if (tab.localFile == null && tab.uri == null) {
                    val workspaceRoot = ProjectManager.ensureDefaultLocalWorkspace(this)
                    tab.localFile = File(workspaceRoot, tab.displayName)
                }
                if (writeTabToStorage(tab, showToast = false)) {
                    savedCount++
                }
            }
            refreshFileTree()
            val msg = if (savedCount > 1) {
                "Saved $savedCount files"
            } else {
                "Saved ${current.displayName}"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        if (current.localFile == null && current.uri == null) {
            val workspaceRoot = ProjectManager.ensureDefaultLocalWorkspace(this)
            current.localFile = File(workspaceRoot, current.displayName)
        }
        if (current.localFile != null || current.uri != null) {
            writeTabToStorage(current, showToast = true)
            refreshFileTree()
        } else {
            saveAsLauncher.launch(current.displayName)
        }
    }

    private fun writeTabToStorage(tab: OpenedFile, showToast: Boolean = true): Boolean {
        return try {
            if (tab === currentFile) {
                val rawBuf = binding.editor.text.toString()
                val processed = PluginManager.applyPreSaveHooks(this, tab.displayName, rawBuf)
                if (processed != rawBuf) {
                    val curLine = binding.editor.cursor.leftLine
                    val curCol = binding.editor.cursor.leftColumn
                    suppressModifiedEvent = true
                    try {
                        binding.editor.setText(processed)
                        val safeL = curLine.coerceIn(0, (binding.editor.text.lineCount - 1).coerceAtLeast(0))
                        val maxC = binding.editor.text.getLineString(safeL).length
                        binding.editor.setSelection(safeL, curCol.coerceIn(0, maxC))
                    } finally {
                        suppressModifiedEvent = false
                    }
                }
                tab.content = processed
            } else {
                tab.content = PluginManager.applyPreSaveHooks(this, tab.displayName, tab.content)
            }
            val local = tab.localFile
            val targetUri = tab.uri
            if (local != null) {
                local.parentFile?.mkdirs()
                local.writeText(tab.content, Charsets.UTF_8)
                ProjectManager.ensureProjectFilesExecutable(local.parentFile ?: local)
            } else if (targetUri != null) {
                contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                    out.write(tab.content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } else {
                return false
            }
            tab.modified = false
            GitHubManager.invalidateStatusCache()
            renderTabs()
            if (showToast) {
                Toast.makeText(this, "Saved ${tab.displayName}", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            if (showToast) {
                Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    private fun updateStatusLine() {
        val cursor = binding.editor.cursor
        binding.positionText.text =
            getString(R.string.line_col, cursor.leftLine + 1, cursor.leftColumn)

        val fileName = currentFile?.displayName ?: "No File"
        val spec = MaterialFileIcons.resolveFileSpec(fileName)
        binding.languageStatusText.text = spec.badgeText
    }

    private fun updateStickyScrollHeader() {
        if (!settings.stickyScrollEnabled || openTabs.isEmpty()) {
            binding.stickyScrollBar.visibility = View.GONE
            return
        }
        val firstLine0 = runCatching { binding.editor.firstVisibleLine }.getOrDefault(0)
        val info = editorController.resolveStickyHeaderForLine(firstLine0)
        if (info == null) {
            binding.stickyScrollBar.visibility = View.GONE
        } else {
            binding.stickyScrollBar.tag = info.line1Based
            binding.stickyScrollLineBadge.text = "Ln ${info.line1Based}"
            binding.stickyScrollText.text = info.signature
            binding.stickyScrollBar.visibility = View.VISIBLE
        }
    }

    private fun compileAndRunCurrentFile() {
        val current = currentFile ?: return
        editorController.unfoldAllBlocks()
        saveDocument()
        val localFile = current.localFile ?: File(ProjectManager.ensureDefaultLocalWorkspace(this), current.displayName)
        val cfg = BuildRunManager.loadRunConfig(this, localFile.name)

        if (cfg.runInTerminal) {
            val cmd = BuildRunManager.buildExpandedShellCommand(this, localFile, cfg)
            openTerminalInDirectory(localFile.parentFile ?: ProjectManager.ensureDefaultLocalWorkspace(this), cmd)
            return
        }

        selectBottomPanelTab(TAB_BUILD_OUTPUT, expand = true)
        binding.buildOutputContainer.removeAllViews()
        binding.btnBottomSheetStop.visibility = View.VISIBLE
        val baseTitle = getString(R.string.tab_build_output)
        binding.bottomSheetTabs.getTabAt(TAB_BUILD_OUTPUT)?.text = "$baseTitle (Running…)"

        BuildRunManager.executeFileStreaming(
            context = this,
            file = localFile,
            config = cfg,
            onOutputLine = { line ->
                runOnUiThread { appendBuildOutputLine(line) }
            },
            onFinished = { exitCode, elapsedMs ->
                runOnUiThread {
                    binding.btnBottomSheetStop.visibility = View.GONE
                    val statusLine = if (exitCode == 0) {
                        "✓ Process finished with exit code 0 (${elapsedMs} ms)"
                    } else {
                        "✗ Process exited with code $exitCode (${elapsedMs} ms)"
                    }
                    appendBuildOutputLine(statusLine, isStatusSummary = true, isError = exitCode != 0)
                    binding.bottomSheetTabs.getTabAt(TAB_BUILD_OUTPUT)?.text =
                        if (exitCode == 0) "$baseTitle (✓)" else "$baseTitle (Exit $exitCode)"
                    refreshFileTree()
                }
            }
        )
    }

    private fun appendBuildOutputLine(lineText: String, isStatusSummary: Boolean = false, isError: Boolean = false) {
        val density = resources.displayMetrics.density
        val fgColor = editorController.currentForegroundColor()
        val color = when {
            isStatusSummary && !isError -> Color.parseColor("#66BB6A")
            isStatusSummary && isError -> Color.parseColor("#FF5252")
            lineText.startsWith("▶") || lineText.startsWith("›") -> ModernUiHelper.PRIMARY_LIGHT_COLOR
            lineText.contains("error:", ignoreCase = true) || lineText.contains("Exception") -> Color.parseColor("#FF7B72")
            lineText.contains("warning:", ignoreCase = true) -> Color.parseColor("#FFB300")
            else -> fgColor
        }

        val tv = MaterialTextView(this).apply {
            text = lineText
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(color)
            if (isStatusSummary || lineText.startsWith("▶")) {
                setTypeface(typeface, Typeface.BOLD)
            }
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())

            val locMatch = Regex("([a-zA-Z0-9_./-]+\\.[a-zA-Z0-9]+):(\\d+)(?::(\\d+))?").find(lineText)
            if (locMatch != null) {
                val lineNum = locMatch.groupValues[2].toIntOrNull() ?: 1
                val colNum = (locMatch.groupValues.getOrNull(3)?.toIntOrNull() ?: 1) - 1
                setOnClickListener {
                    jumpToPosition(lineNum, colNum.coerceAtLeast(0))
                }
            }
        }
        binding.buildOutputContainer.addView(tv)
        binding.buildOutputScroll.post {
            binding.buildOutputScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showRunConfigForCurrentFile() {
        val fileName = currentFile?.displayName ?: "Main.kt"
        BuildRunManager.showRunConfigDialog(this, fileName) {
            compileAndRunCurrentFile()
        }
    }

    private fun formatCurrentDocument() {
        val current = currentFile ?: return
        editorController.unfoldAllBlocks()
        val rawText = binding.editor.text.toString()
        if (rawText.isBlank()) return
        val cursorLine = binding.editor.cursor.leftLine
        val cursorCol = binding.editor.cursor.leftColumn
        if (current.localFile != null) {
            writeTabToStorage(current, showToast = false)
        }
        BuildRunManager.formatDocumentAsync(
            context = this,
            fileName = current.displayName,
            content = rawText,
            localFile = current.localFile
        ) { formattedText, label ->
            if (formattedText != rawText) {
                binding.editor.setText(formattedText)
                val safeLine = cursorLine.coerceIn(0, (binding.editor.text.lineCount - 1).coerceAtLeast(0))
                val maxCol = binding.editor.text.getLineString(safeLine).length
                binding.editor.setSelection(safeLine, cursorCol.coerceIn(0, maxCol))
                current.content = formattedText
                current.modified = true
                renderTabs()
                runLspAnalysisForCurrentFile()
            }
            Toast.makeText(this, "Formatted with $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPluginsDialogForCurrentFile() {
        val current = currentFile
        val fileName = current?.displayName ?: "untitled.kt"
        val currentText = binding.editor.text.toString()
        val cursorLine0 = binding.editor.cursor.leftLine
        PluginManager.showEditorPluginsBottomSheet(
            context = this,
            fileName = fileName,
            currentText = currentText,
            cursorLine0 = cursorLine0,
            localFile = current?.localFile,
            onReplaceEditorText = { updatedText ->
                if (updatedText != currentText) {
                    val safeLine = cursorLine0.coerceIn(0, (updatedText.lines().size - 1).coerceAtLeast(0))
                    binding.editor.setText(updatedText)
                    binding.editor.setSelection(safeLine, 0)
                    current?.content = updatedText
                    current?.modified = true
                    renderTabs()
                    runLspAnalysisForCurrentFile(force = true)
                }
            },
            onInsertAtCursor = { snippet ->
                val cur = binding.editor.cursor
                binding.editor.text.insert(cur.leftLine, cur.leftColumn, snippet)
                runLspAnalysisForCurrentFile(force = true)
            },
            onJumpToLine = { line1, col0 ->
                jumpToPosition(line1, col0)
            },
            onOpenPluginsSettings = {
                startActivity(
                    Intent(this, SettingsActivity::class.java).apply {
                        putExtra(SettingsActivity.EXTRA_INITIAL_PAGE, SettingsActivity.PAGE_PLUGINS)
                    }
                )
            }
        )
    }

    private fun showLspCodeActionsDialog() {
        val fileName = currentFile?.displayName ?: "untitled.kt"
        val text = binding.editor.text.toString()
        val line0 = binding.editor.cursor.leftLine
        val col0 = binding.editor.cursor.leftColumn
        val symbol = LspManager.extractWordAtPosition(text, line0, col0)
        val symLabel = if (symbol.isNotEmpty()) "'$symbol'" else "symbol at cursor"

        val options = listOf(
            ModernUiHelper.MenuOption(
                id = 0,
                title = "Show Documentation",
                subtitle = "Signature and documentation for $symLabel",
                iconRes = R.drawable.ic_info,
                action = { showHoverDocumentationAtCursor() }
            ),
            ModernUiHelper.MenuOption(
                id = 1,
                title = "Go to Definition",
                subtitle = "Jump to declaration of $symLabel",
                iconRes = R.drawable.ic_lsp,
                action = { goToDefinitionAtCursor() }
            ),
            ModernUiHelper.MenuOption(
                id = 2,
                title = "Find References",
                subtitle = "Search all usages of $symLabel",
                iconRes = R.drawable.ic_search,
                action = { findReferencesAtCursor() }
            ),
            ModernUiHelper.MenuOption(
                id = 3,
                title = "Rename Symbol",
                subtitle = "Rename $symLabel across project",
                iconRes = R.drawable.ic_edit,
                action = { promptRenameSymbolAtCursor() }
            ),
            ModernUiHelper.MenuOption(
                id = 4,
                title = "Document Outline",
                subtitle = "View classes and functions in $fileName",
                iconRes = R.drawable.ic_outline,
                action = {
                    selectSidebarTab(SIDEBAR_TAB_OUTLINE)
                    openProjectSidebar()
                }
            ),

            ModernUiHelper.MenuOption(
                id = 7,
                title = "Format Document",
                subtitle = "Format code in $fileName",
                iconRes = R.drawable.ic_format_align,
                action = { formatCurrentDocument() }
            )
        )

        ModernUiHelper.showModernContextMenu(
            context = this,
            title = "Code Actions",
            subtitle = "$fileName • Ln ${line0 + 1}, Col $col0",
            headerIconRes = R.drawable.ic_lsp,
            options = options
        )
    }

    private fun showHoverDocumentationAtCursor() {
        val current = currentFile ?: return
        val text = binding.editor.text.toString()
        val line0 = binding.editor.cursor.leftLine
        val col0 = binding.editor.cursor.leftColumn
        Thread {
            val hover = LspManager.resolveHoverDocumentation(
                context = this,
                fileName = current.displayName,
                text = text,
                line0 = line0,
                col0 = col0,
                localFile = current.localFile
            )
            runOnUiThread {
                if (hover == null) {
                    Toast.makeText(this, "Place cursor on a symbol to view documentation", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                ModernUiHelper.showModernConfirmDialog(
                    context = this,
                    title = "${hover.symbol} • ${hover.sourceServer}",
                    message = "${hover.signature}\n\n${hover.documentation}",
                    confirmText = if (hover.declarationLocation != null) "Go to Definition" else "OK",
                    cancelText = "Close",
                    iconRes = R.drawable.ic_info,
                    isDestructive = false
                ) {
                    hover.declarationLocation?.let { loc ->
                        navigateToSymbolLocation(loc)
                    }
                }
            }
        }.start()
    }

    private fun goToDefinitionAtCursor() {
        val current = currentFile ?: return
        val text = binding.editor.text.toString()
        val line0 = binding.editor.cursor.leftLine
        val col0 = binding.editor.cursor.leftColumn
        Thread {
            val defs = LspManager.findDefinitions(
                context = this,
                fileName = current.displayName,
                text = text,
                line0 = line0,
                col0 = col0,
                localFile = current.localFile
            )
            runOnUiThread {
                when {
                    defs.isEmpty() -> {
                        Toast.makeText(this, "No definition found", Toast.LENGTH_SHORT).show()
                    }
                    defs.size == 1 -> {
                        navigateToSymbolLocation(defs.first())
                    }
                    else -> {
                        val options = defs.mapIndexed { idx, loc ->
                            ModernUiHelper.MenuOption(
                                id = idx,
                                title = "${loc.relativePath}:${loc.line1Based}",
                                subtitle = loc.preview,
                                iconRes = R.drawable.ic_lsp,
                                action = { navigateToSymbolLocation(loc) }
                            )
                        }
                        ModernUiHelper.showModernContextMenu(
                            context = this,
                            title = "Definitions (${defs.size})",
                            subtitle = "Select declaration to open",
                            headerIconRes = R.drawable.ic_lsp,
                            options = options
                        )
                    }
                }
            }
        }.start()
    }

    private fun findReferencesAtCursor() {
        val current = currentFile ?: return
        val text = binding.editor.text.toString()
        val line0 = binding.editor.cursor.leftLine
        val col0 = binding.editor.cursor.leftColumn
        val symbol = LspManager.extractWordAtPosition(text, line0, col0)
        if (symbol.isEmpty()) {
            Toast.makeText(this, "Place cursor on a symbol to find references", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val refs = LspManager.findReferences(
                context = this,
                fileName = current.displayName,
                text = text,
                line0 = line0,
                col0 = col0,
                localFile = current.localFile
            )
            runOnUiThread {
                val matches = refs.map { loc ->
                    ProjectSearchEngine.SearchMatch(
                        fileName = loc.fileName,
                        relativePath = loc.relativePath,
                        lineNumber = loc.line1Based,
                        columnNumber = loc.col0Based,
                        matchLength = symbol.length.coerceAtLeast(1),
                        linePreview = loc.preview,
                        localFile = loc.file,
                        safUri = null
                    )
                }
                renderProjectSearchResults(
                    ProjectSearchEngine.SearchSummary(
                        matches = matches,
                        filesScanned = matches.map { it.relativePath }.distinct().size,
                        elapsedMillis = 1.2
                    )
                )
                selectBottomPanelTab(TAB_SEARCH_RESULTS, expand = true)
                Toast.makeText(this, "Found ${refs.size} reference(s) for '$symbol'", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun promptRenameSymbolAtCursor() {
        val current = currentFile ?: return
        editorController.unfoldAllBlocks()
        val text = binding.editor.text.toString()
        val line0 = binding.editor.cursor.leftLine
        val col0 = binding.editor.cursor.leftColumn
        val symbol = LspManager.extractWordAtPosition(text, line0, col0)
        if (symbol.isEmpty()) {
            Toast.makeText(this, "Place cursor on an identifier to rename", Toast.LENGTH_SHORT).show()
            return
        }

        ModernUiHelper.showModernInputDialog(
            context = this,
            title = "Rename Symbol",
            subtitle = "Rename '$symbol' across project",
            initialValue = symbol,
            hint = "newName",
            positiveText = "Rename"
        ) { newName ->
            val clean = newName.trim()
            if (clean.isNotEmpty() && clean != symbol) {
                val summary = LspManager.renameSymbol(
                    context = this,
                    fileName = current.displayName,
                    text = text,
                    line0 = line0,
                    col0 = col0,
                    newName = clean,
                    localFile = current.localFile,
                    openTabs = openTabs
                )
                if (summary != null) {
                    binding.editor.setText(summary.updatedEditorText)
                    val safeLine = line0.coerceIn(0, (binding.editor.text.lineCount - 1).coerceAtLeast(0))
                    val maxCol = binding.editor.text.getLineString(safeLine).length
                    binding.editor.setSelection(safeLine, col0.coerceIn(0, maxCol))
                    current.content = summary.updatedEditorText
                    saveDocument()
                    runLspAnalysisForCurrentFile()
                    Toast.makeText(
                        this,
                        "Renamed '${summary.oldName}' to '${summary.newName}' (${summary.totalReplacements} in ${summary.modifiedFilesCount} file(s))",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "Invalid identifier '$clean'", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateToSymbolLocation(loc: LspManager.SymbolLocation) {
        if (loc.file != null && loc.file.exists()) {
            openLocalFileInTab(loc.file, switchImmediately = true, jumpLine = loc.line1Based, jumpCol = loc.col0Based)
        } else {
            jumpToPosition(loc.line1Based, loc.col0Based)
        }
    }

    private fun showGitHubIntegrationDialog() {
        saveCurrentTabBufferState()
        GitHubManager.showGitHubHubDialog(
            context = this,
            onProjectChanged = { clonedDir ->
                switchActiveAppProject(clonedDir)
            },
            onTreeRefreshRequested = {
                refreshActiveSidebarPage()
                updateToolbarProjectTitle()
            }
        )
    }

    companion object {
        const val TAB_CODE_ANALYSIS = 0
        const val TAB_SEARCH_RESULTS = 1
        const val TAB_BUILD_OUTPUT = 2

        const val SIDEBAR_TAB_FILES = 0
        const val SIDEBAR_TAB_GIT = 1
        const val SIDEBAR_TAB_OUTLINE = 2
    }
}
