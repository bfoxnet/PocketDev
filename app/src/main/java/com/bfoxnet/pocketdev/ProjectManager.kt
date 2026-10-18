package com.bfoxnet.pocketdev

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Manages projects inside the workspace directory (`/root/projects/<project-name>`).
 */
object ProjectManager {

    data class FileNode(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val depth: Int,
        val localFile: File? = null,
        val safTreeUri: Uri? = null,
        val safDocId: String? = null,
        val safDocUri: Uri? = null,
        val sizeBytes: Long = 0L,
        var isExpanded: Boolean = false
    ) {
        val uniqueKey: String
            get() = localFile?.absolutePath ?: safDocId ?: "$relativePath/$name"
    }

    private val IGNORED_DIRS = setOf(
        ".git", ".gradle", ".idea", "build", "node_modules", "__pycache__", ".DS_Store"
    )

    private val EXECUTABLE_EXTENSIONS = setOf(
        "sh", "bash", "zsh", "py", "pl", "rb", "js", "go", "kts", "bin", "out", "elf"
    )

    @Volatile
    private var cachedProjectsRoot: File? = null

    @Volatile
    private var cachedActiveWorkspace: File? = null

    @Volatile
    private var legacyWorkspaceChecked: Boolean = false

    fun invalidateWorkspaceCache() {
        cachedActiveWorkspace = null
        cachedProjectsRoot = null
    }

    fun ubuntuRootHomeDir(context: Context): File =
        File(UbuntuProotManager.ubuntuRootDir(context), "root").apply {
            if (!exists()) {
                mkdirs()
                setReadable(true, false)
                setExecutable(true, false)
            }
        }

    fun projectsRootDir(context: Context): File {
        cachedProjectsRoot?.let { cached ->
            if (cached.exists()) return cached
        }
        val ubuntuProjects = File(ubuntuRootHomeDir(context), "projects")
        if (!ubuntuProjects.exists()) {
            ubuntuProjects.mkdirs()
            ubuntuProjects.setReadable(true, false)
            ubuntuProjects.setExecutable(true, false)
        }
        if (!legacyWorkspaceChecked) {
            legacyWorkspaceChecked = true
            migrateLegacyWorkspaceIfNeeded(context, ubuntuProjects)
        }
        cachedProjectsRoot = ubuntuProjects
        return ubuntuProjects
    }

    private fun migrateLegacyWorkspaceIfNeeded(context: Context, ubuntuProjectsDir: File) {
        val legacyRoot = File(context.filesDir, "workspace")
        if (!legacyRoot.exists() || !legacyRoot.isDirectory) return
        runCatching {
            val children = legacyRoot.listFiles() ?: emptyArray()
            for (child in children) {
                if (child.isDirectory && !child.name.startsWith(".")) {
                    val dest = File(ubuntuProjectsDir, child.name)
                    if (!dest.exists()) {
                        child.copyRecursively(dest, overwrite = false)
                        ensureProjectFilesExecutable(dest)
                    }
                }
            }
            val settings = Settings(context)
            val oldLocalPath = settings.localProjectPath
            if (!oldLocalPath.isNullOrEmpty() && oldLocalPath.startsWith(legacyRoot.absolutePath)) {
                val projName = File(oldLocalPath).name
                val migratedDir = File(ubuntuProjectsDir, projName)
                if (migratedDir.exists()) {
                    settings.localProjectPath = migratedDir.absolutePath
                }
            }
            legacyRoot.deleteRecursively()
        }
    }

    fun guestPathForDir(context: Context, dir: File): String =
        UbuntuProotManager.hostDirToGuestPath(context, dir)

    /**
     * Lists all available project directories in `/root/projects` as well as any user-created
     * folders in `/root` so projects created via the IDE or terminal are always discoverable.
     */
    fun listAppDirProjects(context: Context): List<File> {
        ensureDefaultLocalWorkspace(context)
        val result = LinkedHashMap<String, File>()
        val root = projectsRootDir(context)
        root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { result[it.absolutePath] = it }

        val home = ubuntuRootHomeDir(context)
        home.listFiles()
            ?.filter {
                it.isDirectory &&
                    !it.name.startsWith(".") &&
                    it.name != "projects" &&
                    it.name != "snap"
            }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { result[it.absolutePath] = it }

        val active = ensureDefaultLocalWorkspace(context)
        if (active.exists() && active.isDirectory) {
            result[active.absolutePath] = active
        }

        return result.values.toList()
    }

    fun listInternalProjects(context: Context): List<File> = listAppDirProjects(context)

    fun createNewAppDirProject(context: Context, rawName: String): File? {
        val trimmed = rawName.trim()
            .removePrefix("/root/projects/")
            .removePrefix("~/projects/")
            .removePrefix("/root/")
            .trim('/')
        val cleanName = trimmed.replace(Regex("[^a-zA-Z0-9._/-]"), "_").trim('_')
        if (cleanName.isEmpty()) return null
        val targetDir = File(projectsRootDir(context), cleanName)
        if (!targetDir.exists()) {
            if (!targetDir.mkdirs()) return null
            seedStarterProject(targetDir, targetDir.name)
        }
        ensureProjectFilesExecutable(targetDir)
        val settings = Settings(context)
        settings.projectMode = "local"
        settings.localProjectPath = targetDir.absolutePath
        cachedActiveWorkspace = targetDir
        return targetDir
    }

    fun createInternalProject(context: Context, rawName: String): File? =
        createNewAppDirProject(context, rawName)

    /**
     * Resolves and opens an existing project folder by name or path (e.g., `my-app`,
     * `/root/projects/my-app`, or `~/my-app`).
     */
    fun openProjectByPath(context: Context, rawInput: String): File? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        val rootfs = UbuntuProotManager.ubuntuRootDir(context)
        val projectsRoot = projectsRootDir(context)
        val homeDir = ubuntuRootHomeDir(context)

        val candidates = listOf(
            File(projectsRoot, trimmed.removePrefix("/root/projects/").removePrefix("~/projects/").trim('/')),
            File(homeDir, trimmed.removePrefix("/root/").removePrefix("~/").trim('/')),
            File(rootfs, trimmed.trimStart('/')),
            File(trimmed)
        )
        val resolved = candidates.firstOrNull {
            it.exists() && it.isDirectory && it.absolutePath.startsWith(context.filesDir.absolutePath)
        } ?: return null

        return switchToAppDirProject(context, resolved)
    }

    fun switchToAppDirProject(context: Context, projectDir: File): File {
        if (!projectDir.exists()) {
            projectDir.mkdirs()
        }
        ensureProjectFilesExecutable(projectDir)
        val settings = Settings(context)
        settings.projectMode = "local"
        settings.localProjectPath = projectDir.absolutePath
        cachedActiveWorkspace = projectDir
        return projectDir
    }

    fun selectInternalProject(context: Context, projectDir: File): File =
        switchToAppDirProject(context, projectDir)

    fun importSafTreeIntoAppDir(context: Context, treeUri: Uri): File? {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }

            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val rawFolderName = docId.substringAfterLast(':').substringAfterLast('/')
                .ifBlank { "ImportedProject" }
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destRoot = File(projectsRootDir(context), rawFolderName)
            destRoot.mkdirs()
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            copySafRecursive(context, treeUri, rootDocUri, destRoot, depth = 0)
            ensureProjectFilesExecutable(destRoot)
            val settings = Settings(context)
            settings.projectMode = "local"
            settings.localProjectPath = destRoot.absolutePath
            cachedActiveWorkspace = destRoot
            destRoot
        } catch (_: Exception) {
            null
        }
    }

    fun importExternalTreeToInternal(context: Context, treeUri: Uri): File? =
        importSafTreeIntoAppDir(context, treeUri)

    private fun copySafRecursive(
        context: Context,
        treeUri: Uri,
        parentDocUri: Uri,
        targetDir: File,
        depth: Int
    ) {
        if (depth > 8) return
        targetDir.mkdirs()
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        var cursor: Cursor? = null
        val entries = mutableListOf<Triple<String, String, Boolean>>()
        try {
            cursor = resolver.query(childrenUri, projection, null, null, null)
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDir && name in IGNORED_DIRS) continue
                    entries.add(Triple(id, name, isDir))
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }

        for ((id, name, isDir) in entries) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            val targetFile = File(targetDir, name)
            if (isDir) {
                copySafRecursive(context, treeUri, docUri, targetFile, depth + 1)
            } else {
                runCatching {
                    resolver.openInputStream(docUri)?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    fun saveSafProjectTree(context: Context, treeUri: Uri): Boolean {
        return importSafTreeIntoAppDir(context, treeUri) != null
    }

    fun switchToLocalWorkspace(context: Context): File {
        val settings = Settings(context)
        settings.projectMode = "local"
        val defaultRoot = File(projectsRootDir(context), "PocketDevProject")
        if (!defaultRoot.exists()) {
            defaultRoot.mkdirs()
            seedStarterProject(defaultRoot, "PocketDevProject")
        }
        ensureProjectFilesExecutable(defaultRoot)
        settings.localProjectPath = defaultRoot.absolutePath
        cachedActiveWorkspace = defaultRoot
        return defaultRoot
    }

    fun activeProjectName(context: Context): String =
        ensureDefaultLocalWorkspace(context).name

    fun ensureDefaultLocalWorkspace(context: Context): File {
        cachedActiveWorkspace?.let { cached ->
            if (cached.exists() && cached.isDirectory) return cached
        }
        val projectsRoot = projectsRootDir(context)
        val settings = Settings(context)
        val customPath = settings.localProjectPath
        if (!customPath.isNullOrEmpty()) {
            val customDir = File(customPath)
            if (customDir.exists() &&
                customDir.isDirectory &&
                customDir.absolutePath.startsWith(context.filesDir.absolutePath)
            ) {
                cachedActiveWorkspace = customDir
                return customDir
            }
        }
        val root = File(projectsRoot, "PocketDevProject")
        if (!root.exists()) {
            root.mkdirs()
            seedStarterProject(root, "PocketDevProject")
            ensureProjectFilesExecutable(root)
        }
        settings.projectMode = "local"
        settings.localProjectPath = root.absolutePath
        cachedActiveWorkspace = root
        return root
    }

    fun ensureProjectFilesExecutable(dir: File) {
        runCatching {
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
            dir.walkTopDown().maxDepth(6).forEach { f ->
                if (f.isDirectory) {
                    f.setExecutable(true, false)
                } else {
                    val ext = f.extension.lowercase()
                    if (ext in EXECUTABLE_EXTENSIONS || !f.name.contains('.')) {
                        f.setExecutable(true, false)
                    }
                }
            }
        }
    }

    private fun seedStarterProject(root: File, projectName: String = "PocketDevProject") {
        runCatching {
            val srcDir = File(root, "src").apply { mkdirs() }
            val scriptsDir = File(root, "scripts").apply { mkdirs() }
            val configDir = File(root, "config").apply { mkdirs() }

            File(srcDir, "Main.kt").writeText(
                """
                package sample

                /**
                 * $projectName
                 * Workspace: /root/projects/$projectName
                 */
                fun main() {
                    val items = listOf("Kotlin", "Rust", "Go", "TypeScript", "Python")
                    items.filter { it.isNotEmpty() }
                        .forEachIndexed { index, name ->
                            println("[#${'$'}index] Ready => ${'$'}name")
                        }
                }
                """.trimIndent() + "\n"
            )

            File(srcDir, "Server.go").writeText(
                """
                package main

                import "fmt"

                func main() {
                    fmt.Println("PocketDev Go service running in /root/projects/$projectName")
                }
                """.trimIndent() + "\n"
            )

            val scriptFile = File(scriptsDir, "env_check.sh")
            scriptFile.writeText(
                """
                #!/usr/bin/env sh
                echo "Workspace: $(pwd)"
                uname -a
                cat /etc/os-release 2>/dev/null || true
                """.trimIndent() + "\n"
            )
            scriptFile.setExecutable(true, false)

            File(configDir, "project.json").writeText(
                """
                {
                  "project": "$projectName",
                  "path": "/root/projects/$projectName",
                  "version": "2.1.0"
                }
                """.trimIndent() + "\n"
            )

            File(root, "README.md").writeText(
                """
                # $projectName

                Welcome to **PocketDev**.

                - **Workspace**: Located at `/root/projects/$projectName`.
                - **Build & Run**: Tap the Run button in the top toolbar to compile and execute the active file.
                - **Terminal**: Launch the integrated Ubuntu 24.04 LTS terminal directly in your project folder.
                - **Source Control**: Manage Git branches, diffs, and commits from the sidebar.
                """.trimIndent() + "\n"
            )
        }
    }

    fun computeTreeSignature(context: Context): Long {
        val rootDir = ensureDefaultLocalWorkspace(context)
        var hash = 1125899906842597L
        fun visit(dir: File, depth: Int) {
            if (depth > 6) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory && f.name in IGNORED_DIRS) continue
                for (ch in f.name) {
                    hash = 31L * hash + ch.code
                }
                hash = 31L * hash + (if (f.isDirectory) 1L else 2L)
                hash = 31L * hash + (f.lastModified() / 1000L)
                if (f.isDirectory) {
                    visit(f, depth + 1)
                }
            }
        }
        visit(rootDir, 0)
        return hash
    }

    fun listRootNodes(context: Context): List<FileNode> {
        val rootDir = ensureDefaultLocalWorkspace(context)
        return listLocalChildren(rootDir, "", 0)
    }

    fun listChildNodes(context: Context, parent: FileNode): List<FileNode> {
        if (!parent.isDirectory) return emptyList()
        if (parent.localFile != null) {
            return listLocalChildren(parent.localFile, parent.relativePath, parent.depth + 1)
        }
        if (parent.safTreeUri != null && parent.safDocId != null) {
            return listSafChildren(
                context.contentResolver,
                parent.safTreeUri,
                parent.safDocId,
                parent.relativePath,
                parent.depth + 1
            )
        }
        return emptyList()
    }

    private fun listLocalChildren(dir: File, parentRel: String, depth: Int): List<FileNode> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { !(it.isDirectory && it.name in IGNORED_DIRS) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { f ->
                val rel = if (parentRel.isEmpty()) f.name else "$parentRel/${f.name}"
                FileNode(
                    name = f.name,
                    relativePath = rel,
                    isDirectory = f.isDirectory,
                    depth = depth,
                    localFile = f,
                    sizeBytes = if (f.isFile) f.length() else 0L
                )
            }
    }

    private fun listSafChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        parentRel: String,
        depth: Int
    ): List<FileNode> {
        val out = ArrayList<FileNode>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val docId = if (idIdx >= 0) c.getString(idIdx) else continue
                    val name = (if (nameIdx >= 0) c.getString(nameIdx) else null)
                        ?: docId.substringAfterLast('/')
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else ""
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val rel = if (parentRel.isEmpty()) name else "$parentRel/$name"
                    out.add(
                        FileNode(
                            name = name,
                            relativePath = rel,
                            isDirectory = isDir,
                            depth = depth,
                            safTreeUri = treeUri,
                            safDocId = docId,
                            safDocUri = docUri,
                            sizeBytes = size
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedWith(compareBy<FileNode>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun createInProject(
        context: Context,
        parentNode: FileNode?,
        name: String,
        isDirectory: Boolean
    ): FileNode? {
        val cleanName = name.trim().replace('/', '_').replace('\\', '_')
        if (cleanName.isEmpty()) return null

        val baseDir = when {
            parentNode?.localFile != null && parentNode.isDirectory -> parentNode.localFile
            parentNode?.localFile != null -> parentNode.localFile.parentFile ?: ensureDefaultLocalWorkspace(context)
            else -> ensureDefaultLocalWorkspace(context)
        }
        val target = File(baseDir, cleanName)
        return try {
            if (isDirectory) {
                target.mkdirs()
                target.setExecutable(true, false)
            } else {
                target.parentFile?.mkdirs()
                if (!target.exists()) target.writeText("")
                if (target.extension.lowercase() in EXECUTABLE_EXTENSIONS) {
                    target.setExecutable(true, false)
                }
            }
            val rel = if (parentNode != null && parentNode.isDirectory) {
                "${parentNode.relativePath}/$cleanName"
            } else {
                cleanName
            }
            FileNode(
                name = cleanName,
                relativePath = rel,
                isDirectory = isDirectory,
                depth = (parentNode?.depth ?: -1) + 1,
                localFile = target,
                sizeBytes = if (target.isFile) target.length() else 0L
            )
        } catch (_: Exception) {
            null
        }
    }

    fun renameNode(
        context: Context,
        node: FileNode,
        newName: String
    ): FileNode? {
        val cleanName = newName.trim().replace('/', '_').replace('\\', '_')
        if (cleanName.isEmpty() || cleanName == node.name) return null

        val srcFile = node.localFile ?: return null
        if (!srcFile.exists()) return null
        val parentDir = srcFile.parentFile ?: ensureDefaultLocalWorkspace(context)
        val destFile = File(parentDir, cleanName)
        if (destFile.exists() && destFile.absolutePath != srcFile.absolutePath) return null

        return try {
            val ok = srcFile.renameTo(destFile)
            if (!ok) return null
            if (destFile.isDirectory || destFile.extension.lowercase() in EXECUTABLE_EXTENSIONS) {
                destFile.setExecutable(true, false)
            }
            val parentRel = node.relativePath.substringBeforeLast('/', "")
            val newRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"
            FileNode(
                name = cleanName,
                relativePath = newRel,
                isDirectory = destFile.isDirectory,
                depth = node.depth,
                localFile = destFile,
                sizeBytes = if (destFile.isFile) destFile.length() else 0L,
                isExpanded = node.isExpanded
            )
        } catch (_: Exception) {
            null
        }
    }

    fun deleteNode(context: Context, node: FileNode): Boolean {
        return try {
            if (node.localFile != null) {
                node.localFile.deleteRecursively()
            } else if (node.safDocUri != null) {
                DocumentsContract.deleteDocument(context.contentResolver, node.safDocUri)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun collectAllProjectFiles(context: Context, maxFiles: Int = 2500): List<FileNode> {
        val result = ArrayList<FileNode>(256)
        val queue = ArrayDeque<FileNode>()
        queue.addAll(listRootNodes(context))
        while (queue.isNotEmpty() && result.size < maxFiles) {
            val node = queue.removeFirst()
            if (node.isDirectory) {
                if (node.name.lowercase() in IGNORED_DIRS) continue
                queue.addAll(listChildNodes(context, node))
            } else {
                if (node.sizeBytes <= 4 * 1024 * 1024) {
                    result.add(node)
                }
            }
        }
        return result
    }
}
